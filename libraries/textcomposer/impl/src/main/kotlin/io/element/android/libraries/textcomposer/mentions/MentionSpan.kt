/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.mentions

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ReplacementSpan
import androidx.core.text.getSpans
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.wysiwyg.view.spans.CustomMentionSpan
import kotlin.math.roundToInt

/**
 * A span that represents a mention (user, room, etc.) in text.
 * @param type The type of mention this span represents.
 */
class MentionSpan(
    val type: MentionType,
) : ReplacementSpan() {
    private val backgroundPaint = Paint()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val avatarCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarLetterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val avatarBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarBitmapMatrix = Matrix()
    private var cachedBitmap: Bitmap? = null

    private var backgroundColor: Int = 0
    private var textColor: Int = 0
    private var startPadding: Int = 0
    private var endPadding: Int = 0
    private var typeface: Typeface = Typeface.DEFAULT

    private var avatarBackgroundColor: Int = 0
    private var avatarForegroundColor: Int = 0
    private var avatarInitial: String = ""
    private var avatarDiameter: Int = 0
    private var avatarGapPx: Int = 0

    var avatarBitmap: Bitmap? = null

    private var measuredTextWidth = 0

    // The formatted display text, will be set by the formatter
    var displayText: CharSequence = ""
        private set

    /**
     * Updates the visual properties of this span.
     */
    fun updateTheme(mentionSpanTheme: MentionSpanTheme) {
        val isCurrentUser = when (type) {
            is MentionType.User -> type.userId == mentionSpanTheme.currentUserId
            else -> false
        }

        backgroundColor = when (type) {
            is MentionType.User -> if (isCurrentUser) mentionSpanTheme.currentUserBackgroundColor else mentionSpanTheme.otherBackgroundColor
            is MentionType.Everyone -> mentionSpanTheme.otherBackgroundColor
            is MentionType.Room -> mentionSpanTheme.otherBackgroundColor
            is MentionType.Message -> mentionSpanTheme.otherBackgroundColor
        }

        textColor = when (type) {
            is MentionType.User -> if (isCurrentUser) mentionSpanTheme.currentUserTextColor else mentionSpanTheme.otherTextColor
            is MentionType.Everyone -> mentionSpanTheme.otherTextColor
            is MentionType.Room -> mentionSpanTheme.otherTextColor
            is MentionType.Message -> mentionSpanTheme.otherTextColor
        }

        val (startPaddingPx, endPaddingPx) = mentionSpanTheme.paddingValuesPx.value
        startPadding = startPaddingPx
        endPadding = endPaddingPx
        typeface = mentionSpanTheme.typeface.value

        avatarGapPx = mentionSpanTheme.avatarGapPx.value
        if (type is MentionType.User && mentionSpanTheme.avatarColorPairs.isNotEmpty()) {
            val idx = type.userId.value.toList().sumOf { it.code } % mentionSpanTheme.avatarColorPairs.size
            val (bg, fg) = mentionSpanTheme.avatarColorPairs[idx]
            avatarBackgroundColor = bg
            avatarForegroundColor = fg
        }
    }

    /**
     * Updates the display text using a formatter.
     */
    fun updateDisplayText(formatter: MentionSpanFormatter) {
        displayText = formatter.formatDisplayText(type)
        avatarInitial = if (type is MentionType.User) {
            displayText.firstOrNull { it.isLetter() }?.uppercase() ?: "?"
        } else {
            ""
        }
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        textPaint.set(paint)
        textPaint.typeface = typeface
        // Measure the full text width without truncation
        measuredTextWidth = textPaint.measureText(displayText, 0, displayText.length).roundToInt()

        avatarDiameter = if (type is MentionType.User) {
            val metrics = fm ?: Paint.FontMetricsInt().also { paint.getFontMetricsInt(it) }
            metrics.descent - metrics.ascent
        } else {
            0
        }
        val avatarSpace = if (avatarDiameter > 0) avatarDiameter + avatarGapPx else 0

        return startPadding + avatarSpace + measuredTextWidth + endPadding
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val availableWidth = (canvas.width - x).coerceAtLeast(0f)
        val avatarDiameterF = avatarDiameter.toFloat()
        val avatarSpace = if (avatarDiameterF > 0) avatarDiameterF + avatarGapPx else 0f
        val measuredWidth = startPadding + avatarSpace + measuredTextWidth + endPadding
        val pillWidth = minOf(availableWidth, measuredWidth)

        // Size pill and avatar from font ascent/descent, not line top/bottom, so text is centered
        val fm = paint.fontMetrics
        val pillTop = y + fm.ascent
        val pillBottom = y + fm.descent
        val cy = (pillTop + pillBottom) / 2f

        // Pill starts after the avatar, avatar floats freely to the left
        val pillStartX = x + avatarSpace
        backgroundPaint.color = backgroundColor
        backgroundPaint.alpha = 180
        val rect = RectF(pillStartX, pillTop, x + pillWidth, pillBottom)
        val radius = rect.height() / 2
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

        if (avatarDiameterF > 0) {
            val avatarRadius = avatarDiameterF / 2f
            val cx = x + avatarRadius
            val bitmap = avatarBitmap
            when {
                bitmap != null -> drawBitmapAvatar(canvas, bitmap, cx, cy, avatarRadius)
                avatarBackgroundColor != 0 -> drawLetterAvatar(canvas, cx, cy, avatarRadius, paint)
            }
        }

        textPaint.set(paint)
        textPaint.color = textColor
        textPaint.typeface = typeface

        val textStartX = pillStartX + startPadding
        val availableWidthForText = (availableWidth - avatarSpace - startPadding - endPadding).coerceAtLeast(0f)
        val textToDraw = if (measuredTextWidth > availableWidthForText) {
            TextUtils.ellipsize(displayText, textPaint, availableWidthForText, TextUtils.TruncateAt.END)
        } else {
            displayText
        }
        canvas.drawText(textToDraw, 0, textToDraw.length, textStartX, y.toFloat(), textPaint)
    }

    private fun drawBitmapAvatar(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, avatarRadius: Float) {
        val diameter = avatarRadius * 2f
        if (bitmap !== cachedBitmap) {
            cachedBitmap = bitmap
            avatarBitmapPaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val scale = diameter / minOf(bitmap.width.toFloat(), bitmap.height.toFloat())
        avatarBitmapMatrix.setScale(scale, scale)
        avatarBitmapMatrix.postTranslate(
            cx - avatarRadius - (bitmap.width * scale - diameter) / 2f,
            cy - avatarRadius - (bitmap.height * scale - diameter) / 2f,
        )
        (avatarBitmapPaint.shader as? BitmapShader)?.setLocalMatrix(avatarBitmapMatrix)
        canvas.drawCircle(cx, cy, avatarRadius, avatarBitmapPaint)
    }

    private fun drawLetterAvatar(canvas: Canvas, cx: Float, cy: Float, avatarRadius: Float, paint: Paint) {
        avatarCirclePaint.color = avatarBackgroundColor
        canvas.drawCircle(cx, cy, avatarRadius, avatarCirclePaint)
        if (avatarInitial.isNotEmpty()) {
            avatarLetterPaint.set(paint)
            avatarLetterPaint.color = avatarForegroundColor
            avatarLetterPaint.textAlign = Paint.Align.CENTER
            avatarLetterPaint.textSize = avatarRadius * 2f * 0.5f
            avatarLetterPaint.typeface = typeface
            val fm = avatarLetterPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(avatarInitial, cx, textY, avatarLetterPaint)
        }
    }
}

/**
 * Sealed interface representing different types of mentions.
 */
sealed interface MentionType {
    data class User(val userId: UserId) : MentionType
    data class Room(val roomIdOrAlias: RoomIdOrAlias) : MentionType
    data class Message(val roomIdOrAlias: RoomIdOrAlias, val eventId: EventId) : MentionType
    data object Everyone : MentionType
}

/**
 * Extension function to get all MentionSpans from a CharSequence.
 */
fun CharSequence.getMentionSpans(start: Int = 0, end: Int = length): List<MentionSpan> {
    return if (this is android.text.Spanned) {
        // If we have custom mention spans created by the RTE, we need to extract the provided spans and filter them
        val customMentionSpans = getSpans<CustomMentionSpan>(start, end)
            .map { it.providedSpan }
            .filterIsInstance<MentionSpan>()
        // Collect all direct mention spans
        val directMentionSpans = getSpans<MentionSpan>(start, end)
        // Return the union of both
        customMentionSpans + directMentionSpans
    } else {
        emptyList()
    }
}
