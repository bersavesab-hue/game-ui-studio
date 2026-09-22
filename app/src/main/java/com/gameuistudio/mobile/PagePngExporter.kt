package com.gameuistudio.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.io.OutputStream

object PagePngExporter {
    fun writeCurrentPage(
        context: Context,
        page: EditorPage,
        output: OutputStream,
        transparent: Boolean = false
    ) {
        val bitmap = Bitmap.createBitmap(DESIGN_WIDTH.toInt(), DESIGN_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (!transparent) canvas.drawColor(android.graphics.Color.WHITE)

        page.elements.sortedBy { it.zIndex }.filterNot { it.hidden }.forEach { element ->
            drawElement(context, canvas, element)
        }

        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
    }

    private fun drawElement(context: Context, canvas: Canvas, e: EditorElement) {
        val rect = RectF(e.x, e.y, e.x + e.width, e.y + e.height)
        canvas.save()
        canvas.rotate(e.rotation, rect.centerX(), rect.centerY())
        canvas.scale(if (e.flipX) -1f else 1f, if (e.flipY) -1f else 1f, rect.centerX(), rect.centerY())

        when (e.type) {
            ElementType.IMAGE -> drawImage(context, canvas, e, rect)
            ElementType.TEXT -> drawText(canvas, e, rect)
            ElementType.BUTTON -> {
                drawSurface(canvas, e, rect)
                drawText(canvas, e, rect)
            }
            ElementType.PANEL -> drawSurface(canvas, e, rect)
        }
        canvas.restore()
    }

    private fun drawSurface(canvas: Canvas, e: EditorElement, rect: RectF) {
        val radius = e.cornerRadius
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (e.shadowAlpha > 0.01f && e.shadowRadius > 0.5f) {
            paint.color = android.graphics.Color.argb(
                (e.shadowAlpha.coerceIn(0f, 1f) * 190f).toInt(),
                0, 0, 0
            )
            val shadowRect = RectF(rect.left, rect.top + e.shadowOffsetY, rect.right, rect.bottom + e.shadowOffsetY)
            canvas.drawRoundRect(shadowRect, radius, radius, paint)
        }

        paint.alpha = (e.opacity.coerceIn(0f, 1f) * 255f).toInt()
        if (e.gradientEnabled) {
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                e.fillColor, e.gradientEndColor, Shader.TileMode.CLAMP
            )
        } else {
            paint.shader = null
            paint.color = e.fillColor
        }
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (e.borderWidth > 0.1f) {
            paint.shader = null
            paint.alpha = 255
            paint.color = e.borderColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = e.borderWidth
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawText(canvas: Canvas, e: EditorElement, rect: RectF) {
        if (e.text.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = e.textColor
            alpha = (e.opacity.coerceIn(0f, 1f) * 255f).toInt()
            textSize = e.fontSize
            isFakeBoldText = e.fontWeightMode == FontWeightMode.BOLD
            typeface = when (e.fontFamilyMode) {
                FontFamilyMode.SANS -> android.graphics.Typeface.SANS_SERIF
                FontFamilyMode.SERIF -> android.graphics.Typeface.SERIF
                FontFamilyMode.MONO -> android.graphics.Typeface.MONOSPACE
            }
            textAlign = when (e.textAlign) {
                TextAlignMode.LEFT -> Paint.Align.LEFT
                TextAlignMode.CENTER -> Paint.Align.CENTER
                TextAlignMode.RIGHT -> Paint.Align.RIGHT
            }
        }

        val fm = paint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        val x = when (e.textAlign) {
            TextAlignMode.LEFT -> rect.left + 12f
            TextAlignMode.CENTER -> rect.centerX()
            TextAlignMode.RIGHT -> rect.right - 12f
        }
        canvas.drawText(e.text.take(160), x, baseline, paint)
    }

    private fun drawImage(context: Context, canvas: Canvas, e: EditorElement, rect: RectF) {
        val path = e.assetPath ?: return
        val file = ProjectStorage.assetFile(context, path)
        if (!file.exists()) return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return

        val src = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val matrix = Matrix()
        val sx = rect.width() / src.width()
        val sy = rect.height() / src.height()
        val scale = when (e.imageFit) {
            ImageFit.COVER -> maxOf(sx, sy)
            ImageFit.CONTAIN -> minOf(sx, sy)
            ImageFit.FILL -> 1f
            ImageFit.FIT_WIDTH -> sx
            ImageFit.FIT_HEIGHT -> sy
        }

        if (e.imageFit == ImageFit.FILL) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (e.opacity.coerceIn(0f, 1f) * 255f).toInt()
            }
            canvas.drawBitmap(bitmap, null, rect, paint)
        } else {
            val drawW = bitmap.width * scale * e.cropZoom
            val drawH = bitmap.height * scale * e.cropZoom
            val left = rect.centerX() - drawW / 2f + e.cropOffsetX
            val top = rect.centerY() - drawH / 2f + e.cropOffsetY
            matrix.postScale(scale * e.cropZoom, scale * e.cropZoom)
            matrix.postTranslate(left, top)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (e.opacity.coerceIn(0f, 1f) * 255f).toInt()
            }
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawBitmap(bitmap, matrix, paint)
            canvas.restore()
        }
        bitmap.recycle()
    }
}
