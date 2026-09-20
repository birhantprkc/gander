package com.arjun.gander

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * The viewer, driven by the intents that really reach it.
 *
 * The WebView is Robolectric's shadow, so nothing here renders a document.
 * What it can answer is everything decided before the renderer runs: which
 * surface a file gets, what URL the page is loaded from, and what the request
 * interceptor serves. The rendering itself is tested in tests/viewer and on a
 * device.
 */
@RunWith(AndroidJUnit4::class)
class ViewerActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
    }

    @After
    fun tearDown() {
        ArchivePasswords.forgetAll()
    }

    // ---------------------------------------------------------------
    // Launching
    // ---------------------------------------------------------------

    private fun view(uri: Uri, type: String? = null): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, type ?: context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
    }

    private fun open(fixture: String): ActivityController<ViewerActivity> =
        view(FixtureProvider.uriFor(fixture))

    private fun ActivityController<ViewerActivity>.container(): FrameLayout =
        get().findViewById(R.id.container)

    private fun ActivityController<ViewerActivity>.webView(): WebView? =
        container().children().filterIsInstance<WebView>().firstOrNull()

    private fun FrameLayout.children(): List<android.view.View> =
        (0 until childCount).map { getChildAt(it) }

    private fun ActivityController<ViewerActivity>.loadedUrl(): String =
        shadowOf(webView()!!).lastLoadedUrl

    // ---------------------------------------------------------------
    // Which surface a file gets
    // ---------------------------------------------------------------

    @Test
    fun aPdfIsLoadedIntoThePdfPage() {
        val url = open("six-pages.pdf").loadedUrl()
        assertThat(url).startsWith("https://appassets.androidplatform.net/assets/viewer/pdf.html")
        assertThat(url).contains("ext=pdf")
    }

    @Test
    fun eachWebFormatGetsItsOwnPage() {
        mapOf(
            "report.docx" to "docx.html",
            "budget.xlsx" to "xlsx.html",
            "budget.csv" to "xlsx.html",
            "deck.pptx" to "pptx.html",
            "notes.md" to "md.html",
            "plain.txt" to "text.html",
            "icon.svg" to "imgweb.html",
            "anim.gif" to "imgweb.html",
            "unknown.xyz" to "unsupported.html",
        ).forEach { (fixture, page) ->
            val url = open(fixture).loadedUrl()
            assertThat("$fixture -> ${url.substringAfter("viewer/").substringBefore('?')}")
                .isEqualTo("$fixture -> $page")
        }
    }

    /** A photo gets the tiling view, not a WebView. */
    @Test
    fun aPhotoIsDrawnByTheTilingViewInstead() {
        val controller = open("exif-1.jpg")
        assertThat(controller.webView()).isNull()
        assertThat(controller.container().children().filterIsInstance<SubsamplingScaleImageView>())
            .hasSize(1)
    }

    @Test
    fun audioGetsThePlayerRatherThanAPage() {
        val controller = open("tone.wav")
        assertThat(controller.webView()).isNull()
        assertThat(controller.container().childCount).isAtLeast(1)
    }

    /** The file name reaches the page, so it can title itself. */
    @Test
    fun thePageIsToldTheFileName() {
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        assertThat(view(uri).loadedUrl()).contains("name=Alder%20Court.pdf")
    }

    @Test
    fun theToolbarShowsTheDocumentName() {
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        val toolbar = view(uri).get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(toolbar.title.toString()).isEqualTo("Alder Court.pdf")
    }

    // ---------------------------------------------------------------
    // Ranged loading
    // ---------------------------------------------------------------

    /** Below the threshold, the page is told to read the file whole. */
    @Test
    fun aSmallDocumentIsNotRanged() {
        assertThat(open("six-pages.pdf").loadedUrl()).contains("ranged=0")
    }

    /**
     * Above it, both sides switch together: the page uses the ranged loader
     * and the interceptor starts offering ranges. They are decided from the
     * one number so they cannot disagree.
     */
    @Test
    fun aLargeDocumentIsRanged() {
        val big = Fixtures.sized("big.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("big.pdf", big)
        assertThat(view(uri, "application/pdf").loadedUrl()).contains("ranged=1")
    }

    // ---------------------------------------------------------------
    // Serving the document
    // ---------------------------------------------------------------

    private class Request(
        private val url: Uri,
        private val headers: Map<String, String>
    ) : WebResourceRequest {
        override fun getUrl() = url
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    private fun ActivityController<ViewerActivity>.serve(
        path: String,
        range: String? = null
    ): WebResourceResponse? {
        val web = webView()!!
        val headers = range?.let { mapOf("Range" to it) } ?: emptyMap()
        return web.webViewClient.shouldInterceptRequest(
            web, Request(Uri.parse("https://appassets.androidplatform.net$path"), headers)
        )
    }

    @Test
    fun theDocumentIsServedWholeWhenNoRangeIsAskedFor() {
        val response = open("six-pages.pdf").serve("/doc/file.pdf")!!
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.mimeType).isEqualTo("application/pdf")
        assertThat(response.responseHeaders["Content-Length"])
            .isEqualTo(Fixtures.file("six-pages.pdf").length().toString())
        assertThat(response.data.readBytes()).isEqualTo(Fixtures.bytes("six-pages.pdf"))
    }

    /** A small document never offers ranges, so a Range header is ignored. */
    @Test
    fun aRangeAskedOfASmallDocumentIsAnsweredWithTheWholeThing() {
        val response = open("six-pages.pdf").serve("/doc/file.pdf", "bytes=0-99")!!
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.responseHeaders).doesNotContainKey("Accept-Ranges")
    }

    @Test
    fun aRangeableDocumentAnswersARangeWithExactlyThoseBytes() {
        val size = (RANGE_THRESHOLD_BYTES + 512).toInt()
        val big = Fixtures.sized("ranged.pdf", size)
        val uri = FixtureProvider.install().add("ranged.pdf", big)
        val controller = view(uri, "application/pdf")

        val response = controller.serve("/doc/file.pdf", "bytes=100-199")!!

        assertThat(response.statusCode).isEqualTo(206)
        assertThat(response.reasonPhrase).isEqualTo("Partial Content")
        assertThat(response.responseHeaders["Content-Range"])
            .isEqualTo("bytes 100-199/$size")
        assertThat(response.responseHeaders["Content-Length"]).isEqualTo("100")
        assertThat(response.data.readBytes()).hasLength(100)
    }

    @Test
    fun aRangeableDocumentAdvertisesThatItAcceptsRanges() {
        val big = Fixtures.sized("ranged2.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("ranged2.pdf", big)
        val response = view(uri, "application/pdf").serve("/doc/file.pdf")!!
        assertThat(response.responseHeaders["Accept-Ranges"]).isEqualTo("bytes")
    }

    @Test
    fun anUnsatisfiableRangeFallsBackToTheWholeDocument() {
        val big = Fixtures.sized("ranged3.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("ranged3.pdf", big)
        val response = view(uri, "application/pdf")
            .serve("/doc/file.pdf", "bytes=99999999999-")!!
        assertThat(response.statusCode).isEqualTo(200)
    }

    /**
     * A provider that dies mid-document gets a 404 rather than an exception
     * thrown inside the renderer, which pdf.js reports as a readable error.
     */
    @Test
    fun aProviderThatFailsIsAnsweredWithNotFound() {
        val controller = view(FixtureProvider.uriFor(FixtureProvider.BROKEN), "application/pdf")
        val response = controller.serve("/doc/file.pdf")!!
        assertThat(response.statusCode).isEqualTo(404)
    }

    /** Everything that is not the document comes from the bundled assets. */
    @Test
    fun assetRequestsAreServedFromTheApk() {
        val response = open("six-pages.pdf").serve("/assets/viewer/app.js")
        assertThat(response).isNotNull()
        assertThat(response!!.data.readBytes().decodeToString()).contains("vwDocUrl")
    }

    // ---------------------------------------------------------------
    // The boundary around the page
    // ---------------------------------------------------------------

    /**
     * A document is untrusted content, and this is the wall around it. The
     * page may load nothing but the assets host: no CDN, no tracking pixel,
     * no link a crafted PDF talks the renderer into following.
     */
    @Test
    fun thePageMayNotNavigateAnywhereButTheAssetHost() {
        val web = open("six-pages.pdf").webView()!!
        listOf(
            "https://example.com/",
            "http://appassets.androidplatform.net.example.com/",
            "file:///etc/hosts",
            "content://com.arjun.gander.debug.fileprovider/cache/x",
        ).forEach { url ->
            val blocked = web.webViewClient.shouldOverrideUrlLoading(
                web, Request(Uri.parse(url), emptyMap())
            )
            assertThat("$url blocked: $blocked").isEqualTo("$url blocked: true")
        }
    }

    @Test
    fun theAssetHostItselfIsAllowedThrough() {
        val web = open("six-pages.pdf").webView()!!
        val blocked = web.webViewClient.shouldOverrideUrlLoading(
            web, Request(Uri.parse("https://appassets.androidplatform.net/assets/viewer/text.html"), emptyMap())
        )
        assertThat(blocked).isFalse()
    }

    @Test
    fun theWebViewReachesNeitherTheFilesystemNorTheProviders() {
        val settings = open("six-pages.pdf").webView()!!.settings
        assertThat(settings.allowFileAccess).isFalse()
        assertThat(settings.allowContentAccess).isFalse()
    }

    // ---------------------------------------------------------------
    // How a file arrives
    // ---------------------------------------------------------------

    @Test
    fun sharedPlainTextIsWrittenOutAndShownAsText() {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Something copied out of another app")
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()

        assertThat(controller.loadedUrl()).contains("viewer/text.html")
        assertThat(File(context.cacheDir, "shared-text.txt").readText())
            .isEqualTo("Something copied out of another app")
    }

    @Test
    fun aSharedFileArrivesThroughExtraStream() {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, FixtureProvider.uriFor("six-pages.pdf"))
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.loadedUrl()).contains("viewer/pdf.html")
    }

    /** The path extra, which only the bundled licence viewer uses. */
    @Test
    fun aPlainPathIsOpenedAsAFile() {
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, Fixtures.file("notes.md").absolutePath)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.loadedUrl()).contains("viewer/md.html")
    }

    /** Nothing to show is not a blank screen; it is not a screen at all. */
    @Test
    fun anIntentCarryingNothingClosesTheViewer() {
        val intent = Intent(context, ViewerActivity::class.java)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.get().isFinishing).isTrue()
    }

    // ---------------------------------------------------------------
    // Recents, and state
    // ---------------------------------------------------------------

    @Test
    fun openingAPickedFileRemembersIt() {
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit().clear().commit()
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        view(uri)
        assertThat(Recents.all(context).map { it.name }).containsExactly("Alder Court.pdf")
    }

    /**
     * The save picker outlives the activity if Android reclaims the process
     * while it is open, and comes back with a destination but no source. The
     * source is kept in the bundle for exactly that.
     */
    @Test
    fun theSaveDestinationSurvivesTheProcessGoingAway() {
        val controller = open("six-pages.pdf")
        controller.get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.action_save_copy, 0)

        val state = android.os.Bundle()
        controller.saveInstanceState(state)

        assertThat(state.getString("copy_source")).contains("six-pages.pdf")
    }

    @Test
    fun leavingTheViewerTakesTheWebViewWithIt() {
        val controller = open("six-pages.pdf")
        val web = controller.webView()!!
        controller.pause().stop().destroy()
        assertThat(shadowOf(web).wasDestroyCalled()).isTrue()
    }

    // ---------------------------------------------------------------
    // A zip, issue #30
    // ---------------------------------------------------------------

    /** A zip, with its index read inline so the list is there to assert on. */
    private fun zip(
        uri: Uri = FixtureProvider.uriFor("archive.zip"),
        state: Bundle? = null,
    ): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/zip")
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent)
        controller.get().archiveLoader = Executor { it.run() }
        // setup(null) restores from a null bundle and throws; the no-argument form is the one
        // that means a fresh start
        val started = if (state == null) controller.setup() else controller.setup(state)
        return started.also { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun ActivityController<ViewerActivity>.list(): RecyclerView =
        container().children().filterIsInstance<RecyclerView>().single()

    /** Each row, bound through the adapter, as its title or its message. */
    private fun ActivityController<ViewerActivity>.rows(): List<android.view.View> {
        val rv = list()
        val adapter = rv.adapter!!
        return (0 until adapter.itemCount).map { position ->
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView
        }
    }

    private fun ActivityController<ViewerActivity>.titles(): List<String> = rows().map {
        (it.findViewById<TextView>(R.id.title) ?: it.findViewById(R.id.hintText)).text.toString()
    }

    private fun ActivityController<ViewerActivity>.tap(title: String) {
        rows().single { it.findViewById<TextView>(R.id.title)?.text == title }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ActivityController<ViewerActivity>.toolbarTitle(): String =
        get().findViewById<MaterialToolbar>(R.id.toolbar).title.toString()

    @Test
    fun aZipOpensAsAListOfWhatIsInIt() {
        val controller = zip()
        assertThat(controller.webView()).isNull()
        assertThat(controller.titles())
            .containsExactly("nested", "photos", "private", "reports", "plain.txt").inOrder()
        assertThat(controller.toolbarTitle()).isEqualTo("archive.zip")
    }

    /** Back walks up a level at a time, and only leaves from the top. */
    @Test
    fun aFolderOpensInPlaceAndBackWalksOutOfIt() {
        val controller = zip()
        controller.tap("reports")
        assertThat(controller.titles()).containsExactly("notes.md", "six-pages.pdf").inOrder()
        assertThat(controller.toolbarTitle()).isEqualTo("reports")

        controller.get().onBackPressedDispatcher.onBackPressed()
        assertThat(controller.titles()).contains("reports")
        assertThat(controller.toolbarTitle()).isEqualTo("archive.zip")
        assertThat(controller.get().isFinishing).isFalse()

        controller.get().onBackPressedDispatcher.onBackPressed()
        assertThat(controller.get().isFinishing).isTrue()
    }

    /** A file opens in a viewer of its own, under the name only Gander can start. */
    @Test
    fun aFileOpensInTheViewerUnderTheEntryAlias() {
        val controller = zip()
        controller.tap("plain.txt")
        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(ViewerActivity.ENTRY_VIEWER)
        assertThat(started.data?.authority).isEqualTo(ArchiveProvider.authority(context))
        assertThat(ArchiveProvider.parse(started.data!!)?.name).isEqualTo("plain.txt")
    }

    /**
     * The list keeps its span indexes, as the home screen's does. Without them a tablet's two
     * columns lay a long folder out in time that grows with the square of its length.
     */
    @Test
    fun aZipsListCachesItsSpanIndexes() {
        val list = zip().container().children().filterIsInstance<RecyclerView>().single()
        val spans = (list.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanSizeLookup
        assertThat(spans.isSpanIndexCacheEnabled).isTrue()
        assertThat(spans.isSpanGroupIndexCacheEnabled).isTrue()
    }

    @Test
    fun aFileThatCannotBeOpenedSaysWhyAndOpensNothing() {
        val controller = zip()
        controller.tap("private")
        controller.tap("table.dat")
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(context.getString(R.string.entry_unsupported_open))
    }

    /** It says so in the row as well, before anybody taps it, and so does a file under a password. */
    @Test
    fun aFileThatCannotBeOpenedIsMarkedInTheList() {
        val controller = zip()
        controller.tap("private")
        val subtitles = controller.rows().associate {
            it.findViewById<TextView>(R.id.title).text.toString() to
                it.findViewById<TextView>(R.id.subtitle).text.toString()
        }
        assertThat(subtitles["locked.txt"]).startsWith(context.getString(R.string.entry_locked))
        assertThat(subtitles["table.dat"]).isEqualTo(context.getString(R.string.entry_unsupported))
    }

    // ---------------------------------------------------------------
    // Under a password
    // ---------------------------------------------------------------

    private fun locked() = zip(FixtureProvider.uriFor("locked.zip"))

    /** The password box on screen, and the field in it. */
    private fun passwordBox(): Pair<androidx.appcompat.app.AlertDialog, android.widget.EditText> {
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        fun find(v: android.view.View): android.widget.EditText? = when (v) {
            is android.widget.EditText -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return box to find(box.window!!.decorView)!!
    }

    private fun androidx.appcompat.app.AlertDialog.type(password: String) {
        passwordBox().second.setText(password)
        getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aFileUnderAPasswordAsksForIt() {
        val controller = zip()
        controller.tap("private")
        controller.tap("locked.txt")
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        val (box, field) = passwordBox()
        assertThat(box.isShowing).isTrue()
        assertThat(field.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD).isNotEqualTo(0)
        // What is typed shows as dots. The input type alone does not make it so: calling
        // setSingleLine after it swaps the dots back out, which is how this was found
        assertThat(field.transformationMethod)
            .isInstanceOf(android.text.method.PasswordTransformationMethod::class.java)
    }

    /** A wrong one says so where it was typed, and the box stays for another try. */
    @Test
    fun aWrongPasswordSaysSoAndOpensNothing() {
        val controller = locked()
        controller.tap("zipcrypto.txt")
        val (box, field) = passwordBox()
        box.type("goose")
        assertThat(box.isShowing).isTrue()
        assertThat(field.error?.toString()).isEqualTo(context.getString(R.string.password_wrong))
        assertThat(field.text.toString()).isEmpty()
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
    }

    /** The right one opens the file, and every other file under it without asking again. */
    @Test
    fun theRightPasswordOpensTheFileAndIsNotAskedForAgain() {
        val controller = locked()
        controller.tap("zipcrypto.txt")
        val (box, _) = passwordBox()
        box.type("gander")
        assertThat(box.isShowing).isFalse()
        val first = shadowOf(controller.get()).nextStartedActivity
        assertThat(first.component?.className).isEqualTo(ViewerActivity.ENTRY_VIEWER)
        assertThat(ArchiveProvider.parse(first.data!!)?.name).isEqualTo("zipcrypto.txt")

        controller.tap("aes256-deflate64.md")
        val second = shadowOf(controller.get()).nextStartedActivity
        assertThat(ArchiveProvider.parse(second.data!!)?.name).isEqualTo("aes256-deflate64.md")
        assertThat(ShadowDialog.getLatestDialog()).isSameInstanceAs(box)
    }

    /** Kept while the list is open, so a rotation keeps it, and forgotten once it is left. */
    @Test
    fun thePasswordIsForgottenWhenTheListIsLeft() {
        val uri = FixtureProvider.uriFor("locked.zip")
        val controller = locked()
        controller.tap("zipcrypto.txt")
        passwordBox().first.type("gander")
        assertThat(ArchivePasswords.get(uri)).isEqualTo("gander")
        controller.get().finish()
        controller.pause().stop().destroy()
        assertThat(ArchivePasswords.get(uri)).isNull()
    }

    @Test
    fun aFileLockedInAWayGanderCannotReadSaysWhy() {
        val controller = locked()
        controller.tap("strong.bin")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(context.getString(R.string.entry_locked_unsupported))
        assertThat(ShadowDialog.getLatestDialog()).isNull()
    }

    // ---------------------------------------------------------------
    // Pictures in the list
    // ---------------------------------------------------------------

    /** Whether the row for [title] asked for a thumbnail. Thumbs tags the view when it is asked. */
    private fun ActivityController<ViewerActivity>.asksForAPicture(title: String): Boolean =
        rows().single { it.findViewById<TextView>(R.id.title)?.text == title }
            .findViewById<android.widget.ImageView>(R.id.thumb).tag != null

    @Test
    fun aPhotoInAZipGetsAPictureAndAPdfKeepsItsBadge() {
        val controller = zip()
        controller.tap("photos")
        assertThat(controller.asksForAPicture("tiny.png")).isTrue()
        controller.get().onBackPressedDispatcher.onBackPressed()
        controller.tap("reports")
        assertThat(controller.asksForAPicture("six-pages.pdf")).isFalse()
    }

    /** A photo under a password gets its picture once the password is known, and not before. */
    @Test
    fun aPhotoUnderAPasswordGetsAPictureOnceItIsUnlocked() {
        val controller = locked()
        assertThat(controller.asksForAPicture("aes256.png")).isFalse()
        controller.tap("zipcrypto.txt")
        passwordBox().first.type("gander")
        assertThat(controller.asksForAPicture("aes256.png")).isTrue()
    }

    // ---------------------------------------------------------------
    // The code page names are read in
    // ---------------------------------------------------------------

    private fun ActivityController<ViewerActivity>.encodingItem(): android.view.MenuItem =
        get().findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.action_name_encoding)

    /** Offered only where names needed a guess, since everywhere else it would change nothing. */
    @Test
    fun theNameEncodingIsOfferedOnlyWhereNamesNeededAGuess() {
        assertThat(zip().encodingItem().isVisible).isFalse()
        assertThat(zip(FixtureProvider.uriFor("names-gbk.zip")).encodingItem().isVisible).isTrue()
    }

    /** Choosing a code page by hand reads every name again in it, and says what the guess was. */
    @Test
    fun choosingACodePageReadsTheNamesAgain() {
        val controller = zip(FixtureProvider.uriFor("names-gbk.zip"))
        assertThat(controller.titles()).containsExactly("季度报告", "照片").inOrder()
        controller.encodingItem().let {
            controller.get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(it.itemId, 0)
        }
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val choices = box.listView.adapter
        assertThat(choices.getItem(0).toString()).isEqualTo(
            context.getString(R.string.name_encoding_automatic_as, context.getString(R.string.encoding_gbk))
        )
        val big5 = (0 until choices.count).single { choices.getItem(it).toString() == context.getString(R.string.encoding_big5) }
        box.listView.performItemClick(null, big5, big5.toLong())
        shadowOf(Looper.getMainLooper()).idle()

        val raw = "季度报告".toByteArray(charset("GBK"))
        assertThat(controller.titles()).contains(String(raw, charset("Big5")))
    }

    /**
     * The list waits on the bar Save a copy reports on, and leaves it alone while a save is
     * using it: reading the names again used to hide it partway through a save.
     */
    @Test
    fun aZipsListLeavesTheBarToASaveUnderWay() {
        val controller = zip(FixtureProvider.uriFor("names-gbk.zip"))
        val activity = controller.get()
        val bar = activity.findViewById<LinearProgressIndicator>(R.id.saveProgress)
        // As Save a copy leaves it partway through
        activity.saving = true
        bar.isIndeterminate = false
        bar.visibility = android.view.View.VISIBLE
        activity.findViewById<MaterialToolbar>(R.id.toolbar).menu
            .performIdentifierAction(controller.encodingItem().itemId, 0)
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val choices = box.listView.adapter
        val big5 = (0 until choices.count).single { choices.getItem(it).toString() == context.getString(R.string.encoding_big5) }
        box.listView.performItemClick(null, big5, big5.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(bar.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(bar.isIndeterminate).isFalse()
    }

    /** And the choice survives a recreation, which reads the index again. */
    @Test
    fun aChosenCodePageSurvivesARecreation() {
        val first = zip(FixtureProvider.uriFor("names-gbk.zip"))
        first.encodingItem().let {
            first.get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(it.itemId, 0)
        }
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val big5 = (0 until box.listView.adapter.count)
            .single { box.listView.adapter.getItem(it).toString() == context.getString(R.string.encoding_big5) }
        box.listView.performItemClick(null, big5, big5.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        val titles = first.titles()
        val state = Bundle()
        first.saveInstanceState(state)

        val second = zip(FixtureProvider.uriFor("names-gbk.zip"), state)
        assertThat(second.titles()).isEqualTo(titles)
    }

    /** A change of theme recreates the viewer, and the reader stays in the folder they were in. */
    @Test
    fun theFolderOnScreenSurvivesARecreation() {
        val first = zip()
        first.tap("reports")
        val state = Bundle()
        first.saveInstanceState(state)

        val second = zip(state = state)
        assertThat(second.titles()).containsExactly("notes.md", "six-pages.pdf").inOrder()
        assertThat(second.toolbarTitle()).isEqualTo("reports")
    }

    @Test
    fun whatIsNotReallyAZipSaysSo() {
        val provider = FixtureProvider.install()
        val uri = provider.add("damaged.zip", Fixtures.file("plain.txt"))
        assertThat(zip(uri).titles())
            .containsExactly(context.getString(R.string.archive_unreadable))
    }

    /** An archive too big to list says so, in place of a list or a crash. */
    @Test
    fun aZipTooLargeToListSaysSo() {
        val bytes = Fixtures.bytes("odd-names.zip")
        val end = (bytes.size - 22 downTo 0).first { at ->
            bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4B.toByte() &&
                bytes[at + 2] == 0x05.toByte() && bytes[at + 3] == 0x06.toByte()
        }
        // The size of the index, claimed at 40 MB
        val claimed = 40 * 1024 * 1024
        for (i in 0 until 4) bytes[end + 12 + i] = (claimed ushr (8 * i)).toByte()
        val file = File.createTempFile("huge-index", ".zip").apply { writeBytes(bytes); deleteOnExit() }
        val uri = FixtureProvider.install().add("huge-index.zip", file)
        assertThat(zip(uri).titles())
            .containsExactly(context.getString(R.string.archive_too_large))
    }

    /**
     * ArchiveProvider reads whatever archive its URI names, with Gander's access, so one of its
     * URIs arriving from another app is turned away rather than shown.
     */
    @Test
    fun aFileInsideAZipIsRefusedFromAnywhereButGandersOwnList() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val controller = view(entry, "text/plain")
        assertThat(controller.get().isFinishing).isTrue()
    }

    /**
     * Android takes "0@" in front of an authority for the same provider, for the phone's own
     * user, so the gate goes by host. Checked by authority, this spelling got past it, and any
     * app could have Gander show a file from any zip Gander can read.
     */
    @Test
    fun aFileInsideAZipIsRefusedHoweverItsAuthorityIsSpelled() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val spelled = entry.buildUpon().encodedAuthority("0@" + entry.encodedAuthority).build()
        assertThat(spelled.authority).isNotEqualTo(ArchiveProvider.authority(context))
        val controller = view(spelled, "text/plain")
        assertThat(controller.get().isFinishing).isTrue()
    }

    /**
     * Share from a file in a zip leaves Gander out of the list, as the home screen's share does.
     * Picked, Gander would turn the file away at the gate above without a word.
     */
    @Test
    fun shareLeavesGanderOutOfTheList() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.ENTRY_VIEWER))
            .setData(entry)
        val activity = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        activity.findViewById<MaterialToolbar>(R.id.toolbar).menu
            .performIdentifierAction(R.id.action_share, 0)
        val chooser = shadowOf(activity).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS).orEmpty()
        assertThat(excluded.toList()).contains(ComponentName(context, ViewerActivity::class.java))
    }

    /**
     * A stored file in a zip is served as a window onto the archive, and a window is what
     * openFileDescriptor refuses outright. A large PDF asks for ranges, so the range server
     * has to take the window's own offset into account, or every piece comes back not found
     * and pdf.js reports the document broken.
     */
    @Test
    fun aLargeStoredFileInAZipIsServedInRangesFromItsOwnBytes() {
        val size = (RANGE_THRESHOLD_BYTES + 1).toInt()
        val body = ByteArray(size) { (it % 251).toByte() }
        val file = File.createTempFile("ranged", ".zip").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            // Something ahead of it, so the file does not start where the archive does
            z.putNextEntry(java.util.zip.ZipEntry("first.txt"))
            z.write("first".toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("big.pdf").apply {
                method = java.util.zip.ZipEntry.STORED
                this.size = body.size.toLong()
                compressedSize = body.size.toLong()
                crc = java.util.zip.CRC32().apply { update(body) }.value
            })
            z.write(body)
            z.closeEntry()
        }
        val archive = FixtureProvider.install().add("ranged.zip", file)
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
        val raf = java.io.RandomAccessFile(file, "r")
        val entry = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, java.util.Locale.US).single { e -> e.path == "big.pdf" }
        }
        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.ENTRY_VIEWER))
            .setData(ArchiveProvider.uriFor(context, archive, entry))
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()

        assertThat(controller.loadedUrl()).contains("ranged=1")
        val response = controller.serve("/doc/file.pdf", "bytes=100-199")!!
        assertThat(response.statusCode).isEqualTo(206)
        assertThat(response.responseHeaders["Content-Range"]).isEqualTo("bytes 100-199/$size")
        assertThat(response.data.readBytes()).isEqualTo(body.copyOfRange(100, 200))
    }

    /**
     * A PDF inside a zip keeps no reading position. Positions are filed on the phone under a
     * fingerprint of the file, and nothing in a zip is written to the phone, so the fingerprint
     * is never taken. Seeded with a page under exactly the one the viewer would take, which a
     * viewer that still looked would open on.
     */
    @Test
    fun aPdfInsideAZipKeepsNoReadingPosition() {
        val pdf = Fixtures.bytes("six-pages.pdf")
        val file = File.createTempFile("positions", ".zip").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("six-pages.pdf").apply {
                method = java.util.zip.ZipEntry.STORED
                this.size = pdf.size.toLong()
                compressedSize = pdf.size.toLong()
                crc = java.util.zip.CRC32().apply { update(pdf) }.value
            })
            z.write(pdf)
            z.closeEntry()
        }
        val archive = FixtureProvider.install().add("positions.zip", file)
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
        val raf = java.io.RandomAccessFile(file, "r")
        val entry = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, java.util.Locale.US).single()
        }
        val uri = ArchiveProvider.uriFor(context, archive, entry)
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { it.length }
        Positions.save(context, Positions.keyFor(context.contentResolver, uri, length)!!, 4, 6)

        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.ENTRY_VIEWER))
            .setData(uri)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.loadedUrl()).contains("viewer/pdf.html")
        assertThat(controller.loadedUrl()).doesNotContain("resume=")
    }

    @Test
    fun theEntryAliasIsLetThrough() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.ENTRY_VIEWER))
            .setData(entry)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.get().isFinishing).isFalse()
        assertThat(controller.loadedUrl()).contains("viewer/text.html")
    }
}
