package com.sap.steps

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import com.sap.steps.sensor.StepsCounterListener
import com.sap.steps.sensor.StepsCounterService

/**
 * Custom application class that takes care of most of the application configuration, such as providing application
 * metadata and initializing connectivity.
 *
 * By doing those things in the application class (and only here), we can ensure that whenever the application is
 * started and as long as it is running, the required configuration is in place.
 */
class StepsApplication: Application() {

    override fun onCreate() {
        super.onCreate()

        scheduleStepsUpdateJob()
        receiveStepsUpdates()
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