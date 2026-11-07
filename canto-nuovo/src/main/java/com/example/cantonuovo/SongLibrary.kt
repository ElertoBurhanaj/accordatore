package com.example.cantonuovo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Song(
    val id: String,
    val title: String,
    val basePath: String,
    val vocalPath: String,
    val trackPath: String,
    val durationMs: Int
)

class SongLibrary(context: Context) {
    private val appFiles = context.filesDir
    private val songsFolder = File(appFiles, "songs")
    private val indexFile = File(appFiles, "songs.json")

    fun newSongId(): String = UUID.randomUUID().toString()

    fun songFolder(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Identificativo brano non valido" }
        return File(songsFolder, id)
    }

    fun load(): List<Song> {
        if (!indexFile.exists()) return emptyList()
        val index = JSONArray(indexFile.readText())
        return List(index.length()) { position ->
            val item = index.getJSONObject(position)
            Song(
                item.getString("id"),
                item.getString("title"),
                storedFile(item.getString("basePath")),
                storedFile(item.getString("vocalPath")),
                storedFile(item.getString("trackPath")),
                item.getInt("durationMs").also { require(it > 0) }
            )
        }
    }

    fun add(song: Song) {
        val songs = load().filterNot { it.id == song.id } + song
        val index = JSONArray()
        songs.forEach { item ->
            index.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("basePath", relativePath(item.basePath))
                    .put("vocalPath", relativePath(item.vocalPath))
                    .put("trackPath", relativePath(item.trackPath))
                    .put("durationMs", item.durationMs)
            )
        }
        val temporary = File(appFiles, "songs.json.tmp")
        temporary.writeText(index.toString())
        if (!temporary.renameTo(indexFile)) {
            temporary.delete()
            error("Impossibile salvare la libreria")
        }
    }

    fun delete(song: Song) {
        val remaining = load().filterNot { it.id == song.id }
        val index = JSONArray()
        remaining.forEach { item ->
            index.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("basePath", relativePath(item.basePath))
                    .put("vocalPath", relativePath(item.vocalPath))
                    .put("trackPath", relativePath(item.trackPath))
                    .put("durationMs", item.durationMs)
            )
        }
        val temporary = File(appFiles, "songs.json.tmp")
        temporary.writeText(index.toString())
        check(temporary.renameTo(indexFile)) { "Impossibile aggiornare la libreria" }
        songFolder(song.id).deleteRecursively()
    }

    private fun relativePath(path: String): String {
        val file = File(path).canonicalFile
        val appRoot = appFiles.canonicalPath + File.separator
        val songsRoot = appRoot + "songs" + File.separator
        require(file.path.startsWith(songsRoot)) {
            "File del brano non presente nella cartella dell'app"
        }
        return file.path.substring(appRoot.length)
    }

    private fun storedFile(relativePath: String): String {
        val file = File(appFiles, relativePath).canonicalFile
        val songsRoot = songsFolder.canonicalPath + File.separator
        require(file.path.startsWith(songsRoot)) {
            "Percorso file del brano non valido"
        }
        require(file.isFile) { "File audio o analisi non trovato: ${file.name}" }
        return file.path
    }
}
