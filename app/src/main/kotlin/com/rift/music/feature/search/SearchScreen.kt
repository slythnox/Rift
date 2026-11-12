/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.rift.music.ui.component.DefaultDialog
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.utils.YouTubeUrlParser
import com.rift.music.LocalDatabase
import com.rift.music.LocalIsPlayerExpanded
import com.rift.music.LocalPlayerAwareWindowInsets
import com.rift.music.LocalPlayerConnection
import com.rift.music.R
import com.rift.music.constants.PauseSearchHistoryKey
import com.rift.music.constants.LocalOnlyModeKey
import com.rift.music.constants.SearchSource
import com.rift.music.db.entities.SearchHistory
import com.rift.music.playback.queue.YouTubeQueue
import com.rift.music.ui.component.NavigationTitle
import com.rift.music.ui.utils.bounceClick
import com.rift.music.utils.rememberPreference
import com.rift.music.feature.home.MoodAndGenresViewModel
import com.rift.music.feature.search.suggestions.SuggestionsTabContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder
import androidx.compose.runtime.collectAsState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.foundation.layout.PaddingValues
import com.rift.music.ui.component.LargeScreenTitle
import com.rift.music.ui.component.HeroBackground
import com.rift.music.ui.component.HomeImageBackground
import com.rift.music.ui.component.rememberAppBackgroundColor
import com.rift.music.ui.component.rememberHeroSource
import com.rift.music.ui.component.rememberHeroTint
import com.rift.music.ui.theme.AppleTokens
import com.rift.music.ui.theme.HeroTintedContent

import com.rift.music.ui.component.LocalNavSearchState
import com.rift.music.ui.component.shapes.ContinuousRoundedRectangle
import com.rift.music.ui.component.backdrop.backdrops.layerBackdrop
import com.rift.music.ui.component.backdrop.backdrops.rememberBackdropFreeze
import com.rift.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import com.rift.music.ui.component.backdrop.catalog.components.LiquidBottomTab
import com.rift.music.ui.component.backdrop.catalog.components.LiquidBottomTabs
import androidx.compose.ui.text.font.FontWeight
import com.rift.music.ui.component.GlassCircleButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    pureBlack: Boolean
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isPlayerExpanded = LocalIsPlayerExpanded.current
    val playerConnection = LocalPlayerConnection.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // The nav bar owns the actual search text field now (see NavBarSearchInputBar
    // in FloatingNavBar.kt) â€” this screen just reads the live query to filter results.
    val navSearch = LocalNavSearchState.current
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)

    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    // The Explore/Suggestions/Albums tabs are all YouTube browse pages.
    val (localOnly) = rememberPreference(LocalOnlyModeKey, false)
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showSearchContent by remember { mutableStateOf(false) }


    // Fixed elevated token, not derived from any specific result's thumbnail â€”
    // safe to override with the user's own picked theme color.
    val tint = rememberAppBackgroundColor(AppleTokens.BgElevated)
    val onTint = AppleTokens.onColor(tint)
    val heroSource = rememberHeroSource(staticArt = null)
    val heroBackdrop = rememberLayerBackdrop {
        drawRect(tint)
        drawContent()
    }
    val backdropFreeze = rememberBackdropFreeze()

    HeroBackground(
        tint = tint,
        heroSource = heroSource,
        showDefaultIcon = false,
        modifier = Modifier.fillMaxSize(),
    ) {
      HomeImageBackground()
      HeroTintedContent(tint = tint, backdrop = heroBackdrop) {
        val chromeShape = ContinuousRoundedRectangle(percent = 50)
        val accent = com.rift.music.ui.theme.LocalAccentColor.current

        // Space for the floating side bar in tab view (0 otherwise). The hero
        // background stays full-bleed behind the Scaffold; only the foreground â€”
        // title, search pill, tabs and results â€” shifts clear of the panel. The
        // result lists inset bottom-only, so nothing double-pads.
        val sideInset = LocalPlayerAwareWindowInsets.current
            .asPaddingValues()
            .calculateStartPadding(LocalLayoutDirection.current)

        Scaffold(
            modifier = Modifier.padding(start = sideInset),
            topBar = {
                Column(
                    modifier = Modifier.background(Color.Transparent)
                ) {
                    LargeScreenTitle(
                        title = stringResource(R.string.search),
                        color = onTint,
                    )

                    AnimatedVisibility(
                        visible = navSearch.query.text.isEmpty() && !localOnly,
                        enter = expandVertically(animationSpec = tween(durationMillis = 245, easing = FastOutSlowInEasing)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = tween(durationMillis = 245, easing = FastOutSlowInEasing)) + fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            // Wide floating pill, swipeable with the same drag-puck
                            // ("pluck") feel as the floating nav bar's own tab selector.
                            LiquidBottomTabs(
                                selectedTabIndex = { selectedTabIndex },
                                onTabSelected = { selectedTabIndex = it },
                                backdrop = heroBackdrop,
                                tabsCount = 3,
                                // Blend into this screen's own tint instead of the
                                // component's default near-black glass fill.
                                containerColor = onTint.copy(alpha = 0.12f),
                                height = 44.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 30.dp , vertical = 16.dp)
                            ) {
                                LiquidBottomTab(onClick = { selectedTabIndex = 0 }) {
                                    Text(
                                        text = stringResource(R.string.tab_explore),
                                        color = if (selectedTabIndex == 0) accent else onTint.copy(alpha = 0.6f),
                                    )
                                }
                                LiquidBottomTab(onClick = { selectedTabIndex = 1 }) {
                                    Text(
                                        text = stringResource(R.string.tab_Suggestions),
                                        color = if (selectedTabIndex == 1) accent else onTint.copy(alpha = 0.6f),
                                    )
                                }
                                LiquidBottomTab(onClick = { selectedTabIndex = 2 }) {
                                    Text(
                                        text = stringResource(R.string.tab_history),
                                        color = if (selectedTabIndex == 2) accent else onTint.copy(alpha = 0.6f),
                                    )
                                }
                            }
                    }
                }
                }
            },
            // The bottom search pill used to live here â€” now it's part of the
            // floating nav bar itself (NavBarSearchInputBar in FloatingNavBar.kt),
            // which stays mounted across this whole screen instead of being owned
            // by it. See PLAN_search_navbar.md.
            containerColor = Color.Transparent
        ) { paddingValues ->
            val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
            
            Box(
                modifier = Modifier
                    .nestedScroll(backdropFreeze.connection)

                    // OUTER layer, and it must come BEFORE layerBackdrop: the layer has to
                    // enclose the backdrop node, or that node's draw re-runs whenever anything
                    // else in the window redraws. The mini player, the playing indicator and
                    // the position poll are all siblings that tick on their own schedule, and
                    // each tick was re-recording this entire list. MainActivity pairs an outer
                    // and inner layer for exactly this; the screen-local backdrops were left
                    // with only the inner half.
                    .graphicsLayer()
                    .layerBackdrop(heroBackdrop, frozen = backdropFreeze.frozen)
                    // Content becomes ONE cached RenderNode, so the backdrop's
                    // layer.record { drawContent() } records a single
                    // drawRenderNode instead of re-issuing every op below.
                    .graphicsLayer()
                    .padding(
                        top = paddingValues.calculateTopPadding(),
                    )
                    .fillMaxSize()
            ) {
                if (navSearch.query.text.isEmpty() && !localOnly) {
                    val tabPadding = PaddingValues(bottom = bottomPadding + 50.dp)
                    when (selectedTabIndex) {
                        0 -> ExploreTabContent(navController = navController, contentPadding = tabPadding)
                        1 -> SuggestionsTabContent(navController = navController, contentPadding = tabPadding)
                        2 -> HistoryTabContent(navController = navController, contentPadding = tabPadding)
                    }
                } else {
                    when (navSearch.searchSource) {
                        SearchSource.LOCAL -> LocalSearchScreen(
                            query = navSearch.query.text,
                            navController = navController,
                            onDismiss = navSearch.onCloseKeyboard,
                            pureBlack = pureBlack
                        )
                        SearchSource.ONLINE -> OnlineSearchScreen(
                            query = navSearch.query.text,
                            onQueryChange = navSearch.onQueryChange,
                            navController = navController,
                            onSearch = navSearch.onSubmit,
                            onDismiss = navSearch.onCloseKeyboard,
                            pureBlack = pureBlack,
                            contentColor = onTint,
                        )
                    }
                }
            }
        }
      }
    }

    // Handle lifecycle events to manage keyboard visibility. No auto-focus on
    // launch anymore â€” the nav bar's search bar starts unfocused (first tap
    // navigates here; a second tap on the bar opens the keyboard).
    DisposableEffect(lifecycleOwner, isPlayerExpanded) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (isPlayerExpanded) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        navSearch.onCloseKeyboard()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    navSearch.onCloseKeyboard()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (isPlayerExpanded) {
            keyboardController?.hide()
            focusManager.clearFocus()
            navSearch.onCloseKeyboard()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
fun ExploreTabContent(
    navController: NavController,
    viewModel: MoodAndGenresViewModel = hiltViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val moodAndGenresList by viewModel.moodAndGenres.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        moodAndGenresList?.forEach { section ->
            item {
                NavigationTitle(title = section.title)
            }
            val rows = section.items.chunked(2)
            items(rows) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                ) {
                    row.forEach { item ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .weight(1f)
                                .padding(6.dp)
                                .height(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(LocalContentColor.current.copy(alpha = 0.1f))
                                .bounceClick {
                                    navController.navigate(
                                        "youtube_browse/${item.endpoint.browseId}?params=${item.endpoint.params}"
                                    )
                                }
                                .padding(horizontal = 14.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    repeat(2 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (moodAndGenresList == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator()
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun HistoryTabContent(
    navController: NavController,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val database = LocalDatabase.current
    val navSearch = LocalNavSearchState.current
    val historyList by database.searchHistory().collectAsState(initial = emptyList())
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    var showClearDialog by remember { mutableStateOf(false) }
    val contentColor = LocalContentColor.current

    if (showClearDialog) {
        DefaultDialog(
            onDismiss = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_search_history)) },
            buttons = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showClearDialog = false
                        database.query { clearSearchHistory() }
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        ) {
            Text(stringResource(R.string.clear_search_history_confirm))
        }
    }

    if (historyList.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.history),
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = stringResource(R.string.no_search_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (pauseSearchHistory) {
                        stringResource(R.string.search_history_paused)
                    } else {
                        stringResource(R.string.search_history_empty_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {
            item(key = "history_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.search_history),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                        if (pauseSearchHistory) {
                            Text(
                                text = "(${stringResource(R.string.search_history_paused)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                    TextButton(
                        onClick = { showClearDialog = true }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.delete_history),
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.clear_all),
                            color = contentColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            items(
                items = historyList,
                key = { it.id }
            ) { item ->
                SuggestionItem(
                    query = item.query,
                    online = false,
                    contentColor = contentColor,
                    onClick = {
                        navSearch.onQueryChange(TextFieldValue(item.query, TextRange(item.query.length)))
                        navSearch.onSubmit(item.query)
                    },
                    onDelete = {
                        database.query { delete(item) }
                    },
                    onFillTextField = {
                        navSearch.onQueryChange(TextFieldValue(item.query, TextRange(item.query.length)))
                        navSearch.onTapBar()
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
fun DynamicSearchPlaceholder(searchSource: SearchSource, style: TextStyle) {
    Text(
        text = stringResource(
            when (searchSource) {
                SearchSource.ONLINE -> R.string.search_yt_music
                SearchSource.LOCAL -> R.string.search_library
            }
        ),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}


