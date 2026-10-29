package com.fcgamers.gravador

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Bloco "Gravar" nas configurações rápidas: inicia ou para a gravação sem botão na tela. */
class RecordTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (RecorderService.isRecording) {
            try {
                startService(
                    Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        } else {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 2, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (RecorderService.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (RecorderService.isRecording) "Parar" else "Gravar"
        tile.updateTile()
    }
}
