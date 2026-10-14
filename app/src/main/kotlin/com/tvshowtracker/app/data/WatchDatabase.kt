package com.tvshowtracker.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class WatchDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracked_shows (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                summary TEXT,
                rating REAL,
                image_url TEXT,
                genres TEXT NOT NULL,
                premiered TEXT,
                ended TEXT,
                show_status TEXT,
                network TEXT,
                runtime INTEGER,
                official_site TEXT,
                url TEXT,
                watch_status TEXT NOT NULL,
                added_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE watched_episodes (
                show_id INTEGER NOT NULL,
                episode_id INTEGER NOT NULL,
                watched INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (show_id, episode_id),
                FOREIGN KEY (show_id) REFERENCES tracked_shows(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveShow(show: TvShow, status: WatchStatus) {
        val values = show.toContentValues(status)
        val updated = writableDatabase.update(
            TABLE_SHOWS,
            values,
            "id = ?",
            arrayOf(show.id.toString()),
        )
        if (updated == 0) {
            values.put("added_at", System.currentTimeMillis())
            writableDatabase.insertOrThrow(TABLE_SHOWS, null, values)
        }
    }

    fun removeShow(showId: Int) {
        writableDatabase.delete(TABLE_SHOWS, "id = ?", arrayOf(showId.toString()))
    }

    fun statusFor(showId: Int): WatchStatus? {
        readableDatabase.query(
            TABLE_SHOWS,
            arrayOf("watch_status"),
            "id = ?",
            arrayOf(showId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return WatchStatus.fromStoredValue(cursor.getString(0))
        }
    }

    fun trackedShows(): List<TrackedShow> {
        val query = """
            SELECT s.*,
                (SELECT COUNT(*) FROM watched_episodes e
                 WHERE e.show_id = s.id AND e.watched = 1) AS watched_count
            FROM tracked_shows s
            ORDER BY s.added_at DESC
        """.trimIndent()
        readableDatabase.rawQuery(query, null).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val status = WatchStatus.fromStoredValue(cursor.getStringOrNull("watch_status"))
                        ?: continue
                    add(
                        TrackedShow(
                            show = cursor.toShow(),
                            status = status,
                            watchedEpisodes = cursor.getInt(cursor.getColumnIndexOrThrow("watched_count")),
                        ),
                    )
                }
            }
        }
    }

    fun watchedEpisodeIds(showId: Int): Set<Int> {
        readableDatabase.query(
            TABLE_EPISODES,
            arrayOf("episode_id"),
            "show_id = ? AND watched = 1",
            arrayOf(showId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            return buildSet {
                while (cursor.moveToNext()) add(cursor.getInt(0))
            }
        }
    }

    fun setEpisodeWatched(showId: Int, episodeId: Int, watched: Boolean) {
        if (watched) {
            val values = ContentValues().apply {
                put("show_id", showId)
                put("episode_id", episodeId)
                put("watched", 1)
            }
            writableDatabase.insertWithOnConflict(
                TABLE_EPISODES,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } else {
            writableDatabase.delete(
                TABLE_EPISODES,
                "show_id = ? AND episode_id = ?",
                arrayOf(showId.toString(), episodeId.toString()),
            )
        }
    }

    private fun TvShow.toContentValues(status: WatchStatus) = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("summary", summary)
        put("rating", rating)
        put("image_url", imageUrl)
        put("genres", JSONArray(genres).toString())
        put("premiered", premiered)
        put("ended", ended)
        put("show_status", this@toContentValues.status)
        put("network", network)
        put("runtime", runtime)
        put("official_site", officialSite)
        put("url", url)
        put("watch_status", status.name)
    }

    private fun Cursor.toShow(): TvShow {
        val genresText = getStringOrNull("genres")
        val genres = runCatching {
            val array = JSONArray(genresText ?: "[]")
            buildList { for (index in 0 until array.length()) add(array.optString(index)) }
        }.getOrDefault(emptyList())

        return TvShow(
            id = getInt(getColumnIndexOrThrow("id")),
            name = getString(getColumnIndexOrThrow("name")),
            summary = getStringOrNull("summary"),
            rating = getDoubleOrNull("rating"),
            imageUrl = getStringOrNull("image_url"),
            genres = genres,
            premiered = getStringOrNull("premiered"),
            ended = getStringOrNull("ended"),
            status = getStringOrNull("show_status"),
            network = getStringOrNull("network"),
            runtime = getIntOrNull("runtime"),
            officialSite = getStringOrNull("official_site"),
            url = getStringOrNull("url"),
        )
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getDoubleOrNull(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private companion object {
        const val DATABASE_NAME = "watchline.db"
        const val DATABASE_VERSION = 1
        const val TABLE_SHOWS = "tracked_shows"
        const val TABLE_EPISODES = "watched_episodes"
    }
}
