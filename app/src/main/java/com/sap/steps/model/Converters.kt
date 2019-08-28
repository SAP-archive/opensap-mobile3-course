package com.sap.steps.model

import android.arch.persistence.room.TypeConverter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Converter functions required by the local Room database.
 */
class Converters {

    companion object {
        /**
         * Date format used to distinguish between days.
         */
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    /**
     * Conversion function deserializing dates in the DATE_FORMAT format.
     *
     * @param value Date string to deserialize
     */
    @TypeConverter
    fun dateFromString(value: String?): Date? {
        return value?.let { DATE_FORMAT.parse(it) }
    }

    /**
     * Conversion function serializing dates in the DATE_FORMAT format.
     *
     * @param date Date to serialize
     */
    @TypeConverter
    fun dateToString(date: Date?): String? {
        return DATE_FORMAT.format(date)
    }
}