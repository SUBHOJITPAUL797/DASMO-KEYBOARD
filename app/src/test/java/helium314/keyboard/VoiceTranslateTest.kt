// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.voice.TranslationService
import helium314.keyboard.latin.voice.VoiceTranslateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranslateTest {

    @Test
    fun testToolbarKeyAndKeyCodeMapping() {
        assertEquals(-234, KeyCode.VOICE_TRANSLATE)
        assertEquals(KeyCode.VOICE_TRANSLATE, getCodeForToolbarKey(ToolbarKey.VOICE_TRANSLATE))
        assertTrue(ToolbarKey.entries.contains(ToolbarKey.VOICE_TRANSLATE))
    }

    @Test
    fun testTargetLanguagesConstants() {
        assertEquals("en", VoiceTranslateManager.TARGET_EN)
        assertEquals("hi", VoiceTranslateManager.TARGET_HI)
        assertEquals("pref_voice_translate_target_lang", VoiceTranslateManager.PREF_VOICE_TRANSLATE_TARGET)
    }

    @Test
    fun testTranslationServiceEmptyInput() = runBlocking {
        val result = TranslationService.translate("", "bn", "en")
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull())
    }
}
