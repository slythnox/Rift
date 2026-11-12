/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.feedback

import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UserPlaybackCommand {
    val source: CommandSource
    val timestampMs: Long

    data class SeekNext(
        override val source: CommandSource,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : UserPlaybackCommand

    data class SeekPrevious(
        override val source: CommandSource,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : UserPlaybackCommand

    data class ManualSelectTrack(
        val targetIndex: Int,
        val targetMediaId: String? = null,
        override val source: CommandSource,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : UserPlaybackCommand

    data class ManualPlayQueue(
        override val source: CommandSource,
        override val timestampMs: Long = System.currentTimeMillis(),
    ) : UserPlaybackCommand
}

/**
 * Thread-safe registry capturing explicit user-initiated playback commands across:
 * - In-App UI controls
 * - MediaLibrarySessionCallback (Lockscreen, Notification, Bluetooth AVRCP, Android Auto)
 * - Hardware / Media Keys
 */
@Singleton
class CommandOriginRegistry @Inject constructor() {

    @PublishedApi
    internal val pendingCommands = ConcurrentLinkedDeque<UserPlaybackCommand>()

    fun recordCommand(command: UserPlaybackCommand) {
        pendingCommands.addLast(command)
        // Bound queue size to 20 recent commands
        while (pendingCommands.size > 20) {
            pendingCommands.pollFirst()
        }
    }

    /**
     * Finds and consumes a recent command of type [T] within the given expiry window.
     */
    inline fun <reified T : UserPlaybackCommand> consumeMatchingCommand(
        maxAgeMs: Long = 3000L,
        predicate: (T) -> Boolean = { true },
    ): T? {
        val now = System.currentTimeMillis()
        val iterator = pendingCommands.iterator()
        while (iterator.hasNext()) {
            val cmd = iterator.next()
            if (now - cmd.timestampMs > maxAgeMs) {
                iterator.remove() // Prune expired command
                continue
            }
            if (cmd is T && predicate(cmd)) {
                iterator.remove()
                return cmd
            }
        }
        return null
    }

    fun clear() {
        pendingCommands.clear()
    }
}
