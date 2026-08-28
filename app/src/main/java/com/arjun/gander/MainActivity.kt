package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private sealed interface Row {
        /** Shown on a first run, in place of a Recent files header with nothing under it. */
        data object Welcome : Row
        data class Header(val title: String) : Row
        data class Hint(val text: String) : Row
        data class Item(
            val badge: String,
            val color: Int,
            val title: String,
            val subtitle: String?,
            val onClick: () -> Unit,
            val onLongClick: (() -> Unit)? = null,
            val thumbUri: Uri? = null,
            val thumbExt: String = ""
        ) : Row
    }

    private data class Crumb(val treeUri: Uri, val docId: String, val label: String)

    private val stack = ArrayDeque<Crumb>()
    private val adapter = RowAdapter()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator

    /**
     * Where the rows are built.
     *
     * Reading a granted folder is a query to another app's DocumentsProvider, and so is
     * asking a tree for its display name. Both were done inline in render(), which runs
     * on every resume and every tap, so a folder holding a few thousand files, or a
     * provider on an SD card, a USB stick or a cloud account, froze the home screen and
     * would eventually have shown up as an ANR. Play tracks ANR rate and a bad one
     * suppresses the listing, which makes this the one item on the pre-launch list that
     * could quietly cost reach.
     *
     * Single threaded on purpose: one folder is being looked at at a time, and it keeps
     * treeLabels below confined to one thread without a lock.
     */
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Bumped by every render. A load that finishes after another has been asked for is
     * dropped rather than drawn: tapping through three folders quickly used to be three
     * blocking reads in order, and now it is three racing ones, only the last of which
     * describes where the reader actually is.
     */
    private var renderToken = 0

    /**
     * Display names for granted trees, which cost a query each and never change while
     * the grant lasts. Read and written only on [loader], so it needs no synchronising.
     */
    private val treeLabels = mutableMapOf<String, String>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            stack.removeLast()
            render()
        }
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openInViewer(uri)
        }

    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { backCallback.handleOnBackPressed() }
        // render() rewrites the title and navigation icon on every resume and on
        // every folder change, but never touches the menu, so inflating once here
        // survives all of it.
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_about) {
                showAbout()
                true
            } else {
                false
            }
        }
        progress = findViewById(R.id.loadProgress)
        findViewById<RecyclerView>(R.id.list).let {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }
        findViewById<ExtendedFloatingActionButton>(R.id.openFab).setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun openInViewer(uri: Uri) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    /**
     * The app's only About surface. Carries the version, who made it, and the
     * permission list read back out of Android, plus the way in to the licence
     * text the bundled libraries require to travel with the binary.
     */
    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        view.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, version)

        val permissions = requestedPermissions()
        val field = view.findViewById<TextView>(R.id.aboutPermissions)
        when {
            // Only when the package manager refused to answer. Printing "none"
            // for a question we could not ask would be the one dishonest thing
            // this dialog could do, so it says nothing at all instead.
            permissions == null ->
                view.findViewById<View>(R.id.aboutPermissionsCard).visibility = View.GONE
            permissions.isEmpty() -> field.setText(R.string.about_permissions_none)
            // Never expected: assembleRelease fails before a build can get here.
            // Shown rather than swallowed, because a broken promise is the thing
            // a reader of this dialog most needs to know.
            else -> {
                field.text = permissions.joinToString("\n")
                field.setTextColor(
                    MaterialColors.getColor(field, com.google.android.material.R.attr.colorError)
                )
            }
        }

        view.findViewById<View>(R.id.aboutAuthor)
            .setOnClickListener { openUrl(getString(R.string.url_author)) }
        view.findViewById<View>(R.id.aboutSource)
            .setOnClickListener { openUrl(getString(R.string.url_source)) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_gander)
            .setView(view)
            .setPositiveButton(R.string.about_close, null)
            .show()

        view.findViewById<View>(R.id.aboutLicences).setOnClickListener {
            dialog.dismiss()
            openLicences()
        }
    }

    /**
     * What Android says this install asks for, or null if it would not say.
     *
     * androidx.core declares DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION under our
     * own package name so libraries can registerReceiver safely. It is signature
     * level, self-granted and never shown to a user, which is why the permission
     * check in build.gradle.kts allowlists it as well. Anything else carrying our
     * package prefix is ours on the same reasoning, so drop those and report what
     * is left, which is the list Android would actually confront someone with.
     */
    private fun requestedPermissions(): List<String>? = runCatching {
        packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .filterNot { it.startsWith("$packageName.") }
    }.getOrNull()

    /**
     * Gander shows its own licences. The asset is copied into the cache and
     * handed to the viewer as a plain path, so the bundled Markdown renderer
     * draws it and there is no second document surface to keep alive.
     *
     * Copied on every open rather than once: the cache outlives an app update,
     * and an update is exactly when the text changes. The viewer only records
     * content:// URIs in Recents, so this cannot turn up there.
     */
    private fun openLicences() {
        val file = File(cacheDir, getString(R.string.licences_file_name))
        val opened = runCatching {
            assets.open(LICENCES_ASSET).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
            )
        }.isSuccess
        if (!opened) Toast.makeText(this, R.string.licences_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Hands a URL to whichever browser the user has. Gander never fetches
     * anything itself, and without the INTERNET permission it could not.
     */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Redraws the screen for wherever the reader is now.
     *
     * The toolbar changes at once and the list follows, because the title is known here
     * and the rows are not: they come from a provider that may be slow. Nothing is
     * cleared in the meantime, so returning from a document leaves the old list on
     * screen until the new one is ready rather than blinking through empty.
     */
    private fun render() {
        val here = stack.lastOrNull()
        backCallback.isEnabled = here != null
        toolbar.title = here?.label ?: getString(R.string.app_name)
        toolbar.navigationIcon =
            if (here == null) null
            else androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_back)
        toolbar.navigationContentDescription = getString(R.string.back)

        val token = ++renderToken
        // Delayed rather than shown at once. Most folders come back in a few
        // milliseconds, and a bar that appears and vanishes inside one frame reads as a
        // flicker rather than as progress.
        val announce = Runnable {
            if (token == renderToken && !isDestroyed) progress.visibility = View.VISIBLE
        }
        main.postDelayed(announce, RENDER_PROGRESS_DELAY_MS)

        loader.execute {
            val rows = if (here == null) homeRows() else folderRows(here)
            main.post {
                main.removeCallbacks(announce)
                if (token != renderToken || isDestroyed) return@post
                progress.visibility = View.GONE
                adapter.submit(rows)
            }
        }
    }

    private fun homeRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val recents = Recents.all(this)
        // Labelled first, then sorted. sortedBy runs its selector on every comparison,
        // so naming the tree inside it cost a provider query per comparison rather than
        // one per folder.
        val roots = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && isTreeUri(it.uri) }
            .map { it to treeLabel(it.uri) }
            .sortedBy { (_, label) -> label.lowercase() }

        // Nothing opened and nothing granted is a first run, and a first run gets told
        // what this is rather than being shown two empty headings. Once either has
        // happened the reader knows, and the ordinary list comes back for good.
        if (recents.isEmpty() && roots.isEmpty()) {
            rows += Row.Welcome
        } else {
            rows += Row.Header(getString(R.string.recent_files))
            if (recents.isEmpty()) {
                rows += Row.Hint(getString(R.string.no_recents_hint))
            } else {
            recents.forEach { r ->
                val (badge, color) = badgeFor(r.name, null)
                val ext = r.name.substringAfterLast('.', "").lowercase()
                val uri = Uri.parse(r.uri)
                rows += Row.Item(
                    badge, color, r.name,
                    DateUtils.getRelativeTimeSpanString(r.time).toString(),
                    onClick = { openInViewer(uri) },
                    onLongClick = {
                        Recents.remove(this, r.uri)
                        Thumbs.evict(this, r.uri)
                        Toast.makeText(this, R.string.removed, Toast.LENGTH_SHORT).show()
                        render()
                    },
                    thumbUri = uri.takeIf { Thumbs.supported(FileKind.detect(ext, null), ext) },
                    thumbExt = ext
                )
            }
            }
        }
        rows += Row.Header(getString(R.string.folders))
        if (roots.isEmpty()) rows += Row.Hint(getString(R.string.no_folders_hint))
        roots.forEach { (perm, label) ->
            rows += Row.Item(
                "DIR", DIR_COLOR, label, null,
                onClick = {
                    stack.addLast(
                        Crumb(perm.uri, DocumentsContract.getTreeDocumentId(perm.uri), label)
                    )
                    render()
                },
                onLongClick = {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(
                            perm.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    Toast.makeText(this, R.string.removed, Toast.LENGTH_SHORT).show()
                    render()
                }
            )
        }
        rows += Row.Item("+", ADD_COLOR, getString(R.string.add_folder), null,
            onClick = { openTree.launch(null) })
        return rows
    }

    private fun folderRows(crumb: Crumb): List<Row> {
        data class Child(
            val docId: String, val name: String, val mime: String,
            val size: Long, val modified: Long
        )

        val children = mutableListOf<Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            crumb.treeUri, crumb.docId
        )
        runCatching {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    children += Child(
                        c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "",
                        c.getLong(3), c.getLong(4)
                    )
                }
            }
        }

        val dirs = children
            .filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
            .filterNot { it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val files = children
            .filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
            .filterNot { it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }

        val rows = mutableListOf<Row>()
        dirs.forEach { d ->
            rows += Row.Item("DIR", DIR_COLOR, d.name, null, onClick = {
                stack.addLast(Crumb(crumb.treeUri, d.docId, d.name))
                render()
            })
        }
        files.forEach { f ->
            val (badge, color) = badgeFor(f.name, f.mime)
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(crumb.treeUri, f.docId)
            val subtitle = listOfNotNull(
                Formatter.formatShortFileSize(this, f.size).takeIf { f.size > 0 },
                DateUtils.getRelativeTimeSpanString(f.modified).toString()
                    .takeIf { f.modified > 0 }
            ).joinToString(" · ").ifEmpty { null }
            rows += Row.Item(
                badge, color, f.name, subtitle,
                onClick = { openInViewer(fileUri) },
                thumbUri = fileUri.takeIf {
                    Thumbs.supported(FileKind.detect(ext, f.mime), ext)
                },
                thumbExt = ext
            )
        }
        if (rows.isEmpty()) rows += Row.Hint(getString(R.string.empty_folder))
        return rows
    }

    private fun isTreeUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.isSuccess &&
            uri.pathSegments.firstOrNull() == "tree"

    /** Cached: the name of a granted tree costs a query and does not change. */
    private fun treeLabel(uri: Uri): String =
        treeLabels.getOrPut(uri.toString()) { readTreeLabel(uri) }

    private fun readTreeLabel(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return "Folder"
        val name = runCatching {
            contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(uri, id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return name ?: id.substringAfterLast(':').ifEmpty { id }
    }

    /**
     * The two or three letters, and the colour behind them, for a file.
     *
     * Four of these moved when the app adopted the brand's terracotta primary. PPT sat
     * about four degrees of hue from the new accent, so a PowerPoint tile and the app's
     * own accent would have read as one signal; it moved to 16 degrees off. The other
     * three moved because they were already failing WCAG AA against their own white
     * label, which is what Play's pre-launch accessibility scan looks for: DIR was the
     * worst thing in the app at 1.97:1, FILE at 3.35 and PPT at 3.92, against the 4.5
     * that 12sp bold needs. They now measure 4.90, 4.65 and 5.20.
     *
     * PDF moved too, from 4.98 to 6.54. It was already passing, and it sits close to the
     * accent, but Thumbs draws a real first page over it whenever it can, so the tile is
     * mostly a placeholder. Mostly: a document that will not render, an encrypted one
     * above all, falls back to this badge and keeps it.
     *
     * The rest are untouched. Every one of them clears AA and sits at least 82 degrees
     * of hue away from the accent.
     */
    private fun badgeFor(name: String, mime: String?): Pair<String, Int> {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (FileKind.detect(ext, mime)) {
            FileKind.PDF -> "PDF" to 0xFFB3261E.toInt()
            FileKind.DOCX -> "DOC" to 0xFF1565C0.toInt()
            FileKind.XLSX -> "XLS" to 0xFF2E7D32.toInt()
            FileKind.PPTX -> "PPT" to 0xFFB25000.toInt()
            FileKind.IMAGE, FileKind.IMAGE_WEB -> "IMG" to 0xFF7B1FA2.toInt()
            FileKind.PLAYER ->
                if (FileKind.isAudioExt(ext)) "AUD" to 0xFF00838F.toInt()
                else "VID" to 0xFFAD1457.toInt()
            FileKind.MD -> "MD" to 0xFF455A64.toInt()
            FileKind.TEXT -> "TXT" to 0xFF616161.toInt()
            FileKind.UNSUPPORTED -> "FILE" to 0xFF607884.toInt()
        }
    }

    override fun onDestroy() {
        // Anything already queued still runs to completion and the thread ends with it,
        // rather than outliving the activity it was drawing.
        loader.shutdown()
        super.onDestroy()
    }

    private companion object {
        val DIR_COLOR = 0xFF8A6D1F.toInt()

        /**
         * The brand accent, and the one badge that is an action rather than a file type.
         *
         * Fixed rather than ?attr/colorPrimary, which is what it looks like it should be.
         * Material inverts primary for dark mode, to #FFB39E, and the label on every
         * badge is a hardcoded white in row_item.xml: a white "+" on that measured
         * 1.72:1, worse than the amber DIR badge this release exists partly to fix.
         * Pinned to the light tone it stays 6.54:1 in both themes, and against the night
         * surface it sits at 2.83 against the DOC badge's 3.22, so it reads as a shape
         * exactly like its neighbours.
         */
        val ADD_COLOR = 0xFFAF2D18.toInt()
        const val LICENCES_ASSET = "licences.md"

        /** How long a folder may take to read before the screen says anything about it. */
        const val RENDER_PROGRESS_DELAY_MS = 150L
    }

    private class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Hint -> 1
            is Row.Item -> 2
            is Row.Welcome -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                0 -> R.layout.row_header
                1 -> R.layout.row_hint
                3 -> R.layout.row_welcome
                else -> R.layout.row_item
            }
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                // Every word of it is in the layout.
                is Row.Welcome -> Unit
                is Row.Header ->
                    holder.itemView.findViewById<TextView>(R.id.headerText).text = row.title
                is Row.Hint ->
                    holder.itemView.findViewById<TextView>(R.id.hintText).text = row.text
                is Row.Item -> {
                    val badge = holder.itemView.findViewById<TextView>(R.id.badge)
                    val thumb = holder.itemView.findViewById<ImageView>(R.id.thumb)
                    badge.text = row.badge
                    badge.background.mutate().setTint(row.color)
                    badge.visibility = View.VISIBLE
                    thumb.visibility = View.GONE
                    thumb.setImageDrawable(null)
                    thumb.tag = null
                    if (row.thumbUri != null) {
                        Thumbs.load(
                            holder.itemView.context, row.thumbUri, row.thumbExt, thumb, badge
                        )
                    }
                    holder.itemView.findViewById<TextView>(R.id.title).text = row.title
                    val sub = holder.itemView.findViewById<TextView>(R.id.subtitle)
                    sub.text = row.subtitle
                    sub.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                    // The row children are not-important for accessibility, so this is
                    // the whole announcement. Keeping the badge in it matters: the badge
                    // is hidden once a thumbnail loads, and the file type would go with it
                    holder.itemView.contentDescription =
                        listOfNotNull(row.title, row.badge, row.subtitle).joinToString(", ")
                    holder.itemView.setOnClickListener { row.onClick() }
                    holder.itemView.setOnLongClickListener {
                        row.onLongClick?.invoke()
                        row.onLongClick != null
                    }
                }
            }
        }
    }
}
