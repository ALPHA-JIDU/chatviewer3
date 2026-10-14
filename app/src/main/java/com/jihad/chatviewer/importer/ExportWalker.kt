package com.jihad.chatviewer.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

typealias FileVisitor = (rel: List<String>, size: Long, open: () -> InputStream) -> Unit

class ExportWalker(
    private val context: Context,
    private val isActive: () -> Boolean,
    private val onProgress: (String) -> Unit
) {
    private val resolver = context.contentResolver
    private var counter = 0

    private fun checkActive() {
        if (!isActive()) throw CancellationException("Cancelled")
    }

    fun walk(source: ImportSource, visit: FileVisitor) {
        when (source) {
            is ImportSource.Zips -> {
                source.uris.forEachIndexed { i, uri ->
                    walkZip(uri, i + 1, source.uris.size, visit)
                }
            }
            is ImportSource.Folder -> {
                val rootId = DocumentsContract.getTreeDocumentId(source.uri)
                val rootName = DocumentFile.fromTreeUri(context, source.uri)?.name ?: ""
                findMessages(source.uri, rootId, rootName, visit)
            }
        }
    }

    // ---------- ZIP ----------

    private fun walkZip(uri: Uri, number: Int, total: Int, visit: FileVisitor) {
        val raw = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open ZIP $number")
        ZipInputStream(raw.buffered(256 * 1024)).use { zip ->
            val guard = object : FilterInputStream(zip) {
                override fun close() {}
            }
            var files = 0
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val segs = entry.name.split('/').filter { it.isNotEmpty() }
                val mi = segs.indexOf("messages")
                if (mi < 0) continue
                val rel = segs.subList(mi + 1, segs.size)
                files++
                if (files % 200 == 0) onProgress("ZIP $number of $total: $files files checked")
                visit(rel, entry.size, { guard })
            }
        }
    }

    // ---------- Folder ----------

    private class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    private fun findMessages(treeUri: Uri, dirId: String, dirName: String, visit: FileVisitor) {
        checkActive()
        if (dirName == "messages") {
            walkMessages(treeUri, dirId, emptyList(), visit)
            return
        }
        val children = listChildren(treeUri, dirId)
        val messages = children.firstOrNull { it.isDir && it.name == "messages" }
        if (messages != null) {
            onProgress("Reading messages in ${dirName.ifEmpty { "folder" }}")
            walkMessages(treeUri, messages.docId, emptyList(), visit)
        } else {
            for (c in children) {
                if (c.isDir) findMessages(treeUri, c.docId, c.name, visit)
            }
        }
    }

    private fun walkMessages(treeUri: Uri, dirId: String, rel: List<String>, visit: FileVisitor) {
        checkActive()
        for (c in listChildren(treeUri, dirId)) {
            val path = rel + c.name
            if (c.isDir) {
                walkMessages(treeUri, c.docId, path, visit)
            } else {
                counter++
                if (counter % 200 == 0) onProgress("$counter files checked")
                visit(path, c.size, { openFile(treeUri, c.docId) })
            }
        }
    }

    private fun listChildren(treeUri: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = ArrayList<Child>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: ""
                out.add(
                    Child(
                        docId = c.getString(0),
                        name = c.getString(1) ?: "",
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (c.isNull(3)) 0L else c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    private fun openFile(treeUri: Uri, docId: String): InputStream {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
    }
}
