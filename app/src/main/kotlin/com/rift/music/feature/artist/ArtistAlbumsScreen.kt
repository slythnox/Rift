/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.feature.artist

import com.rift.music.ui.utils.appTopBarWindowInsets
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.grid.GridCells
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.grid.items
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.Icon
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.MaterialTheme
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.SnackbarHost
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.material3.SnackbarHostState
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
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.getValue
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.mutableStateListOf
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.mutableStateOf
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.remember
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.rememberCoroutineScope
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.saveable.listSaver
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.saveable.rememberSaveable
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.setValue
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.runtime.toMutableStateList
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.Alignment
import com.rift.music.ui.utils.appTopBarWindowInsets
import androidx.compose.ui.Modifier
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
import com.rift.music.constants.CONTENT_TYPE_ALBUM
import com.rift.music.constants.CONTENT_TYPE_HEADER
import com.rift.music.constants.GridItemSize
import com.rift.music.constants.GridItemsSizeKey
import com.rift.music.constants.GridThumbnailHeight
import com.rift.music.ui.component.IconButton
import com.rift.music.ui.component.LibraryAlbumGridItem
import com.rift.music.ui.component.ListScrollRail
import com.rift.music.ui.component.LocalMenuState
import com.rift.music.ui.utils.appTopBarWindowInsets
import com.rift.music.ui.utils.backToMain
import com.rift.music.ui.utils.rememberGridColumns
import com.rift.music.utils.rememberEnumPreference
import com.rift.music.feature.artist.ArtistAlbumsViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistAlbumsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistAlbumsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val artist by viewModel.artist.collectAsState()
    val albums by viewModel.albums.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyGridState = rememberLazyGridState()
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    val snackbarHostState = remember { SnackbarHostState() }

    val visibleAlbums = remember(albums) { albums.distinctBy { it.id } }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyVerticalGrid(
            state = lazyGridState,
            columns = rememberGridColumns(),
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
        ) {
            item(
                key = "header",
                span = { GridItemSpan(maxLineSpan) },
                contentType = CONTENT_TYPE_HEADER
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.weight(1f))

                    Text(
                        text = pluralStringResource(R.plurals.n_album, albums.size, albums.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            items(
                items = visibleAlbums,
                key = { it.id },
                contentType = { CONTENT_TYPE_ALBUM }
            ) { album ->
                LibraryAlbumGridItem(
                    navController = navController,
                    menuState = menuState,
                    coroutineScope = coroutineScope,
                    album = album,
                    isActive = album.id == mediaMetadata?.album?.id,
                    isPlaying = isPlaying,
                    modifier = Modifier.animateItem()
                )
            }
        }

        // No sort control on this screen -- albums arrive in release order -- so the rail
        // is a proportional thumb rather than letters.
        ListScrollRail(
            lazyGridState = lazyGridState,
            itemCount = visibleAlbums.size,
            sectionIndexMap = null,
        )

        TopAppBar(
            windowInsets = appTopBarWindowInsets(),
            title = { Text(artist?.artist?.name.orEmpty()) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_back),
                        contentDescription = null
                    )
                }
            },
            scrollBehavior = scrollBehavior
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }
}

