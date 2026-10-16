package com.jihad.chatviewer.importer

import android.content.Context
import java.io.File

object MediaFiles {
    fun root(context: Context): File = File(context.filesDir, "export")

    /** path looks like "messages/inbox/name_123/photos/a.jpg" (as stored in the database) */
    fun resolve(context: Context, path: String): File? {
        val rootDir = root(context)
        val f = File(rootDir, path)
        return try {
            if (f.canonicalPath.startsWith(rootDir.canonicalPath + File.separator)) f else null
        } catch (e: Exception) {
            null
        }
    }
}
