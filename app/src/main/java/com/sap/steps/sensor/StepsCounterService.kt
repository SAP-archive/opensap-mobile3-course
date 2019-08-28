package com.sap.steps.sensor

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.support.v4.app.JobIntentService
import android.util.Log
import com.sap.steps.StepsApplication

/**
 * Job intent service that can be scheduled to update the pedometer database.
 */
class StepsCounterService : JobIntentService() {

    override fun onHandleWork(intent: Intent) {
        Log.d("_steps", "Running scheduled steps database update")
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        stepCounterSensor?.also {
            Log.d("_steps", "Registering trigger listener")
            sensorManager.registerListener(StepsCounterListener(application as StepsApplication), it,
                SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

}
