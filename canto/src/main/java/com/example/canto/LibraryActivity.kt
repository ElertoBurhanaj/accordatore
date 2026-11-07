package com.example.canto

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class LibraryActivity : Activity() {

    private lateinit var store: SongStore
    private lateinit var adapter: ArrayAdapter<Song>
    private lateinit var emptyText: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SongStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16263A"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
            fitsSystemWindows = true
        }

        root.addView(TextView(this).apply {
            text = "Canto"
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Scegli una canzone, ascolta la base e confronta la tua voce con quella originale. Usa le cuffie per evitare che il microfono senta la base."
            textSize = 14f
            setTextColor(Color.parseColor("#A9BCCB"))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        adapter = object : ArrayAdapter<Song>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val song = getItem(position)!!
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                t1.text = song.title
                t1.setTextColor(Color.WHITE)
                t2.text = "Durata " + formatTime(song.durationMs) + "  ·  tocca lungo per eliminare"
                t2.setTextColor(Color.parseColor("#A9BCCB"))
                return v
            }
        }

        val list = ListView(this).apply {
            this.adapter = this@LibraryActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val song = this@LibraryActivity.adapter.getItem(position)!!
                startActivity(
                    Intent(this@LibraryActivity, PracticeActivity::class.java)
                        .putExtra(PracticeActivity.EXTRA_SONG_ID, song.id)
                )
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val song = this@LibraryActivity.adapter.getItem(position)!!
                AlertDialog.Builder(this@LibraryActivity)
                    .setTitle("Eliminare \"${song.title}\"?")
                    .setPositiveButton("Elimina") { _, _ ->
                        store.delete(song)
                        reload()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(16) })

        emptyText = TextView(this).apply {
            text = "Nessuna canzone. Tocca \"Aggiungi canzone\" e scegli due file audio: la base strumentale e la sola voce originale."
            textSize = 16f
            setTextColor(Color.parseColor("#A9BCCB"))
            gravity = Gravity.CENTER
        }
        root.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(Button(this).apply {
            text = "Aggiungi canzone"
            setOnClickListener { startActivity(Intent(this@LibraryActivity, AddSongActivity::class.java)) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val songs = store.load()
        adapter.clear()
        adapter.addAll(songs)
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        fun formatTime(ms: Int): String {
            val totalSec = ms / 1000
            return String.format("%d:%02d", totalSec / 60, totalSec % 60)
        }
    }
}
