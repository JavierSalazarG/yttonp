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

    /** Etiquetas que indican que se está reproduciendo un anuncio. Coincidencia exacta. */
    val AD_EXACT = listOf(
        "Anuncio", "Anuncios", "Patrocinado", "Ad", "Ads", "Sponsored",
        "Visitar anunciante", "Visitar sitio", "Visit advertiser", "Visit site",
        "Saltar", "Omitir", "Skip",
    )

    /** Etiquetas de anuncio que llevan algo detrás ("Anuncio · 1 de 2", "Saltar anuncio en 5"). */
    val AD_PREFIX = listOf(
        "Anuncio ·", "Anuncio:", "Anuncio 1", "Anuncio 2", "Patrocinado ·", "Ad ·", "Ad 1", "Ad 2", "Sponsored ·",
        "Saltar anuncio", "Omitir anuncio", "Skip ad", "Skip Ad", "Más información sobre este anuncio",
        "Why this ad", "Por qué ves este anuncio", "My Ad Center", "Mi Centro de anuncios",
    )

    /** Botón para saltar el anuncio. Coincidencia por prefijo (o exacta para las palabras sueltas). */
    val SKIP_PREFIX = listOf("Saltar anuncio", "Omitir anuncio", "Skip ad", "Skip Ad")
    val SKIP_EXACT = listOf("Saltar", "Omitir", "Skip", "Saltar anuncios", "Skip ads")

    /** Botón para cerrar el minirreproductor. Coincidencia por prefijo. */
    val CLOSE_MINIPLAYER = listOf(
        "Cerrar el minirreproductor", "Cerrar minirreproductor", "Cerrar reproductor",
        "Close miniplayer", "Close the miniplayer", "Close player",
    )
}
