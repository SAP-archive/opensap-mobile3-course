package com.sap.steps.model

import android.arch.persistence.room.ColumnInfo
import android.arch.persistence.room.Entity
import android.arch.persistence.room.PrimaryKey
import java.util.*

/**
 * Model class representing the total number of steps walked up to to and including this date.
 */
@Entity(tableName = "dailyStepsTotal")
data class DailyStepsTotal(
    @ColumnInfo(name="totalStepsToDate") val totalStepsToDate: Int,
    @PrimaryKey val date: Date = Calendar.getInstance().time
)