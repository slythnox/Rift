/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.feature.artist

import com.rift.music.ui.utils.appTopBarWindowInsets
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.ExperimentalFoundationApi

import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.Box
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.Row
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.Spacer
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.padding
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.itemsIndexed
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.rememberLazyListState
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.Icon
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.IconButton
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.MaterialTheme
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.Text
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.TopAppBar
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.TopAppBarScrollBehavior
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.Composable
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.getValue
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.Alignment
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.Modifier
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.platform.LocalContext
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.platform.LocalHapticFeedback
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.res.painterResource
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.res.pluralStringResource
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.unit.dp
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.navigation.NavController
import com.rift.music.LocalPlayerAwareWindowInsets
import com.rift.music.LocalPlayerConnection
import com.rift.music.R
import com.rift.music.constants.ArtistSongSortDescendingKey
import com.rift.music.constants.ArtistSongSortType
import com.rift.music.constants.ArtistSongSortTypeKey
import com.rift.music.constants.CONTENT_TYPE_HEADER
import com.rift.music.constants.HideExplicitKey
import com.rift.music.core.extensions.toMediaItem
import com.rift.music.playback.queue.ListQueue
import com.rift.music.ui.component.HideOnScrollFAB
import com.rift.music.ui.component.IconButton
import com.rift.music.ui.component.ListScrollRail
import com.rift.music.ui.component.LocalMenuState
import com.rift.music.ui.component.SongListItem
import com.rift.music.ui.component.SortHeader
import com.rift.music.ui.component.buildAlphabetSectionIndex
import com.rift.music.ui.menu.SongMenu
import com.rift.music.ui.utils.appTopBarWindowInsets
import com.rift.music.ui.utils.backToMain
import com.rift.music.ui.utils.bounceClick
import com.rift.music.ui.utils.combinedBounceClick
import com.rift.music.utils.listItemShape
import com.rift.music.utils.rememberEnumPreference
import com.rift.music.utils.rememberPreference
import com.rift.music.feature.library.ArtistSongsViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistSongsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistSongsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val (sortType, onSortTypeChange) = rememberEnumPreference(
        ArtistSongSortTypeKey,
        ArtistSongSortType.CREATE_DATE
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        ArtistSongSortDescendingKey,
        true
    )
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)
    val artist by viewModel.artist.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val lazyListState = rememberLazyListState()

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            item(
                key = "header",
                contentType = CONTENT_TYPE_HEADER,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    SortHeader(
                        sortType = sortType,
                        sortDescending = sortDescending,
                        onSortTypeChange = onSortTypeChange,
                        onSortDescendingChange = onSortDescendingChange,
                        sortTypeText = { sortType ->
                            when (sortType) {
                                ArtistSongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                ArtistSongSortType.NAME -> R.string.sort_by_name
                                ArtistSongSortType.PLAY_TIME -> R.string.sort_by_play_time
                            }
                        },
                    )

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            itemsIndexed(
                items = songs,
                key = { _, item -> item.id },
            ) { index, song ->
                SongListItem(
                    song = song,
                    showInLibraryIcon = true,
                    isActive = song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    shape = listItemShape(index, songs.size),
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    },
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .combinedBounceClick(
                            onClick = {
                                if (song.id == mediaMetadata?.id) {
                                    playerConnection.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = context.getString(R.string.queue_all_songs),
                                            items = songs.map { it.toMediaItem() },
                                            startIndex = index,
                                        ),
                                    )
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        )
                        .animateItem(),
                )
            }
        }

        TopAppBar(
            windowInsets = appTopBarWindowInsets(),
            title = { Text(artist?.artist?.name.orEmpty()) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )

        ListScrollRail(
            lazyListState = lazyListState,
            itemCount = songs.size,
            sectionIndexMap = if (sortType == ArtistSongSortType.NAME) {
                remember(songs) { buildAlphabetSectionIndex(songs) { it.title } }
            } else {
                null
            },
        )

        HideOnScrollFAB(
            lazyListState = lazyListState,
            icon = R.drawable.shuffle,
            onClick = {
                playerConnection.playQueue(
                    ListQueue(
                        title = artist?.artist?.name,
                        items = songs.shuffled().map { it.toMediaItem() },
                    ),
                )
            },
        )
    }
}



