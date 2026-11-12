/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Backward-compatibility aliases for CompositionLocals defined in MainActivity.kt
 * which now lives in com.rift.music.app.
 */

package com.rift.music

val LocalDatabase get() = com.rift.music.app.LocalDatabase
val LocalRingtoneViewModel get() = com.rift.music.app.LocalRingtoneViewModel
val LocalPlayerConnection get() = com.rift.music.app.LocalPlayerConnection
val LocalPlayerAwareWindowInsets get() = com.rift.music.app.LocalPlayerAwareWindowInsets
val LocalDownloadUtil get() = com.rift.music.app.LocalDownloadUtil
val LocalSyncUtils get() = com.rift.music.app.LocalSyncUtils
val LocalIsPlayerExpanded get() = com.rift.music.app.LocalIsPlayerExpanded
val LocalTabView get() = com.rift.music.app.LocalTabView
