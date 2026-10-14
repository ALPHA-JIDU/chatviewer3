package com.jihad.chatviewer.importer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ImportRequest(
    val source: ImportSource,
    val chatIds: Set<String>,
    val myName: String
)

object ImportRequestStore {
    private const val FILE_NAME = "import_request.json"

    fun save(context: Context, request: ImportRequest) {
        val o = JSONObject()
        when (val s = request.source) {
            is ImportSource.Zips -> {
                o.put("type", "zip")
                o.put("uris", JSONArray(s.uris.map { it.toString() }))
            }
            is ImportSource.Folder -> {
                o.put("type", "folder")
                o.put("uri", s.uri.toString())
            }
        }
        o.put("chats", JSONArray(request.chatIds.toList()))
        o.put("myName", request.myName)
        File(context.filesDir, FILE_NAME).writeText(o.toString())
    }

    fun load(context: Context): ImportRequest? {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val source: ImportSource = if (o.getString("type") == "zip") {
                val arr = o.getJSONArray("uris")
                ImportSource.Zips((0 until arr.length()).map { Uri.parse(arr.getString(it)) })
            } else {
                ImportSource.Folder(Uri.parse(o.getString("uri")))
            }
            val chats = o.getJSONArray("chats")
            val ids = (0 until chats.length()).map { chats.getString(it) }.toSet()
            ImportRequest(source, ids, o.optString("myName", ""))
        } catch (e: Exception) {
            null
        }
    }
}
