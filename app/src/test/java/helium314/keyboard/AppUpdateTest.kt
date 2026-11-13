// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.update.AppUpdateManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateTest {

    @Test
    fun testIsNewerVersion() {
        val current = "4.3.2"

        // Newer versions
        assertTrue(AppUpdateManager.isNewerVersion("v4.3.3", current))
        assertTrue(AppUpdateManager.isNewerVersion("4.3.3", current))
        assertTrue(AppUpdateManager.isNewerVersion("v4.4.0", current))
        assertTrue(AppUpdateManager.isNewerVersion("v5.0.0", current))
        assertTrue(AppUpdateManager.isNewerVersion("v4.3.2.1", current))
        assertTrue(AppUpdateManager.isNewerVersion("v4.10.0", current))

        // Same version
        assertFalse(AppUpdateManager.isNewerVersion("v4.3.2", current))
        assertFalse(AppUpdateManager.isNewerVersion("4.3.2", current))
        assertFalse(AppUpdateManager.isNewerVersion("v4.3.2-release", current))

        // Older versions
        assertFalse(AppUpdateManager.isNewerVersion("v4.3.1", current))
        assertFalse(AppUpdateManager.isNewerVersion("v4.3.0", current))
        assertFalse(AppUpdateManager.isNewerVersion("v4.2.9", current))
        assertFalse(AppUpdateManager.isNewerVersion("v3.9.9", current))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", current))

        // Edge cases
        assertFalse(AppUpdateManager.isNewerVersion("", current))
        assertFalse(AppUpdateManager.isNewerVersion("v", current))
        assertFalse(AppUpdateManager.isNewerVersion("invalid", current))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", AppUpdateManager.formatFileSize(0))
        assertEquals("500.0 B", AppUpdateManager.formatFileSize(500))
        assertEquals("1.0 KB", AppUpdateManager.formatFileSize(1024))
        assertEquals("1.5 KB", AppUpdateManager.formatFileSize(1536))
        assertEquals("10.0 MB", AppUpdateManager.formatFileSize(10 * 1024 * 1024))
    }

    @Test
    fun testGithubReleaseParsing() {
        val sampleJson = """
            {
              "tag_name": "v4.3.3",
              "name": "DASMO Keyboard v4.3.3",
              "body": "Bug fixes and improvements",
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://github.com/.../source.zip",
                  "size": 1000
                },
                {
                  "name": "DASMO_Keyboard_4.3.3-release.apk",
                  "browser_download_url": "https://github.com/SUBHOJITPAUL797/DASMO-KEYBOARD/releases/download/v4.3.3/DASMO_Keyboard_4.3.3-release.apk",
                  "size": 15728640
                }
              ]
            }
        """.trimIndent()

        val json = JSONObject(sampleJson)
        val tagName = json.getString("tag_name")
        val isNewer = AppUpdateManager.isNewerVersion(tagName, "4.3.2")
        assertTrue(isNewer)

        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        var apkName: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) {
                apkName = name
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }

        assertEquals("DASMO_Keyboard_4.3.3-release.apk", apkName)
        assertEquals(
            "https://github.com/SUBHOJITPAUL797/DASMO-KEYBOARD/releases/download/v4.3.3/DASMO_Keyboard_4.3.3-release.apk",
            apkUrl
        )
    }
}
