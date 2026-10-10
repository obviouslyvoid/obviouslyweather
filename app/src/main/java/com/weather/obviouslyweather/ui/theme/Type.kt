// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.weather.obviouslyweather.R

@OptIn(ExperimentalTextApi::class)
val GoogleSans = FontFamily(
    *listOf(300, 400, 500, 600, 700).map { weight ->
        Font(R.font.google_sans, FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 18f)))
    }.toTypedArray()
)

private fun style(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
    TextStyle(fontFamily = GoogleSans, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp, letterSpacing = spacing.sp)

val Typography = Typography(
    displayLarge = style(64, 70, FontWeight.Medium, -2f),
    displayMedium = style(48, 54, FontWeight.Medium, -1f),
    displaySmall = style(38, 44, FontWeight.Medium, -0.6f),
    headlineLarge = style(32, 38, FontWeight.Medium, -0.5f),
    headlineMedium = style(28, 34, FontWeight.Medium, -0.4f),
    headlineSmall = style(24, 30, FontWeight.Medium, -0.3f),
    titleLarge = style(22, 28, FontWeight.Medium),
    titleMedium = style(17, 23, FontWeight.Medium),
    titleSmall = style(15, 21, FontWeight.Medium),
    bodyLarge = style(16, 23),
    bodyMedium = style(14, 20),
    bodySmall = style(12, 17),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 17, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium)
)
