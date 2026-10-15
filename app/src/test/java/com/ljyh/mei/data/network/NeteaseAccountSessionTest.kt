package com.ljyh.mei.data.network

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.NeteaseWebSessionNoticeDismissedKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.di.NeteaseInterceptor
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NeteaseAccountSessionTest {
    @Test fun queuedMutationRejectsChangedOrSignedOutSessionBeforeTransport() {
        val fingerprint = neteaseSessionFingerprint("original-session")
        var transportCalls = 0
        for (currentCookie in listOf("different-session", "")) {
            val client = OkHttpClient.Builder()
                .addInterceptor(NeteaseInterceptor { mutablePreferencesOf(CookieKey to currentCookie) })
                .addInterceptor {
                    transportCalls++
                    error("A stale mutation must not reach transport")
                }.build()
            val request = Request.Builder().url("https://example.test/weapi/album/sub")
                .header(NETEASE_EXPECTED_SESSION_HEADER, fingerprint)
                .post("{}".toRequestBody()).build()
            try {
                client.newCall(request).execute().close()
                fail("The captured session no longer matches")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("session changed"))
                if (currentCookie.isNotEmpty()) assertFalse(error.message.orEmpty().contains(currentCookie))
            }
        }
        assertEquals(0, transportCalls)
    }

    @Test fun matchingMutationSessionUsesTheCurrentAccountSnapshot() {
        val preferences = mutablePreferencesOf(CookieKey to "current-session")
        preferences.requireExpectedNeteaseSession(neteaseSessionFingerprint("current-session"))
        preferences.requireExpectedNeteaseSession(null)
        assertEquals("current-session", preferences[CookieKey])
    }

    @Test fun emptyCookieNeverEnablesMobileAuthorization() {
        val preferences = mutablePreferencesOf(
            NeteaseSessionTypeKey to "Mobile", NeteaseUrsAppIdKey to "urs-id",
        )
        assertEquals(NeteaseSessionType.None, preferences.neteaseSessionType())
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun recognizesMobileSessionsFromBeforeTheMerge() {
        val preferences = mutablePreferencesOf(CookieKey to "session", NeteaseUrsAppIdKey to "urs-id")
        assertEquals(NeteaseSessionType.Mobile, preferences.neteaseSessionType())
        assertFalse(preferences.shouldExplainLegacyWebSession())
        preferences.requireNeteaseMobileSession()
    }

    @Test fun musicAOrCsrfAloneDoesNotProveMobileAuthentication() {
        val preferences = mutablePreferencesOf(
            CookieKey to "session", NeteaseMusicAKey to "anonymous", NeteaseCsrfKey to "csrf",
        )
        assertEquals(NeteaseSessionType.Web, preferences.neteaseSessionType())
        assertTrue(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun explicitWebSourceWinsOverStaleMobileFields() {
        val preferences = mutablePreferencesOf(
            CookieKey to "session", NeteaseSessionTypeKey to "Web", NeteaseUrsAppIdKey to "stale-urs-id",
        )
        assertEquals(NeteaseSessionType.Web, preferences.neteaseSessionType())
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun explicitMobileSourceDoesNotRequireOptionalRefreshToken() {
        val preferences = mutablePreferencesOf(CookieKey to "session", NeteaseSessionTypeKey to "Mobile")
        preferences.requireNeteaseMobileSession()
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test(expected = IllegalStateException::class)
    fun blocksLegacyWebSessionsBeforePreparingSecurityOrScanning() {
        mutablePreferencesOf(CookieKey to "session").requireNeteaseMobileSession()
    }

    @Test(expected = IllegalStateException::class)
    fun blocksExplicitWebSessionsEvenWithStaleMobileMetadata() {
        mutablePreferencesOf(
            CookieKey to "session", NeteaseSessionTypeKey to "Web", NeteaseUrsAppIdKey to "stale",
        ).requireNeteaseMobileSession()
    }

    @Test fun unknownSourceFailsClosed() {
        val preferences = mutablePreferencesOf(
            CookieKey to "session", NeteaseSessionTypeKey to "unknown", NeteaseUrsAppIdKey to "urs-id",
        )
        assertEquals(NeteaseSessionType.Web, preferences.neteaseSessionType())
    }

    @Test fun reminderIsOnlyForUpgradedWebSessions() {
        val preferences = mutablePreferencesOf(CookieKey to "web-session")
        assertTrue(preferences.shouldExplainLegacyWebSession())
        preferences[NeteaseWebSessionNoticeDismissedKey] = true
        assertFalse(preferences.shouldExplainLegacyWebSession())
        assertEquals("web-session", preferences[CookieKey])
    }

    @Test fun switchingToWebRemovesOnlyMobileCredentials() {
        val preferences = mutablePreferencesOf(
            CookieKey to "mobile-session", NeteaseUrsAppIdKey to "urs-id",
            NeteaseCsrfKey to "csrf", NeteaseMusicAKey to "anonymous",
            NeteaseRefreshTokenKey to "refresh", DeviceIdKey to "device",
        )
        preferences.setNeteaseWebSession("web-session")
        assertEquals(NeteaseSessionType.Web, preferences.neteaseSessionType())
        assertEquals("web-session", preferences[CookieKey])
        assertEquals("device", preferences[DeviceIdKey])
        assertNull(preferences[NeteaseCsrfKey])
        assertNull(preferences[NeteaseMusicAKey])
        assertNull(preferences[NeteaseRefreshTokenKey])
        assertNull(preferences[NeteaseUrsAppIdKey])
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun logoutRetainsDeviceAndReminderPreferences() {
        val preferences = mutablePreferencesOf(
            CookieKey to "session", NeteaseSessionTypeKey to "Mobile", UserIdKey to "123",
            NeteaseUrsAppIdKey to "urs-id", NeteaseCsrfKey to "csrf",
            NeteaseMusicAKey to "anonymous", NeteaseRefreshTokenKey to "refresh",
            DeviceIdKey to "device", NeteaseWebSessionNoticeDismissedKey to true,
        )
        preferences.clearNeteaseAccountSession()
        assertEquals(NeteaseSessionType.None, preferences.neteaseSessionType())
        assertEquals("device", preferences[DeviceIdKey])
        assertEquals(true, preferences[NeteaseWebSessionNoticeDismissedKey])
        assertEquals(2, preferences.asMap().size)
    }
}
