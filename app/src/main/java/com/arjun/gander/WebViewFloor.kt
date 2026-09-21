package com.arjun.gander

/**
 * Whether the WebView about to render a PDF is new enough to do it, and what
 * to tell the page when it is not.
 *
 * Split out of ViewerActivity because deciding this is string parsing against
 * a table of vendor quirks, and the quirks are the part worth testing. The
 * activity keeps only the two calls that ask Android what it is running.
 */

/**
 * Chromium major version the vendored pdf.js needs. Mozilla puts the legacy
 * build's floor at Chrome 125, and `lib/pdf.min.mjs` is pdfjs-dist 5.7.284
 * legacy. Below it, `pdf.html` says so instead of loading the renderer.
 *
 * Read this before raising it alongside a pdf.js upgrade. Chromium 138 is the
 * last WebView that Android 8.0, 8.1 and 9.0 will ever receive, because 139
 * requires Android 10 and minSdk here is 26. A pdf.js release needing more than
 * 138 therefore does not degrade on API 26 to 28, it ends PDF support there for
 * good. docs/VENDORED.md carries the same warning next to the version fetched.
 */
internal const val PDFJS_MIN_CHROMIUM_MAJOR = 125

/**
 * Below this, a parsed major is treated as unreadable rather than as ancient.
 * WebView only became updatable at Chromium 33, so a provider reporting
 * something like "1.0" is telling us it does not use Chromium version numbers,
 * not that it predates them, and refusing its PDFs would break a device that
 * works today.
 *
 * This is why the user agent is asked first. Huawei numbers its WebView 12.1.x,
 * 14.0.x, 15.0.x, so a genuinely old engine parsed to 15 from the package alone,
 * landed under this floor, and was waved through as unreadable. Reading Chrome/
 * out of the user agent gives the engine version whatever the vendor calls the
 * package, and a provider that reports neither is caught by
 * [LOCKED_WEBVIEW_PACKAGES] instead of by guessing.
 */
internal const val PLAUSIBLE_CHROMIUM_MAJOR = 30

/** The Chromium major in a WebView user agent, as in "Chrome/138.0.7204.179". */
internal val CHROME_TOKEN = Regex("""Chrome/(\d+)""")

/**
 * WebView providers that cannot be swapped for another one.
 *
 * On a Huawei device without Google services the provider is pinned to this
 * package: Chrome and Android System WebView are both rejected, because they are
 * signed against a certificate chain the device does not carry. Two consequences.
 * The card must not tell these users to update Android System WebView, since they
 * cannot. And a version we failed to read is old rather than unknown, because no
 * Huawei build reaches the pdf.js floor, so this is the one case where an
 * unreadable version blocks instead of being waved through.
 */
internal val LOCKED_WEBVIEW_PACKAGES = setOf("com.huawei.webview")

/**
 * Chromium major version of the WebView that will render the page.
 *
 * The user agent is asked first, because its Chrome/ token is the engine version
 * whatever the provider calls its package, and a vendor scheme like Huawei's
 * "15.0.4.326" says nothing about the engine. [packageVersionName] gives the
 * provider package's own versionName, kept as a fallback for an engine whose
 * user agent carries no Chrome/ token at all. It is a function so the package is
 * only asked once the user agent has failed to answer: that lookup can throw, and
 * a throw must not cost a version the user agent already gave.
 *
 * Null when neither source answers, or when both are too low to be a Chromium
 * version. A null is treated as new enough unless the provider is locked: refusing
 * PDFs on a WebView that works would be the worse mistake, and pdf.html's nomodule
 * fallback still covers the oldest engines a null could hide.
 */
internal fun chromiumMajor(userAgent: String?, packageVersionName: () -> String?): Int? {
    val fromUa = userAgent
        ?.let { CHROME_TOKEN.find(it) }
        ?.groupValues?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
    return fromUa ?: packageVersionName()
        ?.substringBefore('.')
        ?.toIntOrNull()
        ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
}

/**
 * The parameters telling pdf.html it cannot render, and empty otherwise, including
 * for every other format. Not because the others have no floor: docx-preview needs
 * Chromium 80 and marked needs 92, and below those a .docx or a .md fails with an
 * error that says nothing about the WebView, as on the WebView 64 of issue #31.
 * Only PDF is checked.
 *
 * "&webview=<major>&needs=<floor>" when the engine is older than the vendored
 * pdf.js supports. Both numbers are passed so the floor lives only in Kotlin rather
 * than being repeated as a literal inside a user-facing sentence in the page.
 *
 * "&locked=1" is added when updating the WebView is not something the reader can
 * do, so the page can drop the advice to go and update it. On a locked provider
 * whose version would not parse, that flag goes out on its own with no major beside
 * it, which is why the page gates on either parameter rather than on the version.
 */
internal fun pdfjsFloorParams(kind: FileKind, major: Int?, locked: Boolean): String {
    if (kind != FileKind.PDF) return ""
    if (major == null) {
        return if (locked) "&needs=$PDFJS_MIN_CHROMIUM_MAJOR&locked=1" else ""
    }
    if (major >= PDFJS_MIN_CHROMIUM_MAJOR) return ""
    return "&webview=$major&needs=$PDFJS_MIN_CHROMIUM_MAJOR" + if (locked) "&locked=1" else ""
}
