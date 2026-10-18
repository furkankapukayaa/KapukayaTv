package com.example.tv

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.example.R
import com.example.data.epg.EpgRepository
import com.example.data.model.Channel
import com.example.data.repository.ChannelRepository
import com.example.util.PreferenceManager
import com.example.util.TextUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android TV ana ekranında "Kapukaya TV" satırı: favori kanallar (favori yoksa son izlenenler).
 * Karta tıklanınca kapukayatv://channel/<id> bağlantısıyla kanal doğrudan açılır.
 * Android 8.0+ TV cihazlarında çalışır; diğer cihazlarda hiçbir şey yapmaz.
 */
object TvHomeChannels {
    private const val TAG = "TvHomeChannels"
    private const val MAX_PROGRAMS = 20
    const val SCHEME = "kapukayatv"
    const val HOST_CHANNEL = "channel"

    private val mutex = Mutex()

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun channelUri(channelId: String): Uri =
        Uri.parse("$SCHEME://$HOST_CHANNEL/" + Uri.encode(channelId))

    /** Bağlantıdan kanal kimliğini çıkarır; uygun değilse null. */
    fun channelIdFromUri(uri: Uri?): String? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST_CHANNEL) return null
        return uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    suspend fun sync(context: Context, repository: ChannelRepository) {
        if (!isSupported(context)) return
        mutex.withLock {
            try {
                doSync(context, repository)
            } catch (e: Throwable) {
                // Bazı TV'lerde içerik sağlayıcısı yok veya izin verilmiyor: sessizce geç
                Log.w(TAG, "TV ana ekran satırı güncellenemedi", e)
            }
        }
    }

    private fun doSync(context: Context, repository: ChannelRepository) {
        val prefs = PreferenceManager(context)
        val helper = PreviewChannelHelper(context)

        var channelId = prefs.getTvHomeChannelId()
        val existing = if (channelId != -1L) helper.getPreviewChannel(channelId) else null
        if (existing == null) {
            val logo = BitmapFactory.decodeResource(context.resources, R.drawable.logo)
            val channel = PreviewChannel.Builder()
                .setDisplayName(context.getString(R.string.tv_home_row_title))
                .setAppLinkIntentUri(Uri.parse("$SCHEME://home"))
                .setLogo(logo)
                .build()
            channelId = helper.publishDefaultChannel(channel)
            prefs.setTvHomeChannelId(channelId)
            prefs.setTvHomeProgramIds(emptyList())
        }

        // Eski kartları kaldırıp güncel listeyi yayınla
        prefs.getTvHomeProgramIds().forEach { id ->
            try {
                helper.deletePreviewProgram(id)
            } catch (e: Exception) {
                // Kullanıcı kartı zaten kaldırmış olabilir
            }
        }

        val channels = repository.getFavoriteChannels().ifEmpty { repository.getRecentChannels() }.take(MAX_PROGRAMS)
        val programIds = channels.mapNotNull { ch ->
            try {
                helper.publishPreviewProgram(buildProgram(channelId, ch))
            } catch (e: Exception) {
                Log.w(TAG, "Kart eklenemedi: ${ch.name}", e)
                null
            }
        }
        prefs.setTvHomeProgramIds(programIds)
    }

    private fun buildProgram(tvChannelId: Long, channel: Channel): PreviewProgram {
        val now = EpgRepository.nowAndNext(channel).first
        val description = now?.let { "Şimdi: ${it.title}" } ?: TextUtil.displayGroups(channel.groupTitle)

        val builder = PreviewProgram.Builder()
        builder.setChannelId(tvChannelId)
            .setType(TvContractCompat.PreviewProgramColumns.TYPE_CHANNEL)
            .setTitle(channel.name)
            .setDescription(description)
            .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            .setIntentUri(channelUri(channel.id))
            .setInternalProviderId(channel.id)
            .setLive(true)
        channel.logoUrl?.let { builder.setPosterArtUri(Uri.parse(it)) }
        return builder.build()
    }
}
