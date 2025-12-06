package com.xalies.meshvault

private data class SiteDownloadHandler(
    val domains: List<String>,
    val adjustDownloadUrl: (currentPage: String, originalUrl: String) -> String = { _, original -> original },
    val metadataScript: String? = null,
    val pageLoadScript: String? = null,
    val filenameOverride: (download: PendingDownload, fallback: String) -> String = { _, fallback -> fallback }
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

private val CULTS3D_METADATA_SCRIPT = """
    (function() {
        const STORAGE_KEY = 'meshvault_cults3d_meta';

        const isModelPage = () => {
            const url = window.location.href;
            return url.includes('/3d-model/') && !url.includes('/downloads/') && !url.includes('/orders/');
        };

        const readMetadata = () => {
            const title = document.querySelector('meta[property="og:title"]')?.content
                || document.querySelector('meta[name="twitter:title"]')?.content
                || document.querySelector('h1')?.textContent
                || document.title
                || '';

            const pageUrl = (() => {
                try {
                    const url = new URL(window.location.href);
                    url.hash = '';
                    return url.toString();
                } catch (e) {
                    return window.location.href.split('#')[0];
                }
            })();

            const findImage = () => {
                const ogImage = document.querySelector('meta[property="og:image"]')?.content;
                if (ogImage) return ogImage;

                const twitterImage = document.querySelector('meta[name="twitter:image"]')?.content;
                if (twitterImage) return twitterImage;

                const cover = document.querySelector('img[class*="cover"], img[itemprop="image"], img[class*="thumbnail"]');
                if (cover) return cover.src || cover.dataset?.src || '';

                const largest = (() => {
                    let maxArea = 0;
                    let best = '';
                    document.querySelectorAll('img').forEach((img) => {
                        const area = img.naturalWidth * img.naturalHeight;
                        if (area > maxArea) {
                            maxArea = area;
                            best = img.src || img.dataset?.src || '';
                        }
                    });
                    return best;
                })();

                return largest;
            };

            const image = findImage();
            return (title || '') + '|||' + (image || '') + '|||' + (pageUrl || '');
        };

        try {
            let result = '';

            if (isModelPage()) {
                const current = readMetadata();
                if (current && current.includes('|||')) {
                    sessionStorage.setItem(STORAGE_KEY, current);
                    result = current;
                }
            }

            const cached = sessionStorage.getItem(STORAGE_KEY) || '';
            if (cached && cached.includes('|||')) {
                result = cached;
            }

            return result;
        } catch (e) {
            return '';
        }
    })();
""".trimIndent()

private val CULTS3D_PAGE_LOAD_SCRIPT = """
    (function() {
        const STORAGE_KEY = 'meshvault_cults3d_meta';

        const shouldCapture = () => {
            const url = window.location.href;
            return url.includes('/3d-model/') && !url.includes('/downloads/') && !url.includes('/orders/');
        };

        if (!shouldCapture()) return;

        const collectMetadata = () => {
            const title = document.querySelector('meta[property="og:title"]')?.content
                || document.querySelector('meta[name="twitter:title"]')?.content
                || document.querySelector('h1')?.textContent
                || document.title
                || '';

            const image = document.querySelector('meta[property="og:image"]')?.content
                || document.querySelector('meta[name="twitter:image"]')?.content
                || document.querySelector('img[class*="cover"], img[itemprop="image"], img[class*="thumbnail"]')?.src
                || '';

            const pageUrl = (() => {
                try {
                    const url = new URL(window.location.href);
                    url.hash = '';
                    return url.toString();
                } catch (e) {
                    return window.location.href.split('#')[0];
                }
            })();

            return (title || '') + '|||' + (image || '') + '|||' + (pageUrl || '');
        };

        try {
            const data = collectMetadata();
            if (data && data.includes('|||')) {
                sessionStorage.setItem(STORAGE_KEY, data);
            }
        } catch (e) {
            // Safe no-op
        }
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
        domains = listOf("thangs.com"),
        filenameOverride = { download, fallback ->
            if (!fallback.endsWith(".bin", ignoreCase = true)) return@SiteDownloadHandler fallback

            val safeTitle = download.title
                .lowercase()
                .replace("[^a-z0-9]+".toRegex(), "-")
                .trim('-')
                .ifBlank { "thangs-download" }

            "$safeTitle.zip"
        }
    ),
    SiteDownloadHandler(
        domains = listOf("makerworld.com", "bambulab.com", "bblmw.com"),
        metadataScript = MAKERWORLD_METADATA_SCRIPT
    ),
    SiteDownloadHandler(
        domains = listOf("cults3d.com"),
        metadataScript = CULTS3D_METADATA_SCRIPT,
        pageLoadScript = CULTS3D_PAGE_LOAD_SCRIPT,
        filenameOverride = { download, fallback ->
            val uri = android.net.Uri.parse(download.url)
            val creationSlug = uri.getQueryParameter("creation")?.takeIf { it.isNotBlank() }
                ?: download.pageUrl.substringAfterLast('/')
            val hasUsefulFallback = !fallback.endsWith(".bin", ignoreCase = true)
            val mimeHint = download.mimetype.orEmpty().lowercase()

            if (hasUsefulFallback) return@SiteDownloadHandler fallback

            val guessedExtension = when {
                mimeHint.contains("zip") -> "zip"
                mimeHint.contains("stl") -> "stl"
                else -> "stl"
            }

            if (creationSlug.isNullOrBlank()) fallback else "$creationSlug.$guessedExtension"
        }
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

fun pageLoadScriptForPage(currentPage: String): String? {
    return findHandler(currentPage, currentPage)?.pageLoadScript
}

fun adjustFilenameForSite(download: PendingDownload, fallback: String): String {
    val handler = findHandler(download.pageUrl, download.url)
    return handler?.filenameOverride?.invoke(download, fallback) ?: fallback
}
