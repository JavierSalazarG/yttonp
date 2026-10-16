package com.javi.yt2newpipe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pantalla de ajustes minimalista, construida en código (sin librerías). */
class MainActivity : Activity() {

    // ---- paleta (claro / oscuro)
    private var dark = false
    private val accent = 0xFFE53935.toInt()
    private val ok = 0xFF2E7D32.toInt()
    private val bg get() = if (dark) 0xFF0F0F10.toInt() else 0xFFF6F6F7.toInt()
    private val cardColor get() = if (dark) 0xFF1B1B1D.toInt() else Color.WHITE
    private val ink get() = if (dark) 0xFFF2F2F3.toInt() else 0xFF161618.toInt()
    private val sub get() = if (dark) 0xFF9A9AA0.toInt() else 0xFF6E6E74.toInt()
    private val line get() = if (dark) 0x14FFFFFF else 0x12000000

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- partes que cambian en onResume
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var targetsCard: LinearLayout
    private lateinit var diagText: TextView
    private lateinit var enabledSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        setupSystemBars()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }

        // ---------------------------------------------------------- cabecera
        col.addView(text("YT → NewPipe", 28f, ink, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        col.addView(text("Navega en YouTube, mira en NewPipe.", 15f, sub).apply { setPadding(0, dp(4), 0, dp(20)) })

        // ---------------------------------------------------------- estado
        val status = card()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(14) }
        }
        val statusTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTitle = text("", 17f, ink, bold = true)
        statusSub = text("", 13.5f, sub).apply { setPadding(0, dp(2), 0, 0) }
        statusTexts.addView(statusTitle); statusTexts.addView(statusSub)
        statusRow.addView(statusDot); statusRow.addView(statusTexts)
        status.addView(statusRow)

        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(18))
        }
        setupBox.addView(pill("Activar en Accesibilidad", filled = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setupBox.addView(text(
            "¿Sale «Ajuste restringido»? Pulsa abajo → menú ⋮ → «Permitir ajustes restringidos» y vuelve a intentarlo.",
            12.5f, sub
        ).apply { setPadding(dp(2), dp(12), dp(2), dp(8)) })
        setupBox.addView(pill("Información de la app", filled = false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        status.addView(setupBox)
        col.addView(status)

        // ---------------------------------------------------------- ajustes
        col.addView(section("Ajustes"))
        val settings = card()
        enabledSwitch = row(settings, "Abrir vídeos en NewPipe", "Pausa rápida también desde el botón flotante",
            Prefs.enabled(this)) {
            Prefs.setEnabled(this, it); YtInterceptService.instance?.onToggled(); QuickTile.requestUpdate(this)
            refreshStatus()
        }
        row(settings, "Incluir Shorts", "Si está apagado, los Shorts se ven en YouTube", Prefs.shorts(this)) {
            Prefs.setShorts(this, it)
        }
        row(settings, "Silenciar YouTube", "Mientras pasa el vídeo y durante los anuncios", Prefs.mute(this)) {
            Prefs.setMute(this, it)
        }
        row(settings, "Cerrar el reproductor de YouTube", "Al volver, te deja en la lista donde estabas",
            Prefs.closeYoutubePlayer(this)) { Prefs.setCloseYoutubePlayer(this, it) }
        row(settings, "Botón en YouTube", "Toca para pausar · arrastra para mover", Prefs.overlay(this)) {
            Prefs.setOverlay(this, it); YtInterceptService.instance?.refreshOverlay()
        }
        row(settings, "Botón «YouTube» en NewPipe", "Vuelve al inicio de YouTube de un toque",
            Prefs.backButton(this), last = true) {
            Prefs.setBackButton(this, it); YtInterceptService.instance?.refreshOverlay()
        }
        col.addView(settings)

        // ---------------------------------------------------------- destino
        col.addView(section("Abrir en"))
        targetsCard = card()
        col.addView(targetsCard)

        // ---------------------------------------------------------- consejos
        col.addView(section("Consejos"))
        val tips = card().apply { setPadding(dp(18), dp(14), dp(18), dp(16)) }
        listOf(
            "Reproductor emergente" to "En NewPipe: Ajustes → Comportamiento → Acción preferida al abrir → " +
                "Reproductor emergente. Verás el vídeo flotando sin salir de YouTube.",
            "Ajustes rápidos" to "Baja la cortinilla, pulsa el lápiz y arrastra «YT → NewPipe».",
        ).forEachIndexed { i, (t, d) ->
            tips.addView(text(t, 15f, ink, bold = true).apply { setPadding(0, if (i == 0) 0 else dp(14), 0, dp(2)) })
            tips.addView(text(d, 13.5f, sub))
        }
        col.addView(tips)

        // ---------------------------------------------------------- diagnóstico
        col.addView(section("Diagnóstico"))
        val diag = card()
        diagText = text("", 11f, sub).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(18), 0, dp(18), dp(14))
            visibility = View.GONE
            maxLines = 40
        }
        actionRow(diag, "Copiar último diagnóstico",
            "Mantén pulsado el botón flotante en YouTube para guardar uno") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("diag", Prefs.lastDiag(this)))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }
        actionRow(diag, "Ver", null, last = true) {
            diagText.visibility = if (diagText.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        diag.addView(diagText)
        col.addView(diag)

        col.addView(text("v${BuildConfigCompat.version(this)}", 12f, sub).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            addView(col)
        }
        // Respeta la barra de estado y la de navegación (Android 15 dibuja la app de borde a borde).
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        enabledSwitch.isChecked = Prefs.enabled(this)
        renderTargets()
        diagText.text = Prefs.lastDiag(this).ifEmpty { "(sin datos todavía)" }
    }

    // ================================================================ partes dinámicas

    private fun refreshStatus() {
        val service = isServiceEnabled()
        val on = Prefs.enabled(this)
        val (color, title, subtitle) = when {
            !service -> Triple(accent, "Servicio desactivado", "Actívalo una vez en Accesibilidad")
            !on -> Triple(sub, "En pausa", "Los vídeos se abren en YouTube")
            else -> Triple(ok, "Activo", "Abre YouTube y toca un vídeo")
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusTitle.text = title
        statusSub.text = subtitle
        setupBox.visibility = if (service) View.GONE else View.VISIBLE
    }

    private fun renderTargets() {
        targetsCard.removeAllViews()
        val current = Prefs.target(this)
        val radios = ArrayList<RadioButton>()
        val entries = Prefs.TARGETS.entries.toList()
        entries.forEachIndexed { i, (pkg, name) ->
            val installed = isInstalled(pkg)
            if (!installed && pkg != current && pkg.endsWith(".debug")) return@forEachIndexed
            val rb = RadioButton(this).apply {
                text = name
                textSize = 15f
                setTextColor(if (installed) ink else sub)
                isEnabled = installed
                isChecked = pkg == current
                buttonTintList = ColorStateList.valueOf(accent)
                setPadding(dp(10), 0, 0, 0)
            }
            radios += rb
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(18), dp(10))
                addView(rb)
                if (!installed) addView(text("No instalada", 12f, sub).apply { setPadding(dp(46), 0, 0, dp(2)) })
            }
            rb.setOnClickListener {
                Prefs.setTarget(this, pkg)
                radios.forEach { o -> o.isChecked = o === rb }
            }
            targetsCard.addView(r)
            if (i < entries.size - 1) targetsCard.addView(divider())
        }
        // quita un posible separador final
        targetsCard.getChildAt(targetsCard.childCount - 1)?.let { if (it.tag == "div") targetsCard.removeView(it) }
    }

    // ================================================================ componentes

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun section(t: String) = text(t.uppercase(), 12f, sub, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(6), dp(26), 0, dp(8))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(cardColor) }
        clipToOutline = true
    }

    private fun divider() = View(this).apply {
        tag = "div"
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(18)
        }
    }

    private fun ripple(content: android.graphics.drawable.Drawable?, radius: Float = 0f): RippleDrawable {
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(if (dark) 0x22FFFFFF else 0x14000000), content, mask)
    }

    private fun pill(t: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) Color.WHITE else ink)
        val r = dp(24).toFloat()
        val shape = GradientDrawable().apply {
            cornerRadius = r
            if (filled) setColor(accent) else { setColor(Color.TRANSPARENT); setStroke(dp(1), if (dark) 0x33FFFFFF else 0x22000000) }
        }
        background = ripple(shape, r)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Fila con título, subtítulo e interruptor; toda la fila es pulsable. */
    private fun row(
        parent: LinearLayout, title: String, subtitle: String?, value: Boolean,
        last: Boolean = false, onChange: (Boolean) -> Unit,
    ): Switch {
        val sw = Switch(this).apply {
            isChecked = value
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, if (dark) 0xFFBDBDC2.toInt() else Color.WHITE)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, if (dark) 0xFF3A3A3E.toInt() else 0xFFD5D5DA.toInt())
            )
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            background = ripple(null)
            isClickable = true
            setOnClickListener { sw.toggle() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
            addView(text(title, 15.5f, ink))
            if (subtitle != null) addView(text(subtitle, 13f, sub).apply { setPadding(0, dp(2), 0, 0) })
        }
        r.addView(texts); r.addView(sw)
        parent.addView(r)
        if (!last) parent.addView(divider())
        return sw
    }

    private fun actionRow(parent: LinearLayout, title: String, subtitle: String?, last: Boolean = false, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = ripple(null)
            isClickable = true
            setOnClickListener { onClick() }
            addView(text(title, 15.5f, accent))
            if (subtitle != null) addView(text(subtitle, 13f, sub).apply { setPadding(0, dp(2), 0, 0) })
        }
        parent.addView(r)
        if (!last) parent.addView(divider())
    }

    private fun setupSystemBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = if (dark) 0 else
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
    }

    // ================================================================ utilidades

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, YtInterceptService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
