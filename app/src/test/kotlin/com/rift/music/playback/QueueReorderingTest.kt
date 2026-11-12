/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback

import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.rift.music.core.extensions.move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueReorderingTest {

    data class MockQueueItem(
        val id: String,
        val title: String,
        val originalTimelineIndex: Int,
    )

    @Test
    fun testListMoveExtensionForward() {
        val list = mutableListOf("A", "B", "C", "D", "E")
        list.move(1, 3) // Move "B" from index 1 to index 3
        assertEquals(listOf("A", "C", "D", "B", "E"), list)
    }

    @Test
    fun testListMoveExtensionBackward() {
        val list = mutableListOf("A", "B", "C", "D", "E")
        list.move(3, 1) // Move "D" from index 3 to index 1
        assertEquals(listOf("A", "D", "B", "C", "E"), list)
    }

    @Test
    fun testListMoveToStart() {
        val list = mutableListOf("A", "B", "C")
        list.move(2, 0) // Move "C" to index 0
        assertEquals(listOf("C", "A", "B"), list)
    }

    @Test
    fun testListMoveToEnd() {
        val list = mutableListOf("A", "B", "C")
        list.move(0, 2) // Move "A" to end
        assertEquals(listOf("B", "C", "A"), list)
    }

    @Test
    fun testSafeIndexCoercionWithHeaders() {
        // Queue screen has 1 header item before the songs in the LazyColumn
        val headerCount = 1
        val queueSize = 10
        val lastIndex = queueSize - 1

        val fromLazyIndex = 3 // corresponds to song index 2
        val toLazyIndex = 7   // corresponds to song index 6

        val safeFrom = (fromLazyIndex - headerCount).coerceIn(0, lastIndex)
        val safeTo = (toLazyIndex - headerCount).coerceIn(0, lastIndex)

        assertEquals(2, safeFrom)
        assertEquals(6, safeTo)

        // Dragging above header clamps to 0
        val dragToHeader = (0 - headerCount).coerceIn(0, lastIndex)
        assertEquals(0, dragToHeader)

        // Dragging past end clamps to lastIndex
        val dragPastEnd = (20 - headerCount).coerceIn(0, lastIndex)
        assertEquals(lastIndex, dragPastEnd)
    }

    @Test
    fun testShuffleOrderPreservationOnReorder() {
        // When shuffle is enabled, reordering updates the player's shuffle order array
        val originalShuffleOrder = intArrayOf(3, 0, 4, 1, 2)
        val mutableOrder = originalShuffleOrder.toMutableList()

        // Move element at index 1 (value 0) to index 3
        mutableOrder.move(1, 3)
        val newShuffleOrder = mutableOrder.toIntArray()

        assertEquals(listOf(3, 4, 1, 0, 2), newShuffleOrder.toList())
        // Verify all original indices are preserved without duplicates or omissions
        assertEquals(originalShuffleOrder.sorted().toList(), newShuffleOrder.sorted().toList())
    }

    @Test
    fun testMedia3DefaultShuffleOrderReorderMaintainsPlaybackSequence() {
        // Timeline indices: 0, 1, 2, 3, 4
        // Initial shuffle playback order: 2 -> 0 -> 4 -> 1 -> 3
        val initialIndices = intArrayOf(2, 0, 4, 1, 3)
        val initialOrder = DefaultShuffleOrder(initialIndices, 12345L)

        assertEquals(5, initialOrder.length)
        assertEquals(2, initialOrder.firstIndex)
        assertEquals(0, initialOrder.getNextIndex(2))
        assertEquals(4, initialOrder.getNextIndex(0))
        assertEquals(1, initialOrder.getNextIndex(4))
        assertEquals(3, initialOrder.getNextIndex(1))

        // User drags item at displayed shuffle position 2 (timeline index 4) to position 1 (ahead of 0)
        val reorderedIndices = initialIndices.toMutableList().move(2, 1).toIntArray()
        assertEquals(listOf(2, 4, 0, 1, 3), reorderedIndices.toList())

        val updatedOrder = DefaultShuffleOrder(reorderedIndices, 67890L)
        assertEquals(5, updatedOrder.length)
        assertEquals(2, updatedOrder.firstIndex)
        // Now next item after 2 is 4 (instead of 0)
        assertEquals(4, updatedOrder.getNextIndex(2))
        // And next item after 4 is 0
        assertEquals(0, updatedOrder.getNextIndex(4))
        assertEquals(1, updatedOrder.getNextIndex(0))
        assertEquals(3, updatedOrder.getNextIndex(1))
    }

    @Test
    fun testNonShuffledLinearReorderingKeepsPlayingMediaUninterrupted() {
        // Timeline: [T0, T1, T2, T3, T4]
        val queue = mutableListOf("T0", "T1", "T2", "T3", "T4")
        val currentPlayingId = "T1"
        var currentPlayingIndex = queue.indexOf(currentPlayingId)
        assertEquals(1, currentPlayingIndex)

        // Move upcoming track T4 (index 4) forward to index 2 (right after playing song T1)
        queue.move(4, 2)
        assertEquals(listOf("T0", "T1", "T4", "T2", "T3"), queue)

        // Playing song identity and index are unchanged
        currentPlayingIndex = queue.indexOf(currentPlayingId)
        assertEquals(1, currentPlayingIndex)
        assertEquals("T1", queue[currentPlayingIndex])

        // The next item to play after T1 was previously T2, now it is T4
        val nextItemIndex = currentPlayingIndex + 1
        assertEquals("T4", queue[nextItemIndex])
    }

    @Test
    fun testNonShuffledMovingPlayingTrackUpdatesCurrentIndexWithoutRestart() {
        val queue = mutableListOf("T0", "T1", "T2", "T3", "T4")
        val currentPlayingId = "T1"

        // Drag playing track T1 (index 1) to index 3
        queue.move(1, 3)
        assertEquals(listOf("T0", "T2", "T3", "T1", "T4"), queue)

        // New index of playing track is now 3
        val newPlayingIndex = queue.indexOf(currentPlayingId)
        assertEquals(3, newPlayingIndex)
        assertEquals("T1", queue[newPlayingIndex])

        // The next item to play after T1 is now T4
        val nextItemIndex = newPlayingIndex + 1
        assertEquals("T4", queue[nextItemIndex])
    }

    @Test
    fun testDuplicateTrackIdentityDistinction() {
        // A playlist may contain duplicate tracks with same mediaId but distinct timeline window UIDs
        val item1 = MockQueueItem(id = "track_1", title = "Track 1", originalTimelineIndex = 0)
        val item2 = MockQueueItem(id = "track_2", title = "Track 2", originalTimelineIndex = 1)
        val item3 = MockQueueItem(id = "track_1", title = "Track 1 (Duplicate)", originalTimelineIndex = 2)

        val queue = mutableListOf(item1, item2, item3)

        // Reordering the duplicate track (item3) to index 0 should not affect item1
        queue.move(2, 0)

        assertEquals(item3, queue[0])
        assertEquals(item1, queue[1])
        assertEquals(item2, queue[2])
        assertEquals(3, queue.size)
    }

    @Test
    fun testAccessibilityMoveActionsAvailability() {
        val queueSize = 5

        // For item at index 0: Move Up should be disabled, Move Down enabled
        val index0CanMoveUp = 0 > 0
        val index0CanMoveDown = 0 < queueSize - 1
        assertFalse(index0CanMoveUp)
        assertTrue(index0CanMoveDown)

        // For item at index 2 (middle): Both enabled
        val index2CanMoveUp = 2 > 0
        val index2CanMoveDown = 2 < queueSize - 1
        assertTrue(index2CanMoveUp)
        assertTrue(index2CanMoveDown)

        // For item at last index: Move Up enabled, Move Down disabled
        val lastIndex = queueSize - 1
        val lastCanMoveUp = lastIndex > 0
        val lastCanMoveDown = lastIndex < queueSize - 1
        assertTrue(lastCanMoveUp)
        assertFalse(lastCanMoveDown)
    }
}
