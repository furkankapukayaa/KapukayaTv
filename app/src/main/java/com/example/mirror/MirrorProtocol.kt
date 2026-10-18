package com.example.mirror

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Telefon (gönderici) ile TV (alıcı) arasındaki basit ekran yansıtma protokolü (TCP).
 *
 * Bağlantı açılınca gönderici: "KTVM" + sürüm (int) + cihaz adı (UTF) yollar.
 * Ardından paketler: tür (byte) + uzunluk (int) + zaman damgası µs (long) + veri.
 *  - VIDEO_FORMAT: genişlik (int) + yükseklik (int)  → alıcı çözücüyü bu boyutla (yeniden) kurar
 *  - VIDEO_FRAME : H.264 verisi; bayraklar zaman damgasından önce ilk byte'ta (config / keyframe)
 *  - AUDIO_FORMAT: örnekleme hızı (int) + kanal sayısı (int)
 *  - AUDIO_PCM   : 16 bit PCM ses
 *  - BYE         : gönderici yansıtmayı bitirdi
 */
object MirrorProtocol {
    const val MAGIC = "KTVM"
    const val VERSION = 1
    const val PORT = 8790
    const val SERVICE_TYPE = "_kapukayatv._tcp."

    const val VIDEO_FORMAT: Byte = 1
    const val VIDEO_FRAME: Byte = 2
    const val AUDIO_FORMAT: Byte = 3
    const val AUDIO_PCM: Byte = 4
    const val BYE: Byte = 9

    const val FLAG_CONFIG = 1
    const val FLAG_KEYFRAME = 2

    private const val MAX_PACKET_BYTES = 8 * 1024 * 1024

    class Packet(val type: Byte, val flags: Int, val ptsUs: Long, val data: ByteArray)

    fun writeHandshake(out: DataOutputStream, deviceName: String) {
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeInt(VERSION)
        out.writeUTF(deviceName.take(60))
        out.flush()
    }

    /** El sıkışmayı okur; geçersizse IOException fırlatır. Gönderen cihaz adını döndürür. */
    fun readHandshake(input: DataInputStream): String {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw IOException("Geçersiz bağlantı")
        val version = input.readInt()
        if (version != VERSION) throw IOException("Uygulama sürümleri uyumsuz (telefon: $version, TV: $VERSION)")
        return input.readUTF()
    }

    /** Paket yazar. Birden fazla iş parçacığı aynı akışa yazdığı için çağıran senkronize etmelidir. */
    fun writePacket(out: DataOutputStream, type: Byte, flags: Int, ptsUs: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        out.writeByte(type.toInt())
        out.writeInt(length)
        out.writeByte(flags)
        out.writeLong(ptsUs)
        out.write(data, offset, length)
    }

    fun readPacket(input: DataInputStream): Packet {
        val type = input.readByte()
        val length = input.readInt()
        if (length < 0 || length > MAX_PACKET_BYTES) throw IOException("Bozuk paket")
        val flags = input.readUnsignedByte()
        val pts = input.readLong()
        val data = ByteArray(length)
        input.readFully(data)
        return Packet(type, flags, pts, data)
    }

    fun intPair(a: Int, b: Int): ByteArray = java.nio.ByteBuffer.allocate(8).putInt(a).putInt(b).array()

    fun readIntPair(data: ByteArray): Pair<Int, Int> {
        val buffer = java.nio.ByteBuffer.wrap(data)
        return buffer.int to buffer.int
    }
}
