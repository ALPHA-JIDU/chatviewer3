package com.jihad.chatviewer.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

class ExportScanner(
    private val context: Context,
    private val isActive: () -> Boolean,
    private val onProgress: (String) -> Unit
) {
    private val resolver = context.contentResolver
    private val acc = ScanAccumulator()
    private var counter = 0

    private fun checkActive() {
        if (!isActive()) throw CancellationException("Cancelled")
    }

    // ---------- ZIP ----------

    fun scanZips(uris: List<Uri>): ScanResult {
        uris.forEachIndexed { index, uri -> scanOneZip(uri, index + 1, uris.size) }
        return acc.result()
    }

    private fun scanOneZip(uri: Uri, number: Int, total: Int) {
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

                val isJson = rel.size == 3 && MESSAGE_FILE.matches(rel[2])
                if (isJson) {
                    acc.onFile(rel, entry.size) { guard }
                } else {
                    var size = entry.size
                    if (size < 0) size = countBytes(zip)
                    acc.onFile(rel, size) { guard }
                }
            }
        }
    }

    private fun countBytes(input: InputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
        }
        return total
    }

    // ---------- Folder ----------

    private class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    fun scanFolder(treeUri: Uri): ScanResult {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = DocumentFile.fromTreeUri(context, treeUri)?.name ?: ""
        walkForMessages(treeUri, rootId, rootName)
        return acc.result()
    }

    private fun walkForMessages(treeUri: Uri, dirId: String, dirName: String) {
        checkActive()
        if (dirName == "messages") {
            walkMessages(treeUri, dirId, emptyList())
            return
        }
        val children = listChildren(treeUri, dirId)
        val messages = children.firstOrNull { it.isDir && it.name == "messages" }
        if (messages != null) {
            onProgress("Reading messages in ${dirName.ifEmpty { "folder" }}")
            walkMessages(treeUri, messages.docId, emptyList())
        } else {
            for (c in children) {
                if (c.isDir) walkForMessages(treeUri, c.docId, c.name)
            }
        }
    }

    private fun walkMessages(treeUri: Uri, dirId: String, rel: List<String>) {
        checkActive()
        for (c in listChildren(treeUri, dirId)) {
            val path = rel + c.name
            if (c.isDir) {
                walkMessages(treeUri, c.docId, path)
            } else {
                counter++
                if (counter % 200 == 0) onProgress("$counter files checked")
                acc.onFile(path, c.size) { openFile(treeUri, c.docId) }
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
