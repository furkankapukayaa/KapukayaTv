package com.example.data.db.entity

import androidx.room.Entity

@Entity(tableName = "category_channel_cross_ref", primaryKeys = ["categoryId", "channelId"])
data class CategoryChannelCrossRef(
    val categoryId: Long,
    val channelId: String
)
