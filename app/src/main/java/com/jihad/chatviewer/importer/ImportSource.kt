package com.jihad.chatviewer.importer

import android.net.Uri

sealed class ImportSource {
    data class Zips(val uris: List<Uri>) : ImportSource()
    data class Folder(val uri: Uri) : ImportSource()
}
