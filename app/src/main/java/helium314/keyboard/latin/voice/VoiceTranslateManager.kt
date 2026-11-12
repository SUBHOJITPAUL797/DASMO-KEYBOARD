// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller for the Bengali Voice-to-Text and Real-Time Translation feature.
 * Coordinates UI, SpeechRecognitionManager, TranslationService, and LatinIME text input.
 */
class VoiceTranslateManager(private val latinIME: LatinIME) : SpeechRecognitionManager.Callback {

    private val context: Context get() = latinIME
    private val prefs: SharedPreferences get() = latinIME.prefs()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var barView: VoiceTranslateBarView? = null
    private var speechRecognizer: SpeechRecognitionManager? = null

    var isActive: Boolean = false
        private set

    private var currentTargetLang: String = TARGET_EN
    private var resetStatusRunnable: Runnable? = null

    fun toggle() {
        if (isActive) {
            close()
        } else {
            open()
        }
    }

    fun open() {
        isActive = true
        currentTargetLang = prefs.getString(PREF_VOICE_TRANSLATE_TARGET, TARGET_EN) ?: TARGET_EN

        val view = barView ?: VoiceTranslateBarView(context).also { barView = it }
        view.applyThemeColors()
        view.updateTargetLanguage(currentTargetLang)
        view.setListeningState(false)
        view.setStatusText(context.getString(R.string.voice_translate_status_tap_mic))

        view.onCloseClickListener = {
            provideFeedback(view)
            close()
        }

        view.onTargetToggleClickListener = {
            provideFeedback(view)
            toggleTargetLanguage()
        }

        view.onMicClickListener = {
            provideFeedback(view)
            toggleMic()
        }

        // Attach translation strip to SuggestionStripView
        latinIME.suggestionStripView?.setVoiceTranslateView(view)

        // Check audio recording permission
        if (hasRecordAudioPermission()) {
            startListening()
        } else {
            view.setStatusText(context.getString(R.string.voice_translate_permission_required))
            requestMicrophonePermission()
        }
    }

    fun close() {
        isActive = false
        cancelResetStatusTimer()
        speechRecognizer?.stopListening()
        barView?.setListeningState(false)

        latinIME.suggestionStripView?.removeVoiceTranslateView()
        latinIME.setNeutralSuggestionStrip()
    }

    private fun toggleTargetLanguage() {
        currentTargetLang = if (currentTargetLang == TARGET_EN) TARGET_HI else TARGET_EN
        prefs.edit().putString(PREF_VOICE_TRANSLATE_TARGET, currentTargetLang).apply()
        barView?.updateTargetLanguage(currentTargetLang)

        if (speechRecognizer?.isListening != true) {
            barView?.setStatusText(context.getString(R.string.voice_translate_status_tap_mic))
        }
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
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognitionManager(context, this)
        }
        barView?.setListeningState(true)
        barView?.setStatusText(context.getString(R.string.voice_translate_status_listening))
        speechRecognizer?.startListening()
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

    // --- SpeechRecognitionManager.Callback implementations ---

    override fun onReady() {
        barView?.setListeningState(true)
        barView?.setStatusText(context.getString(R.string.voice_translate_status_listening))
    }

    override fun onBeginningOfSpeech() {
        barView?.setListeningState(true)
        barView?.setStatusText(context.getString(R.string.voice_translate_status_listening))
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Can optionally provide amplitude visualizer feedback
    }

    override fun onPartialResult(partialText: String) {
        if (!partialText.isBlank()) {
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
        translateAndCommit(finalText)
    }

    override fun onError(errorCode: Int, userFriendlyMessage: String) {
        barView?.setListeningState(false)
        barView?.setStatusText(userFriendlyMessage)
        scheduleResetStatus(3500)
    }

    override fun onStopped() {
        barView?.setListeningState(false)
    }

    private fun translateAndCommit(bengaliText: String) {
        val targetLang = currentTargetLang
        scope.launch {
            val result = TranslationService.translate(
                text = bengaliText,
                sourceLang = "bn",
                targetLang = targetLang
            )

            result.fold(
                onSuccess = { translatedText ->
                    if (translatedText.isNotBlank()) {
                        // Commit directly via LatinIME
                        latinIME.onTextInput(translatedText)
                        barView?.setStatusText("✓ $translatedText")
                        scheduleResetStatus(3000)
                    } else {
                        barView?.setStatusText(bengaliText)
                        latinIME.onTextInput(bengaliText)
                        scheduleResetStatus(3000)
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Translation error", error)
                    barView?.setStatusText(context.getString(R.string.voice_translate_error_translation))
                    // Fallback: commit original Bengali speech if translation network fails
                    latinIME.onTextInput(bengaliText)
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
        speechRecognizer?.stopListening()
        barView?.setListeningState(false)
    }

    fun onDestroy() {
        cancelResetStatusTimer()
        speechRecognizer?.destroy()
        speechRecognizer = null
        barView = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "VoiceTranslateManager"
        const val PREF_VOICE_TRANSLATE_TARGET = "pref_voice_translate_target_lang"
        const val TARGET_EN = "en"
        const val TARGET_HI = "hi"
    }
}
