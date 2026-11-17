package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Плавающая кнопка поверх игр + захват экрана + OCR + перевод EN -> RU.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.example.gametranslator.STOP"
        private const val CHANNEL_ID = "translator_channel"
        private const val NOTIF_ID = 1
        private const val CAPTURE_DELAY_MS = 400L
        private const val AUTO_HIDE_MS = 8000L
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        @Volatile
        var running = false
    }

    private data class Item(val box: Rect, val lines: Int, val text: String)

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("gt", MODE_PRIVATE) }

    // захват экрана
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestImage: Image? = null
    private var capW = 0
    private var capH = 0
    private var capDpi = 0

    // ML Kit
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )

    // интерфейс
    private var bubbleRoot: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleView: TextView? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private var autoHideBtn: TextView? = null
    private var resultView: FrameLayout? = null
    private var selectorView: FrameLayout? = null
    private var busy = false

    private val autoHideRunnable = Runnable { dismissResults() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { stopSelf() }
                }
            }
            // Android 14+: колбэк нужно зарегистрировать до createVirtualDisplay
            mp.registerCallback(cb, handler)
            projection = mp
            projectionCallback = cb
            setupCapture()
            buildBubble()
            running = true
        } catch (e: Exception) {
            toast("Не удалось начать захват экрана: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Переводчик экрана", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Game Translator работает")
            .setContentText("Кнопка A→Я поверх экрана")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), "Остановить", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ================================================================ Захват экрана

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        display.getRealMetrics(dm)
        return dm
    }

    private fun setupCapture() {
        val m = realMetrics()
        capW = m.widthPixels
        capH = m.heightPixels
        capDpi = m.densityDpi
        val reader = newReader(capW, capH)
        imageReader = reader
        virtualDisplay = projection!!.createVirtualDisplay(
            "GameTranslatorCapture", capW, capH, capDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // Всегда держим самый свежий кадр, чтобы взять его по нажатию кнопки.
        reader.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                latestImage?.close()
                latestImage = img
            }
        }, handler)
        return reader
    }

    /** Если экран повернули (игры часто в ландшафте), подгоняем размер захвата. */
    private fun ensureCaptureSize() {
        val m = realMetrics()
        if (m.widthPixels == capW && m.heightPixels == capH) return
        val old = imageReader
        latestImage?.close()
        latestImage = null
        capW = m.widthPixels
        capH = m.heightPixels
        capDpi = m.densityDpi
        val reader = newReader(capW, capH)
        imageReader = reader
        virtualDisplay?.resize(capW, capH, capDpi)
        virtualDisplay?.surface = reader.surface
        old?.close()
    }

    private fun grabFrame(): Bitmap? {
        val img = latestImage ?: return null
        return try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val wide = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            plane.buffer.rewind()
            wide.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) {
                wide
            } else {
                Bitmap.createBitmap(wide, 0, 0, img.width, img.height).also { wide.recycle() }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================ Перевод

    private fun startCapture(region: Rect?) {
        if (busy) return
        busy = true
        dismissResults()
        setBusyUi(true)
        ensureCaptureSize()
        bubbleRoot?.visibility = View.INVISIBLE // чтобы кнопка не попала в кадр
        handler.postDelayed({
            val bmp = grabFrame()
            bubbleRoot?.visibility = View.VISIBLE
            if (bmp == null) {
                busy = false
                setBusyUi(false)
                toast("Не удалось получить кадр. Попробуйте ещё раз")
                return@postDelayed
            }
            scope.launch { process(bmp, region) }
        }, CAPTURE_DELAY_MS)
    }

    private suspend fun process(full: Bitmap, region: Rect?) {
        var src: Bitmap = full
        try {
            var offX = 0
            var offY = 0
            if (region != null) {
                val r = Rect(
                    max(0, region.left), max(0, region.top),
                    min(full.width, region.right), min(full.height, region.bottom)
                )
                if (r.width() > 8 && r.height() > 8) {
                    src = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
                    offX = r.left
                    offY = r.top
                }
            }

            val result = recognizer.process(InputImage.fromBitmap(src, 0)).await()
            val blocks = result.textBlocks.filter { b ->
                b.boundingBox != null && b.text.count { it.isLetter() } >= 2
            }
            if (blocks.isEmpty()) {
                toast("Текст не найден")
                return
            }

            // Если модели уже скачаны, вызов мгновенный и интернет не нужен.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()

            val items = ArrayList<Item>()
            for (b in blocks) {
                val translated = translator.translate(b.text.replace('\n', ' ')).await()
                items.add(Item(b.boundingBox!!, b.lines.size, translated))
            }
            showResults(items, offX, offY)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            toast("Ошибка: ${e.message ?: "не удалось перевести"}. Модели скачаны в приложении?")
        } finally {
            if (src !== full) src.recycle()
            full.recycle()
            busy = false
            setBusyUi(false)
        }
    }

    // ================================================================ Плавающая кнопка и меню

    private fun buildBubble() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "A→Я"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = circle(Palette.ACCENT, 0x66FFFFFF, dp(2))
            alpha = 0.92f
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(Palette.CARD, dp(16), Palette.STROKE, dp(1))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(220), WRAP).apply { topMargin = dp(8) }
        }

        val title = TextView(this).apply {
            text = "Game Translator"
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), 0)
        }
        val status = TextView(this).apply {
            text = "Готово к переводу"
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        statusText = status
        menu.addView(title)
        menu.addView(status)

        menu.addView(menuButton("Перевести весь экран", primary = true) {
            collapsePanel()
            startCapture(null)
        })
        menu.addView(menuButton("Выбрать область") {
            collapsePanel()
            showSelector()
        })
        val autoBtn = menuButton("") {
            prefs.edit().putBoolean("autohide", !prefs.getBoolean("autohide", false)).apply()
            updateAutoHideLabel()
        }
        autoHideBtn = autoBtn
        menu.addView(autoBtn)
        updateAutoHideLabel()
        menu.addView(menuButton("Выключить переводчик") { stopSelf() })

        root.addView(bubble)
        root.addView(menu)

        val params = WindowManager.LayoutParams(
            WRAP, WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(140)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = bubbleParams ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                        if (moved) {
                            p.x = (startX + dx).toInt()
                            p.y = (startY + dy).toInt()
                            clampBubble()
                            bubbleRoot?.let { wm.updateViewLayout(it, p) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) togglePanel()
                }
                return true
            }
        })

        bubbleRoot = root
        bubbleParams = params
        bubbleView = bubble
        panel = menu
        wm.addView(root, params)
    }

    private fun menuButton(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(if (primary) Palette.ACCENT else Palette.BTN, dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun updateAutoHideLabel() {
        val on = prefs.getBoolean("autohide", false)
        autoHideBtn?.text = if (on) "Автоскрытие перевода: вкл" else "Автоскрытие перевода: выкл"
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        clampBubble()
        bubbleRoot?.let { wm.updateViewLayout(it, bubbleParams) }
    }

    private fun collapsePanel() {
        panel?.visibility = View.GONE
        bubbleRoot?.let { wm.updateViewLayout(it, bubbleParams) }
    }

    private fun clampBubble() {
        val p = bubbleParams ?: return
        val r = bubbleRoot ?: return
        r.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val m = realMetrics()
        p.x = p.x.coerceIn(0, max(0, m.widthPixels - r.measuredWidth))
        p.y = p.y.coerceIn(0, max(0, m.heightPixels - r.measuredHeight))
    }

    private fun setBusyUi(isBusy: Boolean) {
        bubbleView?.text = if (isBusy) "…" else "A→Я"
        statusText?.text = if (isBusy) "Распознаю и перевожу…" else "Готово к переводу"
    }

    private fun bringBubbleToFront() {
        val r = bubbleRoot ?: return
        val p = bubbleParams ?: return
        try {
            wm.removeViewImmediate(r)
            wm.addView(r, p)
        } catch (e: Exception) {
            // не критично
        }
    }

    // ================================================================ Плашки с переводом

    private fun fullScreenParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            capW, capH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun showResults(items: List<Item>, offX: Int, offY: Int) {
        dismissResults()
        val frame = FrameLayout(this)
        frame.setOnClickListener { dismissResults() }

        for (item in items) {
            val box = item.box
            val lineH = box.height().toFloat() / max(1, item.lines)
            val textPx = (lineH * 0.7f).coerceIn(sp(12f), sp(22f))
            val boxW = max(box.width() + dp(12), dp(120)).coerceAtMost(capW - dp(8))
            val left = (box.left + offX - dp(6)).coerceIn(dp(4), max(dp(4), capW - boxW - dp(4)))
            val top = (box.top + offY - dp(3)).coerceIn(0, max(0, capH - dp(28)))

            val tv = TextView(this).apply {
                text = item.text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = rounded(0xE6101418.toInt(), dp(6))
            }
            val lp = FrameLayout.LayoutParams(boxW, WRAP)
            lp.leftMargin = left
            lp.topMargin = top
            frame.addView(tv, lp)
        }

        val hint = TextView(this).apply {
            text = "Нажмите на экран, чтобы скрыть перевод"
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0xB3000000.toInt(), dp(14))
        }
        frame.addView(
            hint,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(36) }
        )

        wm.addView(frame, fullScreenParams())
        resultView = frame
        bringBubbleToFront()

        if (prefs.getBoolean("autohide", false)) {
            handler.postDelayed(autoHideRunnable, AUTO_HIDE_MS)
        }
    }

    private fun dismissResults() {
        handler.removeCallbacks(autoHideRunnable)
        resultView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // уже удалено
            }
        }
        resultView = null
    }

    // ================================================================ Выбор области

    private inner class SelectView(ctx: Context, val onDone: (Rect?) -> Unit) : View(ctx) {
        private var sx = 0f
        private var sy = 0f
        private var ex = 0f
        private var ey = 0f
        private var has = false

        private val dim = Paint().apply { color = 0x88000000.toInt() }
        private val fill = Paint().apply { color = 0x224F8CFF }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sp(16f)
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (!has) {
                c.drawRect(0f, 0f, w, h, dim)
                c.drawText("Выделите область с текстом", w / 2, h / 2, hintPaint)
                return
            }
            val l = min(sx, ex)
            val t = min(sy, ey)
            val r = max(sx, ex)
            val b = max(sy, ey)
            c.drawRect(0f, 0f, w, t, dim)
            c.drawRect(0f, b, w, h, dim)
            c.drawRect(0f, t, l, b, dim)
            c.drawRect(r, t, w, b, dim)
            c.drawRect(l, t, r, b, fill)
            c.drawRect(l, t, r, b, stroke)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.x
                    sy = e.y
                    ex = sx
                    ey = sy
                    has = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    ex = e.x
                    ey = e.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    ex = e.x
                    ey = e.y
                    val r = Rect(
                        min(sx, ex).toInt(), min(sy, ey).toInt(),
                        max(sx, ex).toInt(), max(sy, ey).toInt()
                    )
                    onDone(if (r.width() > dp(24) && r.height() > dp(24)) r else null)
                }
            }
            return true
        }
    }

    private fun showSelector() {
        dismissSelector()
        dismissResults()
        bubbleRoot?.visibility = View.INVISIBLE

        val frame = FrameLayout(this)
        val sv = SelectView(this) { rect ->
            handler.post {
                dismissSelector()
                if (rect == null) {
                    bubbleRoot?.visibility = View.VISIBLE
                    toast("Область слишком маленькая")
                } else {
                    startCapture(rect)
                }
            }
        }
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))

        val cancel = TextView(this).apply {
            text = "Отмена"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = rounded(Palette.CARD, dp(18), Palette.STROKE, dp(1))
            setOnClickListener {
                dismissSelector()
                bubbleRoot?.visibility = View.VISIBLE
            }
        }
        frame.addView(
            cancel,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(56) }
        )

        ensureCaptureSize()
        wm.addView(frame, fullScreenParams())
        selectorView = frame
    }

    private fun dismissSelector() {
        selectorView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // уже удалено
            }
        }
        selectorView = null
    }

    // ================================================================ Прочее

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        dismissResults()
        dismissSelector()
        bubbleRoot?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // уже удалено
            }
        }
        bubbleRoot = null
        latestImage?.close()
        latestImage = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null
        recognizer.close()
        translator.close()
        super.onDestroy()
    }
}
