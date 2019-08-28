package com.sap.steps.model

import android.arch.persistence.room.Database
import android.arch.persistence.room.Room
import android.arch.persistence.room.RoomDatabase
import android.arch.persistence.room.TypeConverters
import android.content.Context

/**
 * Room database storing daily steps total tracked and used in this app.
 */
@Database(entities = [DailyStepsTotal::class], version = 1)
@TypeConverters(Converters::class)
abstract class StepsDatabase: RoomDatabase() {

    /**
     * Creates a DAO for this database.
     */
    abstract fun stepsDao(): StepsDao

    companion object {
        /**
         * (File) name of this database
         */
        private const val DATABASE_NAME = "steps.db"


        // Singleton magic as per Google's Room documentation
        private var INSTANCE: StepsDatabase? = null

        fun getInstance(context: Context): StepsDatabase {
            if (INSTANCE == null) {
                synchronized(StepsDatabase::class) {
                    INSTANCE = Room.databaseBuilder(context.applicationContext,
                        StepsDatabase::class.java, DATABASE_NAME)
                        .build()
                }
            }
            return INSTANCE!!
        }

        fun destroyInstance() {
            INSTANCE = null
        }
    }

}