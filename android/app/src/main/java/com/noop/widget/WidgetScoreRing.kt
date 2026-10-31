package com.noop.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.noop.ui.DarkTokens
import com.noop.ui.LightTokens

internal fun widgetTokens(dark: Boolean) = if (dark) DarkTokens else LightTokens

/** RemoteViews cannot draw Compose Canvas; render the app's score-ring treatment into a small bitmap. */
internal fun scoreRing(value: Int?, color: Color, dark: Boolean, context: android.content.Context): Bitmap {
    val tokens = widgetTokens(dark)
    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 13f; strokeCap = Paint.Cap.ROUND }
    val bounds = RectF(12f, 12f, 180f, 180f)
    paint.color = tokens.hairline.toArgb()
    canvas.drawArc(bounds, 0f, 360f, false, paint)
    value?.let {
        paint.color = color.toArgb()
        canvas.drawArc(bounds, -90f, 360f * it.coerceIn(0, 100) / 100f, false, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = (if (value == null) tokens.textTertiary else tokens.textPrimary).toArgb()
    paint.textSize = com.noop.ui.NoopType.metricInline.fontSize.value * 192f / com.noop.ui.Metrics.widgetRingSize.value
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, com.noop.R.font.nunito_semibold) }.getOrNull()
        ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    canvas.drawText(value?.toString() ?: "—", 96f, 96f - (paint.ascent() + paint.descent()) / 2f, paint)
    return bitmap
}
