package com.zeeload.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheManagerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun trimsOldestButPreservesPinnedDownload() {
        val cache = CacheManager(temp.root) { 12L }
        val old = File(cache.directory, "old.apk").apply { writeBytes(ByteArray(6)); setLastModified(1000) }
        val active = File(cache.directory, "active.apk").apply { writeBytes(ByteArray(6)); setLastModified(2000) }
        val recent = File(cache.directory, "recent.apk").apply { writeBytes(ByteArray(6)); setLastModified(3000) }
        cache.pin(active)
        cache.trim()
        assertFalse(old.exists())
        assertTrue(active.exists())
        assertTrue(recent.exists())
        assertEquals(12, cache.sizeBytes())
        cache.unpin(active)
        cache.trim(6)
        assertFalse(active.exists())
        assertTrue(recent.exists())
    }

    @Test fun clearSkipsPinnedAndStartupRemovesStalePart() {
        val cache = CacheManager(temp.root) { 100L }
        val active = File(cache.directory, "active.part").apply {
            writeBytes(ByteArray(5)); setLastModified(1000)
        }
        val stale = File(cache.directory, "stale.part").apply {
            writeBytes(ByteArray(5)); setLastModified(1000)
        }
        cache.pin(active)
        cache.cleanStale()
        assertTrue(active.exists())
        assertFalse(stale.exists())
        assertEquals(0, cache.clear())
        cache.unpin(active)
        assertEquals(5, cache.clear())
        assertFalse(active.exists())
    }

    @Test fun pinningUsesReferenceCounts() {
        val cache = CacheManager(temp.root) { 100L }
        val file = File(cache.directory, "pending.apk").apply { writeBytes(ByteArray(5)) }
        cache.pin(file)
        cache.pin(file)
        cache.unpin(file)
        assertEquals(0, cache.clear())
        cache.unpin(file)
        assertEquals(5, cache.clear())
        assertFalse(file.exists())
    }
}
