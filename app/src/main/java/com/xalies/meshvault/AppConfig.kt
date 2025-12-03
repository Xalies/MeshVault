package com.xalies.meshvault

// 1. Data Class for the Dashboard Grid
data class SupportedSite(val name: String, val url: String)

// 2. The List of Websites
val SUPPORTED_SITES = listOf(
    SupportedSite("Printables", "https://www.printables.com"),
    SupportedSite("MakerWorld", "https://makerworld.com"),
    SupportedSite("Thingiverse", "https://www.thingiverse.com"),
    SupportedSite("Cults3D", "https://cults3d.com"),
    SupportedSite("Thangs", "https://thangs.com")
)

// 3. Allowed Domains for the Browser
val ALLOWED_DOMAINS = listOf(
    "printables", "prusa3d", "makerworld", "bambulab", "thangs", "cults3d", "thingiverse",
    "google", "facebook", "twitter", "github", "amazonaws", "fastly", "cloudflare", "cdn"
)

// 4. Data Class for handling downloads
data class PendingDownload(
    val url: String,       // The Direct File Link (e.g. .zip)
    val pageUrl: String,   // NEW: The Website Page (e.g. /model/123)
    val userAgent: String,
    val contentDisposition: String,
    val mimetype: String,
    val title: String,
    val imageUrl: String
)