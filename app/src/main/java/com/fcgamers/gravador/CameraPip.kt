package com.fcgamers.gravador

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Janelinha flutuante com a prévia da câmera traseira. Como fica sobre a tela, a gravação de tela a captura.
 *
 * Gestos na própria janelinha:
 *  - arrastar com um dedo: muda a posição;
 *  - pinça com dois dedos: muda o tamanho;
 *  - toque duplo: troca o formato (círculo, quadrado, retângulo).
 *
 * Posição, tamanho e formato são lembrados para a próxima gravação.
 */
class CameraPip(
    private val context: Context,
    private val bufferSize: Size,   // tamanho do quadro da câmera (largura > altura)
    private val rotation: Int,      // Surface.ROTATION_* da tela
    private val screenW: Int,
    private val screenH: Int,
    private val onSurface: (Surface) -> Unit
) {
    companion object {
        const val PREFS = "config"
        const val PREF_SHAPE = "pip_shape"
        const val PREF_SIZE = "pip_size"
        const val PREF_HAS_POS = "pip_has_pos"
        const val PREF_X = "pip_x"
        const val PREF_Y = "pip_y"

        const val SHAPE_CIRCLE = 0
        const val SHAPE_SQUARE = 1
        const val SHAPE_RECT = 2

        /** Tamanho em % do menor lado da tela. */
        const val DEFAULT_SIZE = 32f
        const val MIN_SIZE = 12f
        const val MAX_SIZE = 70f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val portrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

    // Proporção (largura / altura) da imagem da câmera já "em pé".
    private val contentAspect: Float =
        if (portrait) bufferSize.height.toFloat() / bufferSize.width
        else bufferSize.width.toFloat() / bufferSize.height

    private var shape = prefs.getInt(PREF_SHAPE, SHAPE_SQUARE).coerceIn(0, 2)
    private var sizePct = prefs.getFloat(PREF_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
    private var winW = 0
    private var winH = 0
    private var surface: Surface? = null

    private val root = FrameLayout(context)
    private val texture = TextureView(context)
    private lateinit var params: WindowManager.LayoutParams

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        computeSize()
        layoutChild()

        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                when (shape) {
                    SHAPE_CIRCLE -> outline.setOval(0, 0, w, h)
                    SHAPE_SQUARE -> outline.setRoundRect(0, 0, w, h, 18 * density)
                    else -> outline.setRoundRect(0, 0, w, h, 10 * density)
                }
            }
        }

        val x0: Int
        val y0: Int
        if (prefs.getBoolean(PREF_HAS_POS, false)) {
            x0 = prefs.getInt(PREF_X, 0)
            y0 = prefs.getInt(PREF_Y, 0)
        } else {
            x0 = screenW - winW - (16 * density).toInt()
            y0 = screenH - winH - (96 * density).toInt()
        }

        params = WindowManager.LayoutParams(
            winW, winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampX(x0)
            y = clampY(y0)
        }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(bufferSize.width, bufferSize.height)
                val s = Surface(st)
                surface = s
                onSurface(s)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        setupTouch()
        wm.addView(root, params)
    }

    fun remove() {
        try { wm.removeView(root) } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }

    // ---------------------------------------------------------------- layout

    private fun computeSize() {
        val base = (minOf(screenW, screenH) * sizePct / 100f).toInt().coerceAtLeast((48 * density).toInt())
        if (shape == SHAPE_RECT) {
            if (portrait) {
                winW = base
                winH = (base / contentAspect).toInt()
            } else {
                winW = (base * contentAspect).toInt()
                winH = base
            }
        } else {
            winW = base
            winH = base
        }
        winW = minOf(winW, screenW)
        winH = minOf(winH, screenH)
    }

    /**
     * A prévia fica sempre com a proporção da câmera e é maior ou igual à janela,
     * que a recorta ao formato escolhido (sem esticar a imagem).
     */
    private fun layoutChild() {
        val cw: Int
        val ch: Int
        if (winW.toFloat() / winH > contentAspect) {
            cw = winW
            ch = (winW / contentAspect).toInt()
        } else {
            ch = winH
            cw = (winH * contentAspect).toInt()
        }
        val lp = FrameLayout.LayoutParams(cw, ch, Gravity.CENTER)
        if (texture.parent == null) root.addView(texture, lp) else texture.layoutParams = lp
        applyTransform(cw, ch)
    }

    private fun applyTransform(viewW: Int, viewH: Int) {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufferRect = RectF(0f, 0f, bufferSize.height.toFloat(), bufferSize.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(viewH.toFloat() / bufferSize.height, viewW.toFloat() / bufferSize.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        texture.setTransform(matrix)
    }

    /** Recalcula tamanho e formato mantendo o centro da janelinha no mesmo lugar. */
    private fun relayout() {
        val cx = params.x + winW / 2
        val cy = params.y + winH / 2
        computeSize()
        layoutChild()
        params.width = winW
        params.height = winH
        params.x = clampX(cx - winW / 2)
        params.y = clampY(cy - winH / 2)
        root.invalidateOutline()
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun clampX(x: Int) = x.coerceIn(0, maxOf(0, screenW - winW))
    private fun clampY(y: Int) = y.coerceIn(0, maxOf(0, screenH - winH))

    // ---------------------------------------------------------------- gestos

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val scaler = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    sizePct = (sizePct * detector.scaleFactor).coerceIn(MIN_SIZE, MAX_SIZE)
                    relayout()
                    return true
                }
            }
        )
        val taps = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    shape = (shape + 1) % 3
                    relayout()
                    save()
                    return true
                }
            }
        )

        var lastX = 0f
        var lastY = 0f
        root.setOnTouchListener { _, ev ->
            scaler.onTouchEvent(ev)
            taps.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    lastX = ev.rawX
                    lastY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && ev.pointerCount == 1) {
                    params.x = clampX(params.x + (ev.rawX - lastX).toInt())
                    params.y = clampY(params.y + (ev.rawY - lastY).toInt())
                    lastX = ev.rawX
                    lastY = ev.rawY
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> save()
            }
            true
        }
    }

    private fun save() {
        prefs.edit()
            .putInt(PREF_SHAPE, shape)
            .putFloat(PREF_SIZE, sizePct)
            .putBoolean(PREF_HAS_POS, true)
            .putInt(PREF_X, params.x)
            .putInt(PREF_Y, params.y)
            .apply()
    }
}
