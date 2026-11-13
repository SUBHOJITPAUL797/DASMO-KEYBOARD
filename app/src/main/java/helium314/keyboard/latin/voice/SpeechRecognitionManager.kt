// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log

/**
 * Manages android.speech.SpeechRecognizer for Bengali (bn-IN) speech-to-text.
 * Provides callbacks for real-time partial transcripts, final speech, audio volume changes, and errors.
 */
class SpeechRecognitionManager(
    private val context: Context,
    private val listener: Callback
) {
    interface Callback {
        fun onReady()
        fun onBeginningOfSpeech()
        fun onRmsChanged(rmsdB: Float)
        fun onPartialResult(partialText: String)
        fun onFinalResult(finalText: String)
        fun onError(errorCode: Int, userFriendlyMessage: String)
        fun onStopped()
    }

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    fun startListening(sourceLanguage: String = "bn") {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(
                SpeechRecognizer.ERROR_CLIENT,
                context.getString(R.string.voice_translate_error_generic, SpeechRecognizer.ERROR_CLIENT)
            )
            return
        }
        stopListening()

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    listener.onReady()
                }

                override fun onBeginningOfSpeech() {
                    listener.onBeginningOfSpeech()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    listener.onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    val message = mapErrorCodeToMessage(error)
                    listener.onError(error, message)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    listener.onFinalResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        listener.onPartialResult(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val (primaryLocale, extraLocales) = when (sourceLanguage) {
                "hi" -> "hi-IN" to arrayOf("hi-IN")
                "en" -> "en-IN" to arrayOf("en-IN", "en-US")
                "benglish" -> "bn-IN" to arrayOf("bn-BD", "bn-IN", "en-IN")
                else -> "bn-IN" to arrayOf("bn-BD", "bn-IN")
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLocale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLocale)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", extraLocales)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            speechRecognizer = recognizer
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            isListening = false
            listener.onError(
                SpeechRecognizer.ERROR_CLIENT,
                e.localizedMessage ?: "Failed to start speech recognition"
            )
        }
    }

    fun stopListening() {
        isListening = false
        speechRecognizer?.let {
            try {
                it.stopListening()
                it.cancel()
                it.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying speech recognizer", e)
            }
        }
        speechRecognizer = null
        listener.onStopped()
    }

    fun destroy() {
        stopListening()
    }

    private fun mapErrorCodeToMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO ->
                context.getString(R.string.voice_translate_error_generic, errorCode)
            SpeechRecognizer.ERROR_CLIENT ->
                context.getString(R.string.voice_translate_error_generic, errorCode)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                context.getString(R.string.voice_translate_permission_required)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                context.getString(R.string.voice_translate_error_network)
            SpeechRecognizer.ERROR_NO_MATCH ->
                context.getString(R.string.voice_translate_error_no_match)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                context.getString(R.string.voice_translate_error_generic, errorCode)
            SpeechRecognizer.ERROR_SERVER ->
                context.getString(R.string.voice_translate_error_network)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                context.getString(R.string.voice_translate_error_speech_timeout)
            else ->
                context.getString(R.string.voice_translate_error_generic, errorCode)
        }
    }

    companion object {
        private const val TAG = "SpeechRecognitionMgr"
    }
}
