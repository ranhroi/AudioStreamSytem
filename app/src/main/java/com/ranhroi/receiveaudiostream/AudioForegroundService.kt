package com.ranhroi.receiveaudiostream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioForegroundService : Service() {

    private var port = 2027
    private var sampleRate = 48000
    private var isStereo = true
    private var rmsThreshold = 30.0f
    private var isForcedPlay = false

    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var socket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null

    companion object {
        const val ACTION_START = "com.example.action.START_SERVICE"
        const val ACTION_STOP = "com.example.action.STOP_SERVICE"
        const val ACTION_START_BOOT = "com.example.action.START_BOOT"
        
        private const val NOTIFICATION_ID = 1005
        private const val CHANNEL_ID = "audio_receiver_channel_id"

        private val _snapState = MutableStateFlow(ServiceStateSnapshot())
        val snapState = _snapState.asStateFlow()

        private val logList = mutableListOf<String>()

        fun addLog(message: String) {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val formattedMsg = "[$time] $message"
            logList.add(0, formattedMsg)
            if (logList.size > 80) {
                logList.removeAt(logList.size - 1)
            }
            _snapState.value = _snapState.value.copy(statusLogs = logList.toList())
            Log.d("AudioService", message)
        }

        fun clearLogs() {
            logList.clear()
            _snapState.value = _snapState.value.copy(statusLogs = emptyList())
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AudioService", "Service onCreate called")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopServiceGracefully()
            return START_NOT_STICKY
        }
        
        port = intent?.getIntExtra("extra_port", 5005) ?: 5005
        sampleRate = intent?.getIntExtra("extra_sample_rate", 48000) ?: 48000
        isStereo = intent?.getBooleanExtra("extra_is_stereo", true) ?: true
        rmsThreshold = intent?.getFloatExtra("extra_rms_threshold", 30.0f) ?: 30.0f
        isForcedPlay = intent?.getBooleanExtra("extra_force_play", false) ?: false

        if (action == ACTION_START || action == ACTION_START_BOOT) {
            startServiceGracefully()
        }
        return START_STICKY
    }

    private fun startServiceGracefully() {
        if (_snapState.value.isRunning) {
            _snapState.value = _snapState.value.copy(
                rmsThreshold = rmsThreshold,
                isForcedPlay = isForcedPlay,
                port = port,
                sampleRate = sampleRate,
                isStereo = isStereo
            )
            addLog("Service config updated dynamically (Threshold: $rmsThreshold, ForcedPlay: $isForcedPlay)")
            updateNotification(_snapState.value.receiverState, _snapState.value.packetCount)
            return
        }

        addLog("Initializing UDP Audio Server. Port: $port | sampleRate: $sampleRate | stereo: $isStereo | RMS: $rmsThreshold")

        createNotificationChannel()
        val notification = createNotification("Initializing...", "Waiting for incoming signal")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.e("AudioService", "Error starting foreground: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (t: Throwable) {
                Log.e("AudioService", "Fallback startForeground also failed: ${t.message}")
            }
        }

        acquireLocks()

        _snapState.value = ServiceStateSnapshot(
            isRunning = true,
            receiverState = ReceiverState.SLEEP,
            port = port,
            sampleRate = sampleRate,
            isStereo = isStereo,
            rmsThreshold = rmsThreshold,
            isForcedPlay = isForcedPlay,
            packetCount = 0,
            totalBytesReceived = 0,
            lastPacketTime = 0,
            currentRms = 0.0,
            statusLogs = logList.toList()
        )

        serviceJob = serviceScope.launch(Dispatchers.IO) {
            runAudioServerLoop()
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioReceiver:WakeLock").apply {
                acquire(10 * 60 * 1000L /*10 minutes fallback max if unplugged, but we run ongoing*/)
            }
            
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AudioReceiver:WifiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "AudioReceiver:WifiLock")
            }.apply {
                acquire()
            }
            addLog("Power & Wi-Fi locks acquired (Wakelock & Low Latency Wi-Fi Mode)")
        } catch (e: Exception) {
            addLog("Lock acquisition warning: ${e.message}")
        }
    }

    private suspend fun runAudioServerLoop() {
        var lastState = ReceiverState.SLEEP
        var silenceCount = 0

        val channelConfig = if (isStereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val finalBufferSize = Math.max(minBufferSize, 4096 * 4)

        try {
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(finalBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    finalBufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                addLog("ERROR: AudioTrack state is uninitialized. Selected config unsupported on device.")
                withContext(Dispatchers.Main) {
                    stopServiceGracefully()
                }
                return
            }
            addLog("Output AudioTrack initialized successfully.")
        } catch (e: Exception) {
            addLog("ERROR creating AudioTrack: ${e.message}")
            withContext(Dispatchers.Main) {
                stopServiceGracefully()
            }
            return
        }

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
            }
            addLog("UDP Socket bound to port $port successfully.")
        } catch (e: Exception) {
            addLog("ERROR opening socket on port $port: ${e.message}")
            withContext(Dispatchers.Main) {
                stopServiceGracefully()
            }
            return
        }

        val bufferSize = 4096
        val receiveBuffer = ByteArray(bufferSize)
        val packet = DatagramPacket(receiveBuffer, bufferSize)

        var currentState = ReceiverState.SLEEP
        silenceCount = 0

        while (_snapState.value.isRunning && socket != null && !socket!!.isClosed) {
            try {
                val currentTimeout = if (currentState == ReceiverState.SLEEP) 0 else 2000
                socket?.soTimeout = currentTimeout

                // Receive network packet
                socket?.receive(packet)

                val len = packet.length
                val rawRms = calculateRMS(receiveBuffer, len)
                
                val activeThreshold = _snapState.value.rmsThreshold
                val dynamicForcedPlay = _snapState.value.isForcedPlay
                
                val isSilence = if (dynamicForcedPlay) false else rawRms < activeThreshold

                val now = System.currentTimeMillis()
                val updatedPacketCount = _snapState.value.packetCount + 1
                val updatedBytes = _snapState.value.totalBytesReceived + len
                
                if (isSilence) {
                    silenceCount++
                    // Moving to IDLE if idle threshold (~45 silent frames, roughly 1 second) is crossed
                    if (silenceCount >= 45 && currentState == ReceiverState.PLAYING) {
                        currentState = ReceiverState.IDLE
                        addLog("No active signal (RMS: ${String.format("%.2f", rawRms)}). Pausing hardware stream.")
                        try {
                            audioTrack?.pause()
                        } catch (e: Exception) {
                            Log.e("AudioService", "Error pausing audiotrack: ${e.message}")
                        }
                    }
                } else {
                    silenceCount = 0
                    if (currentState != ReceiverState.PLAYING) {
                        currentState = ReceiverState.PLAYING
                        addLog("Active signal detected (RMS: ${String.format("%.2f", rawRms)}). Initializing playback output.")
                        try {
                            audioTrack?.play()
                        } catch (e: Exception) {
                            Log.e("AudioService", "Error starting audiotrack: ${e.message}")
                        }
                    }
                }

                if (currentState == ReceiverState.PLAYING) {
                    audioTrack?.write(receiveBuffer, 0, len)
                }

                // Push telemetry state to subscribers
                if (currentState != lastState || updatedPacketCount % 45 == 0L || updatedPacketCount == 1L) {
                    if (currentState != lastState) {
                        addLog("Receiver toggled state to: $currentState")
                        lastState = currentState
                        updateNotification(currentState, updatedPacketCount)
                    }
                    _snapState.value = _snapState.value.copy(
                        receiverState = currentState,
                        currentRms = rawRms,
                        packetCount = updatedPacketCount,
                        totalBytesReceived = updatedBytes,
                        lastPacketTime = now
                    )
                }

            } catch (e: SocketTimeoutException) {
                if (currentState != ReceiverState.SLEEP) {
                    currentState = ReceiverState.SLEEP
                    addLog("Network socket timeout (No packet received for 2s). Transitioning to SLEEP.")
                    try {
                        audioTrack?.stop()
                        audioTrack?.flush()
                    } catch (e: Exception) {
                        Log.e("AudioService", "Error stopping audiotrack: ${e.message}")
                    }
                    silenceCount = 0
                    _snapState.value = _snapState.value.copy(
                        receiverState = currentState,
                        currentRms = 0.0
                    )
                    updateNotification(currentState, _snapState.value.packetCount)
                }
            } catch (e: SocketException) {
                // Occurs normally on socket close
                addLog("UDP socket channel closed.")
                break
            } catch (e: IOException) {
                addLog("UDP network stream read IO Error: ${e.message}")
                break
            } catch (e: Exception) {
                addLog("Stream loop exception: ${e.message}")
                break
            }
        }

        cleanupAudioTrackAndSocket()
    }

    private fun calculateRMS(buffer: ByteArray, len: Int): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        val numSamples = len / 2
        if (numSamples == 0) return 0.0
        
        for (i in 0 until numSamples) {
            val idx = 2 * i
            if (idx + 1 < len) {
                val low = buffer[idx].toInt() and 0xFF
                val high = buffer[idx + 1].toInt()
                val shortSample = ((high shl 8) or low).toShort()
                sum += shortSample.toDouble() * shortSample.toDouble()
            }
        }
        return Math.sqrt(sum / numSamples)
    }

    private fun cleanupAudioTrackAndSocket() {
        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    try {
                        stop()
                    } catch (e: Exception) {}
                    release()
                }
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.e("AudioService", "Error releasing AudioTrack: ${e.message}")
        }

        try {
            socket?.apply {
                if (!isClosed) {
                    close()
                }
            }
            socket = null
        } catch (e: Exception) {
            Log.e("AudioService", "Error closing Socket: ${e.message}")
        }
    }

    private fun stopServiceGracefully() {
        if (!_snapState.value.isRunning) return
        
        addLog("Shutting down foreground service process.")
        
        serviceJob?.cancel()
        serviceJob = null

        cleanupAudioTrackAndSocket()

        try {
            wakeLock?.apply {
                if (isHeld) release()
            }
            wakeLock = null
            
            wifiLock?.apply {
                if (isHeld) release()
            }
            wifiLock = null
            addLog("All network system-wake configurations released.")
        } catch (e: Exception) {
            Log.e("AudioService", "Wakelocks release error: ${e.message}")
        }

        _snapState.value = ServiceStateSnapshot(
            isRunning = false,
            receiverState = ReceiverState.SLEEP,
            currentRms = 0.0,
            statusLogs = logList.toList()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "UDP Audio Receiver Service"
            val descriptionText = "Monitors and playback incoming audio over local network"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(stateText: String, detailText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UDP Audio Receiver Server")
            .setContentText("$stateText | $detailText")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$stateText\n$detailText\nPort: $port, Sample Rate: $sampleRate Hz"))
            .build()
    }

    private fun updateNotification(state: ReceiverState, packetCount: Long) {
        val stateString = when(state) {
            ReceiverState.SLEEP -> "SLEEP (Listening...)"
            ReceiverState.PLAYING -> "PLAYING (Streaming activity)"
            ReceiverState.IDLE -> "IDLE (Silence threshold matched)"
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(stateString, "Port: $port | Total packets received: $packetCount")
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopServiceGracefully()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
