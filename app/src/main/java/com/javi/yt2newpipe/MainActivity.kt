package com.javi.yt2newpipe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pantalla de ajustes, construida en código para no depender de librerías. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var targets: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 3)
        }.also(col::addView)

        fun note(t: String) = TextView(this).apply { text = t; textSize = 14f }.also(col::addView)

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }.also(col::addView)

        fun switch(t: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
            text = t; isChecked = value; textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _: CompoundButton, v: Boolean ->
                onChange(v); YtInterceptService.instance?.refreshOverlay()
            }
        }.also(col::addView)

        title("1. Activar el servicio")
        status = note("")
        button("Abrir ajustes de Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        note(
            "Si Android dice \"Ajuste restringido\" (pasa con apps instaladas desde un APK), " +
                "pulsa el botón de abajo → menú ⋮ arriba a la derecha → \"Permitir ajustes restringidos\" " +
                "y vuelve a intentarlo."
        )
        button("Abrir información de la app") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        title("2. Comportamiento")
        switch("Mandar vídeos a NewPipe", Prefs.enabled(this)) {
            Prefs.setEnabled(this, it); YtInterceptService.instance?.onToggled(); QuickTile.requestUpdate(this)
        }
        switch("Mandar también los Shorts", Prefs.shorts(this)) { Prefs.setShorts(this, it) }
        switch("Silenciar YouTube mientras pasa el vídeo y durante los anuncios", Prefs.mute(this)) {
            Prefs.setMute(this, it)
        }
        switch("Botón flotante en YouTube (tócalo para activar/desactivar; arrástralo para moverlo)",
            Prefs.overlay(this)) { Prefs.setOverlay(this, it) }
        switch("Cerrar el reproductor de YouTube al volver", Prefs.closeYoutubePlayer(this)) {
            Prefs.setCloseYoutubePlayer(this, it)
        }

        title("3. Abrir en")
        targets = RadioGroup(this).also(col::addView)

        title("Consejos")
        note(
            "• Lo más cómodo: en NewPipe → Ajustes → Comportamiento → \"Acción preferida al abrir\" " +
                "→ \"Reproductor emergente\". El vídeo se abre en una ventanita flotante y sigues " +
                "navegando por YouTube sin salir.\n\n" +
                "• Interruptor rápido: baja la cortinilla → lápiz (editar) → arrastra \"YT → NewPipe\" " +
                "a tus ajustes rápidos.\n\n" +
                "• Botón flotante en YouTube: tócalo para pausar/activar, arrástralo para moverlo y " +
                "mantenlo pulsado para guardar un diagnóstico.\n\n" +
                "• Los Shorts y las playlists se quedan en YouTube."
        )

        title("Diagnóstico")
        note(
            "Si un día deja de funcionar (YouTube cambia su interfaz a menudo), aquí aparece lo que " +
                "había en pantalla. Cópialo y pásaselo a quien te ayude a ajustar los textos en YtTexts.kt."
        )
        button("Copiar diagnóstico") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diag", Prefs.lastDiag(this)))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }
        diag = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE }.also(col::addView)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "✅ Servicio activo. Abre YouTube y toca un vídeo."
        else "❌ Servicio desactivado. Actívalo en Accesibilidad → Apps instaladas → YT → NewPipe."

        targets.removeAllViews()
        val current = Prefs.target(this)
        for ((pkg, name) in Prefs.TARGETS) {
            val installed = isInstalled(pkg)
            targets.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = if (installed) name else "$name (no instalada)"
                isEnabled = installed
                isChecked = pkg == current
                setOnClickListener { Prefs.setTarget(this@MainActivity, pkg) }
            })
        }

        diag.text = Prefs.lastDiag(this).ifEmpty { "(sin datos todavía)" }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, YtInterceptService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
