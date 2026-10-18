package com.example.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MirrorSenderActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Gönderici durumu (telefon ekranında gösterilir). */
sealed class MirrorSendState {
    object Idle : MirrorSendState()
    data class Connecting(val target: String) : MirrorSendState()
    data class Streaming(val target: String, val withAudio: Boolean) : MirrorSendState()
    data class Error(val message: String) : MirrorSendState()
}

/**
 * Telefon ekranını (ve Android 10+ cihazlarda sesini) yakalayıp TV'deki Kapukaya TV'ye gönderen servis.
 * Görüntü: MediaProjection → VirtualDisplay → H.264 donanım kodlayıcı.
 * Ses: AudioPlaybackCapture → 48 kHz stereo PCM.
 */
class MirrorSenderService : Service() {

    companion object {
        private const val TAG = "MirrorSender"
        private const val CHANNEL_ID = "mirror_sender"
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_TARGET_NAME = "target_name"
        private const val ACTION_STOP = "com.example.mirror.STOP"

        private const val VIDEO_BITRATE = 8_000_000
        private const val VIDEO_FPS = 30
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 2

        private val _state = MutableStateFlow<MirrorSendState>(MirrorSendState.Idle)
        val state: StateFlow<MirrorSendState> = _state

        fun start(context: Context, resultCode: Int, data: Intent, target: MirrorTarget) {
            val intent = Intent(context, MirrorSenderService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_HOST, target.host)
                putExtra(EXTRA_PORT, target.port)
                putExtra(EXTRA_TARGET_NAME, target.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorSenderService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var audioRecord: AudioRecord? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    @Volatile
    private var running = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var targetName = ""
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMirroring(null)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, MirrorProtocol.PORT) ?: MirrorProtocol.PORT
        targetName = intent?.getStringExtra(EXTRA_TARGET_NAME) ?: host.orEmpty()

        // Android 14+: MediaProjection alınmadan ÖNCE ön plan servisi başlatılmalı
        startAsForeground()

        if (data == null || host == null || resultCode != Activity.RESULT_OK) {
            stopMirroring("Ekran kaydı izni verilmedi")
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            stopMirroring("Ekran yakalama başlatılamadı")
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopMirroring(null)
            }
        }, null)

        running = true
        _state.value = MirrorSendState.Connecting(targetName)
        Thread({ connectAndStream(host, port) }, "mirror-connect").start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ekran yansıtma", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MirrorSenderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, MirrorSenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Ekran TV'ye yansıtılıyor")
            .setContentText(targetName.ifEmpty { "Kapukaya TV" })
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Durdur", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun connectAndStream(host: String, port: Int) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.sendBufferSize = 512 * 1024
            s.connect(InetSocketAddress(host, port), 5000)
            socket = s
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
            output = out
            MirrorProtocol.writeHandshake(out, Build.MODEL ?: "Telefon")

            lastOrientation = resources.configuration.orientation
            startVideo()
            val withAudio = startAudio()
            _state.value = MirrorSendState.Streaming(targetName, withAudio)
        } catch (e: Exception) {
            Log.w(TAG, "Bağlantı hatası", e)
            stopMirroring("TV'ye bağlanılamadı. Aynı Wi-Fi ağında olduğunuzdan ve TV'de \"Bu cihazda izle\" ekranının açık olduğundan emin olun.")
        }
    }

    /** Ekran boyutunu kodlayıcının desteklediği en yüksek boyuta ölçekler. */
    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            ?.videoCapabilities

        for (longSide in intArrayOf(1920, 1600, 1280, 960, 720)) {
            val scale = minOf(1f, longSide.toFloat() / maxOf(screenW, screenH))
            val w = ((screenW * scale).toInt() / 16) * 16
            val h = ((screenH * scale).toInt() / 16) * 16
            if (caps == null || caps.isSizeSupported(w, h)) return w to h
        }
        return 720 to 1280
    }

    private fun createEncoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Ekran değişmese de kare gönder (TV tarafı donmuş görünmesin)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // gerçek zamanlı
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun startVideo() {
        val (width, height) = captureSize()
        val codec = createEncoder(width, height)
        val surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        encoderSurface = surface

        sendPacket(MirrorProtocol.VIDEO_FORMAT, 0, 0, MirrorProtocol.intPair(width, height))

        val density = resources.displayMetrics.densityDpi
        val existing = virtualDisplay
        if (existing == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "KapukayaMirror", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
        } else {
            existing.resize(width, height, density)
            existing.surface = surface
        }

        videoThread = Thread({ drainEncoder(codec) }, "mirror-video").apply { start() }
    }

    private fun drainEncoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var buffer = ByteArray(256 * 1024)
        while (running && encoder === codec) {
            val index = try {
                codec.dequeueOutputBuffer(info, 50_000)
            } catch (e: IllegalStateException) {
                break // kodlayıcı yeniden başlatıldı
            }
            if (index < 0) continue
            try {
                val data = codec.getOutputBuffer(index)
                if (data != null && info.size > 0) {
                    if (buffer.size < info.size) buffer = ByteArray(info.size * 2)
                    data.position(info.offset)
                    data.get(buffer, 0, info.size)
                    var flags = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or MirrorProtocol.FLAG_CONFIG
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or MirrorProtocol.FLAG_KEYFRAME
                    sendPacket(MirrorProtocol.VIDEO_FRAME, flags, info.presentationTimeUs, buffer, info.size)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Görüntü gönderilemedi", e)
                stopMirroring("Bağlantı koptu")
                break
            }
        }
    }

    /** Ses yakalamayı başlatır. Android 10 altı veya izin yoksa false döner (yalnızca görüntü). */
    @SuppressLint("MissingPermission")
    private fun startAudio(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val mp = projection ?: return false
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, 16 * 1024) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record.startRecording()
            audioRecord = record
            sendPacket(MirrorProtocol.AUDIO_FORMAT, 0, 0, MirrorProtocol.intPair(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS))

            audioThread = Thread({
                // 20 ms'lik parçalar: düşük gecikme
                val chunk = ByteArray(AUDIO_SAMPLE_RATE / 50 * AUDIO_CHANNELS * 2)
                while (running) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        try {
                            sendPacket(MirrorProtocol.AUDIO_PCM, 0, System.nanoTime() / 1000, chunk, read)
                        } catch (e: Exception) {
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            }, "mirror-audio").apply { start() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Ses yakalanamadı", e)
            false
        }
    }

    private fun sendPacket(type: Byte, flags: Int, ptsUs: Long, data: ByteArray, length: Int = data.size) {
        val out = output ?: throw IllegalStateException("Bağlantı yok")
        synchronized(writeLock) {
            MirrorProtocol.writePacket(out, type, flags, ptsUs, data, 0, length)
            out.flush()
        }
    }

    /** Telefon döndürülünce kodlayıcı yeni boyutla yeniden kurulur. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!running || encoder == null || newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation
        Thread({
            try {
                val old = encoder
                encoder = null
                videoThread?.join(500)
                old?.stop()
                old?.release()
                encoderSurface?.release()
                startVideo()
            } catch (e: Exception) {
                Log.w(TAG, "Döndürme sonrası yeniden başlatılamadı", e)
            }
        }, "mirror-rotate").start()
    }

    @Synchronized
    private fun stopMirroring(error: String?) {
        val wasRunning = running
        running = false

        try {
            output?.let { out -> synchronized(writeLock) { MirrorProtocol.writePacket(out, MirrorProtocol.BYE, 0, 0, ByteArray(0)); out.flush() } }
        } catch (e: Exception) {
            // Bağlantı zaten kopmuş olabilir
        }
        try { audioRecord?.stop() } catch (e: Exception) {}
        audioRecord?.release()
        audioRecord = null

        val codec = encoder
        encoder = null
        try { codec?.stop() } catch (e: Exception) {}
        codec?.release()
        encoderSurface?.release()
        encoderSurface = null

        virtualDisplay?.release()
        virtualDisplay = null
        try { projection?.stop() } catch (e: Exception) {}
        projection = null

        try { socket?.close() } catch (e: Exception) {}
        socket = null
        output = null

        _state.value = if (error != null) MirrorSendState.Error(error) else MirrorSendState.Idle
        if (wasRunning || error != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopMirroring(null)
        super.onDestroy()
    }
}
