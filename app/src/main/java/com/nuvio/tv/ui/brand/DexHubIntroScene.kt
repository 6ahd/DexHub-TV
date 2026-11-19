package com.nuvio.tv.ui.brand

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.nuvio.tv.R
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dex Hub TV's entrance (DEXHUB.md), in place of Arc's Convergence / Ignition: the same 1920 x 1080 stage, clock,
 * calls and backdrop, so [ArcIgnitionView], the start window hand-off and the exit fade work as they did.
 *
 * The infinity mark is drawn in from the left behind a band of light, DEX HUB rises under it, a sheen crosses the
 * mark (where the sound's chime lands), and the mark then breathes softly while Home loads. The mark sits where the
 * start window puts the launcher foreground (Android 8 to 11), so a hand-off starts with it already there.
 * Bitmaps, shaders and paints are made once; [draw] allocates nothing.
 */
class DexHubIntroScene private constructor(
    private val mark: Bitmap,
    private val lettering: Bitmap,
    palette: ArcPalette,
    /** Device pixels per stage unit the art was prepared for. */
    private val pixelScale: Float
) {
    /** 2 = full detail, 1 = no sheen, 0 = also no glow or light band. */
    var detail = 2

    private val glowRgb = palette.map(0xFF8B7CF6.toInt()) and 0xFFFFFF
    private val markRect = RectF(MarkX - MarkW / 2f, MarkY - MarkH / 2f, MarkX + MarkW / 2f, MarkY + MarkH / 2f)
    private val letterH = LetterW * lettering.height / lettering.width
    private val letterRect = RectF(LetterX - LetterW / 2f, LetterTop, LetterX + LetterW / 2f, LetterTop + letterH)
    private val layerRect = RectF(markRect).apply { inset(-BandW, -40f) }
    private val stageRect = RectF(0f, 0f, StageW, StageH)

    private val backdrop = Bitmap.createBitmap(
        max(1, (StageW * pixelScale).roundToInt()), max(1, (StageH * pixelScale).roundToInt()), Bitmap.Config.ARGB_8888
    ).also {
        val c = Canvas(it)
        c.scale(it.width / StageW, it.height / StageH)
        c.drawColor(Backdrop)
        c.drawRect(stageRect, radial(960f, 470f, 700f, 0.18f, 0x192F65))
        c.drawRect(stageRect, radial(760f, 670f, 360f, 0.045f, 0x782CDF))
        it.prepareToDraw()
    }

    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = radial(MarkX, MarkY, 560f, 1f, glowRgb).apply { isDither = true }
    private val bandMatrix = Matrix()
    private val bandShader = LinearGradient(
        -BandW / 2f, 0f, BandW / 2f, 0f,
        intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
    )
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = bandShader
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val sheenMatrix = Matrix()
    private val sheenShader = LinearGradient(
        -SheenW / 2f, 0f, SheenW / 2f, 0f,
        intArrayOf(0x00FFFFFF, 0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
    )
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = sheenShader
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    /**
     * Draws the frame at [clock] seconds onto a canvas of [width] x [height] pixels. [alpha] fades the whole frame,
     * [content] only the mark, lettering and light, leaving the stage. On a [handoff] the mark is there from the start.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, clock: Float, alpha: Float = 1f, content: Float = 1f, handoff: Boolean = false) {
        val scale = min(width / StageW, height / StageH)
        val backdropFits = backdrop.width == width && backdrop.height == height
        if (!backdropFits || alpha < 0.997f || content <= 0.003f) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (alpha <= 0.003f) return
        val outer = if (alpha < 0.997f) canvas.saveLayerAlpha(null, (alpha * 255).toInt()) else canvas.save()
        plainPaint.alpha = 255
        if (backdropFits) {
            canvas.drawBitmap(backdrop, 0f, 0f, plainPaint)
            canvas.translate((width - StageW * scale) / 2f, (height - StageH * scale) / 2f)
            canvas.scale(scale, scale)
        } else {
            canvas.drawColor(Backdrop)
            canvas.translate((width - StageW * scale) / 2f, (height - StageH * scale) / 2f)
            canvas.scale(scale, scale)
            canvas.drawBitmap(backdrop, null, stageRect, plainPaint)
        }
        if (content > 0.003f) drawContent(canvas, clock, content, handoff)
        canvas.restoreToCount(outer)
    }

    private fun drawContent(canvas: Canvas, clock: Float, content: Float, handoff: Boolean) {
        // the glow behind the mark: it gathers while the mark is drawn in, then breathes
        if (detail > 0) {
            val gather = smooth((clock - 0.2f) / 1.6f)
            val breathe = if (clock > SettleSeconds) 0.5f + 0.5f * sin((clock - SettleSeconds) * 1.9f) else 0f
            glowPaint.alpha = ((0.16f * gather + 0.07f * breathe) * content * 255).toInt()
            canvas.drawRect(stageRect, glowPaint)
        }

        // the mark, drawn in from the left behind the light; a hand-off starts with all of it there
        val reveal = if (handoff) 1f else smooth((clock - RevealStart) / (RevealEnd - RevealStart))
        val grow = if (handoff) 1f else 0.94f + 0.06f * smooth(clock / RevealEnd)
        val front = markRect.left - BandW / 2f + (markRect.width() + BandW) * reveal
        canvas.save()
        canvas.scale(grow, grow, MarkX, MarkY)
        val layer = canvas.saveLayer(layerRect, null)
        canvas.save()
        canvas.clipRect(markRect.left - 2f, markRect.top - 2f, if (reveal >= 1f) markRect.right + 2f else front, markRect.bottom + 2f)
        plainPaint.alpha = (content * 255).toInt()
        canvas.drawBitmap(mark, null, markRect, plainPaint)
        canvas.restore()
        if (detail > 0 && !handoff && reveal > 0f && reveal < 1f) {
            bandMatrix.setTranslate(front, 0f)
            bandShader.setLocalMatrix(bandMatrix)
            bandPaint.alpha = (content * 255).toInt()
            canvas.drawRect(layerRect, bandPaint)
        }
        val sheen = (clock - SheenStart) / (SheenEnd - SheenStart)
        if (detail > 1 && sheen > 0f && sheen < 1f) {
            sheenMatrix.setRotate(-18f)
            sheenMatrix.postTranslate(markRect.left - SheenW + (markRect.width() + SheenW * 2f) * smooth(sheen), MarkY)
            sheenShader.setLocalMatrix(sheenMatrix)
            sheenPaint.alpha = (content * 255 * sin(sheen * Math.PI).toFloat()).toInt()
            canvas.drawRect(layerRect, sheenPaint)
        }
        canvas.restoreToCount(layer)
        canvas.restore()

        // DEX HUB rises under the mark
        val letters = smooth((clock - LetterStart) / (LetterEnd - LetterStart))
        if (letters > 0f) {
            canvas.save()
            canvas.translate(0f, (1f - letters) * 26f)
            plainPaint.alpha = (letters * content * 255).toInt()
            canvas.drawBitmap(lettering, null, letterRect, plainPaint)
            canvas.restore()
        }
    }

    private fun radial(x: Float, y: Float, r: Float, alpha: Float, rgb: Int) = Paint().apply {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        shader = RadialGradient(
            x, y, r, intArrayOf((a shl 24) or rgb, ((a * 0.45f).toInt() shl 24) or rgb, rgb), floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        isDither = true
    }

    companion object {
        const val StageW = 1920f
        const val StageH = 1080f
        /** Past this the mark breathes while Home loads. */
        const val SettleSeconds = 3.3f
        private const val RevealStart = 0.3f
        private const val RevealEnd = 1.55f
        private const val LetterStart = 1.2f
        private const val LetterEnd = 2.0f
        private const val SheenStart = 2.15f
        private const val SheenEnd = 3.05f
        /** Where the start window's launcher foreground (dexhub/brand/make_brand.py: 56 % of it) puts the mark. */
        private const val MarkX = 960f
        private const val MarkY = 441f
        private const val MarkW = 477f
        private const val MarkH = MarkW * 187f / 382f
        private const val LetterX = 960f
        private const val LetterTop = 640f
        private const val LetterW = 470f
        private const val BandW = 150f
        private const val SheenW = 220f
        private val Backdrop = Color.rgb(3, 5, 10)

        /** Decodes and sizes the art for [pixelScale]. Blocking; call off the main thread. */
        fun prepare(
            resources: Resources,
            palette: ArcPalette,
            pixelScale: Float = 1f,
            keepGoing: () -> Boolean = { true }
        ): DexHubIntroScene {
            val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            fun art(id: Int, stageWidth: Float, recolor: Boolean): Bitmap {
                if (!keepGoing()) throw CancellationException("no longer wanted")
                val decoded = BitmapFactory.decodeResource(resources, id, options)
                val sw = decoded.width
                val sh = decoded.height
                var pixels = IntArray(sw * sh)
                decoded.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                decoded.recycle()
                var w = sw
                var h = sh
                val tw = (stageWidth * pixelScale).roundToInt()
                if (tw in 1 until sw) {
                    w = tw
                    h = max(1, (sh * tw.toFloat() / sw).roundToInt())
                    pixels = ArcPixels.areaResample(pixels, sw, sh, w, h)
                }
                if (recolor) palette.mapPixels(pixels)
                return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also { it.prepareToDraw() }
            }
            return DexHubIntroScene(
                mark = art(R.drawable.dexhub_intro_mark, MarkW, recolor = !palette.isOriginal),
                lettering = art(R.drawable.dexhub_intro_lettering, LetterW, recolor = false),
                palette = palette,
                pixelScale = pixelScale
            )
        }

        fun smooth(value: Float): Float = ArcIgnitionScene.smooth(value)
    }
}
