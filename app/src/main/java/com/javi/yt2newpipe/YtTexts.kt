package com.javi.yt2newpipe

/**
 * Textos de la interfaz de YouTube que buscamos en pantalla.
 * Si YouTube cambia algún texto o usas otro idioma, añádelo aquí.
 * Se comparan sin distinguir mayúsculas.
 */
object YtTexts {
    /** Botón "Compartir" bajo el vídeo. Exacto o empezando por él con poco más ("Compartir vídeo"). */
    val SHARE = listOf("Compartir", "Share", "Partager", "Teilen", "Condividi", "Compartilhar", "Partilhar")

    /** Opción "Copiar enlace" del panel de compartir. */
    val COPY_LINK = listOf(
        "Copiar enlace", "Copiar vínculo", "Copy link", "Copier le lien",
        "Link kopieren", "Copia link", "Copiar link",
    )

    /** Si alguno de estos textos está en pantalla es una página de playlist/mix, no un vídeo. */
    val PLAYLIST_PAGE = listOf("Reproducir todo", "Play all", "Tout lire", "Alle wiedergeben", "Riproduci tutti")

    /** Textos exclusivos de los Shorts (además se detectan por su estructura interna). */
    val SHORTS_MARKERS = listOf("Remezclar", "Remix")

    /** Indicadores de anuncio (exactos). */
    val AD_EXACT = listOf(
        "Anuncio", "Anuncios", "Patrocinado", "Ad", "Ads", "Sponsored",
        "Visitar anunciante", "Visitar sitio", "Visit advertiser", "Visit site",
    )

    /** Indicadores de anuncio que llevan algo detrás ("Anuncio · 1 de 2", "Saltar anuncio en 5"). */
    val AD_PREFIX = listOf(
        "Anuncio ·", "Anuncio:", "Anuncio 1", "Anuncio 2", "Patrocinado ·", "Ad ·", "Ad 1", "Ad 2", "Sponsored ·",
        "Saltar anuncio", "Omitir anuncio", "Skip ad", "Más información sobre este anuncio",
        "Why this ad", "Por qué ves este anuncio", "My Ad Center", "Mi Centro de anuncios",
    )

    /** Botón para saltar el anuncio. */
    val SKIP_PREFIX = listOf("Saltar anuncio", "Omitir anuncio", "Skip ad")
    val SKIP_EXACT = listOf("Saltar", "Omitir", "Skip", "Saltar anuncios", "Skip ads")

    /** Botón para cerrar el minirreproductor (por prefijo; "Cerrar" a secas solo en la parte baja). */
    val CLOSE_MINIPLAYER = listOf(
        "Cerrar el minirreproductor", "Cerrar minirreproductor", "Cerrar reproductor",
        "Close miniplayer", "Close the miniplayer", "Close player",
    )
    val CLOSE_EXACT = listOf("Cerrar", "Close")

    /** Pestaña "Inicio" de la barra inferior. */
    val HOME = listOf("Inicio", "Home", "Accueil", "Startseite", "Início")
}
