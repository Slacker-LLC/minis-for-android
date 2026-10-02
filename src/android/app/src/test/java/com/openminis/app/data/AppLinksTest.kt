package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {
    private val repo = "https://github.com/Slacker-LLC/minis-for-android"

    private val all = listOf(
        AppLinks.REPOSITORY_URL,
        AppLinks.RELEASES_URL,
        AppLinks.NEW_ISSUE_URL,
        AppLinks.PRIVACY_POLICY_URL,
        AppLinks.PRIVACY_POLICY_URL_ZH,
        AppLinks.THIRD_PARTY_LICENSES_URL,
        AppLinks.LICENSE_URL,
        AppLinks.RELEASES_API_URL,
    )

    @Test
    fun `every link points at this repository`() {
        assertEquals(repo, AppLinks.REPOSITORY_URL)
        assertEquals("$repo/releases", AppLinks.RELEASES_URL)
        assertEquals("$repo/issues/new", AppLinks.NEW_ISSUE_URL)
        assertEquals("$repo/blob/main/PRIVACY.md", AppLinks.PRIVACY_POLICY_URL)
        assertEquals("$repo/blob/main/PRIVACY.zh-CN.md", AppLinks.PRIVACY_POLICY_URL_ZH)
        assertEquals("$repo/blob/main/THIRD_PARTY_LICENSES.md", AppLinks.THIRD_PARTY_LICENSES_URL)
        assertEquals("$repo/blob/main/LICENSE", AppLinks.LICENSE_URL)
    }

    @Test
    fun `no link names a previous fork or another owner`() {
        for (url in all) {
            assertFalse(url, url.contains("limuzi013", ignoreCase = true))
            assertFalse(url, url.contains("OpenMinis", ignoreCase = true))
            assertTrue(url, url.contains("Slacker-LLC/minis-for-android"))
        }
    }

    @Test
    fun `update checker and its manual-download link read this repository's releases`() {
        assertEquals(
            "https://api.github.com/repos/Slacker-LLC/minis-for-android/releases?per_page=30",
            AppLinks.RELEASES_API_URL,
        )
        assertEquals(AppLinks.RELEASES_URL, UpdateChecker.RELEASES_URL)
    }

    @Test
    fun `privacy policy follows a Chinese UI language and falls back to English`() {
        assertEquals(AppLinks.PRIVACY_POLICY_URL_ZH, AppLinks.privacyPolicyUrl("zh"))
        assertEquals(AppLinks.PRIVACY_POLICY_URL_ZH, AppLinks.privacyPolicyUrl("ZH"))
        assertEquals(AppLinks.PRIVACY_POLICY_URL, AppLinks.privacyPolicyUrl("en"))
        assertEquals(AppLinks.PRIVACY_POLICY_URL, AppLinks.privacyPolicyUrl("ja"))
        assertEquals(AppLinks.PRIVACY_POLICY_URL, AppLinks.privacyPolicyUrl(""))
    }
}
