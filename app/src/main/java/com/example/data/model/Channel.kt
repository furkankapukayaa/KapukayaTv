package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    var isFavorite: Boolean = false,
    val orderIndex: Int = 0,
    val isPriority: Boolean = false,
    val channelNumber: Int = 0,
    // Aynı kanalın yedek yayın adresleri (satır satır). Ana yayın açılmazsa sırayla denenir.
    val alternateUrls: String? = null,
    // Görüntü kalitesi etiketi: 4K / FHD / HD / SD
    val quality: String? = null
) : Serializable {

    /** Ana yayın + yedek yayınlar, denenme sırasına göre. */
    fun allStreamUrls(): List<String> {
        val alternates = alternateUrls
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return (listOf(streamUrl) + alternates).distinct()
    }

    fun displayName(): String =
        if (channelNumber > 0) "$channelNumber. $name" else name
}
