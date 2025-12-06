package com.xalies.meshvault

private data class SiteDownloadHandler(
    val domains: List<String>,
    val adjustDownloadUrl: (currentPage: String, originalUrl: String) -> String = { _, original -> original },
    val metadataScript: String? = null
)

private val DEFAULT_METADATA_SCRIPT = """
    (function() {
        function getLargestImage() {
            var maxArea = 0;
            var bestUrl = "";
            var images = document.getElementsByTagName('img');

            for (var i = 0; i < images.length; i++) {
                var img = images[i];
                if (img.naturalWidth < 150 || img.naturalHeight < 150) continue;

                var area = img.width * img.height;
                if (area > maxArea) {
                    maxArea = area;
                    bestUrl = img.src || img.dataset.src;
                }
            }
            return bestUrl;
        }

        function findMainImage() {
            var img = document.querySelector('meta[property="og:image"]')?.content ||
                      document.querySelector('meta[name="twitter:image"]')?.content;
            if (img) return img;

            var schemaImg = document.querySelector('[itemprop="image"]');
            if (schemaImg) {
                return schemaImg.content || schemaImg.src;
            }

            return getLargestImage();
        }

        var title = document.querySelector('meta[property="og:title"]')?.content || document.title;
        var finalImg = findMainImage();

        return title + "|||" + (finalImg || "");
    })();
""".trimIndent()

private val MAKERWORLD_METADATA_SCRIPT = """
    (function() {
        const state = window.__NUXT__?.state || (window.__NUXT__?.data && window.__NUXT__.data[0]) || {};
        const model = state.model || state.pageData?.model || {};
        const findFallbackImage = () => {
            const og = document.querySelector('meta[property="og:image"]')?.content;
            if (og) return og;

            const thumb = document.querySelector('img[class*="cover"], img[class*="preview"]');
            if (thumb) return thumb.src || thumb.dataset?.src || '';

            return '';
        };

        const title = model.display_name || model.name || document.querySelector('meta[property="og:title"]')?.content || document.title || '';
        const image = model.cover_url || model.cover?.url || findFallbackImage();
        return title + "|||" + image;
    })();
""".trimIndent()

private val SITE_HANDLERS = listOf(
    SiteDownloadHandler(
        domains = listOf("thingiverse.com"),
        adjustDownloadUrl = { currentPage, originalUrl ->
            val isThingiversePage = currentPage.contains("thingiverse.com/thing:")
            val alreadyZip = currentPage.endsWith("/zip") || originalUrl.endsWith("/zip")

            if (isThingiversePage && !alreadyZip) {
                val rawBase = if (originalUrl.contains("thingiverse.com/thing:")) originalUrl else currentPage
                val cleanedBase = rawBase
                    .removeSuffix("/")
                    .replace("/files", "")

                cleanedBase + "/zip"
            } else {
                originalUrl
            }
        }
    ),
    SiteDownloadHandler(
        domains = listOf("makerworld.com", "bambulab.com", "bblmw.com"),
        metadataScript = MAKERWORLD_METADATA_SCRIPT
    )
)

private fun findHandler(currentPage: String, originalUrl: String): SiteDownloadHandler? {
    return SITE_HANDLERS.firstOrNull { handler ->
        handler.domains.any { domain ->
            currentPage.contains(domain, ignoreCase = true) || originalUrl.contains(domain, ignoreCase = true)
        }
    }
}

fun adjustDownloadUrlForSite(currentPage: String, originalUrl: String): String {
    val handler = findHandler(currentPage, originalUrl)
    return handler?.adjustDownloadUrl?.invoke(currentPage, originalUrl) ?: originalUrl
}

fun metadataScriptForPage(currentPage: String): String {
    val handler = findHandler(currentPage, currentPage)
    return handler?.metadataScript ?: DEFAULT_METADATA_SCRIPT
}
