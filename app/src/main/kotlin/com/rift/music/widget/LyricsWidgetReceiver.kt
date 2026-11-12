/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.rift.music.R
import com.rift.music.app.MainActivity
import com.rift.music.playback.MusicService

/**
 * Rift Lyrics Player widget (Widget B):
 * Split-panel 2×4 layout with album artwork and synchronised lyrics.
 *
 * Inherits transport and update forwarding from [MusicWidgetReceiver].
 */
class LyricsWidgetReceiver : MusicWidgetReceiver() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (MusicService.isRunning) {
            super.onUpdate(context, appWidgetManager, appWidgetIds)
        } else {
            // Standby initialization: ensure placed widget displays dark card and click-to-open action
            val openAppIntent = android.app.PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            appWidgetIds.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_lyrics_player)
                views.setInt(R.id.widget_lyrics_root, "setBackgroundResource", R.drawable.widget_lyrics_card_bg)
                // Use dedicated proportional standby panel with note and red accent divider
                views.setImageViewResource(R.id.widget_lyrics_art, R.drawable.ic_widget_lyrics_art_idle)
                views.setTextViewText(R.id.widget_lyrics_prev_text, "Rift")
                views.setTextViewText(R.id.widget_lyrics_active_text, "Tap to start listening")
                views.setTextViewText(R.id.widget_lyrics_dots_indicator, "•  •  •")
                views.setOnClickPendingIntent(R.id.widget_lyrics_root, openAppIntent)
                views.setOnClickPendingIntent(R.id.widget_lyrics_art, openAppIntent)
                views.setOnClickPendingIntent(R.id.widget_lyrics_action_play_pause, openAppIntent)
                views.setOnClickPendingIntent(R.id.widget_lyrics_bg, openAppIntent)
                try {
                    appWidgetManager.updateAppWidget(id, views)
                } catch (_: Exception) {}
            }
        }
    }
}
