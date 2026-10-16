package com.javi.yt2newpipe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Flujo:
 *  1. Tocas un vídeo en YouTube → aparece su reproductor con el botón "Compartir".
 *  2. Si hay anuncio: silencio + "Saltar" en cuanto se pueda; esperamos a que acabe.
 *  3. "Compartir" → "Copiar enlace" → pausa YouTube → ClipboardActivity abre NewPipe.
 *  4. Cuando YouTube vuelve a estar delante, minimiza y cierra su reproductor.
 *
 * Los Shorts y las páginas de playlist se ignoran.
 */
class YtInterceptService : AccessibilityService() {

    companion object {
        const val TAG = "YT2NP"
        const val YT = "com.google.android.youtube"

        private const val IDLE_SCAN_GAP_MS = 80L      // como mucho un escaneo cada 80 ms al hacer scroll
        private const val POLL_MS = 50L
        private const val AD_POLL_MS = 120L
        private const val SHARE_PANEL_TIMEOUT_MS = 2500L
        private const val AD_TIMEOUT_MS = 120_000L
        private const val AFTER_AD_MS = 450L          // margen tras el anuncio para que cargue el vídeo real
        private const val HANDOFF_TIMEOUT_MS = 4000L
        private const val CLEANUP_TIMEOUT_MS = 3000L
        private const val MAX_NODES = 2500

        @Volatile var instance: YtInterceptService? = null
            private set
    }

    private enum class State { IDLE, WAIT_AD, WAIT_COPY_LINK, HANDOFF }

    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private var state = State.IDLE
    private var stateDeadline = 0L
    private var adClearSince = 0L

    /** Se "arma" cuando no hay reproductor en pantalla; así cada vídeo se procesa una sola vez. */
    private var armed = true

    private var pendingCleanup = false
    private var cleanupStartedAt = 0L
    private var cleanupBacks = 0
    private var lastBackAt = 0L
    private var noPlayerSince = 0L

    /** Momento en que pulsamos "Copiar enlace" (para ignorar enlaces viejos del portapapeles). */
    @Volatile var copyRequestedAt = 0L
        private set

    private var ytForeground = false
    private var overlay: TextView? = null
    private var scanScheduled = false
    private var lastScanAt = 0L

    // ================================================================ ciclo de vida

    override fun onServiceConnected() {
        instance = this
        if (Prefs.mutedByUs(this)) unmute() // por si la app se cerró a mitad
        Log.i(TAG, "Servicio conectado")
    }

    override fun onDestroy() {
        unmute()
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val fg = youtubeOnTop()
            if (fg != ytForeground) {
                ytForeground = fg
                refreshOverlay()
            }
        }
        if (event.packageName?.toString() != YT || !Prefs.enabled(this)) return
        requestScan()
    }

    /** Agrupa ráfagas de eventos (scroll) en un escaneo como mucho cada 80 ms. */
    private fun requestScan(delay: Long = -1) {
        if (delay >= 0) {
            handler.removeCallbacks(scanRunnable)
            scanScheduled = true
            handler.postDelayed(scanRunnable, delay)
            return
        }
        if (scanScheduled) return
        scanScheduled = true
        val wait = (lastScanAt + IDLE_SCAN_GAP_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        handler.postDelayed(scanRunnable, wait)
    }

    private val scanRunnable = Runnable {
        scanScheduled = false
        lastScanAt = SystemClock.uptimeMillis()
        try { scan() } catch (e: Exception) { Log.e(TAG, "scan", e); reset() }
    }

    // ================================================================ máquina de estados

    private fun scan() {
        if (!Prefs.enabled(this)) { reset(); return }
        val now = SystemClock.uptimeMillis()
        val roots = youtubeRoots()

        if (roots.isEmpty()) {
            when (state) {
                // YouTube no está delante: si estábamos a medias, abandonamos.
                State.WAIT_AD, State.WAIT_COPY_LINK -> if (now > stateDeadline || !ytForeground) reset()
                else -> Unit
            }
            return
        }
        val s = Screen.read(roots, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

        if (pendingCleanup && state == State.IDLE) { cleanup(s, now); return }

        when (state) {
            State.IDLE -> {
                if (!s.isWatchPage) {
                    if (s.share == null) armed = true
                    return
                }
                if (s.isShorts && !Prefs.shorts(this)) return
                if (!armed) return
                armed = false
                Log.i(TAG, "Vídeo abierto")
                mute()
                if (s.adVisible) enterWaitAd(now) else clickShare(s, now)
            }

            State.WAIT_AD -> {
                when {
                    now > stateDeadline -> { Log.w(TAG, "El anuncio no terminó"); reset() }
                    s.adVisible -> {
                        adClearSince = 0
                        s.skip?.let { click(it) }
                        requestScan(AD_POLL_MS)
                    }
                    s.share == null -> {
                        // Ni anuncio ni reproductor: o está cargando, o te has ido.
                        if (adClearSince == 0L) adClearSince = now
                        if (now - adClearSince > 1500) reset() else requestScan(AD_POLL_MS)
                    }
                    else -> {
                        if (adClearSince == 0L) adClearSince = now
                        if (now - adClearSince >= AFTER_AD_MS) clickShare(s, now) else requestScan(AD_POLL_MS)
                    }
                }
            }

            State.WAIT_COPY_LINK -> {
                when {
                    s.adVisible -> {
                        // Empezó un anuncio con el panel abierto: lo cerramos y esperamos.
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        enterWaitAd(now)
                    }
                    s.copyLink != null -> {
                        copyRequestedAt = System.currentTimeMillis()
                        click(s.copyLink!!)
                        state = State.HANDOFF
                        stateDeadline = now + HANDOFF_TIMEOUT_MS
                        updateOverlayLook()
                        // YouTube escribe el enlace y cierra el panel; mientras, pausamos y abrimos NewPipe.
                        handler.postDelayed({ pauseMedia(); launchClipboardActivity() }, 40)
                        handler.postDelayed({ if (state == State.HANDOFF) { Log.w(TAG, "Timeout"); reset() } },
                            HANDOFF_TIMEOUT_MS)
                    }
                    now > stateDeadline -> {
                        saveDiagnostic("No apareció 'Copiar enlace' tras pulsar Compartir")
                        reset()
                    }
                    else -> requestScan(POLL_MS)
                }
            }

            State.HANDOFF -> Unit
        }
    }

    private fun enterWaitAd(now: Long) {
        Log.i(TAG, "Anuncio: esperando")
        state = State.WAIT_AD
        adClearSince = 0
        stateDeadline = now + AD_TIMEOUT_MS
        updateOverlayLook()
        requestScan(AD_POLL_MS)
    }

    private fun clickShare(s: Screen, now: Long) {
        val share = s.share ?: return reset()
        click(share)
        state = State.WAIT_COPY_LINK
        stateDeadline = now + SHARE_PANEL_TIMEOUT_MS
        updateOverlayLook()
        requestScan(POLL_MS)
    }

    private fun reset() {
        state = State.IDLE
        unmute()
        updateOverlayLook()
    }

    private fun launchClipboardActivity() {
        try {
            startActivity(
                Intent(this, ClipboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir ClipboardActivity", e)
            reset()
        }
    }

    /** Lo llama ClipboardActivity al terminar. */
    fun onHandoffFinished(success: Boolean) {
        handler.post {
            state = State.IDLE
            // NewPipe tarda más de medio segundo en empezar a sonar; devolvemos el audio un poco antes.
            handler.postDelayed({ if (state == State.IDLE) unmute() }, 250)
            if (success && Prefs.closeYoutubePlayer(this)) {
                pendingCleanup = true
                cleanupStartedAt = 0
                cleanupBacks = 0
                noPlayerSince = 0
            } else {
                armed = false // no repetir sobre el mismo vídeo
            }
            updateOverlayLook()
            requestScan(150)
        }
    }

    /**
     * Con YouTube delante otra vez: BACK para minimizar el reproductor (y cerrar el panel si quedó
     * abierto) y luego la X del minirreproductor. Así vuelves a la lista o búsqueda donde estabas.
     */
    private fun cleanup(s: Screen, now: Long) {
        if (!activeWindowIsYoutube()) { requestScan(200); return }
        if (cleanupStartedAt == 0L) cleanupStartedAt = now
        if (now - cleanupStartedAt > CLEANUP_TIMEOUT_MS) { finishCleanup(); return }

        if (s.share != null || s.copyLink != null) {
            noPlayerSince = 0
            if (cleanupBacks < 3 && now - lastBackAt > 400) {
                cleanupBacks++
                lastBackAt = now
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            requestScan(150)
            return
        }
        s.closeMini?.let { click(it); finishCleanup(); return }
        if (noPlayerSince == 0L) noPlayerSince = now
        if (now - noPlayerSince > 800) finishCleanup() else requestScan(120)
    }

    private fun finishCleanup() {
        pendingCleanup = false
        armed = true
    }

    // ================================================================ audio

    private fun pauseMedia() {
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) { Log.w(TAG, "pause", e) }
    }

    private fun mute() {
        if (!Prefs.mute(this) || Prefs.mutedByUs(this)) return
        try {
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) return // ya lo tenías silenciado tú
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            Prefs.setMutedByUs(this, true)
        } catch (e: Exception) { Log.w(TAG, "mute", e) }
    }

    private fun unmute() {
        if (!Prefs.mutedByUs(this)) return
        try { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
        catch (e: Exception) { Log.w(TAG, "unmute", e) }
        Prefs.setMutedByUs(this, false)
    }

    // ================================================================ ventanas y nodos

    /** ¿La app que está delante (ignorando teclados, barras y ventanas flotantes) es YouTube? */
    private fun youtubeOnTop(): Boolean = try {
        val r = Rect()
        val minH = resources.displayMetrics.heightPixels / 2
        var result = false
        for (w in windows) { // de arriba abajo
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = w.root?.packageName?.toString() ?: continue
            if (pkg == packageName) continue
            w.getBoundsInScreen(r)
            if (r.height() < minH) continue // ventanas flotantes (popup de NewPipe, etc.)
            result = pkg == YT
            break
        }
        result
    } catch (_: Exception) {
        rootInActiveWindow?.packageName?.toString() == YT
    }

    private fun activeWindowIsYoutube() = rootInActiveWindow?.packageName?.toString() == YT

    private fun youtubeRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(2)
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == YT) out += r
            }
        } catch (_: Exception) { }
        if (out.isEmpty()) rootInActiveWindow?.let { if (it.packageName?.toString() == YT) out += it }
        return out
    }

    /** Todo lo que nos interesa de la pantalla, leído en una sola pasada. */
    private class Screen {
        var share: AccessibilityNodeInfo? = null
        val shareRect = Rect()
        var copyLink: AccessibilityNodeInfo? = null
        var closeMini: AccessibilityNodeInfo? = null
        var skip: AccessibilityNodeInfo? = null
        var skipTop = Int.MAX_VALUE
        val adTops = ArrayList<Int>()
        var reelIds = false
        var shortsText = false
        var playlistPage = false
        var w = 0
        var h = 0

        /** Límite inferior del reproductor: por encima del botón Compartir. */
        private val playerBottom get() = if (share != null) shareRect.top else (h * 0.45f).toInt()

        val adVisible get() = adTops.any { it < playerBottom }

        /** Shorts: estructura "reel" o Compartir en la columna derecha de la mitad baja. */
        val isShorts get() = reelIds || shortsText ||
            (share != null && shareRect.exactCenterX() > w * 0.78f && shareRect.top > h * 0.4f)

        val isWatchPage get() = share != null && !playlistPage

        fun skipIfInPlayer() = skip?.takeIf { skipTop < playerBottom }

        companion object {
            private fun eq(l: String, list: List<String>) = list.any { l.equals(it, true) }
            private fun pre(l: String, list: List<String>) = list.any { l.startsWith(it, true) }

            fun read(roots: List<AccessibilityNodeInfo>, w: Int, h: Int): Screen {
                val s = Screen().also { it.w = w; it.h = h }
                val r = Rect()
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                roots.forEach { stack.addLast(it) }
                var visited = 0
                while (stack.isNotEmpty() && visited < MAX_NODES) {
                    val n = stack.removeLast()
                    visited++
                    if (!n.isVisibleToUser) continue

                    n.viewIdResourceName?.let { full ->
                        val id = full.substringAfter(":id/")
                        if (id.contains("reel")) s.reelIds = true
                        val parts = id.split('_')
                        if ("ad" in parts || "ads" in parts || id.contains("skip_ad")) {
                            n.getBoundsInScreen(r); s.adTops += r.top
                        }
                    }

                    for (raw in arrayOf(n.text, n.contentDescription)) {
                        val l = raw?.toString()?.trim() ?: continue
                        if (l.isEmpty() || l.length > 60) continue
                        if (s.share == null && SHARE_MATCH(l)) {
                            s.share = n; n.getBoundsInScreen(s.shareRect)
                        }
                        if (s.copyLink == null && eq(l, YtTexts.COPY_LINK)) s.copyLink = n
                        if (eq(l, YtTexts.PLAYLIST_PAGE)) s.playlistPage = true
                        if (eq(l, YtTexts.SHORTS_MARKERS)) s.shortsText = true
                        if (eq(l, YtTexts.AD_EXACT) || pre(l, YtTexts.AD_PREFIX)) {
                            n.getBoundsInScreen(r); s.adTops += r.top
                        }
                        if (s.skip == null && (eq(l, YtTexts.SKIP_EXACT) ||
                                // "Saltar anuncio" sí; "Saltar anuncio en 5" (cuenta atrás) no.
                                (pre(l, YtTexts.SKIP_PREFIX) && l.none(Char::isDigit)))
                        ) {
                            s.skip = n; n.getBoundsInScreen(r); s.skipTop = r.top
                        }
                        if (s.closeMini == null) {
                            if (pre(l, YtTexts.CLOSE_MINIPLAYER)) s.closeMini = n
                            else if (eq(l, YtTexts.CLOSE_EXACT)) {
                                n.getBoundsInScreen(r)
                                if (r.top > h * 0.6f) s.closeMini = n
                            }
                        }
                    }
                    for (i in n.childCount - 1 downTo 0) {
                        val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                        stack.addLast(c)
                    }
                }
                s.skip = s.skipIfInPlayer()
                return s
            }

            private val SHARE_MATCH: (String) -> Boolean = { l ->
                YtTexts.SHARE.any { t -> l.equals(t, true) || (l.startsWith(t, true) && l.length <= t.length + 20) }
            }
        }
    }

    /** Pulsa el nodo (o su primer padre pulsable); si falla, toca su centro con un gesto. */
    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
            depth++
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 40)).build(),
            null, null
        )
    }

    // ================================================================ diagnóstico

    /** Guarda lo que hay en pantalla para poder ajustar los textos si YouTube cambia. */
    fun saveDiagnostic(reason: String) {
        val roots = youtubeRoots().ifEmpty {
            try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }
        }
        val dm = resources.displayMetrics
        val s = Screen.read(roots, dm.widthPixels, dm.heightPixels)
        val sb = StringBuilder("Motivo: $reason\n")
            .append("v${BuildConfigCompat.version(this)} estado=$state armado=$armed limpieza=$pendingCleanup ytDelante=$ytForeground\n")
            .append("watch=${s.isWatchPage} shorts=${s.isShorts}(reel=${s.reelIds},txt=${s.shortsText}) ")
            .append("playlist=${s.playlistPage} anuncio=${s.adVisible} compartir=${s.share != null} ")
            .append("copiar=${s.copyLink != null} saltar=${s.skip != null} cerrarMini=${s.closeMini != null}\n")
        try {
            for (w in windows) sb.append("Ventana tipo=${w.type} capa=${w.layer} activa=${w.isActive} ")
                .append("pkg=${w.root?.packageName}\n")
        } catch (_: Exception) { }
        sb.append('\n')
        var count = 0
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || count > 700) return
            count++
            val lbl = listOfNotNull(n.text?.toString()?.trim(), n.contentDescription?.toString()?.trim())
                .filter { it.isNotEmpty() }.joinToString(" | ").take(80)
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            if (lbl.isNotEmpty() || id.isNotEmpty()) {
                n.getBoundsInScreen(r)
                sb.append("  ".repeat(d.coerceAtMost(20)))
                    .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                    .append(if (id.isNotEmpty()) " #$id" else "")
                    .append(if (lbl.isNotEmpty()) " «$lbl»" else "")
                    .append(" @${r.left},${r.top}")
                    .append(if (n.isClickable) " [click]" else "")
                    .append(if (!n.isVisibleToUser) " [oculto]" else "")
                    .append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        roots.forEach { walk(it, 0) }
        Prefs.setLastDiag(this, sb.toString())
        Log.i(TAG, sb.toString())
    }

    // ================================================================ botón flotante

    /** Llamado también desde MainActivity y el mosaico de ajustes rápidos. */
    fun refreshOverlay() {
        handler.post {
            if (ytForeground && Prefs.overlay(this)) showOverlay() else removeOverlay()
            updateOverlayLook()
        }
    }

    /** Al activar/desactivar desde fuera: no disparar sobre el vídeo que ya está abierto. */
    fun onToggled() {
        handler.post { armed = false; reset(); requestScan(0) }
        refreshOverlay()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (x, y) = Prefs.overlayPos(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x; this.y = y
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false
        val longPress = Runnable {
            longPressed = true
            saveDiagnostic("Captura manual (pulsación larga en el botón)")
            Toast.makeText(this, "Diagnóstico guardado. Ábrelo en la app YT → NewPipe.", Toast.LENGTH_LONG).show()
        }
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    handler.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        handler.removeCallbacks(longPress)
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        longPressed -> Unit
                        moved -> Prefs.setOverlayPos(this, lp.x, lp.y)
                        else -> {
                            Prefs.setEnabled(this, !Prefs.enabled(this))
                            QuickTile.requestUpdate(this)
                            onToggled()
                        }
                    }
                }
            }
            true
        }
        wm.addView(tv, lp)
        overlay = tv
        updateOverlayLook()
    }

    private fun updateOverlayLook() {
        val tv = overlay ?: return
        val on = Prefs.enabled(this)
        val (text, color) = when {
            !on -> "NewPipe ○" to 0xB0424242.toInt()
            state == State.WAIT_AD -> "Anuncio… ⏳" to 0xE0EF6C00.toInt()
            state != State.IDLE -> "NewPipe ➜" to 0xE0C62828.toInt()
            else -> "NewPipe ●" to 0xE0C62828.toInt()
        }
        tv.text = text
        tv.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
    }

    private fun removeOverlay() {
        val tv = overlay ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(tv) } catch (_: Exception) { }
        overlay = null
    }
}

/** Versión de la app sin depender de BuildConfig. */
object BuildConfigCompat {
    fun version(c: android.content.Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
