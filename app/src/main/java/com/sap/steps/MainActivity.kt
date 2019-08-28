package com.sap.steps

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.app.AppCompatActivity
import android.util.TypedValue
import android.widget.Toast
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.utils.ColorTemplate
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.cloud.mobile.foundation.logging.Logging
import com.sap.steps.model.StepsViewModel
import org.slf4j.LoggerFactory


/**
 * Pedometer main activity that displays the daily number of steps walked and calories burnt.
 */
class MainActivity : AppCompatActivity() {

    val logger = LoggerFactory.getLogger(MainActivity::class.java)

    /**
     * Swipe refresh layout used to trigger data updates
     */
    private val swipeRefreshLayout by lazy {
        findViewById<SwipeRefreshLayout>(R.id.swiperefresh)
    }

    /**
     * Chart used to display steps and calories
     */
    private val chart by lazy {
        findViewById<PieChart>(R.id.piechart). apply {
            configureChart(this)
        }
    }

    /**
     * Data set representing daily calorie totals and goals
     */
    private val dataSet by lazy {
        PieDataSet(mutableListOf(PieEntry(0f, getString(R.string.kcalBurnt)),
            PieEntry(0f, getString(R.string.kcalRemaining))), "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val viewModel = ViewModelProviders.of(this).get(StepsViewModel::class.java)

        viewModel.apply {
            stepsToday.observe(this@MainActivity, Observer { it?.let(::displaySteps) })
            caloriesToday.observe(this@MainActivity, Observer { it?.let(::displayCaloriesBurnt) })
            caloriesRemaining.observe(this@MainActivity, Observer { it?.let(::displayCaloriesRemaining) })
            refreshing.observe(this@MainActivity, Observer { it?.let(swipeRefreshLayout::setRefreshing) })

            errors.observe(this@MainActivity, Observer {
                it?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            })
        }

        swipeRefreshLayout.setOnRefreshListener(viewModel::refresh)
    }

    override fun onStart() {
        super.onStart()
        uploadLoggingAndUsageData()
    }

    /**
     * Uploads logs and usage data to Mobile Services in a fire-and-forget fashion, and only if network is available.git gui
     */
    private fun uploadLoggingAndUsageData() {
        // This is fire and forget - you may register status listeners via Logging.addLogUploadListener()
        // and/or by providing a log upload listener to UsageBroker#upload
        if((getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true) {
            // The following log message will be uploaded to Mobile Services
            logger.debug("About to upload logs...")
            Logging.uploadLog(ClientProvider.get(), (application as StepsApplication).settingsParameters)
        }
    }

    /**
     * Updates the pie chart center text to display the specified number of steps walked today.
     *
     * @param steps Number of steps to display
     */
    private fun displaySteps(steps: Int) {
        chart.centerText = getString(R.string.stepsToday, steps)
    }

    /**
     * Updates the pie chart segments to display the specified number of calories burnt today.
     *
     * @param kcal Number of calories to display
     */
    private fun displayCaloriesBurnt(kcal: Int) {
        dataSet.values[0].y = kcal.toFloat()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    /**
     * Updates the pie chart segments to display the specified number of calories remaining for today.
     *
     * @param kcal Number of calories to display
     */
    private fun displayCaloriesRemaining(kcal: Int) {
        dataSet.values[1].y = kcal.toFloat()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    /**
     * Configures the specified pie chart to have a nice half circle shape, and to match theme colors.
     *
     * @param chart The chart to configure
     */
    private fun configureChart(chart: PieChart) {
        val backgroundColor = with(TypedValue()) {
            theme.resolveAttribute(android.R.attr.windowBackground, this, true)
            data
        }

        chart.setBackgroundColor(backgroundColor)
        chart.isDrawHoleEnabled = true
        chart.setHoleColor(backgroundColor)
        chart.setTransparentCircleColor(backgroundColor)
        chart.setTransparentCircleAlpha(50)
        chart.holeRadius = 58f
        chart.transparentCircleRadius = 61f
        chart.setDrawCenterText(true)
        chart.maxAngle = 180f // HALF CHART
        chart.rotationAngle = 180f
        chart.setRotationEnabled(false)
        chart.legend.setEnabled(false)

        chart.setCenterTextColor(Color.WHITE)
        chart.centerText = "Loading..."
        chart.description.text = ""

        chart.setCenterTextOffset(0.0f, -20.0f)

        chart.animateY(1400, Easing.EaseInOutQuad)

        dataSet.setColors(*ColorTemplate.MATERIAL_COLORS)
        dataSet.sliceSpace = 3f
        dataSet.selectionShift = 5f

        val data = PieData(dataSet)
        data.setValueTextSize(11f)
        data.setValueTextColor(Color.WHITE)
        chart.data = data
    }

}
