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
    fun testSupportedTranslationPairs() {
        val pairs = VoiceTranslateManager.SUPPORTED_PAIRS
        assertTrue(pairs.isNotEmpty())
        assertEquals("bn", pairs[0].sourceCode)
        assertEquals("en", pairs[0].targetCode)
        assertEquals("বাংলা ➔ English", pairs[0].displayLabel)

        // Check Hindi pair
        assertTrue(pairs.any { it.sourceCode == "bn" && it.targetCode == "hi" })
        // Check English to Bengali pair
        assertTrue(pairs.any { it.sourceCode == "en" && it.targetCode == "bn" })

        assertEquals("pref_translate_pair_index", VoiceTranslateManager.PREF_TRANSLATE_PAIR_INDEX)
    }

    @Test
    fun testTranslationServiceEmptyInput() = runBlocking {
        val result = TranslationService.translate("", "bn", "en")
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull())
    }
}
