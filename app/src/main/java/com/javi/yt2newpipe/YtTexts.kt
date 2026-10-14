package com.javi.yt2newpipe

/**
 * Textos de la interfaz de YouTube que buscamos en pantalla.
 * Si YouTube cambia algún texto o usas otro idioma, añádelo aquí.
 * Se comparan sin distinguir mayúsculas, como texto exacto
 * (o "empieza por" en las listas marcadas como prefijo).
 */
object YtTexts {
    /** Botón "Compartir" bajo el vídeo (y en Shorts). Coincidencia exacta. */
    val SHARE = listOf("Compartir", "Share", "Partager", "Teilen", "Condividi", "Compartilhar", "Partilhar")

    /** Opción "Copiar enlace" del panel de compartir de YouTube. Coincidencia exacta. */
    val COPY_LINK = listOf(
        "Copiar enlace", "Copiar vínculo", "Copy link", "Copier le lien",
        "Link kopieren", "Copia link", "Copiar link",
    )

    /** Botón para cerrar el minirreproductor. Coincidencia por prefijo. */
    val CLOSE_MINIPLAYER = listOf(
        "Cerrar el minirreproductor", "Cerrar minirreproductor", "Cerrar reproductor",
        "Close miniplayer", "Close the miniplayer", "Close player",
    )
}
