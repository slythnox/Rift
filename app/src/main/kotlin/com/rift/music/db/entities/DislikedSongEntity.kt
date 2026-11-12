/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Immutable
@Entity(
    tableName = "disliked_song",
    indices = [
        Index(value = ["normalizedTitle", "normalizedArtist"])
    ]
)
data class DislikedSongEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Int = -1, // in seconds
    val thumbnailUrl: String? = null,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val dislikedAt: LocalDateTime = LocalDateTime.now(),
)
