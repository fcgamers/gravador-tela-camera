package com.fcgamers.gravador

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grava a tela (MediaProjection) e a câmera traseira (Camera2) ao mesmo tempo, com áudio do microfone.
 *
 * Dois modos:
 *  - vídeo único (padrão): a câmera aparece em uma janelinha no canto da tela e é gravada junto com ela;
 *  - dois arquivos: tela e câmera em MP4 separados.
 *
 * Antes de gravar há uma contagem regressiva de 3 segundos. Não existe botão na tela durante a gravação:
 * para parar, toque na notificação ou use o bloco "Gravar" nas configurações rápidas.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.fcgamers.gravador.START"
        const val ACTION_STOP = "com.fcgamers.gravador.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_SINGLE = "single"

        private const val TAG = "Gravador"
        private const val CHANNEL_ID = "gravacao"
        private const val NOTIF_ID = 1
        private const val COUNTDOWN_SECONDS = 3

        @Volatile
        var isRecording = false
            private set
    }

    private class Output(val uri: Uri, val pfd: ParcelFileDescriptor)

    private val handler = Handler(Looper.getMainLooper())

    private var single = true

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenRecorder: MediaRecorder? = null
    private var screenOut: Output? = null

    // Modo "dois arquivos"
    private var camRecorder: MediaRecorder? = null
    private var camOut: Output? = null

    // Câmera (os dois modos)
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Modo "vídeo único": janelinha com a câmera
    private var pip: CameraPip? = null

    private var countdownView: TextView? = null

    private var stamp = ""

    @Volatile private var screenStarted = false
    @Volatile private var camStarted = false
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isRecording) start(intent)
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopping) stopRecording()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- início

    private fun start(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        single = intent.getBooleanExtra(EXTRA_SINGLE, true)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        createChannel()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, buildNotification("Começa em $COUNTDOWN_SECONDS segundos. Toque aqui para parar."), types)

        if (data == null) {
            fail("Sem permissão para gravar a tela")
            return
        }

        isRecording = true
        refreshTile()

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { stopRecording() }
                }
            }
            // No Android 14+ o callback precisa ser registrado antes de criar o VirtualDisplay.
            p.registerCallback(cb, handler)
            projection = p
            projectionCallback = cb
        } catch (e: Exception) {
            fail("Não foi possível capturar a tela", e)
            return
        }

        beginCountdown()
    }

    // ------------------------------------------------------------ contagem

    private fun beginCountdown() {
        val canOverlay = Settings.canDrawOverlays(this)
        if (single && !canOverlay) {
            single = false
            Toast.makeText(
                this,
                "Sem permissão \"Exibir sobre outros apps\": vou gravar tela e câmera em 2 arquivos",
                Toast.LENGTH_LONG
            ).show()
        }
        try {
            if (canOverlay) showCountdownView()
            if (single) startCameraPreviewOverlay() // a câmera já aparece na contagem, para você enquadrar
        } catch (e: Exception) {
            fail("Falha ao preparar a gravação: ${e.message}", e)
            return
        }
        tick(COUNTDOWN_SECONDS)
    }

    private fun tick(n: Int) {
        if (stopping) return
        if (n == 0) {
            removeCountdownView()
            // Pequena folga para a contagem sumir da tela antes de a gravação começar.
            handler.postDelayed({ beginRecording() }, 300)
            return
        }
        countdownView?.text = n.toString()
        updateNotification("Começa em $n… Toque aqui para parar.")
        beep()
        handler.postDelayed({ tick(n - 1) }, 1000)
    }

    private fun showCountdownView() {
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = COUNTDOWN_SECONDS.toString()
            textSize = 96f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAA000000.toInt())
            }
        }
        val size = (200 * d).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        getSystemService(WindowManager::class.java).addView(tv, lp)
        countdownView = tv
    }

    private fun removeCountdownView() {
        countdownView?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        countdownView = null
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ tone.release() }, 500)
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------------------- gravar

    private fun beginRecording() {
        if (stopping) return
        try {
            stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            startScreen()
            if (!single) startCamera()
            updateNotification("Gravando. Toque aqui para parar.")
        } catch (e: Exception) {
            fail("Falha ao iniciar a gravação: ${e.message}", e)
        }
    }

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics
    }

    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation

    // ------------------------------------------------------------------ tela

    private fun startScreen() {
        val metrics = realMetrics()

        // Limita o lado menor a 1080 px e garante dimensões pares.
        val scale = minOf(1f, 1080f / minOf(metrics.widthPixels, metrics.heightPixels))
        val w = (metrics.widthPixels * scale).toInt() and 1.inv()
        val h = (metrics.heightPixels * scale).toInt() and 1.inv()

        val out = createOutput(if (single) "gravacao_$stamp.mp4" else "tela_$stamp.mp4")
        screenOut = out

        val rec = newRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(out.pfd.fileDescriptor)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setVideoSize(w, h)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(8_000_000)
        rec.setAudioEncodingBitRate(128_000)
        rec.setAudioSamplingRate(44_100)
        rec.prepare()
        screenRecorder = rec

        virtualDisplay = projection!!.createVirtualDisplay(
            "gravador-tela", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, handler
        )
        rec.start()
        screenStarted = true
    }

    // ---------------------------------------------------------------- câmera

    private fun backCameraId(cm: CameraManager): String =
        cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalStateException("Este aparelho não tem câmera traseira")

    @SuppressLint("MissingPermission")
    private fun openBackCamera(cm: CameraManager, id: String, onOpened: (CameraDevice) -> Unit) {
        val thread = cameraThread ?: HandlerThread("gravador-camera").also {
            it.start()
            cameraThread = it
        }
        val h = cameraHandler ?: Handler(thread.looper).also { cameraHandler = it }
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                onOpened(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (!stopping) fail("A câmera foi desconectada")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (!stopping) fail("Erro na câmera (código $error)")
            }
        }, h)
    }

    // ---- modo vídeo único: janelinha da câmera no canto, capturada junto com a tela

    private fun startCameraPreviewOverlay() {
        val cm = getSystemService(CameraManager::class.java)
        val id = backCameraId(cm)
        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Câmera sem formatos de vídeo")
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
        val size = sizes
            .filter { it.width <= 1280 && it.height <= 720 && it.width * 9 == it.height * 16 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
            ?: sizes.first()

        val metrics = realMetrics()
        val newPip = CameraPip(this, size, displayRotation(), metrics.widthPixels, metrics.heightPixels) { surface ->
            try {
                openBackCamera(cm, id) { device -> startPreviewSession(device, surface) }
            } catch (e: Exception) {
                fail("Sem acesso à câmera: ${e.message}", e)
            }
        }
        pip = newPip
        newPip.show()
    }

    @Suppress("DEPRECATION")
    private fun startPreviewSession(device: CameraDevice, surface: Surface) {
        if (stopping) return
        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (stopping) return
                        cameraSession = session
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply { addTarget(surface) }
                                .build()
                            session.setRepeatingRequest(request, null, cameraHandler)
                        } catch (e: Exception) {
                            fail("Falha ao abrir a câmera: ${e.message}", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail("Não foi possível configurar a câmera")
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            fail("Sem acesso à câmera: ${e.message}", e)
        }
    }

    // ---- modo dois arquivos: câmera gravada em um MP4 à parte

    private fun startCamera() {
        val cm = getSystemService(CameraManager::class.java)
        val id = backCameraId(cm)
        val ch = cm.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Câmera sem formatos de vídeo")
        val sizes = map.getOutputSizes(MediaRecorder::class.java).toList()
        val size = sizes
            .filter { it.width <= 1920 && it.height <= 1080 && it.width * 9 == it.height * 16 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.first()

        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val hint = (sensor - rotation + 360) % 360

        openBackCamera(cm, id) { device -> startCameraSession(device, size, hint, withAudio = true) }
    }

    @Suppress("DEPRECATION")
    private fun startCameraSession(device: CameraDevice, size: Size, hint: Int, withAudio: Boolean) {
        if (stopping) return
        val rec = try {
            buildCameraRecorder(size, hint, withAudio)
        } catch (e: Exception) {
            if (withAudio) {
                Log.w(TAG, "Câmera com áudio falhou, tentando sem áudio", e)
                return startCameraSession(device, size, hint, false)
            }
            fail("Falha ao preparar a câmera: ${e.message}", e)
            return
        }
        camRecorder = rec
        val surface = rec.surface

        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (stopping) return
                        cameraSession = session
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                .apply { addTarget(surface) }
                                .build()
                            session.setRepeatingRequest(request, null, cameraHandler)
                            rec.start()
                            camStarted = true
                        } catch (e: Exception) {
                            if (withAudio) {
                                // Alguns aparelhos não deixam dois gravadores usarem o microfone.
                                Log.w(TAG, "Início com áudio falhou, tentando sem áudio", e)
                                try { session.close() } catch (_: Exception) {}
                                cameraSession = null
                                try { rec.release() } catch (_: Exception) {}
                                startCameraSession(device, size, hint, false)
                            } else {
                                fail("Falha ao gravar a câmera: ${e.message}", e)
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail("Não foi possível configurar a câmera")
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            fail("Sem acesso à câmera: ${e.message}", e)
        }
    }

    private fun buildCameraRecorder(size: Size, hint: Int, withAudio: Boolean): MediaRecorder {
        camOut?.let { finishOutput(it, keep = false) }
        val out = createOutput("camera_$stamp.mp4")
        camOut = out

        val rec = newRecorder()
        if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(out.pfd.fileDescriptor)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (withAudio) {
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
        }
        rec.setVideoSize(size.width, size.height)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(10_000_000)
        rec.setOrientationHint(hint)
        rec.prepare()
        return rec
    }

    // ------------------------------------------------------------------ fim

    private fun fail(message: String, e: Throwable? = null) {
        Log.e(TAG, message, e)
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)

        // Tela primeiro (no modo único a câmera está dentro dela), depois a câmera.
        val screenOk = stopRecorder(screenRecorder, screenStarted)
        screenRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try {
            cameraSession?.stopRepeating()
            cameraSession?.abortCaptures()
        } catch (_: Exception) {}
        val camOk = stopRecorder(camRecorder, camStarted)
        camRecorder = null
        try { cameraSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraSession = null
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        removeCountdownView()
        pip?.remove()
        pip = null

        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (_: Exception) {}
        projection = null

        finishOutput(camOut, camOk)
        finishOutput(screenOut, screenOk)
        camOut = null
        screenOut = null

        isRecording = false
        refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (camOk || screenOk) {
            Toast.makeText(this, "Gravação salva em Filmes/GravadorTelaCamera", Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    private fun stopRecorder(rec: MediaRecorder?, started: Boolean): Boolean {
        if (rec == null) return false
        var ok = false
        if (started) {
            try {
                rec.stop()
                ok = true
            } catch (e: Exception) {
                Log.w(TAG, "stop() falhou (gravação muito curta?)", e)
            }
        }
        try { rec.release() } catch (_: Exception) {}
        return ok
    }

    // ------------------------------------------------------------- utilitários

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun createOutput(name: String): Output {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/GravadorTelaCamera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar o arquivo de vídeo")
        // "rw": o MP4 precisa ler e regravar o arquivo ao finalizar.
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: error("Não foi possível abrir o arquivo de vídeo")
        return Output(uri, pfd)
    }

    private fun finishOutput(out: Output?, keep: Boolean) {
        if (out == null) return
        try { out.pfd.close() } catch (_: Exception) {}
        try {
            if (keep) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(out.uri, values, null, null)
            } else {
                contentResolver.delete(out.uri, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao finalizar arquivo", e)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Gravação", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_rec)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("Gravando")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(icon, "Parar", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, RecordTileService::class.java))
        } catch (_: Exception) {}
    }
}
