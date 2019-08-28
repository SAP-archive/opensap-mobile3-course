package com.sap.steps.model

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MediatorLiveData
import android.arch.lifecycle.MutableLiveData
import android.net.ConnectivityManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.sap.cloud.mobile.odata.DataQuery
import com.sap.cloud.mobile.odata.GlobalDateTime
import com.sap.cloud.mobile.odata.IntValue
import com.sap.cloud.mobile.odata.QueryFilter
import com.sap.steps.R
import com.sap.steps.StepsApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis

/**
 * Repository encapsulating live data streams and background queries.
 */
class StepsRepository(private val stepsDao: StepsDao, application: StepsApplication) {

    companion object {
        /**
         * Conversion factor between steps and calories. In practice, it depends on metabolism, but values between
         * 0.04 and 0.05 appear to be generally acceptable.
         */
        const val STEPS_TO_CALORIE_FACTOR = 0.04
    }

    /**
     * Connectivity manager to determine if network connections are available
     */
    private val connectivityManager = application.getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * OData backend service providing SAP backend data
     */
    private val canteenService = CanteenService(application.oDataProvider)

    /**
     * Deferred foreground service
     */
    private val deferredOfflineSyncService = application.deferredOfflineSyncService


    /**
     * Coroutine scope used to run backend and local database access. Uses the Android IO thread pool
     * to do so.
     */
    private var parentJob = Job()
    private val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.IO
    private val scope = CoroutineScope(coroutineContext)

    /**
     * Date object representing today
     */
    private val today: Date
        get () = Calendar.getInstance().time

    /**
     * Date object representing the day before today
     */
    private val yesterday: Date
        get() = {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            cal.time
        }()

    /**
     * Observable providing the number of steps walked today.
     */
    val stepsToday: LiveData<Int>
        get() = stepsDao.getStepsBetween(yesterday, today)

    /**
     * Update the local database with the specified number of steps walked today.
     *
     * @param steps Total number of steps walked up to to and including today
     */
    fun insert(steps: Int) {
        scope.launch {
            // Initialize "yesterday" with current step count on first run
            if(stepsDao.getTotalStepsToDate(yesterday) == null) {
                stepsDao.insert(DailyStepsTotal(steps, yesterday))
                Log.d("_steps","Updated steps count for ${Converters.DATE_FORMAT.format(yesterday)}: $steps")
            }

            stepsDao.insert(DailyStepsTotal(steps))
            Log.d("_steps","Updated steps count for ${Converters.DATE_FORMAT.format(today)}: $steps")
        }
    }

    /**
     * Internal tracker for current calorie goal, used to calculate calories remaining today
     */
    private var currentCalorieGoal: Int? = null

    /**
     * Internal tracker for current calorie count burnt today,
     */
    private var currentCaloriesToday = 0

    /**
     * Observable for refreshing state of this app
     */
    private val _refreshing = MediatorLiveData<Boolean>()
    val refreshing: LiveData<Boolean>
        get() = _refreshing

    /**
     * Observable for number of calories burnt today
     */
    private val _caloriesToday = MediatorLiveData<Int>()
    val caloriesToday: LiveData<Int>
        get() = _caloriesToday

    /**
     * Observable for number of calories remaining today
     */
    private val _caloriesRemaining = MediatorLiveData<Int>()
    val caloriesRemaining: LiveData<Int>
        get() = _caloriesRemaining

    /**
     * Internal observable to track the calorie goal provided by the backend
     */
    private val calorieGoal = MutableLiveData<Int>()

    /**
     * Observable for error messages
     */
    private val _errors = MediatorLiveData<Int>()
    val errors: LiveData<Int>
        get() = _errors

    init {
        // Translate from steps to calories
        _caloriesToday.addSource(stepsToday) {
            it?.let {
                _caloriesToday.postValue((it * STEPS_TO_CALORIE_FACTOR).toInt())
            }
        }

        // Combine calories today and calorie goal into calories remaining
        _caloriesRemaining.addSource(caloriesToday) {
            it?.let {
                currentCaloriesToday = it
                updateCaloriesRemaining()
            }
        }
        _caloriesRemaining.addSource(calorieGoal) {
            it?.let {
                currentCalorieGoal = it
                updateCaloriesRemaining()
            }
        }

        synchronizeIfEmpty()
    }

    /**
     * Updates the calories remaining based on the current daily calorie count and daily calorie goal.
     */
    private fun updateCaloriesRemaining() {
        currentCalorieGoal?.let {
            _caloriesRemaining.postValue(Math.max(0, it - currentCaloriesToday))
        }
    }

    /**
     * Checks if there is a booking entry for today in the local database and triggers backend synchronization
     * if there is none.
     */
    private fun synchronizeIfEmpty() {
        scope.launch {
            getTodaysBooking() ?: synchronize()
        }
    }

    /**
     * Queries the service for today's booking. If an Offline provider is used for the service initialization, this targets
     * the local database.
     */
    private suspend fun getTodaysBooking() = try { deferredOfflineSyncService.await().run {
        canteenService.getBookingSet(DataQuery().filter(filterToday).expand(Booking.menuBooked))
            .firstOrNull() }
    } catch(e: Exception) {
        Log.e(StepsRepository::class.java.simpleName, "Unable to synchronize data", e)
        null
    }

    /**
     * Refresh the current calorie goal by querying menu bookings from the backend
     */
    fun synchronize() {
        if(connectivityManager.activeNetworkInfo?.isConnected == true) {
            Log.d("_steps", "** Synchronizing... **")
            _refreshing.postValue(true)

            scope.launch {
                val time = measureTimeMillis {
                    try {
                        deferredOfflineSyncService.await().run {
                            synchronizeAsync().await()
                        }
                        refreshBooking()
                        _refreshing.postValue(false)
                    } catch(e: Exception) {
                        Log.e(StepsRepository::class.java.simpleName, "Unable to synchronize data", e)
                    }

                }

                Log.d("_steps", "** Sync completed in ${time}ms. **")
            }
        } else {
            // If there is no network, make sure to reset loading indicators
            _errors.postValue(R.string.noNetwork)
            _refreshing.postValue(false)
        }
    }

    /**
     * Query filter selecting only today's booking
     */
    private val filterToday: QueryFilter
        get() {
            val today = Calendar.getInstance()
            val tomorrow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            return Booking.bookingDate.greaterEqual(globalDateTimeOf(today))
                .and(Booking.bookingDate.lessThan(globalDateTimeOf(tomorrow)))
        }

    /**
     * Utility function converting dates to OData GlobalDateTime objects used for OData queries.
     *
     * @param calendar Calendar to convert to GlobalDateTime
     */
    private fun globalDateTimeOf(calendar: Calendar): GlobalDateTime {
        return GlobalDateTime.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }

    /**
     * Queries the service for today's booking and updates the observable accordingly.
     * If an Offline provider is used for the service initialization, this targets the local database.
     */
    fun refreshBooking() {
        scope.launch {
            Log.d("_steps", "Refreshing bookings...")
            val time = measureTimeMillis {
                getTodaysBooking()?.let {
                    it.menuBooked.getOptionalValue(Menu.kcalForMain)?.let {
                        calorieGoal.postValue((it as IntValue).value)
                    }
                } ?: _errors.postValue(R.string.noBookingsFound)
            }
            Log.d("_steps", "Done refreshing bookings i n ${time}ms.")
        }
    }

}