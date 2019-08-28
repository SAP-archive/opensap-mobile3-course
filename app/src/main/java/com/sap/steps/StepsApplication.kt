package com.sap.steps

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import ch.qos.logback.classic.Level
import com.sap.cloud.mobile.foundation.authentication.AppLifecycleCallbackHandler
import com.sap.cloud.mobile.foundation.authentication.SamlConfiguration
import com.sap.cloud.mobile.foundation.authentication.SamlInterceptor
import com.sap.cloud.mobile.foundation.authentication.SamlWebViewProcessor
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.cloud.mobile.foundation.common.SettingsParameters
import com.sap.cloud.mobile.foundation.logging.Logging
import com.sap.cloud.mobile.foundation.networking.AppHeadersInterceptor
import com.sap.cloud.mobile.foundation.networking.CorrelationInterceptor
import com.sap.cloud.mobile.foundation.networking.WebkitCookieJar
import com.sap.cloud.mobile.odata.DataServiceProvider
import com.sap.cloud.mobile.odata.core.AndroidSystem
import com.sap.cloud.mobile.odata.offline.OfflineODataDefiningQuery
import com.sap.cloud.mobile.odata.offline.OfflineODataParameters
import com.sap.cloud.mobile.odata.offline.OfflineODataProvider
import com.sap.steps.offline.OfflineSyncService
import com.sap.steps.sensor.StepsCounterListener
import com.sap.steps.sensor.StepsCounterService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.URL

/**
 * Custom application class that takes care of most of the application configuration, such as providing application
 * metadata and initializing connectivity.
 *
 * By doing those things in the application class (and only here), we can ensure that whenever the application is
 * started and as long as it is running, the required configuration is in place.
 */
class StepsApplication: Application() {

    companion object {
        /**
         * Basic URL used to communicate with Mobile Services.
         */
        private const val MOBILE_SERVICES_BASE_URL = "https://pxxxxxxxxxxtrial-dev-com-sap-steps.cfapps.eu10.hana.ondemand.com"
        /**
         * ID of the Mobile Services endpoint configured for this application.
         */
        private const val APPLICATION_ID = "com.sap.steps"
        /**
         * Application version sent to Mobile Services, which may be used to control access from outdated
         * clients.
         */
        private const val APPLICATION_VERSION = "1.0"

        /**
         * ID of the canteen service Mobile Services destination used to connect it to the application.
         */
        private const val CANTEEN_SERVICE_DESTINATION_ID = "com.sap.canteen"

        /**
         * OData Booking data set name
         */
        private const val BOOKING_ENTITY_SET = "BookingSet"

        /**
         * OData Menu data set name
         */
        private const val MENU_ENTITY_SET = "MenuSet"
    }

    /**
     * Bound foreground service that is used to synchronize with the backend
     */
    private val _offlineSyncService = CompletableDeferred<OfflineSyncService>()
    val deferredOfflineSyncService: Deferred<OfflineSyncService>
        get() = _offlineSyncService

    /**
     * Deferred that is resolved as soon as the Offline OData provider is opened, subsequently
     * enabling dependent components to synchronize with the backend.
     */
    private val _deferredOpenedOfflineProvider = CompletableDeferred<OfflineODataProvider>()
    val deferredOpenedOfflineProvider: Deferred<OfflineODataProvider>
        get() = _deferredOpenedOfflineProvider

    override fun onCreate() {
        super.onCreate()

        // SAP SDK Lifecycle handler used to determine the context of authentication challenges to display
        registerActivityLifecycleCallbacks(AppLifecycleCallbackHandler.getInstance())
        initializeHttpClient()
        initializeLoggingAndUsage()

        initializaOfflineService()

        scheduleStepsUpdateJob()
        receiveStepsUpdates()
    }

    /**
     * Offline OData provider configured to store menus and bookings in the local database
     */
    private val offlineODataProvider by lazy {
        OfflineODataProvider(URL("$MOBILE_SERVICES_BASE_URL/$CANTEEN_SERVICE_DESTINATION_ID"),
                    OfflineODataParameters(), ClientProvider.get(), null, null).apply {
            addDefiningQuery(OfflineODataDefiningQuery(BOOKING_ENTITY_SET, BOOKING_ENTITY_SET, false))
            addDefiningQuery(OfflineODataDefiningQuery(MENU_ENTITY_SET, MENU_ENTITY_SET, false))
        }
    }

    /**
     * Returns the OData provider used by this application to access the canteen service data.
     */
    val oDataProvider: DataServiceProvider
        get() = offlineODataProvider

    /**
     * Initializes the Offline component of the SDK and binds a local foreground service that may be used to
     * synchronize between the application and the backend.
     */
    private fun initializaOfflineService() {
        AndroidSystem.setContext(this)
        bindService(Intent(this, OfflineSyncService::class.java), object: ServiceConnection {

            override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                offlineODataProvider.open(
                    {
                        Log.d("_steps", "Offline OData store opened")
                        _deferredOpenedOfflineProvider.complete(offlineODataProvider)
                        _offlineSyncService.complete((binder as OfflineSyncService.LocalBinder).service)
                    }, {
                        Log.e("_steps", "Offline OData store opening failed", it)
                        _deferredOpenedOfflineProvider.completeExceptionally(it)
                        _offlineSyncService.completeExceptionally(it)
                    })
            }

            override fun onServiceDisconnected(p0: ComponentName?) {
                TODO("This shouldn't happen since we run the service in the same process")
            }

        }, Context.BIND_AUTO_CREATE)
    }

    /**
     * Sets this OkHttpClient in the ClientProvider static holder so that other components can more easily access it
     */
    private fun initializeHttpClient() {
        ClientProvider.set(okHttpClient)
    }

    /**
     * Creates an <code>OkHttpClient</code> that is configured to
     *  1. Identify this device, this application and its version
     *  2. Intercept Mobile Services authentication challenges and handle them
     *  3. Use a shared Webkit cookie jar for authentication purposes
     */
    private val okHttpClient by lazy {
        // Reports application ID, application version and device ID to Mobile Services
        val appHeadersInterceptor = AppHeadersInterceptor(settingsParameters)

        // Only allow TLS versions 1.2 and 1.3
        val connectionSpecs = listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .build()
        )

        // SAML processor used for SAML authentication challenge interceptions
        val samlConfiguration = SamlConfiguration.Builder()
            .authUrl("$MOBILE_SERVICES_BASE_URL/SAMLAuthLauncher")
            .build()
        val samlProcessor = SamlWebViewProcessor(samlConfiguration)

        OkHttpClient.Builder()
            .connectionSpecs(connectionSpecs)
            .addInterceptor(appHeadersInterceptor)
            .addInterceptor(SamlInterceptor(samlProcessor))
            .addInterceptor(CorrelationInterceptor())
            .cookieJar(WebkitCookieJar())
            .build()
    }

    /**
     * Unique device ID for logging and usage tracking.
     */
    private val deviceId by lazy {
        Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID)
    }

    /**
     * Mobile Services app metadata including the service base URL, the application ID, application version and
     * device identifier.
     */
    val settingsParameters by lazy {
        SettingsParameters(MOBILE_SERVICES_BASE_URL, APPLICATION_ID, deviceId, APPLICATION_VERSION)
    }

    /**
     * Initializes the SAP logging framework with this application context. Only required when using the Mobile Services
     * log upload feature.
     */
    private fun initializeLoggingAndUsage() {
        Logging.initialize(this, Logging.ConfigurationBuilder()
            .initialLevel(Level.DEBUG)
            .logToConsole(true)
            .build())
    }

    /**
     * Immediately starts receiving step counter updates from the system step counter sensor.
     */
    private fun receiveStepsUpdates() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        stepCounterSensor?.also {
            Log.d("_steps", "Registering trigger listener in application")
            sensorManager.registerListener(StepsCounterListener(this@StepsApplication, false), it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /**
     * Schedules an hourly job to update the step count in the background while the app is closed. The job is not persisted
     * across device restarts.
     */
    private fun scheduleStepsUpdateJob() {
        getSystemService(JobScheduler::class.java)!!
            .schedule(
                JobInfo.Builder(0, ComponentName(this, StepsCounterService::class.java))
                    .setPeriodic(JobInfo.getMinPeriodMillis())
                    .build())
        Log.d("_steps", "Scheduled step job every ${JobInfo.getMinPeriodMillis()} millis")
    }

}