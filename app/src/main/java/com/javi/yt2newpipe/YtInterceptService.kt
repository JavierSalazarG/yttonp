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
import android.widget.Toast
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

        /** Margen tras abrir el vídeo por si aparece un anuncio. */
        private const val SETTLE_MS = 350L
        /** Margen tras desaparecer el anuncio para que YouTube cargue el vídeo real. */
        private const val AFTER_AD_MS = 500L
        private const val AD_POLL_MS = 100L
        private const val AD_TIMEOUT_MS = 90_000L

        @Volatile var instance: YtInterceptService? = null
            private set
    }

    private enum class State { IDLE, WAIT_AD, WAIT_COPY_LINK, WAIT_CLIPBOARD }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var stateDeadline = 0L

    /** No pulsamos Compartir hasta este momento (margen tras abrir el vídeo o tras un anuncio). */
    private var adSettleUntil = 0L

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
            if (state == State.WAIT_AD) { state = State.IDLE; updateOverlayLook() }
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
                    shareTop = -1
                    rememberShare(share)
                    lastTriggerAt = now
                    Log.i(TAG, "Reproductor detectado → comprobando anuncios")
                    // Un instante de margen: el anuncio a veces aparece justo después del reproductor.
                    state = State.WAIT_AD
                    adSettleUntil = now + SETTLE_MS
                    stateDeadline = now + AD_TIMEOUT_MS
                    updateOverlayLook()
                    handler.postDelayed(scanRunnable, AD_POLL_MS)
                }
            }

            State.WAIT_AD -> {
                val share = findExact(roots, YtTexts.SHARE)
                rememberShare(share)
                when {
                    share == null && !adVisible(roots) -> {
                        // Has salido del reproductor: cancelamos.
                        state = State.IDLE; armed = true; updateOverlayLook()
                    }
                    now > stateDeadline -> {
                        Log.w(TAG, "El anuncio no terminó a tiempo")
                        state = State.IDLE; updateOverlayLook()
                    }
                    adVisible(roots) -> {
                        adSettleUntil = now + AFTER_AD_MS
                        findSkip(roots)?.let { click(it) }
                        handler.postDelayed(scanRunnable, AD_POLL_MS)
                    }
                    now < adSettleUntil || share == null -> handler.postDelayed(scanRunnable, AD_POLL_MS)
                    else -> {
                        Log.i(TAG, "Sin anuncio → pulsando Compartir")
                        click(share)
                        state = State.WAIT_COPY_LINK
                        stateDeadline = now + SHARE_PANEL_TIMEOUT_MS
                        updateOverlayLook()
                        handler.postDelayed(scanRunnable, POLL_MS)
                    }
                }
            }

            State.WAIT_COPY_LINK -> {
                if (adVisible(roots)) {
                    // Empezó un anuncio con el panel abierto: lo cerramos y esperamos.
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    state = State.WAIT_AD
                    adSettleUntil = now + AFTER_AD_MS
                    stateDeadline = now + AD_TIMEOUT_MS
                    updateOverlayLook()
                    handler.postDelayed(scanRunnable, AD_POLL_MS)
                    return
                }
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

    /**
     * Coincide si la etiqueta es el texto exacto o empieza por él con poco más
     * (p. ej. "Compartir vídeo"), pero no con títulos largos que lo contengan.
     */
    private fun findExact(roots: List<AccessibilityNodeInfo>, texts: List<String>): AccessibilityNodeInfo? =
        find(roots) { label ->
            texts.any { t -> label.equals(t, true) || (label.startsWith(t, true) && label.length <= t.length + 20) }
        }

    /** ¿Hay un anuncio en pantalla? (coincidencias estrictas para no confundir títulos como "Adele…"). */
    private fun adVisible(roots: List<AccessibilityNodeInfo>): Boolean =
        find(roots, maxTop = playerBottom()) { label ->
            YtTexts.AD_EXACT.any { label.equals(it, true) } ||
                YtTexts.AD_PREFIX.any { label.startsWith(it, true) }
        } != null

    /**
     * Solo miramos anuncios por encima del botón Compartir (reproductor + título),
     * para no confundir las tarjetas "Patrocinado" de la lista de relacionados.
     */
    private var shareTop = -1
    private fun playerBottom(): Int =
        if (shareTop > 0) shareTop else (resources.displayMetrics.heightPixels * 0.45f).toInt()

    private fun rememberShare(share: AccessibilityNodeInfo?) {
        if (share == null) return
        val r = Rect().also { share.getBoundsInScreen(it) }
        if (!r.isEmpty) shareTop = r.top
    }

    private fun findSkip(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        find(roots) { label ->
            YtTexts.SKIP_EXACT.any { label.equals(it, true) } ||
                // "Saltar anuncio" sí; "Saltar anuncio en 5" (cuenta atrás) no.
                YtTexts.SKIP_PREFIX.any { label.startsWith(it, true) && !label.any(Char::isDigit) }
        }

    private fun findPrefix(roots: List<AccessibilityNodeInfo>, texts: List<String>): AccessibilityNodeInfo? =
        find(roots) { label -> texts.any { label.startsWith(it, ignoreCase = true) } }

    /** Recorre todo el árbol (findAccessibilityNodeInfosByText no ve algunas vistas de YouTube). */
    private fun find(
        roots: List<AccessibilityNodeInfo>,
        maxTop: Int = Int.MAX_VALUE,
        match: (String) -> Boolean,
    ): AccessibilityNodeInfo? {
        val r = Rect()
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { stack.addLast(it) }
        while (stack.isNotEmpty() && visited < 4000) {
            val n = stack.removeLast()
            visited++
            if (n.isVisibleToUser && labelOf(n).any { it.isNotEmpty() && match(it) }) {
                if (maxTop == Int.MAX_VALUE) return n
                n.getBoundsInScreen(r)
                if (r.top < maxTop) return n
            }
            for (i in n.childCount - 1 downTo 0) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                stack.addLast(c)
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
        val sb = StringBuilder("Motivo: $reason\nEstado: $state, armado: $armed, YT delante: $ytForeground\n")
        try {
            for (w in windows) sb.append("Ventana tipo=${w.type} capa=${w.layer} activa=${w.isActive} ")
                .append("pkg=${w.root?.packageName}\n")
        } catch (_: Exception) { }
        val share = findExact(youtubeRoots(), YtTexts.SHARE)
        sb.append("Botón Compartir encontrado: ${share != null}\n\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || count > 700) return
            count++
            val lbl = labelOf(n).filter { it.isNotEmpty() }.joinToString(" | ")
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            if (lbl.isNotEmpty() || id.isNotEmpty()) {
                sb.append("  ".repeat(d.coerceAtMost(20)))
                    .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                    .append(if (id.isNotEmpty()) " #$id" else "")
                    .append(if (lbl.isNotEmpty()) " «$lbl»" else "")
                    .append(if (n.isClickable) " [click]" else "")
                    .append(if (!n.isVisibleToUser) " [oculto]" else "")
                    .append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        val roots = youtubeRoots().ifEmpty {
            try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }
        }
        roots.forEach { walk(it, 0) }
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
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            saveDiagnostic("Captura manual (pulsación larga en el botón)")
            Toast.makeText(this, "Diagnóstico guardado. Ábrelo en la app YT → NewPipe.", Toast.LENGTH_LONG).show()
        }
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    longPressed = false
                    handler.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        handler.removeCallbacks(longPress)
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (longPressed) Unit
                    else if (moved) Prefs.setOverlayPos(this, lp.x, lp.y)
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
        tv.text = when {
            !on -> "NewPipe ○"
            state == State.WAIT_AD -> "NewPipe · anuncio…"
            else -> "NewPipe ●"
        }
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
