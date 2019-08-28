package com.sap.steps.model

import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import com.sap.steps.StepsApplication

/**
 * View model exposing steps and calories data, as well as the application's loading state and error messages from
 * other components.
 */
class StepsViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Repository providing access to the local database(s)
     */
    private val repository = StepsRepository(StepsDatabase.getInstance(application).stepsDao(),
        application as StepsApplication)

    // See repository
    val stepsToday = repository.stepsToday
    val caloriesToday = repository.caloriesToday
    val caloriesRemaining = repository.caloriesRemaining
    val refreshing = repository.refreshing
    val errors = repository.errors

    init {
        // When created, make sure a booking is fetched immediately
        repository.refreshBooking()
    }

    /**
     * Refresh the current calorie goal by querying menu bookings from the backend.
     *
     * @param error Optional error handler, e.g. to reset swipe refresh layout state
     */
    fun refresh() {
        repository.synchronize()
    }

}