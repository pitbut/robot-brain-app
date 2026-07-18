package com.robutpit.robotphone

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var hostInput: EditText
    private lateinit var statusText: TextView
    private lateinit var arSurfaceView: GLSurfaceView

    private var arSession: Session? = null
    private var arCoreInstallRequested = false

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

        arSurfaceView = findViewById(R.id.arSurfaceView)
        arSurfaceView.setEGLContextClientVersion(2)
        arSurfaceView.setRenderer(ArDepthRenderer { arSession })
        arSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

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

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            tryCreateArSession()
        }
        arSession?.let {
            try { it.resume() } catch (e: Exception) { /* сессия уже активна либо камера занята */ }
        }
        arSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        arSurfaceView.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        arSession = null
    }

    /** Создаёт ARCore-сессию, если устройство поддерживает и Google Play Services for AR установлены. */
    private fun tryCreateArSession() {
        if (arSession != null) return
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            if (!availability.isSupported) {
                statusText.text = "ARCore не поддерживается этим телефоном — объезда препятствий по камере не будет"
                return
            }
            when (ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallRequested = true
                    return // вернёт управление сюда же после установки/обновления
                }
                ArCoreApk.InstallStatus.INSTALLED -> { /* продолжаем */ }
            }
            val session = Session(this)
            val config = Config(session)
            config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
            session.configure(config)
            arSession = session
        } catch (e: UnavailableException) {
            statusText.text = "ARCore недоступен: ${e.message}"
        } catch (e: Exception) {
            statusText.text = "Ошибка ARCore: ${e.message}"
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
