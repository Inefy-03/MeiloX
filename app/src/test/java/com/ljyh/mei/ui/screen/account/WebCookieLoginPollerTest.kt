package com.ljyh.mei.ui.screen.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebCookieLoginPollerTest {
    @Test
    fun unchangedCookieRetriesOnlyAfterFiveSeconds() = runTest {
        val poller = WebCookieLoginPoller()
        val attempts = mutableListOf<Long>()
        val login = async {
            poller.poll({ "cookie" }) {
                attempts += testScheduler.currentTime
                attempts.size == 2
            }
        }
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(listOf(0L), attempts)

        advanceTimeBy(1)
        runCurrent()

        assertTrue(login.await())
        assertEquals(listOf(0L, 5_000L), attempts)
    }

    @Test
    fun changedCookieIsVerifiedOnTheNextPoll() = runTest {
        val poller = WebCookieLoginPoller()
        var cookie = "first"
        val attempts = mutableListOf<String>()
        val login = async {
            poller.poll({ cookie }) {
                attempts += it
                it == "second"
            }
        }
        runCurrent()
        cookie = "second"
        advanceTimeBy(500)
        runCurrent()

        assertTrue(login.await())
        assertEquals(listOf("first", "second"), attempts)
    }

    @Test
    fun manualLoginWaitsForCanceledAutomaticVerificationAndRunsOnlyOnce() = runTest {
        val poller = WebCookieLoginPoller()
        val releaseAutomatic = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val automatic = launch {
            poller.poll({ "automatic" }) {
                events += "automatic-start"
                withContext(NonCancellable) { releaseAutomatic.await() }
                events += "automatic-finish"
                true
            }
        }
        runCurrent()
        poller.pauseForManualLogin()
        val manual = async {
            poller.loginManually("manual") {
                events += it
                true
            }
        }
        runCurrent()
        assertEquals(listOf("automatic-start"), events)

        releaseAutomatic.complete(Unit)
        runCurrent()

        assertTrue(automatic.isCancelled)
        assertTrue(manual.await())
        assertEquals("manual", events.last())
        assertEquals(1, events.count { it == "manual" })
        assertFalse(poller.loginManually("another") { error("Login already completed") })
        poller.resumeWebLogin()
        assertFalse(poller.poll({ "another" }) { error("Login already completed") })
    }

    @Test
    fun dismissingManualLoginResumesPollingAndLeavingCancelsPendingVerification() = runTest {
        val poller = WebCookieLoginPoller()
        poller.pauseForManualLogin()
        assertFalse(poller.poll({ "cookie" }) { error("Manual login is open") })
        assertFalse(poller.loginManually("manual") { false })
        poller.resumeWebLogin()
        var verifying = false
        var canceled = false
        val automatic = launch {
            poller.poll({ "cookie" }) {
                verifying = true
                try {
                    awaitCancellation()
                } finally {
                    canceled = true
                }
            }
        }
        runCurrent()
        assertTrue(verifying)

        automatic.cancel()
        runCurrent()

        assertTrue(canceled)
        assertTrue(automatic.isCancelled)
    }

    @Test
    fun extractsOnlyANonblankExactMusicUCookie() {
        assertEquals("value==", musicUFromCookieHeader("other=1; MUSIC_U=value==; next=2"))
        assertNull(musicUFromCookieHeader("MUSIC_U=; other=1"))
        assertNull(musicUFromCookieHeader("prefix_MUSIC_U=value; MUSIC_A=other"))
        assertNull(musicUFromCookieHeader(""))
    }
}
