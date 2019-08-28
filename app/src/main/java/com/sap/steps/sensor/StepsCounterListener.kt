package com.sap.steps.sensor

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.sap.steps.model.StepsDatabase
import com.sap.steps.model.StepsRepository

/**
 * Sensor event listener that specifically tracks TYPE_STEP_COUNTER events, persisting them in the local database.
 * Can optionally be configured to only listen to a single event, which may be used for scheduled jobs that should only
 * run briefly.
 */
class StepsCounterListener(private val application: Application, private val once: Boolean = true): SensorEventListener {

    /**
     * Repository used to store daily step totals
     */
    private val repository = StepsRepository(StepsDatabase.getInstance(application).stepsDao())

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            updateDailyStepsTotal(it.values[0].toInt())
            if(once) unregisterListener()
        }
    }

    /**
     * Stores the specified number of steps in the repository.
     *
     * @param steps Number of steps to store
     */
    private fun updateDailyStepsTotal(steps: Int) {
        repository.insert(steps)
        Log.d("_steps","Steps walked today: $steps")
    }

    /**
     * Unregisters this listener, preventing it from receiving further sensor updates.
     */
    private fun unregisterListener() {
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.unregisterListener(this@StepsCounterListener, sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER))
        Log.d("_steps", "Unregistered listener ${this@StepsCounterListener}")
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // no-op
    }
}
