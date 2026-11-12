// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.VoiceTranslateBarBinding
import helium314.keyboard.latin.settings.Settings

/**
 * Custom clean, uncluttered translation strip view that replaces SuggestionStripView when voice translation is active.
 * Houses the [X] close button, unified sleek language selector pill [ বাংলা ➔ English ▼ ],
 * real-time status/transcript display, and a modern prominent microphone button with active listening animation.
 */
class VoiceTranslateBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: VoiceTranslateBarBinding

    var onCloseClickListener: (() -> Unit)? = null
    var onTargetToggleClickListener: (() -> Unit)? = null
    var onMicClickListener: (() -> Unit)? = null

    private var activeAccentColor: Int = Color.parseColor("#1A73E8")
    private var normalTextColor: Int = Color.WHITE
    private var inactiveTextColor: Int = ColorUtils.setAlphaComponent(Color.WHITE, 128)

    init {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        binding = VoiceTranslateBarBinding.inflate(LayoutInflater.from(context), this, true)

        binding.btnClose.setOnClickListener {
            onCloseClickListener?.invoke()
        }

        binding.btnTargetToggle.setOnClickListener {
            onTargetToggleClickListener?.invoke()
        }

        binding.btnMic.setOnClickListener {
            onMicClickListener?.invoke()
        }

        // Enable marquee scrolling for status text
        binding.tvStatusTranscript.isSelected = true

        applyThemeColors()
    }

    fun applyThemeColors() {
        val colors = Settings.getValues().mColors
        normalTextColor = colors.get(ColorType.KEY_TEXT)
        inactiveTextColor = ColorUtils.setAlphaComponent(normalTextColor, 130)
        activeAccentColor = try {
            colors.get(ColorType.SUGGESTION_AUTO_CORRECT)
        } catch (_: Exception) {
            Color.parseColor("#1A73E8")
        }

        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        colors.setColor(binding.btnClose, ColorType.TOOL_BAR_KEY)

        binding.tvSourceLang.setTextColor(normalTextColor)
        binding.tvDirectionArrow.setTextColor(inactiveTextColor)
        binding.tvTargetLang.setTextColor(activeAccentColor)
        binding.tvTargetDropdown.setTextColor(inactiveTextColor)
        binding.tvStatusTranscript.setTextColor(normalTextColor)

        // Sleek Language Selector Pill background
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(ColorUtils.setAlphaComponent(normalTextColor, 24))
            setStroke(1, ColorUtils.setAlphaComponent(normalTextColor, 48))
        }
        binding.btnTargetToggle.background = pillBg
    }

    fun updateTargetLanguage(targetLang: String) {
        val isEnglish = targetLang.equals("en", ignoreCase = true)
        binding.tvTargetLang.text = if (isEnglish) "English" else "हिन्दी"
    }

    fun setListeningState(isListening: Boolean) {
        binding.btnMic.isActivated = isListening
        if (isListening) {
            val activeMicBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activeAccentColor)
            }
            binding.btnMic.background = activeMicBg
            binding.btnMic.setColorFilter(Color.WHITE)
        } else {
            val idleMicBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(normalTextColor, 30))
            }
            binding.btnMic.background = idleMicBg
            binding.btnMic.setColorFilter(normalTextColor)
        }
    }

    fun setStatusText(text: CharSequence) {
        binding.tvStatusTranscript.text = text
        binding.tvStatusTranscript.isSelected = true
    }
}
