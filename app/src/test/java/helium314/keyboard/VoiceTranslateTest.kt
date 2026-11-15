// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.view.inputmethod.InputConnection
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.voice.TranslationService
import helium314.keyboard.latin.voice.VoiceTranslateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(2, pairs.size)
        // Primary / Default (index 0): বাংলা ➔ Benglish
        assertEquals("bn", pairs[0].sourceCode)
        assertEquals("benglish", pairs[0].targetCode)
        assertEquals("বাংলা ➔ Benglish", pairs[0].displayLabel)

        // Secondary (index 1): Benglish ➔ বাংলা
        assertEquals("benglish", pairs[1].sourceCode)
        assertEquals("bn", pairs[1].targetCode)
        assertEquals("Benglish ➔ বাংলা", pairs[1].displayLabel)

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

    @Test
    fun testReplaceWordImmediateSpace() {
        val (fakeIc, getText) = createFakeInputConnection("hello কত ")
        val replaced = VoiceTranslateManager.replaceWord(fakeIc, "কত", "koto")
        assertTrue(replaced)
        assertEquals("hello koto ", getText())
    }

    @Test
    fun testReplaceWordPreservingCharsAfter() {
        // User typed 'd' after space before async conversion returned
        val (fakeIc, getText) = createFakeInputConnection("hello কত d")
        val replaced = VoiceTranslateManager.replaceWord(fakeIc, "কত", "koto")
        assertTrue(replaced)
        assertEquals("hello koto d", getText())
    }

    @Test
    fun testReplaceWordBanglishToBengali() {
        val (fakeIc, getText) = createFakeInputConnection("debo ")
        val replaced = VoiceTranslateManager.replaceWord(fakeIc, "debo", "দেবো")
        assertTrue(replaced)
        assertEquals("দেবো ", getText())
    }

    @Test
    fun testReplaceWordNoMatch() {
        val (fakeIc, getText) = createFakeInputConnection("hello world ")
        val replaced = VoiceTranslateManager.replaceWord(fakeIc, "missing", "koto")
        assertFalse(replaced)
        assertEquals("hello world ", getText())
    }

    @Test
    fun testWordCache() {
        val cache = HashMap<String, String>()
        cache["কত"] = "koto"
        cache["দেবো"] = "debo"
        cache["tumi"] = "তুমি"

        assertEquals("koto", cache["কত"])
        assertEquals("debo", cache["দেবো"])
        assertEquals("তুমি", cache["tumi"])
        assertEquals(null, cache["unknown"])
    }

    private fun createFakeInputConnection(initialText: String): Pair<InputConnection, () -> String> {
        var currentText = initialText
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> {
                    val n = args[0] as Int
                    if (currentText.length <= n) currentText else currentText.substring(currentText.length - n)
                }
                "deleteSurroundingText" -> {
                    val before = args[0] as Int
                    val newLength = (currentText.length - before).coerceAtLeast(0)
                    currentText = currentText.substring(0, newLength)
                    true
                }
                "commitText" -> {
                    val textToCommit = args[0] as? CharSequence ?: ""
                    currentText += textToCommit
                    true
                }
                "getSelectedText" -> null
                else -> null
            }
        } as InputConnection
        return Pair(proxy, { currentText })
    }
}
