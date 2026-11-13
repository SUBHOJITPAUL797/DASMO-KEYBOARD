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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        assertEquals("benglish", pairs[0].sourceCode)
        assertEquals("bn", pairs[0].targetCode)
        assertEquals("Benglish ➔ বাংলা", pairs[0].displayLabel)

        // Check Bengali to English & Hindi pairs
        assertTrue(pairs.any { it.sourceCode == "bn" && it.targetCode == "en" })
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

        val bengResult = TranslationService.transliterateBenglish("")
        assertTrue(bengResult.isSuccess)
        assertEquals("", bengResult.getOrNull())
    }

    @Test
    fun testTransliterateBenglishTumiKemonAcho() = runBlocking {
        val result = TranslationService.transliterateBenglish("tumi kemon acho")
        assertTrue(result.isSuccess)
        val text = result.getOrNull()
        assertNotNull(text)
        assertTrue("Expected Bengali text containing তুমি কেমন আছো, got: $text", text!!.startsWith("তুমি কেমন আছ"))
    }

    @Test
    fun testTransliterateBenglishExactWordMapping() = runBlocking {
        // User requirement: Exact 1:1 transliteration without inserting missing words
        val res1 = TranslationService.transliterateBenglish("ki korcho")
        assertTrue(res1.isSuccess)
        assertEquals("কি করছো", res1.getOrNull())

        val res2 = TranslationService.transliterateBenglish("tumi ki korcho")
        assertTrue(res2.isSuccess)
        assertEquals("তুমি কি করছো", res2.getOrNull())

        val res3 = TranslationService.transliterateBenglish("ki korchis")
        assertTrue(res3.isSuccess)
        assertEquals("কি করছিস", res3.getOrNull())
    }

    @Test
    fun testTranslateMethodWithBenglish() = runBlocking {
        // Typing translation via translate() with sourceLang = "benglish" or targetLang = "bn"
        val res1 = TranslationService.translate("ki korchis", "benglish", "bn")
        assertTrue(res1.isSuccess)
        assertEquals("কি করছিস", res1.getOrNull())

        val res2 = TranslationService.translate("tumi ki korcho", "benglish", "bn")
        assertTrue(res2.isSuccess)
        assertEquals("তুমি কি করছো", res2.getOrNull())
    }

    @Test
    fun testParseInputToolsResponseOffline() {
        val sampleJson = """["SUCCESS",[["tumi kemon acho",["তুমি কেমন আছো","তুমি কেমন আছ"],[],{}]]]"""
        val parsed = TranslationService.parseInputToolsResponse(sampleJson)
        assertEquals("তুমি কেমন আছো", parsed)

        val sampleJsonExact = """["SUCCESS",[["ki korcho",["কি করছো","কি করছ"],[],{}]]]"""
        val parsedExact = TranslationService.parseInputToolsResponse(sampleJsonExact)
        assertEquals("কি করছো", parsedExact)
    }

    @Test
    fun testParseRomanizeResponseOffline() {
        val sampleJson = """[[[null, null, null, "koto debo"]], null, "bn", null, null, null, 1]"""
        val parsed = TranslationService.parseRomanizeResponse(sampleJson)
        assertEquals("koto debo", parsed)

        val sampleJson2 = """[[[null, null, null, "tumi kemon acho"]], null, "bn"]"""
        val parsed2 = TranslationService.parseRomanizeResponse(sampleJson2)
        assertEquals("tumi kemon acho", parsed2)
    }

    @Test
    fun testBengaliToBenglishSupportedPair() {
        val pairs = VoiceTranslateManager.SUPPORTED_PAIRS
        assertTrue(pairs.any { it.sourceCode == "bn" && it.targetCode == "benglish" && it.displayLabel == "বাংলা ➔ Benglish" })
    }
}
