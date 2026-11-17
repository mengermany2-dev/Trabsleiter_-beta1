package com.example.gametranslator

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun rounded(color: Int, radiusPx: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun circle(color: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

object Palette {
    val BG = 0xFF0F1318.toInt()
    val CARD = 0xF2151A21.toInt()
    val BTN = 0xFF232A33.toInt()
    val ACCENT = 0xFF4F8CFF.toInt()
    val STROKE = 0x33FFFFFF
    val TEXT = 0xFFF2F5F8.toInt()
    val MUTED = 0xFF8D99A8.toInt()
    val OK = 0xFF4CD08A.toInt()
    val WARN = 0xFFFFB454.toInt()
}
