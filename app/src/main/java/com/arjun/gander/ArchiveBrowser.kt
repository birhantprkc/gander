package com.arjun.gander

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A zip's flat list of paths, as folders.
 *
 * A zip need not list its folders at all, only the files in them, so every folder on the way
 * to a file exists here whether the archive has an entry for it or not. macOS puts a
 * __MACOSX folder of resource forks into every zip it makes, and that is left out, the way
 * orderChildren already leaves out dotfiles: neither is anything a person put there.
 */
internal class ArchiveTree(private val entries: List<ArchiveEntry>) {

    /** Folder path, "" for the top, to the names of the folders in it. */
    private val folders = HashMap<String, MutableSet<String>>()

    /** Folder path to the entries of the files in it, as indexes into [entries]. */
    private val files = HashMap<String, MutableList<Int>>()

    init {
        folders[""] = LinkedHashSet()
        entries.forEachIndexed { i, entry ->
            val parts = entry.path.split('/')
            if (parts.first() == MACOS_FORKS) return@forEachIndexed
            var parent = ""
            val depth = if (entry.isDirectory) parts.size else parts.size - 1
            for (d in 0 until depth) {
                folders.getValue(parent).add(parts[d])
                parent = if (parent.isEmpty()) parts[d] else "$parent/${parts[d]}"
                folders.getOrPut(parent) { LinkedHashSet() }
            }
            if (!entry.isDirectory) files.getOrPut(parent) { ArrayList() }.add(i)
        }
    }

    fun has(folder: String) = folder in folders

    /**
     * The folders and files directly inside [folder], ordered and filtered exactly as the
     * folder browser orders and filters a folder on the phone, by the same function.
     */
    fun list(folder: String): Pair<List<String>, List<ArchiveEntry>> {
        val children = folders[folder].orEmpty().map { ChildDoc(it, it, MIME_DIR, 0, 0) } +
            files[folder].orEmpty().map { i ->
                val entry = entries[i]
                ChildDoc(i.toString(), entry.name, "", entry.size, entry.modified)
            }
        val (dirs, docs) = orderChildren(children)
        return dirs.map { it.name } to docs.map { entries[it.docId.toInt()] }
    }

    private companion object {
        const val MACOS_FORKS = "__MACOSX"
    }
}

/**
 * What is inside a .zip, as a list. Issue #30.
 *
 * Drawn with the home screen's own rows, so a folder inside a zip looks like a folder on the
 * phone: folders first, then files, by name. Back goes up a level and then leaves, and the
 * arrow in the toolbar does the same, as it does on the home screen.
 *
 * Part of the viewer rather than a screen of its own, because a zip arrives the way every
 * other file does, from Open with, the share sheet, Recents or a folder, and a list is what
 * opening one shows. A file tapped here opens in a viewer of its own, served by
 * [ArchiveProvider] straight out of the archive.
 */
internal class ArchiveBrowser(
    private val activity: AppCompatActivity,
    private val toolbar: MaterialToolbar,
    private val progress: LinearProgressIndicator,
    private val archive: Uri,
    private val archiveName: String,
    /** Where the reader was before a configuration change, or null for the top. */
    restoredFolder: String?,
    /** Where the index is read. Its own thread, which ends with the read; tests run it inline. */
    private val loader: Executor = Executors.newSingleThreadExecutor(),
) {

    /** The folder on screen, "" for the top of the archive. Saved across a recreation. */
    var folder: String = restoredFolder.orEmpty()
        private set

    private var tree: ArchiveTree? = null
    private val adapter = RowAdapter()
    private val list = RecyclerView(activity)
    private val main = Handler(Looper.getMainLooper())

    /** Where each folder was scrolled to, so coming back up lands where the reader went in. */
    private val scrolled = HashMap<String, Int>()

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = show(folder.substringBeforeLast('/', ""))
    }

    /** What reading the index came to: the archive's folders, or what to say instead. */
    private sealed interface Listing {
        class Ready(val tree: ArchiveTree) : Listing
        class Failed(val message: Int) : Listing
    }

    fun attach(container: FrameLayout) {
        // One column on a phone and two on a tablet, the same as the home screen, and for the
        // same reason; a message takes the whole width.
        val columns = activity.resources.getInteger(R.integer.home_list_columns)
        list.layoutManager = GridLayoutManager(activity, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (adapter.isFullSpan(position)) columns else 1
            }
        }
        list.adapter = adapter
        container.addView(
            list,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        activity.onBackPressedDispatcher.addCallback(activity, back)
        toolbar.setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        load()
    }

    /**
     * Reads the index off the main thread. It is small and quick to read, but it comes from
     * another app's provider, which may be a slow card or a cloud account fetching the file
     * first, so the bar comes up if it is taking long enough to notice.
     */
    private fun load() {
        // Indeterminate, set before it shows: the bar is the one a save reports on, and it is
        // left determinate at nought, which would draw as an empty track that never moves
        val announce = Runnable {
            progress.isIndeterminate = true
            progress.visibility = View.VISIBLE
        }
        main.postDelayed(announce, PROGRESS_DELAY_MS)
        loader.execute {
            val result = read()
            main.post {
                main.removeCallbacks(announce)
                if (activity.isDestroyed) return@post
                progress.visibility = View.GONE
                when (result) {
                    is Listing.Ready -> {
                        tree = result.tree
                        show(folder.takeIf(result.tree::has).orEmpty())
                    }
                    is Listing.Failed -> {
                        toolbar.title = archiveName
                        adapter.submit(listOf(Row.Hint(activity.getString(result.message))))
                    }
                }
            }
        }
        (loader as? ExecutorService)?.shutdown()
    }

    private fun read(): Listing = try {
        val afd = activity.contentResolver.openAssetFileDescriptor(archive, "r")
        val source = afd?.let(ZipSource::open)
        when {
            afd == null -> Listing.Failed(R.string.archive_unreadable)
            source == null -> {
                afd.close()
                Listing.Failed(R.string.archive_not_seekable)
            }
            else -> source.use { Listing.Ready(ArchiveTree(ZipReader.entries(it))) }
        }
    } catch (_: ZipReader.TooLarge) {
        Listing.Failed(R.string.archive_too_large)
    } catch (_: OutOfMemoryError) {
        // The count limit is generous, and a phone with a small heap can run out below it. This
        // is a background thread, where an error left to escape ends the app rather than the
        // list, and what failed was one large allocation that is garbage the moment it fails.
        Listing.Failed(R.string.archive_too_large)
    } catch (_: Exception) {
        // Damaged, not a zip at all, or no longer readable: a grant can lapse while the list
        // is on screen, and a change of theme reads the archive again
        Listing.Failed(R.string.archive_unreadable)
    }

    private fun show(to: String) {
        val tree = tree ?: return
        scrolled[folder] = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
        folder = to
        back.isEnabled = to.isNotEmpty()
        toolbar.title = if (to.isEmpty()) archiveName else to.substringAfterLast('/')

        val (dirs, files) = tree.list(to)
        val rows = mutableListOf<Row>()
        dirs.forEach { name ->
            val path = if (to.isEmpty()) name else "$to/$name"
            rows += Row.Item("DIR", DIR_COLOR, name, null, onClick = { show(path) })
        }
        files.forEach { rows += fileRow(it) }
        if (rows.isEmpty()) rows += Row.Hint(activity.getString(R.string.empty_folder))
        adapter.submit(rows)
        list.scrollToPosition(scrolled[to]?.coerceAtLeast(0) ?: 0)
    }

    private fun fileRow(entry: ArchiveEntry): Row.Item {
        val (badge, color) = badgeFor(entry.name, null)
        val subtitle = when {
            entry.encrypted -> activity.getString(R.string.entry_locked)
            !entry.readable -> activity.getString(R.string.entry_unsupported)
            else -> listOfNotNull(
                Formatter.formatShortFileSize(activity, entry.size).takeIf { entry.size > 0 },
                DateUtils.getRelativeTimeSpanString(entry.modified).toString()
                    .takeIf { entry.modified > 0 }
            ).joinToString(" · ").ifEmpty { null }
        }
        return Row.Item(badge, color, entry.name, subtitle, onClick = { open(entry) })
    }

    private fun open(entry: ArchiveEntry) {
        if (!entry.readable) {
            val why = if (entry.encrypted) R.string.entry_locked_open else R.string.entry_unsupported_open
            Toast.makeText(activity, why, Toast.LENGTH_SHORT).show()
            return
        }
        activity.startActivity(
            Intent()
                .setClassName(activity, ViewerActivity.ENTRY_VIEWER)
                .setData(ArchiveProvider.uriFor(activity, archive, entry))
        )
    }

    private companion object {
        /** The same wait the home screen gives a folder before saying anything about it. */
        const val PROGRESS_DELAY_MS = 150L
    }
}
