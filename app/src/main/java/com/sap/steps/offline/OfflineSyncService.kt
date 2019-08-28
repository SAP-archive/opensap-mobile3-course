package com.sap.steps.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.util.Log
import com.sap.cloud.mobile.foundation.authentication.AppLifecycleCallbackHandler
import com.sap.cloud.mobile.odata.offline.OfflineODataProvider
import com.sap.steps.MainActivity
import com.sap.steps.R
import com.sap.steps.StepsApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * Foreground service that is used to synchronize the Offline OData store. A notification is used during synchronization
 * to ensure that the service may continue running even if the app is put to the background.
 */
class OfflineSyncService: Service() {

    companion object {
        /**
         * Notification settings
         */
        const val NOTIFICATION_CHANNEL_ID = "steps-sync"
        const val NOTIFICATION_CHANNEL_NAME = "Steps Offline Synchronization"
        const val NOTIFICATION_CHANNEL_DESCRIPTION = "Step counter offline data synchronization"
        const val NOTIFICATION_ID = 123
    }

    /**
     * Local binder interface providing access to this service instance
     */
    inner class LocalBinder: Binder() {
        val service
            get() = this@OfflineSyncService
    }

    private val binder = LocalBinder()

    /**
     * Deferred that is resolved as soon as the Offline OData provider is opened, subsequently
     * enabling dependent components to synchronize with the backend.
     */
    private var deferredOpenedOfflineProvider: Deferred<OfflineODataProvider>? = null

    /**
     * Mutex ensuring that only one Offline synchronization ever takes place in parallel
     */
    private val syncMutex = Mutex()

    /**
     * Synchronizes the local Offline OData store with the backend, uploading local changes to the backend
     * and downloading remote changes from the backend in the process. A notification is used during synchronization
     * to ensure that the service may continue running even if the app is put to the background.
     */
    suspend fun synchronizeAsync(): Deferred<Unit> {
        return CompletableDeferred<Unit>().also { deferred ->
            if(!AppLifecycleCallbackHandler.getInstance().isInForeground) {
                // This limitation is due to SAML authentication, which requires a WebView to be displayed.
                deferred.completeExceptionally(IllegalStateException("Cannot synchronize while in the background."))
            } else syncMutex.withLock {
                try {
                    val offlineODataProvider = deferredOpenedOfflineProvider!!.await() // Ensure that the store is opened before we proceed

                    displayNotification(android.R.drawable.stat_sys_upload)
                    Log.d("_steps", "Uploading local data...")

                    offlineODataProvider.upload( {
                        displayNotification(android.R.drawable.stat_sys_download)
                        Log.d("_steps", "Downloading remote data...")
                        offlineODataProvider.download( { deferred.complete(Unit) }, { deferred.completeExceptionally(it) })
                    }, { deferred.completeExceptionally(it) })
                    deferred.await() // Block until sync is finished
                    cancelNotification()
                    Log.d("_steps", "Finished.")
                } catch(e: Exception) {
                    Log.e(OfflineSyncService::class.java.simpleName, "Unable to sync offline data", e)
                }

            }
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        registerNotificationChannel()
        deferredOpenedOfflineProvider = (application as StepsApplication).deferredOpenedOfflineProvider
        return binder
    }

    /**
     * Explicitly registers a notification channel, which is required for Android versions >= O.
     */
    private fun registerNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = NOTIFICATION_CHANNEL_DESCRIPTION
        }
        val notificationManager: NotificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Display a notification indicating that synchronization is taking place. May be called while the notification
     * is called to update the icon (e.g. switching from upload to download).
     *
     * @param iconId Resource ID of the icon to display
     */
    private fun displayNotification(iconId: Int) {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        val bigTextStyle = NotificationCompat.BigTextStyle()
        bigTextStyle.setBigContentTitle(application.getString(R.string.syncing))
        builder.setStyle(bigTextStyle)
        builder.setWhen(System.currentTimeMillis())
        builder.setSmallIcon(iconId)
        builder.setProgress(100, 0, true)

        // Clicking the notification will return to the app
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, 0)
        builder.setFullScreenIntent(pendingIntent, false)
        builder.build()

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(NOTIFICATION_ID, builder.build())
        }
    }

    /**
     * Cancels the synchronization notification.
     */
    private fun cancelNotification() {
        with(NotificationManagerCompat.from(applicationContext)) {
            cancel(NOTIFICATION_ID)
        }
    }

}