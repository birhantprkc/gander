package com.arjun.gander

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.arjun.gander.FileKind.Companion.detect
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val STATE_COPY_SOURCE = "copy_source"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

        /**
         * Above this, a document is served in ranges rather than read whole.
         *
         * This is a memory threshold, not a speed one. Bulk and ranged loading were
         * measured against each other at 0.2, 2.7, 8, 16, 32 and 53 MB on a Nothing
         * Phone 2: below about 32 MB the difference had no consistent sign and stayed
         * inside run-to-run noise, and only at 53 MB did ranging win repeatably, by
         * around 80 ms. So anywhere in that band is equally defensible on speed, and
         * the number is chosen instead for what it avoids holding in memory. 16 MB is
         * comfortable to buffer on a low-end device; a 50 MB scan is not.
         */
        private const val RANGE_THRESHOLD_BYTES = 16L * 1024 * 1024

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
        private const val PDFJS_MIN_CHROMIUM_MAJOR = 125

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
        private const val PLAUSIBLE_CHROMIUM_MAJOR = 30

        /** The Chromium major in a WebView user agent, as in "Chrome/138.0.7204.179". */
        private val CHROME_TOKEN = Regex("""Chrome/(\d+)""")

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
        private val LOCKED_WEBVIEW_PACKAGES = setOf("com.huawei.webview")
    }

    private var webView: WebView? = null
    private var player: ExoPlayer? = null

    /** The file the destination picker is currently open for. */
    private var copySource: Uri? = null

    /**
     * ACTION_CREATE_DOCUMENT with the type set per file. The stock contract fixes
     * it at construction, and the viewer does not know what it is showing until an
     * intent arrives, so the picker would otherwise have to be told that every file
     * is of unknown type and would suggest names without extensions.
     */
    private inner class CreateTypedDocument : ActivityResultContracts.CreateDocument("*/*") {
        var type: String = "*/*"
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).setType(type)
    }

    private val createDocument = CreateTypedDocument()

    /**
     * Writes the open file to wherever the reader pointed the picker.
     *
     * No permission is involved. The picker returns a write grant for the single
     * document it just created and for nothing around it, which is the same shape
     * of grant the home screen already takes for folders, and it is why this can
     * exist in an app whose permission list has to stay empty.
     */
    private val saveCopy = registerForActivityResult(createDocument) { dest ->
        val src = copySource
        copySource = null
        if (dest == null || src == null) return@registerForActivityResult

        // Off the main thread. A document is small enough that it would not matter,
        // but Gander opens video too, and copying a few hundred megabytes inline is
        // an ANR rather than a slow save.
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        val bar = findViewById<LinearProgressIndicator>(R.id.saveProgress)

        // Asked once, up front: a provider that cannot say how long the file is
        // gets a spinner rather than a bar that would have to invent a position.
        val total = documentLength(src)
        bar.isIndeterminate = total <= 0L
        if (total > 0L) {
            bar.max = 100
            bar.progress = 0
        }
        bar.visibility = View.VISIBLE

        // Shut down immediately after submitting: the already-queued copy still
        // runs to completion, and the worker thread ends with it instead of idling
        // for the life of the process once per save
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val saved = runCatching {
                contentResolver.openInputStream(src).use { input ->
                    contentResolver.openOutputStream(dest).use { output ->
                        val from = checkNotNull(input)
                        val to = checkNotNull(output)
                        // copyTo would be one line, but it reports nothing on the way
                        // through, and the whole point here is to be able to say how
                        // far along a large file is
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var shown = -1
                        while (true) {
                            val read = from.read(buffer)
                            if (read < 0) break
                            to.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                // Whole percent only: a 500 MB file would otherwise
                                // post thousands of updates nobody can see
                                val percent = ((copied * 100) / total).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    main.post {
                                        if (!isDestroyed) bar.setProgressCompat(percent, true)
                                    }
                                }
                            }
                        }
                    }
                }
            }.isSuccess
            // A failed copy has already created the document and may have written
            // part of it, and half a file under the right name is worse than none:
            // it opens, it looks complete, and it is not.
            if (!saved) runCatching { DocumentsContract.deleteDocument(contentResolver, dest) }
            main.post {
                if (!isDestroyed) bar.visibility = View.GONE
                Toast.makeText(
                    app,
                    if (saved) R.string.save_copy_done else R.string.save_copy_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        worker.shutdown()
    }

    /**
     * The picker outlives the activity if Android reclaims the process while it is
     * open, and the callback is handed the destination but never the source, so
     * without this the save comes back to a null source and silently does nothing.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_COPY_SOURCE, copySource?.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        applySystemBarInsets(findViewById(R.id.root))
        onBackPressedDispatcher.addCallback(this, searchBackCallback)

        copySource = savedInstanceState?.getString(STATE_COPY_SOURCE)?.let(Uri::parse)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val container = findViewById<FrameLayout>(R.id.container)

        // Files arrive via VIEW (data), the share sheet (EXTRA_STREAM),
        // a plain path extra, or as shared text (EXTRA_TEXT).
        val uri = intent.data
            ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.getStringExtra(EXTRA_PATH)?.let { Uri.fromFile(File(it)) }
            ?: sharedTextUri()
        if (uri == null) {
            finish()
            return
        }

        val name = resolveDisplayName(uri)
        toolbar.title = name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: intent.type

        // Picker selections carry a persistable grant; keep those in Recents.
        // Open-with and folder-browsed URIs throw here and are simply skipped.
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Recents.add(this, uri, name)
            }
        }

        // Held past the when because setUpSearch needs it to know whether the
        // format it is offering to search actually has anything findable in it
        val kind = detect(ext, mime)
        when (kind) {
            FileKind.IMAGE -> showImage(container, uri, name, ext)
            FileKind.PLAYER -> showPlayer(container, uri, name, ext)
            else -> showWeb(container, uri, kind, name, ext)
        }
        setUpSearch(toolbar, kind)
        setUpActions(toolbar, uri, name, ext, mime)
    }

    /** Share and "show in file manager" toolbar actions. */
    private fun setUpActions(
        toolbar: MaterialToolbar,
        uri: Uri,
        name: String,
        ext: String,
        mime: String?
    ) {
        toolbar.menu.findItem(R.id.action_share).setOnMenuItemClickListener {
            shareFile(uri, ext, mime)
            true
        }
        toolbar.menu.findItem(R.id.action_save_copy).setOnMenuItemClickListener {
            copySource = uri
            createDocument.type = mime
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "*/*"
            // No picker on the device is the only way this throws, and it leaves
            // the reader on the document rather than on a crash
            runCatching { saveCopy.launch(name) }.onFailure {
                copySource = null
                Toast.makeText(this, R.string.save_copy_failed, Toast.LENGTH_SHORT).show()
            }
            true
        }

        val folder = containingFolder(uri)
        toolbar.menu.findItem(R.id.action_open_folder).apply {
            isVisible = folder != null
            setOnMenuItemClickListener {
                openFolder(folder ?: return@setOnMenuItemClickListener true)
                true
            }
        }
    }

    private fun shareFile(uri: Uri, ext: String, mime: String?) {
        // Received content:// URIs go out with a read grant passed along; our own
        // file:// URIs (the shared-text temp file) go through the FileProvider
        val shareUri =
            if (uri.scheme == "content") uri
            else runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(uri.path!!))
            }.getOrNull()
        if (shareUri == null) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, shareUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some Android versions refuse to delegate a tree-derived grant and
        // throw here rather than at read time
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_file))) }
            .onFailure { Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Best-effort document URI of the folder holding [uri], for the Files app.
     * Null when the source provider does not expose a real filesystem location,
     * which hides the menu item.
     */
    private fun containingFolder(uri: Uri): Uri? = runCatching {
        when {
            uri.scheme == "file" ->
                File(uri.path!!).parent?.let { folderDocUri(it) }
            uri.authority == EXTERNAL_STORAGE_AUTHORITY -> {
                // Document id is "volume:relative/path"; drop the file segment
                val docId = DocumentsContract.getDocumentId(uri)
                val volume = docId.substringBefore(':', "")
                val path = docId.substringAfter(':', "")
                if (volume.isEmpty() || path.isEmpty()) null
                else DocumentsContract.buildDocumentUri(
                    EXTERNAL_STORAGE_AUTHORITY,
                    "$volume:${path.substringBeforeLast('/', "")}"
                )
            }
            uri.authority == DOWNLOADS_AUTHORITY -> {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("raw:")) {
                    File(docId.removePrefix("raw:")).parent?.let { folderDocUri(it) }
                } else {
                    // Opaque ids (msf:42) at least live under Download
                    DocumentsContract.buildDocumentUri(
                        EXTERNAL_STORAGE_AUTHORITY, "primary:Download"
                    )
                }
            }
            uri.authority == "media" ->
                // Our read grant lets us ask MediaStore for the backing path
                contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null
                    else c.getString(0)?.let { File(it).parent }?.let { folderDocUri(it) }
                }
            else -> null
        }
    }.getOrNull()

    /** Maps an absolute folder path to an ExternalStorageProvider document URI. */
    private fun folderDocUri(path: String): Uri? {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val docId = when {
            path.startsWith(primary) ->
                "primary:" + path.removePrefix(primary).trimStart('/')
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                rest.substringBefore('/') + ":" + rest.substringAfter('/', "")
            }
            else -> return null
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
    }

    private fun openFolder(folder: Uri) {
        // The system Files app (and most file managers) handle VIEW on a
        // directory document; no grant needed, they have provider access
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        runCatching { startActivity(view) }.onFailure {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Where a search goes. Two of these, because PDF cannot use the other one.
     *
     * Every other WebView format is found with findAllAsync, Chromium's own
     * find-in-page, which searches the DOM. pdf.html holds only about eleven pages of
     * a document in the DOM at once, so native find would report matches from the
     * part of it currently on screen and silently miss the rest, which is worse than
     * offering no search at all. PDF therefore searches in the page, over text it
     * extracts itself, and reports back over a message port.
     */
    private interface Finder {
        fun query(q: String)
        fun next()
        fun prev()
        fun clear()
    }

    private class NativeFinder(private val web: WebView) : Finder {
        override fun query(q: String) = web.findAllAsync(q)
        override fun next() { web.findNext(true) }
        override fun prev() { web.findNext(false) }
        override fun clear() = web.clearMatches()
    }

    /** The page end of the PDF search channel, held so it can be closed. */
    private var searchPort: WebMessagePortCompat? = null

    /** Set once the counter exists, called with (position, total, indexFinished). */
    private var onSearchCount: ((Int, Int, Boolean) -> Unit)? = null

    /**
     * Closes the search bar, whoever asked: the close button, Back, or a renderer that
     * died underneath it. Held as a field because showRendererGone has to reach it from
     * outside setUpSearch, and it stays a no-op until there is a bar to close.
     */
    private var closeSearchBar: () -> Unit = {}

    /**
     * Takes Back while the search bar is open, so it closes the bar instead of the
     * document. Until this existed, Back on a document with the search box open threw
     * away the reading position and the query together, and from targetSdk 35 on it did
     * it while playing the predictive back animation, so the app visibly peeled away
     * toward the launcher with the cursor still in the box.
     *
     * Nothing here calls finish(). The callback closes the bar and switches itself off,
     * and the next press falls through to the default, which is what keeps predictive
     * back working: an enabled callback suppresses the animation and owns the event.
     */
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearchBar()
    }

    /** In-document search: findAllAsync for most formats, a message port for PDF. */
    private fun setUpSearch(toolbar: MaterialToolbar, kind: FileKind) {
        val bar = findViewById<LinearLayout>(R.id.searchBar)
        val input = findViewById<EditText>(R.id.searchInput)
        val count = findViewById<TextView>(R.id.searchCount)
        val searchItem = toolbar.menu.findItem(R.id.action_search)

        val web = webView
        if (web == null || (kind == FileKind.PDF && !canPortSearch())) {
            // No WebView at all, or a WebView too old to carry a message channel. The
            // second is close to unreachable: message channels landed long before the
            // Chromium 125 pdf.html already refuses to run below. Hiding the button is
            // what PDF did in every release up to this one, so it is a known-good
            // place to land rather than a new failure.
            searchItem.isVisible = false
            return
        }
        searchItem.isVisible = true

        // One place that renders a count, whichever transport produced it. The
        // ordering inside it is load-bearing and is explained where it happens.
        val show = { active: Int, total: Int, settled: Boolean ->
            val asked = input.text.isNotEmpty()
            // Set the spoken form before the text: the live region fires on the text
            // change, and by then the description has to be the one to read out
            count.contentDescription = when {
                total > 0 -> getString(R.string.match_count_spoken, active, total)
                // "No matches" only once there is nothing left to look through. While
                // the PDF index is still being read a zero is a not-yet, and saying so
                // out loud would be wrong twice over: wrong now, and again when the
                // count changes under the reader.
                asked && settled -> getString(R.string.no_matches)
                else -> null
            }
            count.text = when {
                total > 0 -> getString(R.string.match_count, active, total)
                asked && settled -> getString(R.string.match_count, 0, 0)
                else -> ""
            }
        }

        val finder: Finder
        if (kind == FileKind.PDF) {
            onSearchCount = { active, total, settled -> show(active, total, settled) }
            finder = PortFinder()
        } else {
            web.setFindListener { active, total, done ->
                // Native find counts from zero and only means it once done.
                if (done) show(active + 1, total, true)
            }
            finder = NativeFinder(web)
        }

        // Every call into the finder goes through here. NativeFinder holds the WebView
        // directly, and onRenderProcessGone destroys it and nulls the field before
        // showRendererGone runs, so a clear arriving after that would land on a dead
        // one. The close path reaches this twice over: once itself, and once through the
        // text watcher that emptying the box fires.
        fun find(action: (Finder) -> Unit) { if (webView != null) action(finder) }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        searchItem.setOnMenuItemClickListener {
            bar.visibility = LinearLayout.VISIBLE
            searchBackCallback.isEnabled = true
            input.requestFocus()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            true
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) {
                    find { it.clear() }
                    count.text = ""
                    count.contentDescription = null
                } else {
                    find { it.query(q) }
                }
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            find { it.next() }
            true
        }
        findViewById<ImageButton>(R.id.searchPrev).setOnClickListener { find { it.prev() } }
        findViewById<ImageButton>(R.id.searchNext).setOnClickListener { find { it.next() } }

        closeSearchBar = {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.text.clear()
            find { it.clear() }
            bar.visibility = LinearLayout.GONE
            searchBackCallback.isEnabled = false
        }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener { closeSearchBar() }
    }

    /**
     * What the reader last asked for, kept so it can be asked again.
     *
     * The search button is on the toolbar from the moment the activity is built, but
     * the channel does not exist until the page has run. Somebody quick enough to open
     * the box and type in that window would otherwise have their query go nowhere,
     * with the counter sitting empty and no way back except retyping it.
     */
    private var pendingQuery: String = ""

    /** One letter of command, then the query. Read by onSearch() in pdf.html. */
    private inner class PortFinder : Finder {
        private fun send(s: String) {
            searchPort?.postMessage(WebMessageCompat(s))
        }
        override fun query(q: String) {
            pendingQuery = q
            send("q$q")
        }
        override fun next() = send("n")
        override fun prev() = send("p")
        override fun clear() {
            pendingQuery = ""
            send("c")
        }
    }

    private fun canPortSearch(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE) &&
            WebViewFeature.isFeatureSupported(
                WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK)

    /**
     * Hand pdf.html one end of a message channel.
     *
     * This is the only channel from this app into a page, and it is deliberately not
     * addJavascriptInterface. That call reflects a Java object into script running on
     * an untrusted document and lets it call methods on it; this passes strings, in
     * the same direction the query parameters on the URL already go. Nothing on
     * either side is evaluated, and what comes back is read as three integers and
     * dropped if it is anything else. See the note beside vwAskPassword in pdf.html
     * about why that boundary is most of what keeps a document away from the app.
     *
     * Posted on page finished rather than at load, because the listener that takes it
     * lives in the page and is not there until the page has run.
     */
    private fun openSearchChannel(web: WebView) {
        if (!canPortSearch() || searchPort != null) return
        val ends = WebViewCompat.createWebMessageChannel(web)
        val mine = ends[0]
        mine.setWebMessageCallback(
            Handler(Looper.getMainLooper()),
            object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                    val said = message?.data?.split(" ") ?: return
                    if (said.size != 3) return
                    val at = said[0].toIntOrNull() ?: return
                    val total = said[1].toIntOrNull() ?: return
                    if (at < 0 || total < 0) return
                    onSearchCount?.invoke(at, total, said[2] == "1")
                }
            })
        searchPort = mine
        WebViewCompat.postWebMessage(
            web, WebMessageCompat("vw-search-port", arrayOf(ends[1])), Uri.parse("*"))
        // Anything typed while there was nowhere to send it.
        if (pendingQuery.isNotEmpty()) mine.postMessage(WebMessageCompat("q$pendingQuery"))
    }

    private fun closeSearchChannel() {
        runCatching { searchPort?.close() }
        searchPort = null
        onSearchCount = null
    }

    /**
     * Edge to edge is enforced from targetSdk 35 on, so push the layout out of the
     * status bar, display cutout and navigation bar areas.
     */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Shared plain text becomes a temp file shown in the text viewer. */
    private fun sharedTextUri(): Uri? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return runCatching {
            val f = File(cacheDir, "shared-text.txt")
            f.writeText(text)
            Uri.fromFile(f)
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun showImage(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val imageView = SubsamplingScaleImageView(this)
        imageView.contentDescription = name
        imageView.setBackgroundColor(Color.BLACK)
        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        imageView.maxScale = 12f
        imageView.orientation = Thumbs.exifRotation(contentResolver, uri)
        imageView.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onImageLoadError(e: Exception) {
                // Some formats decode fine in the WebView even when the region decoder gives up
                container.removeAllViews()
                showWeb(container, uri, FileKind.IMAGE_WEB, name, ext)
            }
        })
        container.addView(imageView, matchParent())
        imageView.setImage(ImageSource.uri(uri))
    }

    private fun showPlayer(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val playerView = PlayerView(this)
        playerView.setBackgroundColor(Color.BLACK)
        playerView.keepScreenOn = true
        playerView.controllerShowTimeoutMs = 2500
        container.addView(playerView, matchParent())

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                exo.release()
                player = null
                container.removeAllViews()
                showWeb(container, uri, FileKind.UNSUPPORTED, name, ext)
            }
        })
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(container: FrameLayout, uri: Uri, kind: FileKind, name: String, ext: String) {
        val web = WebView(this)
        webView = web

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Neither of these can change while the document is open, and a ranged load
        // asks for hundreds of pieces, so resolve them once here instead of per
        // request. documentLength in particular is a round trip to the provider.
        val total = documentLength(uri)
        val mime = documentMime(ext)

        web.webViewClient = object : WebViewClientCompat() {
            override fun onPageFinished(view: WebView, url: String) {
                // PDF is the only viewer that searches from inside the page, so it is
                // the only one that needs a way to answer.
                if (kind == FileKind.PDF) openSearchChannel(view)
            }

            /**
             * The renderer is a process of its own, and Android is free to kill it
             * when memory runs short. That happens most easily while Gander is in
             * the background and something heavy is starting in front of it, which
             * is exactly what tapping Share and picking a large app looks like.
             *
             * Not overriding this is not the neutral choice. The default returns
             * false, and false tells WebView to kill the whole application rather
             * than leave it holding a WebView it can no longer draw. So a document
             * left open in the background could take Gander down with it, with no
             * crash of ours behind it and nothing on screen to explain it.
             *
             * Returning true keeps the process, and the price is that this WebView
             * is finished: nothing may call into it again, so it is detached and
             * destroyed here and the reader is offered the document back.
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                if (webView === view) {
                    webView = null
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    showRendererGone(container, detail.didCrash())
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // The document is served here rather than through WebViewAssetLoader
                // because a PathHandler is only given the path, and answering range
                // requests needs the Range header off the request itself.
                if (request.url.host == ASSET_HOST &&
                    request.url.path?.startsWith("/doc/") == true
                ) {
                    return docResponse(uri, mime, total, request.requestHeaders["Range"])
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host != ASSET_HOST
        }

        container.addView(web, matchParent())
        // The load strategy is decided here, not in the page, so the headers we serve
        // and the loader the page picks cannot disagree
        val ranged = if (useRanges(total)) 1 else 0
        web.loadUrl(
            "https://$ASSET_HOST/assets/viewer/${kind.page}" +
                "?name=${Uri.encode(name)}&ext=${Uri.encode(ext)}&ranged=$ranged" +
                pdfjsFloorParams(kind, web.settings.userAgentString)
        )
    }

    /**
     * Puts a message and a way back where the document was, once its renderer has
     * gone. Reloading is a full restart of the activity rather than a fresh WebView
     * in place, because search holds the WebView it was wired to and a destroyed one
     * cannot answer findAllAsync; going through onCreate again rebuilds both together.
     */
    private fun showRendererGone(container: FrameLayout, didCrash: Boolean) {
        // Both search surfaces go until there is something to search again, and for
        // PDF so does the channel: its far end was in the renderer that just died, so
        // it can neither be asked anything nor answer. The activity restarts to get a
        // working one, which is where a new channel comes from.
        closeSearchBar()
        findViewById<MaterialToolbar>(R.id.toolbar).menu
            .findItem(R.id.action_search)?.isVisible = false
        closeSearchChannel()

        container.removeAllViews()
        val card = layoutInflater.inflate(R.layout.view_render_gone, container, false)
        card.findViewById<TextView>(R.id.renderGoneTitle).setText(
            if (didCrash) R.string.render_gone_crashed else R.string.render_gone_reclaimed
        )
        card.findViewById<View>(R.id.renderGoneReload).setOnClickListener { recreate() }
        container.addView(card)
    }

    /**
     * Serves the open document at /doc/<anything>, answering range requests.
     *
     * Told that ranges are available, pdf.js pulls a large file in pieces as it
     * needs them instead of buffering all of it before drawing anything. On a
     * 53 MB scan that read was about 400 ms of a one second open, and it kept the
     * whole document in memory for as long as it was on screen.
     *
     * A fresh stream per request: the page asks for many, and out of order.
     */
    private fun docResponse(
        uri: Uri,
        mime: String,
        total: Long,
        range: String?
    ): WebResourceResponse {
        return try {
            // Ranges are only offered for documents big enough to be worth the extra
            // round trips. Measured on a Nothing Phone 2: a 53 MB scan opened 177 ms
            // faster ranged, while a 251 KB document opened 67 ms slower. Below the
            // threshold one bulk read wins; above it, reading the lot dominates.
            val rangeable = useRanges(total)
            val span = if (rangeable) range?.let { parseRange(it, total) } else null
            if (span == null) {
                val headers = mutableMapOf<String, String>()
                if (rangeable) headers["Accept-Ranges"] = "bytes"
                if (total >= 0) headers["Content-Length"] = total.toString()
                WebResourceResponse(
                    mime, null, 200, "OK", headers,
                    contentResolver.openInputStream(uri)
                )
            } else {
                val (start, end) = span
                WebResourceResponse(
                    mime, null, 206, "Partial Content",
                    mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes $start-$end/$total",
                        "Content-Length" to (end - start + 1).toString()
                    ),
                    slice(uri, start, end)
                )
            }
        } catch (e: Exception) {
            WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                null, ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    /**
     * Whether to serve this document in ranges. Decided in one place because both
     * the response headers and the page's choice of loader have to agree.
     */
    private fun useRanges(total: Long): Boolean =
        total >= RANGE_THRESHOLD_BYTES

    /**
     * Chromium major version of the WebView that will render the page.
     *
     * The user agent is asked first, because its Chrome/ token is the engine version
     * whatever the provider calls its package, and a vendor scheme like Huawei's
     * "15.0.4.326" says nothing about the engine. The package versionName is kept as a
     * fallback for a provider whose user agent carries no Chrome/ token at all.
     *
     * Null when neither source answers, or when both are too low to be a Chromium
     * version. A null is treated as new enough unless the provider is locked: refusing
     * PDFs on a WebView that works would be the worse mistake, and pdf.html's nomodule
     * fallback still covers the oldest engines a null could hide.
     */
    private fun webViewChromiumMajor(userAgent: String?): Int? = runCatching {
        val fromUa = userAgent
            ?.let { CHROME_TOKEN.find(it) }
            ?.groupValues?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
        fromUa ?: WebViewCompat.getCurrentWebViewPackage(this)
            ?.versionName
            ?.substringBefore('.')
            ?.toIntOrNull()
            ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
    }.getOrNull()

    /** Whether the WebView about to render cannot be swapped for a different one. */
    private fun webViewProviderIsLocked(): Boolean = runCatching {
        WebViewCompat.getCurrentWebViewPackage(this)?.packageName in LOCKED_WEBVIEW_PACKAGES
    }.getOrDefault(false)

    /**
     * The parameters telling pdf.html it cannot render, and empty otherwise, including
     * for every other format: pdf.html is the only viewer loaded as an ES module, and
     * the rest are classic scripts that any engine can parse.
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
    private fun pdfjsFloorParams(kind: FileKind, userAgent: String?): String {
        if (kind != FileKind.PDF) return ""
        val locked = webViewProviderIsLocked()
        val major = webViewChromiumMajor(userAgent)
            ?: return if (locked) "&needs=$PDFJS_MIN_CHROMIUM_MAJOR&locked=1" else ""
        if (major >= PDFJS_MIN_CHROMIUM_MAJOR) return ""
        return "&webview=$major&needs=$PDFJS_MIN_CHROMIUM_MAJOR" + if (locked) "&locked=1" else ""
    }

    /** Content type for the document, from the extension rather than the provider. */
    private fun documentMime(ext: String): String = when (ext) {
        "svg" -> "image/svg+xml"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** Length in bytes, or -1 when the provider declines to say. */
    private fun documentLength(uri: Uri): Long = runCatching {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it >= 0 } ?: -1L

    /**
     * "bytes=start-end" resolved against a known total. Null means serve the whole
     * thing: an unparseable header, an unsatisfiable one, or a provider that would
     * not give us a length to range against.
     */
    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        if (total <= 0) return null
        // Only the first range of a set; pdf.js never asks for more than one
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val start = spec.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = spec.substringAfter('-').trim().toLongOrNull() ?: (total - 1)
        if (start < 0 || start > end || start >= total) return null
        return start to minOf(end, total - 1)
    }

    /** Exactly [start, end], seeking to the offset rather than reading up to it. */
    private fun slice(uri: Uri, start: Long, end: Long): InputStream {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("cannot open $uri")
        val stream = java.io.FileInputStream(pfd.fileDescriptor)
        // Seekable for anything file backed; a pipe has to be read through instead
        runCatching { stream.channel.position(start) }
            .onFailure { runCatching { stream.skip(start) } }
        return LimitedInputStream(stream, end - start + 1, pfd)
    }

    /** Stops at [remaining] bytes, and closes the descriptor along with the stream. */
    private class LimitedInputStream(
        private val source: InputStream,
        private var remaining: Long,
        private val alsoClose: java.io.Closeable
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return source.read().also { if (it >= 0) remaining-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()

        override fun close() {
            runCatching { source.close() }
            runCatching { alsoClose.close() }
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
