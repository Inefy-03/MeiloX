package com.ljyh.mei.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ljyh.mei.R

val AppFontFamily = FontFamily(Font(R.font.sf_pro, FontWeight.Normal))

/** Shared typography for Material controls, iOS components, inputs, and lyrics. */
fun appTypography(letterSpacing: TextUnit): Typography {
    val base = TextStyle(fontFamily = AppFontFamily, letterSpacing = letterSpacing)
    return Typography(
        displayLarge = base,
        displayMedium = base,
        displaySmall = base,
        headlineLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp),
        headlineMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = base.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
        titleMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = base.copy(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = base.copy(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = base.copy(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
        labelMedium = base.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.copy(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp),
    )
}
