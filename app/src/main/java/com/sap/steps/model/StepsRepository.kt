package com.sap.steps.model

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MediatorLiveData
import android.arch.lifecycle.MutableLiveData
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*
import kotlin.coroutines.CoroutineContext

/**
 * Repository encapsulating live data streams and background queries.
 */
class StepsRepository(private val stepsDao: StepsDao) {

    companion object {
        /**
         * Conversion factor between steps and calories. In practice, it depends on metabolism, but values between
         * 0.04 and 0.05 appear to be generally acceptable.
         */
        const val STEPS_TO_CALORIE_FACTOR = 0.04
    }

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
    private var currentCalorieGoal: Int? = 500

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
    }

    /**
     * Updates the calories remaining based on the current daily calorie count and daily calorie goal.
     */
    private fun updateCaloriesRemaining() {
        currentCalorieGoal?.let {
            _caloriesRemaining.postValue(Math.max(0, it - currentCaloriesToday))
        }
    }

}