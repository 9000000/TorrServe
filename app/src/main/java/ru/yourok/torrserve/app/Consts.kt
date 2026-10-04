package ru.yourok.torrserve.app

object Consts {
    private const val REL_HOST = "https://releases.yourok.ru/torr"
    const val AD_LINK = "$REL_HOST/ad"

    // App APK updates (Primary GitHub raw + CDN jsDelivr mirror + release.json fallback)
    const val UPDATE_APK_PATH = "https://raw.githubusercontent.com/9000000/TorrServe/master/apk_release.json"
    const val UPDATE_APK_PATH_CDN = "https://cdn.jsdelivr.net/gh/9000000/TorrServe@master/apk_release.json"
    const val UPDATE_APK_PATH_FALLBACK = "https://raw.githubusercontent.com/9000000/TorrServe/master/release.json"
    const val UPDATE_APK_PATH_FALLBACK_CDN = "https://cdn.jsdelivr.net/gh/9000000/TorrServe@master/release.json"

    // Server Core updates (Primary GitHub raw + CDN jsDelivr mirror)
    const val UPDATE_SERVER_PATH = "https://raw.githubusercontent.com/9000000/TorrServer-LT/tet/release.json"
    const val UPDATE_SERVER_PATH_CDN = "https://cdn.jsdelivr.net/gh/9000000/TorrServer-LT@tet/release.json"

    val PLAYERS_BLACKLIST = hashSetOf(
        "com.android.gallery3d",
        "com.android.tv.frameworkpackagestubs",
        "com.estrongs.android.pop",
        "com.estrongs.android.pop.pro",
        "com.ghisler.android.totalcommander",
        "com.google.android.apps.photos",
        "com.google.android.tv.frameworkpackagestubs",
        "com.instantbits.cast.webvideo",
        "com.lonelycatgames.xplore",
        "com.mitv.videoplayer",
        "com.mixplorer.silver",
        "com.opera.browser",
        "com.rs.explorer.filemanager",
        "com.tcl.browser",
        "com.tcl.ui_mediacenter",
        "nextapp.fx",
        "org.droidtv.contentexplorer",
        "pl.solidexplorer2",
        // more to add...
    )
}