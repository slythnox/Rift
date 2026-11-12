/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.rift.music.playback.MusicService

class TurntableWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Only trigger update through MusicService if it's already running
        // This prevents BackgroundServiceStartNotAllowedException on Android 14+
        if (MusicService.isRunning) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = ACTION_UPDATE_TURNTABLE_WIDGET
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Service might be restricted in background
            }
        } else {
            // Standby initialization: ensure placed widget displays dark card and click-to-open action
            val openAppIntent = android.app.PendingIntent.getActivity(
                context,
                0,
                Intent(context, com.rift.music.app.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            appWidgetIds.forEach { id ->
                val views = android.widget.RemoteViews(context.packageName, com.rift.music.R.layout.widget_vinyl_deck)
                views.setInt(com.rift.music.R.id.widget_vinyl_root, "setBackgroundResource", com.rift.music.R.drawable.widget_vinyl_card_bg)
                views.setTextViewText(com.rift.music.R.id.widget_vinyl_title, "Rift")
                views.setTextViewText(com.rift.music.R.id.widget_vinyl_artist, "Ready to play")
                views.setImageViewResource(com.rift.music.R.id.widget_vinyl_album_art, com.rift.music.R.drawable.preview_widget_dot)
                views.setImageViewResource(com.rift.music.R.id.widget_vinyl_disc, com.rift.music.R.drawable.ic_widget_vinyl_disc_idle)
                views.setImageViewResource(com.rift.music.R.id.widget_vinyl_play_pause, com.rift.music.R.drawable.ic_widget_play)
                views.setViewVisibility(com.rift.music.R.id.widget_vinyl_controls, android.view.View.VISIBLE)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_vinyl_root, openAppIntent)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_vinyl_disc, openAppIntent)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_vinyl_play_pause, openAppIntent)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_vinyl_album_art, openAppIntent)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_vinyl_bg, openAppIntent)
                try {
                    appWidgetManager.updateAppWidget(id, views)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        if (MusicService.isRunning) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = ACTION_UPDATE_TURNTABLE_WIDGET
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Service might be restricted in background
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TURNTABLE_PLAY_PAUSE, ACTION_TURNTABLE_NEXT, ACTION_TURNTABLE_PREVIOUS -> {
                // User interactions from widget buttons can start the service
                // Android allows starting FGS from widget PendingIntent clicks
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = when (intent.action) {
                        ACTION_TURNTABLE_PLAY_PAUSE -> MusicWidgetReceiver.ACTION_PLAY_PAUSE
                        ACTION_TURNTABLE_NEXT -> MusicWidgetReceiver.ACTION_NEXT
                        ACTION_TURNTABLE_PREVIOUS -> MusicWidgetReceiver.ACTION_PREVIOUS
                        else -> intent.action
                    }
                    putExtras(intent)
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    // Service might be restricted in background
                }
            }
        }
    }

    companion object {
        const val ACTION_TURNTABLE_PLAY_PAUSE = "com.rift.music.widget.TURNTABLE_PLAY_PAUSE"
        const val ACTION_TURNTABLE_NEXT = "com.rift.music.widget.TURNTABLE_NEXT"
        const val ACTION_TURNTABLE_PREVIOUS = "com.rift.music.widget.TURNTABLE_PREVIOUS"
        const val ACTION_UPDATE_TURNTABLE_WIDGET = "com.rift.music.widget.UPDATE_TURNTABLE_WIDGET"
    }
}
