// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One painted table, with visible pixels defining both axes instead of image boxes or font advances. */
object ForecastArtwork {
    data class Row(val label: String, val value: String, val chance: String?,
                   val icon: Drawable, val nightIcon: Drawable?, val drop: Drawable?)
    data class Cell(val iconCenterX: Float, val centerY: Float, val iconRegion: RectF)
    private data class Glyph(val bitmap: Bitmap, val ink: Rect, val baseline: Float) {
        val width: Int get() = ink.width()
        val height: Int get() = ink.height()
    }
    private data class IconGlyph(val bitmap: Bitmap, val ink: Rect, val center: PointF) {
        val radius: Float get() = maxOf(center.x - ink.left, ink.right - center.x)
    }

    @JvmStatic
    fun draw(canvas: Canvas, width: Int, height: Int, density: Float, textSize: Float,
             regular: Typeface, medium: Typeface, color: Int, rows: List<Row>, rtl: Boolean): List<Cell> {
        val labelPaint = textPaint(regular, textSize, color, 400)
        val valuePaint = textPaint(medium, textSize * 15f / 14f, color, 500)
        val chancePaint = textPaint(regular, textSize * 13f / 14f, color, 400)
        val hourly = rows.any { it.chance != null }
        val paired = !hourly && rows.any { it.nightIcon != null }
        val values = rows.map { textGlyph(it.value, valuePaint) }
        val chances = if (hourly) rows.map { textGlyph(it.chance ?: "—", chancePaint) } else emptyList()
        val valueWidth = values.maxOfOrNull { it.width } ?: 0
        val chanceWidth = chances.maxOfOrNull { it.width } ?: 0
        val rainWidth = if (hourly) 15 * density + chanceWidth else 0f
        val reservedIcons = (if (paired) 50 else 28) * density
        val labelLimit = (width - valueWidth - rainWidth - reservedIcons - 10 * density).coerceAtLeast(12 * density)
        // Align the numeric clocks independently of their localized AM/PM suffix. A single
        // left-aligned string puts "PM" a digit farther right in 12:00 PM than in 3:00 PM.
        val clocks = rows.map { if (hourly) clockParts.matchEntire(it.label) else null }
        val clockGap = 4 * density
        val measuredClock = rows.indices.maxOfOrNull { index ->
            labelPaint.measureText(clocks[index]?.groupValues?.get(1) ?: rows[index].label)
        } ?: 0f
        val measuredSuffix = clocks.filterNotNull().maxOfOrNull { labelPaint.measureText(it.groupValues[2]) } ?: 0f
        val balancedClock = hourly && measuredSuffix == 0f && rows.all { bareClock.matches(it.label) }
        val preparedIcons = if (balancedClock) rows.take(4).map { row ->
            val rowHeight = height / 4f
            prepareIcon(row.icon, min(textSize, rowHeight * 0.65f), Float.MAX_VALUE,
                min(textSize * 1.16f, rowHeight * 0.72f), color == Color.BLACK)
        } else emptyList()
        val preparedDrops = if (balancedClock) rows.take(4).map { row ->
            row.drop?.let { prepareIcon(it, 11 * density, 12 * density) }
        } else emptyList()
        val dropRadius = preparedDrops.filterNotNull().maxOfOrNull { it.radius } ?: 6 * density
        val balancedRainWidth = 2 * dropRadius + 4 * density + chanceWidth
        val balancedIconWidth = 2 * (preparedIcons.filterNotNull().maxOfOrNull { it.radius } ?: 14 * density)
        // Reserve visible spacing before fitting a clock, including wider imported fonts.
        val clockLimit = if (balancedClock) min(labelLimit,
            (width - balancedRainWidth - balancedIconWidth - valueWidth - 15 * density).coerceAtLeast(1f)) else labelLimit
        val fixedGap = if (measuredSuffix > 0) clockGap else 0f
        val widestClock = measuredClock + measuredSuffix
        if (hourly && widestClock + fixedGap > clockLimit) {
            labelPaint.textSize *= (clockLimit - fixedGap).coerceAtLeast(1f) / widestClock
        }
        val labels = rows.mapIndexed { index, row ->
            textGlyph(clocks[index]?.groupValues?.get(1) ?: row.label, labelPaint,
                if (hourly) Float.MAX_VALUE else labelLimit)
        }
        val suffixes = clocks.map { it?.let { textGlyph(it.groupValues[2], labelPaint) } }
        val clockWidth = labels.maxOfOrNull { it.width } ?: 0
        val suffixWidth = suffixes.filterNotNull().maxOfOrNull { it.width } ?: 0
        val labelWidth = clockWidth + if (suffixWidth > 0) clockGap + suffixWidth else 0f
        val labelGlyphs = labels + suffixes.filterNotNull()
        val labelTop = labelGlyphs.minOfOrNull { it.ink.top - it.baseline } ?: 0f
        val labelBottom = labelGlyphs.maxOfOrNull { it.ink.bottom - it.baseline } ?: 0f
        // Without AM/PM, a fixed time/rain gap leaves nearly all spare width around the
        // weather icon. Share it between the three content groups using the artwork's
        // real visible extent, so narrower clocks do not crowd the rain symbol.
        val contentGap = if (balancedClock) ((width - labelWidth - balancedRainWidth - balancedIconWidth - valueWidth) / 3f)
            .coerceAtLeast(0f) else 0f
        val labelEnd = labelWidth + if (balancedClock) contentGap else 8 * density
        val rainEnd = labelEnd + if (balancedClock) balancedRainWidth else rainWidth
        val valueStart = width - valueWidth
        val iconInset = if (balancedClock) min(5 * density, contentGap / 2f) else 5 * density
        val iconLeft = rainEnd + iconInset
        val iconRight = (valueStart - iconInset).coerceAtLeast(iconLeft + 1)
        val groupWidth = iconRight - iconLeft
        val dividerPaint = Paint().apply { this.color = color; alpha = if (color == Color.WHITE) 64 else 51 }
        val cells = ArrayList<Cell>()
        try {
            for ((index, row) in rows.take(4).withIndex()) {
                val rowTop = index * height / 4f
                val rowBottom = (index + 1) * height / 4f
                // Give every row the same raster phase, including the final row without a
                // divider. A half-pixel phase difference otherwise magnifies a font's natural
                // numeral overshoot into an inconsistent clock baseline after bitmap rounding.
                val centerY = ((rowTop + rowBottom - if (index < 3) density else 0f) / 2f).roundToInt().toFloat()
                fun x(position: Float) = if (rtl) width - position else position
                val label = labels[index]
                val labelLeft = if (rtl) width - labelWidth else 0f
                if (hourly) {
                    val baseline = centerY - (labelTop + labelBottom) / 2f
                    paintGlyphAtBaseline(canvas, label, labelLeft + clockWidth - label.width, baseline)
                    suffixes[index]?.let { paintGlyphAtBaseline(canvas, it, labelLeft + clockWidth + clockGap, baseline) }
                } else paintGlyph(canvas, label, labelLeft, centerY)
                val value = values[index]
                val valueLeft = if (rtl) 0f else width - value.width.toFloat()
                paintGlyph(canvas, value, valueLeft, centerY)
                val chance = chances.getOrNull(index)
                val chanceLeft = if (rtl) width - rainEnd else rainEnd - (chance?.width ?: 0)
                if (chance != null) {
                    // A small optical adjustment brings the pointed droplet level with the numbers.
                    val dropCenterY = centerY + 0.5f * density
                    if (balancedClock) preparedDrops[index]?.let { paintIcon(canvas, it, x(labelEnd + dropRadius), dropCenterY) }
                    else row.drop?.let { drawIcon(canvas, it, x(labelEnd + 6 * density), dropCenterY, 11 * density, 12 * density) }
                    paintGlyph(canvas, chance, chanceLeft, centerY)
                }
                // Integer glyph origins match the final pixels, including odd-width symbols and RTL.
                val iconCenter = if (chance != null) {
                    if (rtl) (valueLeft.roundToInt() + value.width + chanceLeft.roundToInt()) / 2f
                    else (chanceLeft.roundToInt() + chance.width + valueLeft.roundToInt()) / 2f
                } else x((iconLeft + iconRight) / 2f)
                val separation = if (paired) min(28 * density, groupWidth / 2f) else 0f
                val primaryCenter = iconCenter + if (rtl) separation / 2f else -separation / 2f
                val iconHeight = min(textSize, (rowBottom - rowTop) * 0.65f)
                val iconWidth = if (paired) min(groupWidth / 2f - 2 * density, separation - 4 * density) else groupWidth
                val maximumHeight = min(textSize * 1.16f, (rowBottom - rowTop) * 0.72f)
                if (balancedClock) preparedIcons[index]?.let { paintIcon(canvas, it, primaryCenter, centerY) }
                else drawIcon(canvas, row.icon, primaryCenter, centerY, iconHeight, iconWidth, maximumHeight, color == Color.BLACK)
                row.nightIcon?.let { drawIcon(canvas, it, iconCenter + if (rtl) -separation / 2f else separation / 2f,
                    centerY, iconHeight, iconWidth, maximumHeight, color == Color.BLACK) }
                if (index < 3) canvas.drawRect(0f, rowBottom - density, width.toFloat(), rowBottom, dividerPaint)
                val primaryRight = if (paired) (iconLeft + iconRight) / 2f else iconRight
                val region = if (rtl) RectF(width - primaryRight, rowTop, width - iconLeft, rowBottom - density)
                    else RectF(iconLeft, rowTop, primaryRight, rowBottom - density)
                cells += Cell(primaryCenter, centerY, region)
            }
        } finally {
            (labelGlyphs + values + chances).forEach { it.bitmap.recycle() }
            (preparedIcons + preparedDrops).filterNotNull().forEach { it.bitmap.recycle() }
        }
        return cells
    }

    private val clockParts = Regex("^(\\p{Nd}{1,2}:\\p{Nd}{2})[\\s\\u00A0\\u202F]+(.+)$")
    private val bareClock = Regex("^\\p{Nd}{1,2}:\\p{Nd}{2}$")

    private fun textPaint(font: Typeface, size: Float, color: Int, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = size; this.color = color
        fontVariationSettings = "'wght' $weight"; fontFeatureSettings = "tnum"
    }

    private fun textGlyph(text: String, paint: Paint, limit: Float = Float.MAX_VALUE): Glyph {
        var fitted = text
        if (paint.measureText(fitted) > limit) {
            while (fitted.isNotEmpty() && paint.measureText("$fitted…") > limit) fitted = fitted.dropLast(1)
            fitted += "…"
        }
        val padding = ceil(paint.textSize / 2).toInt() + 2
        val metrics = paint.fontMetrics
        val bitmap = Bitmap.createBitmap((ceil(paint.measureText(fitted)).toInt() + padding * 2).coerceAtLeast(1),
            (ceil(metrics.bottom - metrics.top).toInt() + padding * 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val baseline = padding - metrics.top
        Canvas(bitmap).drawText(fitted, padding.toFloat(), baseline, paint)
        return Glyph(bitmap, inkBounds(bitmap) ?: Rect(padding, padding, padding + 1, padding + 1), baseline)
    }

    private fun paintGlyphAtBaseline(canvas: Canvas, glyph: Glyph, left: Float, baseline: Float) {
        canvas.drawBitmap(glyph.bitmap, (left - glyph.ink.left).roundToInt().toFloat(),
            (baseline - glyph.baseline).roundToInt().toFloat(), null)
    }

    private fun paintGlyph(canvas: Canvas, glyph: Glyph, left: Float, centerY: Float) {
        // Keep the antialiased fringe, but align the solid ink. Integer origins avoid a second filtering shift.
        canvas.drawBitmap(glyph.bitmap, (left - glyph.ink.left).roundToInt().toFloat(),
            (centerY - glyph.height / 2f - glyph.ink.top).roundToInt().toFloat(), null)
    }

    private fun drawIcon(canvas: Canvas, drawable: Drawable, centerX: Float, centerY: Float,
                         requestedHeight: Float, availableWidth: Float, maximumHeight: Float? = null,
                         darkArtwork: Boolean = false) {
        val glyph = prepareIcon(drawable, requestedHeight, availableWidth, maximumHeight, darkArtwork) ?: return
        try { paintIcon(canvas, glyph, centerX, centerY) } finally { glyph.bitmap.recycle() }
    }

    private fun paintIcon(canvas: Canvas, glyph: IconGlyph, centerX: Float, centerY: Float) {
        canvas.drawBitmap(glyph.bitmap, (centerX - glyph.center.x).roundToInt().toFloat(),
            (centerY - glyph.center.y).roundToInt().toFloat(), null)
    }

    private fun prepareIcon(drawable: Drawable, requestedHeight: Float, availableWidth: Float,
                            maximumHeight: Float? = null, darkArtwork: Boolean = false): IconGlyph? {
        val source = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        source.density = Bitmap.DENSITY_NONE
        drawable.setBounds(0, 0, source.width, source.height)
        drawable.draw(Canvas(source))
        val sourceInk = inkBounds(source)
        val glyph = if (sourceInk != null) {
            val center = opticalCenter(source, darkArtwork)
            val radius = maxOf(center.x - sourceInk.left, sourceInk.right - center.x)
            val heightScale = requestedHeight / sourceInk.height()
            // Cloud bodies should have similar visual weight even when rays or rain extend
            // the outline. Keep the correction modest and exclude the separate rain symbol.
            val gain = if (maximumHeight != null) {
                val area = contrastArea(source, darkArtwork) * heightScale * heightScale
                sqrt(0.95f * requestedHeight * requestedHeight / area.coerceAtLeast(1f)).coerceIn(0.90f, 1.16f)
            } else 1f
            val scale = minOf(heightScale * gain, availableWidth.coerceAtLeast(1f) / (2 * radius),
                (maximumHeight ?: requestedHeight) / sourceInk.height())
            val padding = 3
            val scaled = Bitmap.createBitmap((sourceInk.width() * scale).roundToInt().coerceAtLeast(1) + padding * 2,
                (sourceInk.height() * scale).roundToInt().coerceAtLeast(1) + padding * 2, Bitmap.Config.ARGB_8888)
            scaled.density = Bitmap.DENSITY_NONE
            val imageCanvas = Canvas(scaled)
            imageCanvas.translate(padding - sourceInk.left * scale, padding - sourceInk.top * scale)
            imageCanvas.scale(scale, scale)
            // The probe above measures the optical geometry only. Rasterize the
            // vector again at its final pixel size rather than filtering the 192px probe.
            drawable.draw(imageCanvas)
            val actualInk = inkBounds(scaled)
            if (actualInk != null) {
                // Thin sun rays must not push the bright cloud body below or beside the numbers.
                val optical = opticalCenter(scaled, darkArtwork)
                IconGlyph(scaled, actualInk, optical)
            } else { scaled.recycle(); null }
        } else null
        source.recycle()
        return glyph
    }

    private fun contrastArea(bitmap: Bitmap, darkArtwork: Boolean): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var area = 0.0
        for (pixel in pixels) {
            val brightness = (0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)) / 255
            val contrast = if (darkArtwork) 1 - brightness else brightness
            area += Color.alpha(pixel) / 255.0 * contrast
        }
        return area.toFloat()
    }

    private fun inkBounds(bitmap: Bitmap): Rect? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (pixels[y * bitmap.width + x] ushr 24 >= 128) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        return if (right < left) null else Rect(left, top, right + 1, bottom + 1)
    }

    private fun opticalCenter(bitmap: Bitmap, darkArtwork: Boolean): PointF {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var mass = 0.0; var weightedX = 0.0; var weightedY = 0.0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = pixels[y * bitmap.width + x]
            val alpha = Color.alpha(pixel)
            if (alpha < 8) continue
            val brightness = (0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)) / 255
            // Contrast is reversed with the dark palette so color changes retain the
            // same visual center and apparent size as the white artwork.
            val contrast = if (darkArtwork) 1 - brightness else brightness
            val weight = alpha * (0.25 + 0.75 * contrast)
            mass += weight; weightedX += (x + 0.5) * weight; weightedY += (y + 0.5) * weight
        }
        return if (mass > 0) PointF((weightedX / mass).toFloat(), (weightedY / mass).toFloat())
            else PointF(bitmap.width / 2f, bitmap.height / 2f)
    }
}
