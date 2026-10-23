package com.javi.yt2newpipe

import android.app.Application
import android.os.Build
import android.util.Log

/** Guarda cualquier cierre inesperado para poder verlo en "Diagnóstico". */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                Prefs.setLastDiagNow(
                    this,
                    "CIERRE INESPERADO · v${BuildConfigCompat.version(this)} · Android ${Build.VERSION.RELEASE} " +
                        "(SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                        Log.getStackTraceString(e)
                )
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, e)
        }
    }
}
