package com.fcgamers.gravador

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val PREF_SINGLE = "video_unico"
        private const val SHAPE_ID_BASE = 100
    }

    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var singleBox: CheckBox
    private lateinit var shapeGroup: RadioGroup
    private lateinit var sizeBar: SeekBar

    private val prefs by lazy { getSharedPreferences(CameraPip.PREFS, Context.MODE_PRIVATE) }

    private val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraAndMic()) {
                askScreenCapture()
            } else {
                Toast.makeText(this, "Preciso de câmera e microfone para gravar", Toast.LENGTH_LONG).show()
            }
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RecorderService.EXTRA_DATA, data)
                    .putExtra(RecorderService.EXTRA_SINGLE, prefs.getBoolean(PREF_SINGLE, true))
                startForegroundService(intent)
                // Fecha o app: nenhum botão fica na tela durante a gravação.
                finish()
            } else {
                Toast.makeText(this, "Gravação de tela não autorizada", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val gap = (16 * d).toInt()

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }

        singleBox = CheckBox(this).apply {
            text = "Vídeo único: câmera no canto da tela"
            isChecked = prefs.getBoolean(PREF_SINGLE, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(PREF_SINGLE, checked).apply()
            }
        }

        val windowTitle = TextView(this).apply {
            text = "Janela da câmera"
            textSize = 18f
            setPadding(0, gap, 0, 0)
        }
        val windowHint = TextView(this).apply {
            text = "Na gravação: arraste a janelinha para mover, use dois dedos para mudar o tamanho " +
                "e dê dois toques para trocar o formato. O que você ajustar fica salvo."
            textSize = 13f
        }

        val labels = listOf("Círculo", "Quadrado", "Retângulo")
        shapeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            labels.forEachIndexed { i, label ->
                addView(RadioButton(context).apply {
                    id = SHAPE_ID_BASE + i
                    text = label
                })
            }
            check(SHAPE_ID_BASE + prefs.getInt(CameraPip.PREF_SHAPE, CameraPip.SHAPE_SQUARE).coerceIn(0, 2))
            setOnCheckedChangeListener { _, checkedId ->
                prefs.edit().putInt(CameraPip.PREF_SHAPE, checkedId - SHAPE_ID_BASE).apply()
            }
        }

        val sizeLabel = TextView(this).apply {
            text = "Tamanho"
            textSize = 14f
        }
        sizeBar = SeekBar(this).apply {
            max = (CameraPip.MAX_SIZE - CameraPip.MIN_SIZE).toInt()
            progress = (prefs.getFloat(CameraPip.PREF_SIZE, CameraPip.DEFAULT_SIZE) - CameraPip.MIN_SIZE)
                .toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putFloat(CameraPip.PREF_SIZE, CameraPip.MIN_SIZE + value).apply()
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }

        val resetButton = Button(this).apply {
            text = "Voltar a janela para o canto"
            setOnClickListener {
                prefs.edit().remove(CameraPip.PREF_HAS_POS).apply()
                Toast.makeText(context, "Posição redefinida", Toast.LENGTH_SHORT).show()
            }
        }

        button = Button(this).apply {
            setOnClickListener { onButton() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(singleBox)
            addView(windowTitle)
            addView(windowHint)
            addView(shapeGroup)
            addView(sizeLabel)
            addView(sizeBar)
            addView(resetButton)
            addView(button)
        }
        setContentView(ScrollView(this).apply { addView(column) })

        if (intent.getBooleanExtra(EXTRA_AUTO_START, false) && !RecorderService.isRecording) {
            startFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (RecorderService.isRecording) {
            status.text = "Gravando…\nToque na notificação para parar."
            button.text = "Parar gravação"
            singleBox.isEnabled = false
        } else {
            status.text = "Grava a tela e a câmera traseira ao mesmo tempo, com áudio.\n" +
                "Ao iniciar: contagem de 3 segundos e o app fecha. Para parar, toque na notificação " +
                "ou use o bloco \"Gravar\" nas configurações rápidas."
            button.text = "Iniciar gravação"
            singleBox.isEnabled = true
        }
    }

    private fun onButton() {
        if (RecorderService.isRecording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            refresh()
        } else {
            startFlow()
        }
    }

    private fun startFlow() {
        if (hasCameraAndMic()) askScreenCapture() else permissionLauncher.launch(permissions)
    }

    private fun askScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val single = prefs.getBoolean(PREF_SINGLE, true)
        val intent = if (single && Build.VERSION.SDK_INT >= 34) {
            // Só "tela inteira": gravar um único app deixaria a janelinha da câmera fora do vídeo.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun hasCameraAndMic() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
