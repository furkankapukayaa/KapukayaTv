package com.example.mirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TV tarafı: telefondan gelen görüntüyü donanım çözücüyle doğrudan ekrana çizer, sesi çalar.
 * Gecikmeyi düşük tutmak için kareler gelir gelmez gösterilir; ses kuyruğu dolarsa eski parçalar atılır.
 */
class MirrorReceiver(private val listener: Listener) {

    interface Listener {
        fun onWaiting(port: Int)
        fun onConnected(deviceName: String)
        fun onVideoSize(width: Int, height: Int)
        fun onDisconnected(reason: String?)
    }

    @Volatile
    private var surface: Surface? = null
    private var serverSocket: ServerSocket? = null
    private var client: Socket? = null
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private val audioQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CHUNKS)

    @Volatile
    private var running = false

    /** Son gelen boyut ve config (SPS/PPS): çözücü sonradan kurulursa yeniden kullanılır. */
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastConfig: ByteArray? = null
    private var waitingForKeyframe = true

    fun setSurface(s: Surface?) {
        surface = s
    }

    fun start() {
        if (running) return
        running = true
        Thread({ serverLoop() }, "mirror-server").start()
    }

    fun stop() {
        running = false
        try { client?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        releaseMedia()
    }

    private fun resetSession() {
        lastWidth = 0
        lastHeight = 0
        lastConfig = null
    }

    private fun serverLoop() {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(MirrorProtocol.PORT))
            }
        } catch (e: Exception) {
            listener.onDisconnected("Bağlantı noktası açılamadı (${MirrorProtocol.PORT}). Uygulamayı yeniden başlatın.")
            running = false
            return
        }
        serverSocket = server

        while (running) {
            listener.onWaiting(MirrorProtocol.PORT)
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                continue
            }
            client = socket
            var reason: String? = null
            try {
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 1024 * 1024
                handleClient(socket)
            } catch (e: EOFException) {
                reason = "Telefonla bağlantı kesildi"
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Yansıtma hatası", e)
                    reason = e.message ?: "Bağlantı kesildi"
                }
            } finally {
                try { socket.close() } catch (e: Exception) {}
                client = null
                releaseMedia()
                resetSession()
                if (running) listener.onDisconnected(reason)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        val deviceName = MirrorProtocol.readHandshake(input)
        listener.onConnected(deviceName)

        while (running) {
            val packet = MirrorProtocol.readPacket(input)
            when (packet.type) {
                MirrorProtocol.VIDEO_FORMAT -> {
                    val (w, h) = MirrorProtocol.readIntPair(packet.data)
                    lastWidth = w
                    lastHeight = h
                    lastConfig = null
                    setupDecoder(w, h)
                    listener.onVideoSize(w, h)
                }
                MirrorProtocol.VIDEO_FRAME -> handleVideoFrame(packet)
                MirrorProtocol.AUDIO_FORMAT -> {
                    val (rate, channels) = MirrorProtocol.readIntPair(packet.data)
                    setupAudio(rate, channels)
                }
                MirrorProtocol.AUDIO_PCM -> {
                    // Kuyruk doluysa en eski parçayı at: ses gecikmesi birikmesin
                    if (!audioQueue.offer(packet.data)) {
                        audioQueue.poll()
                        audioQueue.offer(packet.data)
                    }
                }
                MirrorProtocol.BYE -> throw EOFException()
            }
        }
    }

    private fun setupDecoder(width: Int, height: Int) {
        releaseDecoder()
        val target = surface ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, target, null, 0)
        codec.start()
        decoder = codec
        waitingForKeyframe = true
        lastConfig?.let { queue(codec, it, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
    }

    private fun handleVideoFrame(packet: MirrorProtocol.Packet) {
        val isConfig = packet.flags and MirrorProtocol.FLAG_CONFIG != 0
        val isKey = packet.flags and MirrorProtocol.FLAG_KEYFRAME != 0
        if (isConfig) lastConfig = packet.data
        // Ekran yüzeyi bağlantıdan sonra hazır olduysa çözücüyü şimdi kur
        if (decoder == null && surface != null && lastWidth > 0) setupDecoder(lastWidth, lastHeight)
        val codec = decoder ?: return
        if (isConfig) {
            queue(codec, packet.data, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            return
        }
        // Bağlantı başında anahtar kare gelene kadar bekle (bozuk görüntü olmasın)
        if (waitingForKeyframe && !isKey) return
        waitingForKeyframe = false
        queue(codec, packet.data, packet.ptsUs, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        drainDecoder(codec)
    }

    private fun queue(codec: MediaCodec, data: ByteArray, ptsUs: Long, flags: Int) {
        try {
            var index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            var attempts = 0
            while (index < 0 && attempts < 10 && running) {
                drainDecoder(codec)
                index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                attempts++
            }
            if (index < 0) return // çözücü yetişemiyor: kareyi atla
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            if (data.size > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                return
            }
            buffer.put(data)
            codec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Çözücüye veri verilemedi", e)
        }
    }

    /** Hazır kareleri bekletmeden ekrana çizer. */
    private fun drainDecoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, true)
                } else {
                    break
                }
            }
        } catch (e: IllegalStateException) {
            // Çözücü kapanıyor
        }
    }

    private fun setupAudio(sampleRate: Int, channels: Int) {
        releaseAudio()
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track
        audioQueue.clear()

        audioThread = Thread({
            while (running && audioTrack === track) {
                val chunk = try {
                    audioQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                try {
                    track.write(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    break
                }
            }
        }, "mirror-audio-out").apply { start() }
    }

    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
    }

    private fun releaseAudio() {
        val track = audioTrack
        audioTrack = null
        audioThread?.interrupt()
        audioThread = null
        try { track?.stop() } catch (e: Exception) {}
        track?.release()
        audioQueue.clear()
    }

    private fun releaseMedia() {
        releaseDecoder()
        releaseAudio()
    }

    companion object {
        private const val TAG = "MirrorReceiver"
        private const val INPUT_TIMEOUT_US = 10_000L
        // 20 ms'lik parçalar: en fazla ~200 ms ses tamponu
        private const val AUDIO_QUEUE_CHUNKS = 10
    }
}
