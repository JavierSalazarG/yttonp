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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import kotlin.math.abs

/**
 * Flujo:
 *  1. Tocas un vídeo en YouTube → aparece la pantalla del reproductor con el botón "Compartir".
 *  2. El servicio pulsa "Compartir" → "Copiar enlace".
 *  3. Abre ClipboardActivity (invisible), que lee el enlace y lo abre en NewPipe.
 *  4. Cuando vuelves a YouTube, cierra el reproductor que se quedó abierto.
 */
class YtInterceptService : AccessibilityService() {

    companion object {
        const val TAG = "YT2NP"
        const val YT = "com.google.android.youtube"

        /** Apps cuyas ventanas no cambian "qué app está delante" (teclado, barra de estado…). */
        private val IGNORED = setOf(
            "com.android.systemui", "android",
            "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey",
        )

        private const val POLL_MS = 50L
        private const val SHARE_PANEL_TIMEOUT_MS = 2500L
        private const val CLIPBOARD_TIMEOUT_MS = 3500L
        private const val RETRIGGER_GUARD_MS = 1500L

        @Volatile var instance: YtInterceptService? = null
            private set
    }

    private enum class State { IDLE, WAIT_COPY_LINK, WAIT_CLIPBOARD }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var stateDeadline = 0L

    /** Se "arma" cuando no hay reproductor en pantalla; así no se dispara en bucle. */
    private var armed = true
    private var lastTriggerAt = 0L

    /** Hay que cerrar el reproductor de YouTube la próxima vez que vuelva a primer plano. */
    private var pendingCleanup = false
    private var cleanupBackSent = false

    /** Momento en que pulsamos "Copiar enlace" (para ignorar enlaces viejos del portapapeles). */
    var copyRequestedAt = 0L
        private set

    private var ytForeground = false
    private var overlay: TextView? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Servicio conectado")
    }

    override fun onDestroy() {
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != packageName && pkg !in IGNORED
        ) {
            val nowYt = pkg == YT
            if (nowYt != ytForeground) {
                ytForeground = nowYt
                refreshOverlay()
            }
        }

        if (pkg != YT || !Prefs.enabled(this)) return
        // Agrupa ráfagas de eventos en un solo escaneo.
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private val scanRunnable = Runnable { scan() }

    // ---------------------------------------------------------------- máquina de estados

    private fun scan() {
        if (!Prefs.enabled(this)) { state = State.IDLE; return }
        val roots = youtubeRoots()
        if (roots.isEmpty()) {
            // YouTube no está en pantalla; si esperábamos el panel, seguimos esperando hasta el timeout.
            if (state == State.WAIT_COPY_LINK) pollOrTimeout()
            return
        }
        val now = SystemClock.uptimeMillis()

        when (state) {
            State.IDLE -> {
                val share = findExact(roots, YtTexts.SHARE)

                if (pendingCleanup) {
                    cleanup(roots, share != null)
                    return
                }
                if (share == null) {
                    armed = true
                    return
                }
                if (armed && now - lastTriggerAt > RETRIGGER_GUARD_MS) {
                    armed = false
                    lastTriggerAt = now
                    Log.i(TAG, "Reproductor detectado → pulsando Compartir")
                    click(share)
                    state = State.WAIT_COPY_LINK
                    stateDeadline = now + SHARE_PANEL_TIMEOUT_MS
                    handler.postDelayed(scanRunnable, POLL_MS)
                }
            }

            State.WAIT_COPY_LINK -> {
                val copy = findExact(roots, YtTexts.COPY_LINK)
                if (copy != null) {
                    Log.i(TAG, "Pulsando Copiar enlace")
                    copyRequestedAt = System.currentTimeMillis()
                    click(copy)
                    state = State.WAIT_CLIPBOARD
                    stateDeadline = now + CLIPBOARD_TIMEOUT_MS
                    // Pequeña pausa para que YouTube termine de escribir en el portapapeles.
                    handler.postDelayed({ launchClipboardActivity() }, 90)
                    handler.postDelayed({ clipboardTimeout() }, CLIPBOARD_TIMEOUT_MS)
                } else {
                    pollOrTimeout()
                }
            }

            State.WAIT_CLIPBOARD -> Unit
        }
    }

    private fun pollOrTimeout() {
        if (SystemClock.uptimeMillis() > stateDeadline) {
            Log.w(TAG, "No encontré 'Copiar enlace' a tiempo")
            saveDiagnostic("No apareció 'Copiar enlace' tras pulsar Compartir")
            state = State.IDLE
        } else {
            handler.removeCallbacks(scanRunnable)
            handler.postDelayed(scanRunnable, POLL_MS)
        }
    }

    private fun launchClipboardActivity() {
        val i = Intent(this, ClipboardActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
        try {
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir ClipboardActivity", e)
            state = State.IDLE
        }
    }

    private fun clipboardTimeout() {
        if (state == State.WAIT_CLIPBOARD) {
            Log.w(TAG, "Timeout esperando el portapapeles")
            state = State.IDLE
        }
    }

    /** Lo llama ClipboardActivity al terminar. */
    fun onHandoffFinished(success: Boolean) {
        handler.post {
            state = State.IDLE
            if (success && Prefs.closeYoutubePlayer(this)) {
                pendingCleanup = true
                cleanupBackSent = false
            }
        }
    }

    /** Al volver a YouTube: minimiza el reproductor y cierra el minirreproductor. */
    private fun cleanup(roots: List<AccessibilityNodeInfo>, playerExpanded: Boolean) {
        if (playerExpanded && !cleanupBackSent) {
            cleanupBackSent = true
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed(scanRunnable, 250)
            return
        }
        val close = findPrefix(roots, YtTexts.CLOSE_MINIPLAYER)
        if (close != null) click(close)
        pendingCleanup = false
        cleanupBackSent = false
        // Sigue desarmado hasta que el reproductor desaparezca de verdad.
        armed = false
    }

    // ---------------------------------------------------------------- búsqueda de nodos

    private fun youtubeRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == YT) out += r
            }
        } catch (_: Exception) { }
        if (out.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName?.toString() == YT) out += it }
        }
        return out
    }

    private fun labelOf(n: AccessibilityNodeInfo): List<String> =
        listOfNotNull(n.text?.toString()?.trim(), n.contentDescription?.toString()?.trim())

    private fun findExact(roots: List<AccessibilityNodeInfo>, texts: List<String>): AccessibilityNodeInfo? =
        find(roots, texts) { label, t -> label.equals(t, ignoreCase = true) }

    private fun findPrefix(roots: List<AccessibilityNodeInfo>, texts: List<String>): AccessibilityNodeInfo? =
        find(roots, texts) { label, t -> label.startsWith(t, ignoreCase = true) }

    private inline fun find(
        roots: List<AccessibilityNodeInfo>,
        texts: List<String>,
        match: (String, String) -> Boolean,
    ): AccessibilityNodeInfo? {
        for (root in roots) for (t in texts) {
            val nodes = try { root.findAccessibilityNodeInfosByText(t) } catch (_: Exception) { null } ?: continue
            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                if (labelOf(n).any { match(it, t) }) return n
            }
        }
        return null
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

    // ---------------------------------------------------------------- diagnóstico

    /** Guarda lo que hay en pantalla para poder ajustar los textos si YouTube cambia. */
    fun saveDiagnostic(reason: String) {
        val sb = StringBuilder("Motivo: $reason\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || count > 500) return
            count++
            val lbl = labelOf(n).filter { it.isNotEmpty() }.joinToString(" | ")
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            if (lbl.isNotEmpty() || id.isNotEmpty()) {
                sb.append("  ".repeat(d.coerceAtMost(20)))
                    .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                    .append(if (id.isNotEmpty()) " #$id" else "")
                    .append(if (lbl.isNotEmpty()) " «$lbl»" else "")
                    .append(if (n.isClickable) " [click]" else "")
                    .append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        youtubeRoots().forEach { walk(it, 0) }
        Prefs.setLastDiag(this, sb.toString())
        Log.i(TAG, sb.toString())
    }

    // ---------------------------------------------------------------- botón flotante

    /** Llamado también desde MainActivity cuando cambias ajustes. */
    fun refreshOverlay() {
        handler.post {
            if (ytForeground && Prefs.overlay(this)) showOverlay() else removeOverlay()
            updateOverlayLook()
        }
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
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Prefs.setOverlayPos(this, lp.x, lp.y)
                    else {
                        Prefs.setEnabled(this, !Prefs.enabled(this))
                        state = State.IDLE
                        armed = false // no dispares sobre el vídeo que ya está abierto
                        updateOverlayLook()
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
        tv.text = if (on) "NewPipe ●" else "NewPipe ○"
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (on) 0xE0C62828.toInt() else 0xB0424242.toInt())
        }
    }

    private fun removeOverlay() {
        val tv = overlay ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(tv) } catch (_: Exception) { }
        overlay = null
    }
}
