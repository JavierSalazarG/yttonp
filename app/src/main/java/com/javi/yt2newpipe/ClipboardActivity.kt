package com.javi.yt2newpipe

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Actividad invisible. Android 10+ solo deja leer el portapapeles a la app que tiene el foco,
 * así que nos ponemos delante un instante, leemos el enlace y se lo pasamos a NewPipe.
 */
class ClipboardActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var done = false

    private val ytUrl = Regex("""https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/\S+""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) tryRead()
    }

    private fun tryRead() {
        if (done) return
        val url = readFreshUrl()
        when {
            url != null -> openInNewPipe(url)
            ++attempts < 20 -> handler.postDelayed({ tryRead() }, 75) // hasta ~1,5 s
            else -> finishWith(false, "No encontré el enlace en el portapapeles")
        }
    }

    /** Devuelve el enlace solo si se copió después de pulsar "Copiar enlace". */
    private fun readFreshUrl(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try { cm.primaryClip } catch (_: Exception) { null } ?: return null
        val since = YtInterceptService.instance?.copyRequestedAt ?: 0L
        val ts = clip.description?.timestamp ?: 0L
        // Margen de 1 s por si el reloj del portapapeles va un poco desfasado.
        if (since > 0 && ts > 0 && ts < since - 1000) return null
        val text = (0 until clip.itemCount)
            .joinToString(" ") { clip.getItemAt(it).coerceToText(this)?.toString() ?: "" }
        return ytUrl.find(text)?.value
    }

    private fun openInNewPipe(url: String) {
        val preferred = Prefs.target(this)
        val candidates = listOf(preferred) + Prefs.TARGETS.keys.filter { it != preferred }
        for (pkg in candidates) {
            if (!isInstalled(pkg)) continue
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                Log.i(YtInterceptService.TAG, "Abierto en $pkg: $url")
                finishWith(true, null)
                return
            } catch (_: ActivityNotFoundException) { }
        }
        finishWith(false, "NewPipe no está instalado")
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun finishWith(success: Boolean, error: String?) {
        done = true
        if (error != null) Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        YtInterceptService.instance?.onHandoffFinished(success)
        finish()
        overridePendingTransition(0, 0)
    }
}
