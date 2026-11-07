package com.keyhub.test

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val bg = Color.parseColor("#0D1117")
    val card = Color.parseColor("#161B22")
    val card2 = Color.parseColor("#232A33")
    val accent = Color.parseColor("#7C9CFF")
    val green = Color.parseColor("#3FD08F")
    val red = Color.parseColor("#FF6B7A")
    val amber = Color.parseColor("#FFB454")
    val text = Color.parseColor("#E6EDF3")
    val muted = Color.parseColor("#8B949E")
}

fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

fun Context.rounded(color: Int, radius: Int = 16): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

fun Context.lp(
    w: Int = MATCH,
    h: Int = WRAP,
    top: Int = 0,
    bottom: Int = 0,
    start: Int = 0,
    weight: Float = 0f
): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply {
        setMargins(dp(start), dp(top), 0, dp(bottom))
    }

fun Context.tv(
    t: String,
    size: Float = 14f,
    color: Int = C.text,
    bold: Boolean = false,
    mono: Boolean = false
): TextView = TextView(this).apply {
    text = t
    textSize = size
    setTextColor(color)
    if (mono) typeface = Typeface.MONOSPACE else if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.card(marginBottom: Int = 12): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(C.card)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(marginBottom) }
}

fun Context.hrow(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.stylePill(v: TextView, bg: Int, fg: Int) {
    v.setTextColor(fg)
    v.background = RippleDrawable(
        ColorStateList.valueOf(withAlpha(Color.WHITE, 50)),
        rounded(bg, 22),
        rounded(Color.WHITE, 22)
    )
}

fun Context.pill(
    t: String,
    bg: Int = C.card2,
    fg: Int = C.text,
    onClick: () -> Unit
): TextView = TextView(this).apply {
    text = t
    textSize = 13f
    gravity = Gravity.CENTER
    typeface = Typeface.DEFAULT_BOLD
    setPadding(dp(16), dp(10), dp(16), dp(10))
    isClickable = true
    setOnClickListener { onClick() }
    stylePill(this, bg, fg)
}

fun Context.chip(t: String, color: Int): TextView = TextView(this).apply {
    text = t
    textSize = 11f
    setTextColor(color)
    typeface = Typeface.DEFAULT_BOLD
    setPadding(dp(10), dp(4), dp(10), dp(4))
    background = rounded(withAlpha(color, 40), 12)
}

fun Context.field(hintText: String, password: Boolean = false): EditText = EditText(this).apply {
    hint = hintText
    setHintTextColor(C.muted)
    setTextColor(C.text)
    textSize = 14f
    inputType = InputType.TYPE_CLASS_TEXT or
        (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL)
    background = rounded(C.card2, 12)
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun Context.space(h: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, dp(h))
}
