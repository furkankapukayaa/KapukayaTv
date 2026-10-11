package com.example.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.db.entity.CategoryChannelCrossRef
import com.example.data.model.Channel

@Dao
interface CategoryChannelCrossRefDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: CategoryChannelCrossRef)

    @Query("DELETE FROM category_channel_cross_ref WHERE categoryId = :categoryId AND channelId = :channelId")
    suspend fun removeChannelFromCategory(categoryId: Long, channelId: String)

    @Query("""
        SELECT channels.* FROM channels 
        INNER JOIN category_channel_cross_ref ON channels.id = category_channel_cross_ref.channelId 
        WHERE category_channel_cross_ref.categoryId = :categoryId
    """)
    suspend fun getChannelsForCategory(categoryId: Long): List<Channel>

    @Query("SELECT categoryId FROM category_channel_cross_ref WHERE channelId = :channelId")
    suspend fun getCategoriesForChannel(channelId: String): List<Long>
}
