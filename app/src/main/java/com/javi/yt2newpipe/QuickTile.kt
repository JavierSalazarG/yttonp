package com.javi.yt2newpipe

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Interruptor en los ajustes rápidos (cortinilla) para activar/desactivar el desvío. */
class QuickTile : TileService() {

    companion object {
        fun requestUpdate(c: Context) = try {
            TileService.requestListeningState(c, ComponentName(c, QuickTile::class.java))
        } catch (_: Exception) { }
    }

    override fun onStartListening() = render()

    override fun onClick() {
        Prefs.setEnabled(this, !Prefs.enabled(this))
        YtInterceptService.instance?.onToggled()
        render()
    }

    private fun render() {
        val t = qsTile ?: return
        val on = Prefs.enabled(this)
        val running = YtInterceptService.instance != null
        t.state = if (on && running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "YT → NewPipe"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when {
                !running -> "Servicio apagado"
                on -> "Activado"
                else -> "Pausado"
            }
        }
        t.updateTile()
    }
}
