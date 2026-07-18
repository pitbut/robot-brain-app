package com.robutpit.robotphone

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var hostInput: EditText
    private lateinit var statusText: TextView

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ).apply {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBrain()
        } else {
            statusText.text = "Нужны все разрешения (камера, GPS, уведомления), чтобы работать"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("robot_brain", MODE_PRIVATE)
        hostInput = findViewById(R.id.hostInput)
        statusText = findViewById(R.id.statusText)
        hostInput.setText(
            prefs.getString("host", "robot-platform-xxxx.onrender.com")
        )

        findViewById<Button>(R.id.startButton).setOnClickListener {
            prefs.edit().putString("host", hostInput.text.toString()).apply()
            if (requiredPermissions.all {
                    checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }) {
                startBrain()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopBrain()
        }

        lifecycleScope.launch {
            RobotBrainState.status.collect { statusText.text = it }
        }
    }

    private fun startBrain() {
        val host = hostInput.text.toString().trim()
        val intent = Intent(this, RobotBrainService::class.java).apply {
            action = RobotBrainService.ACTION_START
            putExtra(RobotBrainService.EXTRA_HOST, host)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBrain() {
        val intent = Intent(this, RobotBrainService::class.java).apply {
            action = RobotBrainService.ACTION_STOP
        }
        startService(intent)
    }
}
