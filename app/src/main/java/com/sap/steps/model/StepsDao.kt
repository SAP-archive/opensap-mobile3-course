package com.sap.steps.model

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.Dao
import android.arch.persistence.room.Insert
import android.arch.persistence.room.OnConflictStrategy.REPLACE
import android.arch.persistence.room.Query
import java.util.*

/**
 * Data access object bridging between our app and the standard Android SQLite database.
 */
@Dao
interface StepsDao {

    /**
     * Retrieves the number of steps walked between the specified start and end dates. Note that start must be before end
     * and that both start and end must have corresponding database entries to succeed.
     *
     * @param start Start date (must be before end date)
     * @param end End date (must be after start date)
     */
    @Query("SELECT (SELECT totalStepsToDate FROM dailyStepsTotal WHERE date = :end) - (SELECT totalStepsToDate FROM dailyStepsTotal WHERE date = :start)")
    fun getStepsBetween(start: Date, end: Date): LiveData<Int>

    /**
     * Retrieves the total number of steps walked up to to and including the specified date. Note that start must be before end
     * and that both start and end must have corresponding database entries to succeed.
     *
     * @param date Date for which to fetch steps total
     */
    @Query("SELECT totalStepsToDate FROM dailyStepsTotal WHERE date = :date")
    fun getTotalStepsToDate(date: Date): Int?

    /**
     * Upserts the specified daily steps total.
     *
     * @param dailyStepsTotal The value to insert or update
     */
    @Insert(onConflict = REPLACE)
    fun insert(dailyStepsTotal: DailyStepsTotal)

}