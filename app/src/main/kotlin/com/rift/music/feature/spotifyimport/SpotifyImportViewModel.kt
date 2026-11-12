package com.rift.music.feature.spotifyimport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import com.rift.music.R
import com.rift.music.db.MusicDatabase
import com.rift.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpotifyImportUiState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class SpotifyImportProgress(
    val playlistName: String,
    val currentSongIndex: Int,
    val totalSongs: Int,
    val percent: Float,
    val isFinished: Boolean = false,
)

@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {

    private val playlistImporter = SpotifyPlaylistImporter(database)

    private val _uiState = MutableStateFlow(SpotifyImportUiState(isLoading = false))
    val uiState: StateFlow<SpotifyImportUiState> = _uiState.asStateFlow()

    val importProgress: StateFlow<SpotifyImportProgress?> = _globalImportProgress.asStateFlow()
    val showDialog: StateFlow<Boolean> = _globalShowDialog.asStateFlow()
    val isBackgrounded: StateFlow<Boolean> = _globalIsBackgrounded.asStateFlow()

    fun doInBackground() {
        _globalIsBackgrounded.value = true
        _globalShowDialog.value = false
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                context.getString(R.string.spotify_import_running_in_background),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun cancelImport() {
        activeImportJob?.cancel()
        activeImportJob = null
        _globalImportProgress.update { null }
        _globalShowDialog.value = false
        _globalIsBackgrounded.value = false
        _uiState.update { it.copy(isLoading = false) }
    }

    fun dismissImportProgress() {
        _globalShowDialog.value = false
        _globalImportProgress.update { null }
        _globalIsBackgrounded.value = false
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun importPlaylistByLink(link: String) {
        if (activeImportJob?.isActive == true) {
            Toast.makeText(context, context.getString(R.string.spotify_import_running_in_background), Toast.LENGTH_SHORT).show()
            return
        }

        _globalImportProgress.update { null }
        _globalShowDialog.value = true
        _globalIsBackgrounded.value = false
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val appContext = context.applicationContext
        activeImportJob = backgroundScope.launch {
            try {
                var finalPlaylistName = ""
                var finalTotalSongs = 0

                playlistImporter.importPlaylistByLink(link) { playlistName, currentSongIndex, totalSongs ->
                    finalPlaylistName = playlistName
                    finalTotalSongs = totalSongs
                    if (currentSongIndex == 0) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    _globalImportProgress.update {
                        SpotifyImportProgress(
                            playlistName = playlistName,
                            currentSongIndex = currentSongIndex,
                            totalSongs = totalSongs,
                            percent = if (totalSongs > 0) currentSongIndex.toFloat() / totalSongs else 0f,
                        )
                    }
                }

                val current = _globalImportProgress.value
                val importedCount = current?.currentSongIndex ?: finalTotalSongs
                val resolvedName = current?.playlistName?.ifBlank { finalPlaylistName } ?: finalPlaylistName

                _globalImportProgress.update { old ->
                    old?.copy(isFinished = true) ?: SpotifyImportProgress(
                        playlistName = resolvedName,
                        currentSongIndex = importedCount,
                        totalSongs = importedCount,
                        percent = 1.0f,
                        isFinished = true,
                    )
                }

                showCompletionNotification(appContext, resolvedName, importedCount)
                Handler(Looper.getMainLooper()).post {
                    val finishMsg = if (resolvedName.isNotBlank()) {
                        appContext.getString(R.string.spotify_import_notification_text, resolvedName, importedCount)
                    } else {
                        appContext.getString(R.string.spotify_imported_songs_count, importedCount, importedCount)
                    }
                    Toast.makeText(appContext, finishMsg, Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    reportException(error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Failed to import playlist",
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
                activeImportJob = null
                if (_globalImportProgress.value?.isFinished != true) {
                    _globalImportProgress.update { null }
                    _globalShowDialog.value = false
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "spotify_import_channel"
        private const val NOTIFICATION_ID = 2001

        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var activeImportJob: Job? = null
        private val _globalImportProgress = MutableStateFlow<SpotifyImportProgress?>(null)
        private val _globalShowDialog = MutableStateFlow(false)
        private val _globalIsBackgrounded = MutableStateFlow(false)
        private var channelCreated = false

        fun ensureNotificationChannel(context: Context) {
            if (channelCreated) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.spotify_import_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.spotify_import_notification_channel_desc)
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.createNotificationChannel(channel)
                channelCreated = true
            }
        }

        fun showCompletionNotification(context: Context, playlistName: String, songCount: Int) {
            ensureNotificationChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val intent = Intent(context, com.rift.music.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = context.getString(R.string.spotify_import_complete)
            val text = if (playlistName.isNotBlank()) {
                context.getString(R.string.spotify_import_notification_text, playlistName, songCount)
            } else {
                context.getString(R.string.spotify_imported_songs_count, songCount, songCount)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_napster)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            try {
                notificationManager.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // Runtime permission not granted on Android 13+
            } catch (e: Exception) {
                reportException(e)
            }
        }
    }
}
