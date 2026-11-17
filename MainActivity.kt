package com.example.gametranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private lateinit var overlayStatus: TextView
    private lateinit var overlayBtn: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelBtn: TextView
    private lateinit var startBtn: TextView

    private var modelsReady = false
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        checkModels()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }

        root.addView(label("Game Translator", 28f, Palette.TEXT, bold = true))
        root.addView(
            label(
                "Переводит английский текст на экране на русский прямо поверх игры. " +
                    "Распознавание и перевод работают на устройстве, без интернета.",
                14f, Palette.MUTED
            ).apply { setPadding(0, dp(6), 0, dp(20)) }
        )

        overlayStatus = label("", 13f, Palette.MUTED)
        overlayBtn = button("Выдать разрешение") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        root.addView(
            card(
                "1. Окно поверх других приложений",
                "Нужно, чтобы кнопка и перевод отображались над игрой.",
                overlayStatus, overlayBtn
            )
        )

        modelStatus = label("", 13f, Palette.MUTED)
        modelBtn = button("Скачать языковые модели") { downloadModels() }
        root.addView(
            card(
                "2. Языковые модели EN → RU",
                "Скачиваются один раз (около 30–60 МБ), потом перевод работает офлайн.",
                modelStatus, modelBtn
            )
        )

        startBtn = button("Запустить плавающую кнопку") { onStartClicked() }
        root.addView(
            card(
                "3. Запуск",
                "Система спросит про запись экрана. Выберите «Весь экран».",
                null, startBtn
            )
        )

        root.addView(
            label(
                "Как пользоваться\n" +
                    "• Откройте игру: поверх неё появится кнопка A→Я.\n" +
                    "• Нажмите кнопку и выберите «Весь экран» или «Область».\n" +
                    "• Нажмите на перевод, чтобы его скрыть. Кнопку можно перетаскивать.\n" +
                    "• Если игра защищает экран от скриншотов, будет чёрный кадр, и перевод не сработает.",
                13f, Palette.MUTED
            ).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        )

        return ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            addView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        }
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = rounded(Palette.ACCENT, dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            setOnClickListener { if (isEnabled) onClick() }
        }

    private fun card(title: String, desc: String, status: TextView?, btn: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(0xFF171D25.toInt(), dp(16), Palette.STROKE, dp(1))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
            addView(label(title, 16f, Palette.TEXT, bold = true))
            addView(label(desc, 13f, Palette.MUTED).apply { setPadding(0, dp(4), 0, 0) })
            if (status != null) {
                status.setPadding(0, dp(8), 0, 0)
                addView(status)
            }
            addView(btn)
        }

    private fun refresh() {
        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatus.text = if (canOverlay) "✓ Разрешение выдано" else "Не выдано"
        overlayStatus.setTextColor(if (canOverlay) Palette.OK else Palette.WARN)
        overlayBtn.visibility = if (canOverlay) View.GONE else View.VISIBLE

        modelStatus.text = when {
            modelsReady -> "✓ Модели загружены"
            downloading -> "Скачиваю… подождите"
            else -> "Не загружены (нужен интернет)"
        }
        modelStatus.setTextColor(if (modelsReady) Palette.OK else Palette.WARN)
        modelBtn.visibility = if (modelsReady) View.GONE else View.VISIBLE
        modelBtn.isEnabled = !downloading
        modelBtn.alpha = if (downloading) 0.4f else 1f

        val running = OverlayService.running
        startBtn.text = if (running) "Остановить переводчик" else "Запустить плавающую кнопку"
        startBtn.isEnabled = running || (canOverlay && modelsReady)
        startBtn.alpha = if (startBtn.isEnabled) 1f else 0.4f
    }

    // ---------------------------------------------------------------- Языковые модели

    private fun checkModels() {
        val mgr = RemoteModelManager.getInstance()
        val en = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
        val ru = TranslateRemoteModel.Builder(TranslateLanguage.RUSSIAN).build()
        mgr.isModelDownloaded(en).addOnSuccessListener { enOk ->
            mgr.isModelDownloaded(ru).addOnSuccessListener { ruOk ->
                modelsReady = enOk && ruOk
                refresh()
            }
        }
    }

    private fun downloadModels() {
        downloading = true
        refresh()
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                downloading = false
                modelsReady = true
                refresh()
            }
            .addOnFailureListener {
                translator.close()
                downloading = false
                Toast.makeText(this, "Не удалось скачать модели. Проверьте интернет", Toast.LENGTH_LONG).show()
                refresh()
            }
    }

    // ---------------------------------------------------------------- Запуск / остановка

    private fun onStartClicked() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            startBtn.postDelayed({ refresh() }, 300)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startForegroundService(svc)
                Toast.makeText(this, "Откройте игру: кнопка A→Я уже поверх экрана", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "Без доступа к экрану перевод не работает", Toast.LENGTH_LONG).show()
            }
        }
    }
}
