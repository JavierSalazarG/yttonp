package com.javi.yt2newpipe

import android.content.Context
import android.content.SharedPreferences

/** Ajustes guardados de la app. */
object Prefs {
    private const val FILE = "cfg"

    /** Apps compatibles a las que podemos mandar el vídeo, por orden de preferencia. */
    val TARGETS = linkedMapOf(
        "org.schabi.newpipe" to "NewPipe",
        "org.polymorphicshade.tubular" to "Tubular (NewPipe + SponsorBlock)",
        "org.schabi.newpipe.debug" to "NewPipe (debug)",
        "org.polymorphicshade.newpipe" to "NewPipe SponsorBlock (antiguo)",
    )

    private var cache: SharedPreferences? = null
    private fun sp(c: Context): SharedPreferences =
        cache ?: c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { cache = it }

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun overlay(c: Context) = sp(c).getBoolean("overlay", true)
    fun setOverlay(c: Context, v: Boolean) = sp(c).edit().putBoolean("overlay", v).apply()

    fun closeYoutubePlayer(c: Context) = sp(c).getBoolean("closeYt", true)
    fun setCloseYoutubePlayer(c: Context, v: Boolean) = sp(c).edit().putBoolean("closeYt", v).apply()

    /** Mandar también los Shorts a NewPipe (por defecto no: se ven en YouTube). */
    fun shorts(c: Context) = sp(c).getBoolean("shorts", false)
    fun setShorts(c: Context, v: Boolean) = sp(c).edit().putBoolean("shorts", v).apply()

    /** Silenciar YouTube mientras se pasa el vídeo (y durante los anuncios). */
    fun mute(c: Context) = sp(c).getBoolean("mute", true)
    fun setMute(c: Context, v: Boolean) = sp(c).edit().putBoolean("mute", v).apply()

    /** Marca de seguridad: si la app se cierra con el audio silenciado, lo restauramos al volver. */
    fun mutedByUs(c: Context) = sp(c).getBoolean("mutedByUs", false)
    fun setMutedByUs(c: Context, v: Boolean) = sp(c).edit().putBoolean("mutedByUs", v).apply()

    /** Botón flotante "YouTube" mientras estás en NewPipe. */
    fun backButton(c: Context) = sp(c).getBoolean("backBtn", true)
    fun setBackButton(c: Context, v: Boolean) = sp(c).edit().putBoolean("backBtn", v).apply()

    fun backPos(c: Context): Pair<Int, Int> = sp(c).getInt("bx", 0) to sp(c).getInt("by", 600)
    fun setBackPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("bx", x).putInt("by", y).apply()

    fun target(c: Context): String = sp(c).getString("target", "org.schabi.newpipe")!!
    fun setTarget(c: Context, v: String) = sp(c).edit().putString("target", v).apply()

    fun overlayPos(c: Context): Pair<Int, Int> = sp(c).getInt("ox", 0) to sp(c).getInt("oy", 300)
    fun setOverlayPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("ox", x).putInt("oy", y).apply()

    fun lastDiag(c: Context): String = sp(c).getString("diag", "") ?: ""
    fun setLastDiag(c: Context, v: String) = sp(c).edit().putString("diag", v.take(20000)).apply()
}
