package de.dtm.videoplayer

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Ordner-Ansicht: zeigt nur Unterordner mit Medien und Dateien, die der Player kennt.
 * Antippen einer Datei spielt den Ordner ab dieser Datei als Playlist.
 */
class FolderActivity : AppCompatActivity() {

    private data class Entry(
        val name: String,
        val uri: Uri,
        val docId: String,
        val isDir: Boolean,
        val isAudio: Boolean,
        val size: Long,
        val isDvd: Boolean = false,
        val extra: List<Uri> = emptyList(),
        val sub: String = ""
    )

    private lateinit var treeUri: Uri
    private val stack = ArrayList<Pair<String, String>>() // docId, Anzeigename
    private var entries: List<Entry> = emptyList()
    private var loadId = 0

    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var list: ListView
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        if (data == null) {
            finish()
            return
        }
        treeUri = data
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        stack.add(rootId to (rootId.substringAfterLast(':').ifEmpty { "Speicher" }))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            setPadding(dp(14), dp(14), dp(14), 0)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(iconBtn(R.drawable.ic_back) { goBack() })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = label("", 18f, Pal.TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        infoView = label("", 12f, Pal.MUTED)
        col.addView(titleView)
        col.addView(infoView)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(24))
            selector = ColorDrawable(Color.TRANSPARENT)
            this.adapter = this@FolderActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onEntryClick(pos) }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        load()
    }

    private fun goBack() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            load()
        } else {
            finish()
        }
    }

    private fun onEntryClick(pos: Int) {
        val e = entries[pos]
        if (e.isDir) {
            stack.add(e.docId to e.name)
            load()
            return
        }
        if (e.isDvd) {
            startActivity(
                Intent(this, VlcPlayerActivity::class.java)
                    .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(e.extra))
            )
            return
        }
        val files = entries.filter { !it.isDir && !it.isDvd }
        val start = files.indexOfFirst { it.docId == e.docId }.coerceAtLeast(0)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(files.map { it.uri }))
                .putExtra(PlayerActivity.EXTRA_START, start)
        )
    }

    // ---------------------------------------------------------------- Laden

    private fun load() {
        val id = ++loadId
        val (docId, name) = stack.last()
        titleView.text = name
        infoView.text = "Lade …"
        entries = emptyList()
        adapter.notifyDataSetChanged()
        Thread {
            val result = try {
                listFolder(docId)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || id != loadId) return@runOnUiThread
                entries = result
                adapter.notifyDataSetChanged()
                val files = result.count { !it.isDir && !it.isDvd }
                val dirs = result.count { it.isDir }
                infoView.text = when {
                    result.isEmpty() -> "Hier gibt es nichts Abspielbares"
                    else -> "$files abspielbare Dateien" + if (dirs > 0) " · $dirs Ordner" else ""
                }
            }
        }.start()
    }

    private fun children(docId: String) =
        contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )

    private fun listFolder(docId: String): List<Entry> {
        val out = ArrayList<Entry>()
        children(docId)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                if (isDir) {
                    out.add(Entry(name, uri, id, true, false, 0L))
                } else if (MediaTypes.isPlayable(name, mime)) {
                    out.add(Entry(name, uri, id, false, MediaTypes.isAudio(name, mime), size))
                }
            }
        }
        // Ordner ohne Medien ausblenden
        val filtered = out.filter { !it.isDir || hasMedia(it.docId, 3) }
        val sorted = filtered.sortedWith(
            compareByDescending<Entry> { it.isDir }.thenBy { natural(it.name) }
        )
        val dvd = try {
            findDvd(docId)
        } catch (_: Exception) {
            null
        }
        return if (dvd != null) listOf(dvd) + sorted else sorted
    }

    private val vtsPattern = Regex("(?i)VTS_(\\d+)_(\\d+)\\.VOB")

    /** Erkennt eine DVD-Ordnerstruktur (VIDEO_TS) und bündelt die Dateien des Hauptfilms. */
    private fun findDvd(docId: String): Entry? {
        var dir = docId
        children(docId)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR && name.equals("VIDEO_TS", ignoreCase = true)) {
                    dir = id
                    break
                }
            }
        }
        val sets = HashMap<String, ArrayList<Triple<Int, Uri, Long>>>()
        children(dir)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val m = vtsPattern.matchEntire(name) ?: continue
                val part = m.groupValues[2].toInt()
                if (part == 0) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                sets.getOrPut(m.groupValues[1]) { ArrayList() }.add(Triple(part, uri, size))
            }
        }
        if (sets.isEmpty()) return null
        val best = sets.entries.maxByOrNull { e -> e.value.sumOf { it.third } } ?: return null
        val parts = best.value.sortedBy { it.first }
        val total = parts.sumOf { it.third }
        return Entry(
            "DVD abspielen (Hauptfilm)", parts[0].second, dir, false, false, total,
            isDvd = true,
            extra = parts.map { it.second },
            sub = "VTS_${best.key} · ${parts.size} Dateien · ${formatSize(total)}"
        )
    }

    private fun hasMedia(docId: String, depth: Int): Boolean {
        if (depth <= 0) return false
        val subDirs = ArrayList<String>()
        var found = false
        try {
            children(docId)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) subDirs.add(id)
                    else if (MediaTypes.isPlayable(name, mime)) {
                        found = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (found) return true
        return subDirs.any { hasMedia(it, depth - 1) }
    }

    private fun natural(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("\\d+")) { it.value.padStart(9, '0') }

    private fun formatSize(b: Long): String = when {
        b <= 0 -> ""
        b >= 1L shl 30 -> String.format(Locale.GERMANY, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.GERMANY, "%.0f MB", b / (1L shl 20).toDouble())
        else -> String.format(Locale.GERMANY, "%.0f KB", b / 1024.0)
    }

    // ---------------------------------------------------------------- Liste

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = entries[position]
            val row = LinearLayout(this@FolderActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = withRipple(roundRect(Pal.SURFACE, dp(16).toFloat(), Pal.SURFACE2, dp(1)))
            }
            val icon = TextView(this@FolderActivity).apply {
                text = if (e.isDvd) "📀" else if (e.isDir) "📁" else if (e.isAudio) "🎵" else "🎬"
                textSize = 22f
                gravity = Gravity.CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
            val col = LinearLayout(this@FolderActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(e.name, 15f, Pal.TEXT).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            val sub = if (e.isDvd) e.sub else if (e.isDir) "Ordner" else formatSize(e.size)
            if (sub.isNotEmpty()) col.addView(label(sub, 11f, Pal.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }
    }
}
