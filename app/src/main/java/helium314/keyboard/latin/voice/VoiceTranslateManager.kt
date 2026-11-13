// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.InputConnection
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getPlatformDialogThemeContext
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller for the Bengali Voice-to-Text and Real-Time Typing Translation feature.
 * Coordinates UI, SpeechRecognitionManager, TranslationService, and LatinIME text input.
 */
class VoiceTranslateManager(private val latinIME: LatinIME) : SpeechRecognitionManager.Callback {

    data class TranslationPair(
        val sourceCode: String,
        val sourceName: String,
        val targetCode: String,
        val targetName: String
    ) {
        val displayLabel: String get() = "$sourceName ➔ $targetName"
    }

    private val context: Context get() = latinIME
    private val prefs: SharedPreferences get() = latinIME.prefs()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var barView: VoiceTranslateBarView? = null
    private var speechRecognizer: SpeechRecognitionManager? = null
    private var debounceJob: Job? = null

    var isActive: Boolean = false
        private set

    private var cachedSourceText: String = ""
    private var cachedTranslatedText: String = ""
    private var resetStatusRunnable: Runnable? = null

    private var currentPairIndex: Int
        get() = prefs.getInt(PREF_TRANSLATE_PAIR_INDEX, 0).coerceIn(0, SUPPORTED_PAIRS.lastIndex)
        set(value) = prefs.edit().putInt(PREF_TRANSLATE_PAIR_INDEX, value).apply()

    val currentPair: TranslationPair
        get() = SUPPORTED_PAIRS[currentPairIndex]

    fun toggle() {
        if (isActive) {
            close()
        } else {
            open()
        }
    }

    fun open() {
        isActive = true

        val view = barView ?: VoiceTranslateBarView(context).also { barView = it }
        view.applyThemeColors()
        applyCurrentLanguagePair()
        view.setListeningState(false)
        view.setTranslateButtonVisible(false)
        view.setStatusText(context.getString(R.string.voice_translate_status_tap_mic))

        view.onCloseClickListener = {
            provideFeedback(view)
            close()
        }

        view.onTargetToggleClickListener = {
            provideFeedback(view)
            toggleTargetLanguage()
        }

        view.onTargetToggleLongClickListener = {
            provideFeedback(view)
            showLanguagePickerDialog()
            true
        }

        view.onMicClickListener = {
            provideFeedback(view)
            toggleMic()
        }

        view.onTranslateTextClickListener = {
            translateAndReplaceCurrentText()
        }

        view.onStatusTranscriptClickListener = {
            if (cachedTranslatedText.isNotBlank()) {
                translateAndReplaceCurrentText()
            }
        }

        // Attach translation strip to SuggestionStripView
        latinIME.suggestionStripView?.setVoiceTranslateView(view)

        // Check if there is already typed text in editor to preview immediately
        onEditorTextChanged()
    }

    fun close() {
        isActive = false
        cancelResetStatusTimer()
        debounceJob?.cancel()
        speechRecognizer?.stopListening()
        barView?.setListeningState(false)
        barView?.setTranslateButtonVisible(false)

        cachedSourceText = ""
        cachedTranslatedText = ""

        latinIME.suggestionStripView?.removeVoiceTranslateView()
        latinIME.setNeutralSuggestionStrip()
    }

    private fun applyCurrentLanguagePair() {
        val pair = currentPair
        barView?.updateLanguagePair(pair.sourceName, pair.targetName)
        // Refresh preview if text exists
        if (cachedSourceText.isNotBlank()) {
            onEditorTextChanged()
        }
    }

    private fun toggleTargetLanguage() {
        currentPairIndex = (currentPairIndex + 1) % SUPPORTED_PAIRS.size
        applyCurrentLanguagePair()
    }

    private fun showLanguagePickerDialog() {
        val windowToken = latinIME.window?.window?.attributes?.token
            ?: latinIME.suggestionStripView?.windowToken
            ?: return
        val items = SUPPORTED_PAIRS.map { it.displayLabel }.toTypedArray()
        val dialog = AlertDialog.Builder(getPlatformDialogThemeContext(latinIME))
            .setTitle(R.string.voice_translate_dialog_title)
            .setSingleChoiceItems(items, currentPairIndex) { di, which ->
                di.dismiss()
                currentPairIndex = which
                applyCurrentLanguagePair()
            }
            .create()

        val window = dialog.window
        val layoutParams = window?.attributes
        layoutParams?.token = windowToken
        layoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window?.attributes = layoutParams
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.show()
    }

    private fun toggleMic() {
        if (speechRecognizer?.isListening == true) {
            speechRecognizer?.stopListening()
            barView?.setListeningState(false)
            barView?.setStatusText(context.getString(R.string.voice_translate_status_tap_mic))
        } else {
            if (hasRecordAudioPermission()) {
                startListening()
            } else {
                requestMicrophonePermission()
            }
        }
    }

    private fun startListening() {
        cancelResetStatusTimer()
        debounceJob?.cancel()
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognitionManager(context, this)
        }
        barView?.setListeningState(true)
        barView?.setStatusText(context.getString(R.string.voice_translate_status_listening))
        speechRecognizer?.startListening(currentPair.sourceCode)
    }

    private fun requestMicrophonePermission() {
        VoicePermissionActivity.onPermissionResult = { isGranted ->
            if (isGranted && isActive) {
                startListening()
            } else if (isActive) {
                barView?.setStatusText(context.getString(R.string.voice_translate_permission_required))
            }
        }
        val intent = Intent(context, VoicePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // --- Typing Translation (Type-to-Translate) ---

    fun onEditorTextChanged() {
        if (!isActive) return
        if (speechRecognizer?.isListening == true) return

        debounceJob?.cancel()
        val ic = latinIME.currentInputConnection ?: return
        val currentText = getRelevantText(ic)

        if (currentText.isBlank()) {
            barView?.setTranslateButtonVisible(false)
            cachedSourceText = ""
            cachedTranslatedText = ""
            if (speechRecognizer?.isListening != true) {
                barView?.setStatusText(context.getString(R.string.voice_translate_status_tap_mic))
            }
            return
        }

        barView?.setTranslateButtonVisible(true)
        cachedSourceText = currentText

        // Debounce live translation preview by 300ms
        debounceJob = scope.launch {
            delay(300)
            val effectiveSource = resolveSourceLang(currentText, currentPair.sourceCode)
            val result = TranslationService.translate(
                text = currentText,
                sourceLang = effectiveSource,
                targetLang = currentPair.targetCode
            )
            result.fold(
                onSuccess = { translated ->
                    if (translated.isNotBlank()) {
                        cachedTranslatedText = translated
                        barView?.setStatusText("➔ $translated")
                    }
                },
                onFailure = {
                    // Silently fail for live preview so normal typing is not disturbed
                }
            )
        }
    }

    fun translateAndReplaceCurrentText() {
        if (!isActive) return
        val ic = latinIME.currentInputConnection ?: return
        debounceJob?.cancel()

        val textToTranslate = getRelevantText(ic)
        if (textToTranslate.isBlank()) return

        barView?.let { provideFeedback(it) }

        // If translation preview is already ready:
        if (cachedTranslatedText.isNotBlank() && cachedSourceText == textToTranslate) {
            applyTranslationToInputConnection(ic, textToTranslate, cachedTranslatedText)
            return
        }

        // Otherwise fetch translation immediately and commit
        barView?.setStatusText(context.getString(R.string.voice_translate_status_translating))
        scope.launch {
            val effectiveSource = resolveSourceLang(textToTranslate, currentPair.sourceCode)
            val result = TranslationService.translate(
                text = textToTranslate,
                sourceLang = effectiveSource,
                targetLang = currentPair.targetCode
            )
            result.fold(
                onSuccess = { translated ->
                    if (translated.isNotBlank()) {
                        applyTranslationToInputConnection(ic, textToTranslate, translated)
                    } else {
                        barView?.setStatusText(context.getString(R.string.voice_translate_error_translation))
                        scheduleResetStatus(2000)
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Typing translation error", error)
                    barView?.setStatusText(context.getString(R.string.voice_translate_error_translation))
                    scheduleResetStatus(2500)
                }
            )
        }
    }

    private fun applyTranslationToInputConnection(
        ic: InputConnection,
        originalText: String,
        translatedText: String
    ) {
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrBlank()) {
            ic.commitText(translatedText, 1)
        } else {
            val before = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
            val deleteLength = if (before.endsWith(originalText)) {
                originalText.length
            } else if (before.trimEnd().endsWith(originalText)) {
                before.length - before.trimEnd().length + originalText.length
            } else {
                before.length
            }
            if (deleteLength > 0) {
                ic.deleteSurroundingText(deleteLength, 0)
            }
            ic.commitText(translatedText, 1)
        }
        cachedSourceText = ""
        cachedTranslatedText = ""
        barView?.setTranslateButtonVisible(false)
        barView?.setStatusText(context.getString(R.string.voice_translate_status_translated))
        scheduleResetStatus(2500)
    }

    private fun getRelevantText(ic: InputConnection): String {
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrBlank()) {
            return selected.trim()
        }
        val before = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        return before.trim()
    }

    private fun resolveSourceLang(text: String, defaultSource: String): String {
        return when {
            defaultSource == "benglish" -> "benglish"
            text.any { it in '\u0980'..'\u09FF' } -> "bn" // Bengali script
            text.any { it in '\u0900'..'\u097F' } -> "hi" // Devanagari (Hindi) script
            text.all { it.isWhitespace() || it.code in 0x00..0x7F } && defaultSource != "en" -> "auto"
            else -> defaultSource
        }
    }

    // --- SpeechRecognitionManager.Callback implementations ---

    override fun onReady() {
        barView?.setListeningState(true)
        barView?.setStatusText(context.getString(R.string.voice_translate_status_listening))
    }

    override fun onBeginningOfSpeech() {
        barView?.setListeningState(true)
        barView?.setStatusText(context.getString(R.string.voice_translate_status_listening))
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onPartialResult(partialText: String) {
        if (partialText.isNotBlank()) {
            barView?.setStatusText(partialText)
        }
    }

    override fun onFinalResult(finalText: String) {
        barView?.setListeningState(false)
        if (finalText.isBlank()) {
            barView?.setStatusText(context.getString(R.string.voice_translate_error_no_match))
            scheduleResetStatus(3000)
            return
        }

        barView?.setStatusText(context.getString(R.string.voice_translate_status_translating))
        translateAndCommitSpeech(finalText)
    }

    override fun onError(errorCode: Int, userFriendlyMessage: String) {
        barView?.setListeningState(false)
        barView?.setStatusText(userFriendlyMessage)
        scheduleResetStatus(3500)
    }

    override fun onStopped() {
        barView?.setListeningState(false)
    }

    private fun translateAndCommitSpeech(speechText: String) {
        val pair = currentPair
        if (pair.targetCode == "benglish") {
            scope.launch {
                val romanizeResult = TranslationService.romanizeBengali(speechText)
                romanizeResult.fold(
                    onSuccess = { benglishText ->
                        val textToCommit = if (benglishText.isNotBlank()) benglishText else speechText
                        barView?.setStatusText("✓ $textToCommit")
                        latinIME.onTextInput(textToCommit)
                        scheduleResetStatus(3000)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Speech romanize error", error)
                        barView?.setStatusText("✓ $speechText")
                        latinIME.onTextInput(speechText)
                        scheduleResetStatus(3000)
                    }
                )
            }
            return
        }

        if (pair.targetCode == "bn") {
            val hasBengali = speechText.any { it in '\u0980'..'\u09FF' }
            val hasLatin = speechText.any { it in 'a'..'z' || it in 'A'..'Z' }

            // If speech contains Bengali script, do NOT translate — commit directly to active field!
            if (hasBengali && !hasLatin) {
                barView?.setStatusText("✓ $speechText")
                latinIME.onTextInput(speechText)
                scheduleResetStatus(3000)
                return
            }

            // If speech contains Latin characters (Benglish / Banglish), transliterate to Bengali script!
            if (hasLatin) {
                scope.launch {
                    val transliterateResult = TranslationService.transliterateBenglish(speechText)
                    transliterateResult.fold(
                        onSuccess = { bengaliText ->
                            val textToCommit = if (bengaliText.isNotBlank()) bengaliText else speechText
                            barView?.setStatusText("✓ $textToCommit")
                            latinIME.onTextInput(textToCommit)
                            scheduleResetStatus(3000)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Speech Benglish transliteration error", error)
                            // Fallback to translation service or commit speech text
                            val fallback = TranslationService.translate(speechText, "benglish", "bn")
                            val fallbackText = fallback.getOrNull()?.takeIf { it.isNotBlank() } ?: speechText
                            barView?.setStatusText("✓ $fallbackText")
                            latinIME.onTextInput(fallbackText)
                            scheduleResetStatus(3000)
                        }
                    )
                }
                return
            }

            // Neither Bengali nor Latin (e.g. numbers, emojis, punctuation)
            barView?.setStatusText("✓ $speechText")
            latinIME.onTextInput(speechText)
            scheduleResetStatus(3000)
            return
        }

        // Translation for other target languages (e.g. bn -> en, bn -> hi, etc.)
        val effectiveSource = resolveSourceLang(speechText, pair.sourceCode)
        scope.launch {
            val result = TranslationService.translate(
                text = speechText,
                sourceLang = effectiveSource,
                targetLang = pair.targetCode
            )

            result.fold(
                onSuccess = { translatedText ->
                    if (translatedText.isNotBlank()) {
                        latinIME.onTextInput(translatedText)
                        barView?.setStatusText("✓ $translatedText")
                        scheduleResetStatus(3000)
                    } else {
                        barView?.setStatusText(speechText)
                        latinIME.onTextInput(speechText)
                        scheduleResetStatus(3000)
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Speech translation error", error)
                    barView?.setStatusText(context.getString(R.string.voice_translate_error_translation))
                    // Fallback: commit original speech text if translation network fails
                    latinIME.onTextInput(speechText)
                    scheduleResetStatus(3500)
                }
            )
        }
    }

    private fun scheduleResetStatus(delayMillis: Long) {
        cancelResetStatusTimer()
        val runnable = Runnable {
            if (isActive && speechRecognizer?.isListening != true) {
                barView?.setStatusText(context.getString(R.string.voice_translate_status_tap_mic))
            }
        }
        resetStatusRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    private fun cancelResetStatusTimer() {
        resetStatusRunnable?.let { mainHandler.removeCallbacks(it) }
        resetStatusRunnable = null
    }

    private fun provideFeedback(view: android.view.View) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
            KeyCode.NOT_SPECIFIED,
            view,
            HapticEvent.KEY_PRESS
        )
    }

    fun onInputViewFinished() {
        cancelResetStatusTimer()
        debounceJob?.cancel()
        speechRecognizer?.stopListening()
        barView?.setListeningState(false)
    }

    fun onDestroy() {
        cancelResetStatusTimer()
        debounceJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        barView = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "VoiceTranslateManager"
        const val PREF_TRANSLATE_PAIR_INDEX = "pref_translate_pair_index"

        val SUPPORTED_PAIRS = listOf(
            TranslationPair("benglish", "Benglish", "bn", "বাংলা"),
            TranslationPair("bn", "বাংলা", "benglish", "Benglish"),
            TranslationPair("bn", "বাংলা", "en", "English"),
            TranslationPair("bn", "বাংলা", "hi", "हिन्दी"),
            TranslationPair("en", "English", "bn", "বাংলা"),
            TranslationPair("hi", "हिन्दी", "bn", "বাংলা"),
            TranslationPair("en", "English", "hi", "हिन्दी")
        )
    }
}
