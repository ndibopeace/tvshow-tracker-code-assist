package com.tvshowtracker.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TvMazeApi {
    fun popularShows(): List<TvShow> {
        val document = Jsoup.parse(getWebsite("/show"))
        val shows = document.select("div.column[data-key]").mapNotNull { card ->
            val id = card.attr("data-key").toIntOrNull() ?: return@mapNotNull null
            val image = card.selectFirst("figure img")
            val name = card.selectFirst(".title h2 a")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: image?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            TvShow(
                id = id,
                name = name,
                rating = card.selectFirst("[itemprop=ratingValue]")?.text()?.toDoubleOrNull(),
                ratingVotes = card.selectFirst("[itemprop=ratingCount]")?.text()?.toIntOrNull(),
                imageUrl = image?.attr("src")?.takeIf(String::isNotBlank)
                    ?: image?.attr("data-src")?.takeIf(String::isNotBlank),
            )
        }
        if (shows.isEmpty()) {
            throw IOException("TVMaze's popular show list did not contain any shows.")
        }
        return shows
    }

    fun searchShows(query: String): List<TvShow> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val results = JSONArray(get("/search/shows?q=$encodedQuery"))
        return buildList {
            for (index in 0 until results.length()) {
                results.optJSONObject(index)
                    ?.optJSONObject("show")
                    ?.let { add(parseShow(it)) }
            }
        }
    }

    fun trendingToday(date: String): List<TvShow> {
        val episodes = JSONArray(get("/schedule/web?date=$date&country="))
        val uniqueShows = linkedMapOf<Int, TvShow>()
        for (index in 0 until episodes.length()) {
            val show = episodes.optJSONObject(index)?.optJSONObject("show") ?: continue
            val parsed = parseShow(show)
            uniqueShows.putIfAbsent(parsed.id, parsed)
        }
        return uniqueShows.values.toList()
    }

    fun showDetails(showId: Int): TvShow =
        parseShow(JSONObject(get("/shows/$showId")))

    fun episodes(showId: Int): List<TvEpisode> {
        val response = JSONArray(get("/shows/$showId/episodes"))
        return buildList {
            for (index in 0 until response.length()) {
                val episode = response.optJSONObject(index) ?: continue
                val id = episode.optInt("id", -1)
                if (id < 0) continue
                add(
                    TvEpisode(
                        id = id,
                        name = episode.optString("name", "Untitled episode"),
                        season = episode.optIntOrNull("season"),
                        number = episode.optIntOrNull("number"),
                        airDate = episode.optNullableString("airdate"),
                        summary = episode.optNullableString("summary"),
                    ),
                )
            }
        }
    }

    private fun get(path: String): String {
        return request("$BASE_URL$path", "WatchlineAndroid/1.0", "application/json")
    }

    private fun getWebsite(path: String): String =
        request(
            "https://www.tvmaze.com$path",
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36 Watchline/1.0",
            "text/html,application/xhtml+xml",
        )

    private fun request(url: String, userAgent: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", userAgent)
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("TVMaze returned HTTP $responseCode.")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseShow(json: JSONObject): TvShow {
        val image = json.optJSONObject("image")
        val network = json.optJSONObject("network")?.optNullableString("name")
            ?: json.optJSONObject("webChannel")?.optNullableString("name")
        val genresJson = json.optJSONArray("genres") ?: JSONArray()

        return TvShow(
            id = json.optInt("id"),
            name = json.optString("name", "Untitled show"),
            summary = json.optNullableString("summary"),
            rating = json.optJSONObject("rating")?.optDoubleOrNull("average"),
            ratingVotes = json.optIntOrNull("ratingCount"),
            imageUrl = image?.optNullableString("original")
                ?: image?.optNullableString("medium"),
            genres = buildList {
                for (index in 0 until genresJson.length()) {
                    genresJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            },
            premiered = json.optNullableString("premiered"),
            ended = json.optNullableString("ended"),
            status = json.optNullableString("status"),
            network = network,
            runtime = json.optIntOrNull("averageRuntime") ?: json.optIntOrNull("runtime"),
            officialSite = json.optNullableString("officialSite"),
            url = json.optNullableString("url"),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (isNull(key) || !has(key)) return null
        return optInt(key).takeIf { it > 0 }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key) || !has(key)) return null
        return optDouble(key).takeIf { it > 0.0 }
    }

    private companion object {
        const val BASE_URL = "https://api.tvmaze.com"
    }
}
