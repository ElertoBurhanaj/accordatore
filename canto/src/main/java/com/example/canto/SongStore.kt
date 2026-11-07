package com.example.canto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Song(
    val id: String,
    val title: String,
    val instrumentalPath: String,
    val vocalPath: String,
    val pitchPath: String,
    val durationMs: Int
)

/** Archivio delle canzoni importate, salvato nella memoria privata dell'app. */
class SongStore(context: Context) {

    private val root = context.filesDir
    private val indexFile = File(root, "library.json")

    fun songDir(id: String): File = File(File(root, "songs"), id)

    fun load(): MutableList<Song> {
        val list = mutableListOf<Song>()
        if (!indexFile.exists()) return list
        try {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Song(
                        o.getString("id"),
                        o.getString("title"),
                        o.getString("instrumentalPath"),
                        o.getString("vocalPath"),
                        o.getString("pitchPath"),
                        o.getInt("durationMs")
                    )
                )
            }
        } catch (e: Exception) {
            // indice illeggibile: riparte vuoto
        }
        return list
    }

    private fun save(list: List<Song>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("instrumentalPath", s.instrumentalPath)
                    .put("vocalPath", s.vocalPath)
                    .put("pitchPath", s.pitchPath)
                    .put("durationMs", s.durationMs)
            )
        }
        indexFile.writeText(arr.toString())
    }

    fun add(song: Song) {
        val list = load()
        list.add(song)
        save(list)
    }

    fun delete(song: Song) {
        val list = load()
        list.removeAll { it.id == song.id }
        save(list)
        songDir(song.id).deleteRecursively()
    }
}
