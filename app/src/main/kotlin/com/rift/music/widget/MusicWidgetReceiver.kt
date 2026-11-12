/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.rift.music.playback.MusicService

open class MusicWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Only trigger update through MusicService if it's already running
        // This prevents BackgroundServiceStartNotAllowedException on Android 14+
        if (MusicService.isRunning) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = ACTION_UPDATE_WIDGET
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
                val views = android.widget.RemoteViews(context.packageName, com.rift.music.R.layout.widget_dot_player)
                views.setImageViewResource(com.rift.music.R.id.widget_dot_center_art, com.rift.music.R.drawable.preview_widget_dot)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_dot_root, openAppIntent)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_dot_center_art, openAppIntent)
                views.setOnClickPendingIntent(com.rift.music.R.id.widget_dot_action_play_pause, openAppIntent)
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
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Trigger widget update when size changes
        if (MusicService.isRunning) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = ACTION_UPDATE_WIDGET
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
            ACTION_PLAY_PAUSE, ACTION_LIKE, ACTION_NEXT, ACTION_PREVIOUS -> {
                // User interactions from widget buttons can start the service.
                // Use startForegroundService so the system grants FGS privilege
                // on Android 12+ (API 31+) where background FGS starts are banned.
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = intent.action
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
        const val ACTION_PLAY_PAUSE = "com.rift.music.widget.PLAY_PAUSE"
        const val ACTION_LIKE = "com.rift.music.widget.LIKE"
        const val ACTION_NEXT = "com.rift.music.widget.NEXT"
        const val ACTION_PREVIOUS = "com.rift.music.widget.PREVIOUS"
        const val ACTION_UPDATE_WIDGET = "com.rift.music.widget.UPDATE_WIDGET"
    }
}
