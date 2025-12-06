package com.xalies.meshvault

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    // A lightweight list of common ad domains found on 3D printing sites
    private val AD_HOSTS = setOf(
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "www.googletagservices.com",
        "adservice.google.com",
        "securepubads.g.doubleclick.net",
        "static.criteo.net",
        "bidder.criteo.com",
        "c.amazon-adsystem.com",
        "aax.amazon-adsystem.com",
        "cdn.adnxs.com",
        "secure.adnxs.com",
        "fastlane.rubiconproject.com",
        "creative.invibes.com",
        "cdn.taboola.com"
    )

    fun shouldBlock(url: String?): Boolean {
        if (url == null) return false
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return false

        // Block if the host contains any of our known ad domains
        return AD_HOSTS.any { host.contains(it, ignoreCase = true) }
    }

    fun createEmptyResource(): WebResourceResponse {
        // Return an empty response to effectively "block" the request
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
    }

    // CSS to hide common annoying elements (Cookie banners, sticky footers)
    // This injects a style tag into the head of the page
    const val HIDE_ANNOYANCES_SCRIPT = """
        (function() {
            var css = `
                /* Common Cookie & Consent Banners */
                #onetrust-banner-sdk, .onetrust-banner-sdk,
                #cookie-banner, .cookie-banner,
                #cookie-notice, .cookie-notice,
                .cc-window, .cc-banner,
                #qc-cmp2-container,
                .osano-cm-window,
                div[aria-label*="cookie" i],
                
                /* Specific site annoyances */
                .fc-ab-root, /* Google Funding Choices */
                .adsbygoogle
            { display: none !important; pointer-events: none !important; z-index: -999 !important; }
            `;
            
            var head = document.head || document.getElementsByTagName('head')[0];
            var style = document.createElement('style');
            style.type = 'text/css';
            if (style.styleSheet){
              style.styleSheet.cssText = css;
            } else {
              style.appendChild(document.createTextNode(css));
            }
            head.appendChild(style);
        })();
    """
}