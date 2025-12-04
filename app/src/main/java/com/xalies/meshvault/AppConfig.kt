package com.xalies.meshvault

// 1. Data Class with Icon Resource ID
data class SupportedSite(
    val name: String,
    val url: String,
    val iconRes: Int // <--- New Field
)

// 2. The List of Websites
val SUPPORTED_SITES = listOf(
    SupportedSite(
        "Printables",
        "https://www.printables.com",
        R.drawable.logo_printables
    ),
    SupportedSite(
        "MakerWorld",
        "https://makerworld.com",
        R.drawable.logo_makerworld
    ),
    SupportedSite(
        "Thingiverse",
        "https://www.thingiverse.com",
        R.drawable.logo_thingiverse
    ),
    SupportedSite(
        "Cults3D",
        "https://cults3d.com",
        R.drawable.logo_cults3d
    ),
    SupportedSite(
        "Thangs",
        "https://thangs.com",
        R.drawable.logo_thangs
    )
)

// 3. Allowed Domains (Unchanged)
val ALLOWED_DOMAINS = listOf(
    "printables", "prusa3d", "makerworld", "bambulab", "thangs", "cults3d", "thingiverse",
    "google", "facebook", "twitter", "github", "amazonaws", "fastly", "cloudflare", "cdn"
)

// 4. Data Class for downloads (Unchanged)
data class PendingDownload(
    val url: String,
    val pageUrl: String,
    val userAgent: String,
    val contentDisposition: String,
    val mimetype: String,
    val title: String,
    val imageUrl: String
)