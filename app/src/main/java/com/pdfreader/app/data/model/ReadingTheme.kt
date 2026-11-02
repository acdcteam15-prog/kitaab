package com.pdfreader.app.data.model

import android.graphics.Color

enum class ReadingTheme(
    val label: String,
    val backgroundColor: Int,
    val nightFilterColor: Int
) {
    WHITE(    "☀️ White",      Color.WHITE,                    Color.TRANSPARENT),
    SEPIA(    "📜 Sepia",      Color.parseColor("#F4ECD8"),    Color.TRANSPARENT),
    DARK(     "🌑 Dark",       Color.parseColor("#1E1E1E"),    Color.TRANSPARENT),
    NIGHT(    "🌚 Night",      Color.parseColor("#0D0D0D"),    Color.TRANSPARENT),
    NO_BLUE(  "🟠 No Blue",    Color.parseColor("#2A1A00"),    Color.parseColor("#99FF8C00")),
    GREEN(    "🟢 Eye Care",   Color.parseColor("#0D1A0D"),    Color.parseColor("#33004400"))
}
