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
    val channelNumber: Int = 0
) : Serializable
