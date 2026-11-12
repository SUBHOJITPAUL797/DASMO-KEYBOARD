// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.databinding.VoiceTranslateBarBinding
import helium314.keyboard.latin.settings.Settings

/**
 * Custom compact translation strip view that replaces SuggestionStripView when voice translation is active.
 * Houses the [X] close button, Bengali source badge, direction arrow, target language toggle [ EN | HI ],
 * microphone button with active listening state, and live transcript / status text.
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
        binding.tvTargetDivider.setTextColor(inactiveTextColor)
        binding.tvStatusTranscript.setTextColor(normalTextColor)

        // Subtle badges background
        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(ColorUtils.setAlphaComponent(normalTextColor, 24))
            setStroke(1, ColorUtils.setAlphaComponent(normalTextColor, 40))
        }
        binding.tvSourceLang.background = badgeBg

        val toggleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(ColorUtils.setAlphaComponent(normalTextColor, 24))
            setStroke(1, ColorUtils.setAlphaComponent(normalTextColor, 40))
        }
        binding.btnTargetToggle.background = toggleBg
    }

    fun updateTargetLanguage(targetLang: String) {
        val isEnglish = targetLang.equals("en", ignoreCase = true)
        if (isEnglish) {
            binding.tvTargetEn.setTextColor(activeAccentColor)
            binding.tvTargetEn.alpha = 1.0f
            binding.tvTargetHi.setTextColor(inactiveTextColor)
            binding.tvTargetHi.alpha = 0.6f
        } else {
            binding.tvTargetHi.setTextColor(activeAccentColor)
            binding.tvTargetHi.alpha = 1.0f
            binding.tvTargetEn.setTextColor(inactiveTextColor)
            binding.tvTargetEn.alpha = 0.6f
        }
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
