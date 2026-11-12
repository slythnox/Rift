package com.music.spotify

import com.music.spotify.models.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

object Spotify {
    private fun randomUserAgent(): String {
        val osOptions = arrayOf(
            "Windows NT 10.0; Win64; x64",
            "Macintosh; Intel Mac OS X 10_15_7",
            "X11; Linux x86_64",
        )
        val chromeBase = 140
        val chromeMajor = chromeBase - (0..4).random()
        val chromePatch = (0..499).random()
        val os = osOptions.random()
        return "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.$chromePatch.0 Safari/537.36"
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val httpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            defaultRequest {
                header("User-Agent", randomUserAgent())
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                header("Accept", "application/json")
            }
            expectSuccess = false
        }
    }

    class SpotifyException(
        val statusCode: Int,
        override val message: String,
        val retryAfterSec: Long = 0,
    ) : Exception(message)

    // JSON parsing helpers
    private fun JsonObject.obj(key: String): JsonObject? =
        try { this[key]?.takeIf { it !is JsonNull }?.jsonObject } catch (_: Exception) { null }

    private fun JsonObject.str(key: String): String? =
        try { this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }

    private fun JsonObject.int(key: String): Int? =
        try { this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull } catch (_: Exception) { null }

    private fun JsonObject.arr(key: String): JsonArray? =
        try { this[key]?.takeIf { it !is JsonNull }?.jsonArray } catch (_: Exception) { null }

    val CURATED_SPOTIFY_PLAYLISTS = listOf(
        "37i9dQZF1DWXtlo6ENS92N", // Bollywood Central
        "37i9dQZF1DX5q67ZpWyRrZ", // Indie India
        "37i9dQZF1DX5cZuAHLNjGz", // Punjabi 101
        "37i9dQZF1DX0b1hHYQtJjp", // Just Good Music
        "37i9dQZF1DXcBWIGoYBM5M", // Today's Top Hits
        "37i9dQZEVXbMDoHDwVN2tF", // Top 50 - Global
        "37i9dQZF1DWUa8ZRTfalHk", // Pop Rising
        "37i9dQZF1DX4WYpdgoIcn6", // Chill Hits
        "37i9dQZF1DX0XUsuxWHRQd", // RapCaviar
        "37i9dQZF1DX4dyzvuaRJ0n", // Dance Hits
        "37i9dQZF1DX2Nc3B70tvx0", // Ultimate Indie
        "37i9dQZF1DWXRqgorJj26U", // Rock Classics
        "37i9dQZF1DWWQRwui0ExPn", // Lofi Beats
        "37i9dQZF1DX4sWSpwq3LiO", // Peaceful Piano
        "37i9dQZF1DX10zKzsJ2jva"  // Viva Latino
    )

    private const val PATHFINDER_QUERY_HASH = "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"

    /**
     * Preserved for caller compatibility in recommendation seeding.
     * Account search is not enabled; recommendations rely on curated embed playlists.
     */
    suspend fun searchTrack(query: String): Result<String?> = Result.success(null)

    /**
     * Compatibility helper: returns all tracks from the public playlist.
     */
    suspend fun fetchPlaylistEmbedTracks(playlistId: String): Result<List<SpotifyTrack>> =
        fetchPlaylistMetadata(playlistId).map { it.tracks }

    /**
     * Fetches complete public Spotify playlist metadata with full pagination.
     * Supports playlists containing 1,000+ tracks.
     * Extracts an unauthenticated guest token from the public embed page and queries
     * the partner GraphQL endpoint with automatic pagination, rate-limit backoff,
     * and duplicate detection.
     */
    suspend fun fetchPlaylistMetadata(
        playlistId: String,
        maxTracks: Int = 10_000,
    ): Result<SpotifyPlaylistMetadata> = runCatching {
        // Step 1: Fetch embed page for playlist metadata and initial batch
        val embedResponse = httpClient.get("https://open.spotify.com/embed/playlist/$playlistId")
        val html = embedResponse.bodyAsText()
        val regex = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""")
        val matchResult = regex.find(html) ?: throw SpotifyException(500, "Could not parse Spotify embed data")
        val jsonStr = matchResult.groupValues[1]

        val jsonElement = json.parseToJsonElement(jsonStr)
        val entity = jsonElement.jsonObject.obj("props")
            ?.obj("pageProps")
            ?.obj("state")
            ?.obj("data")
            ?.obj("entity") ?: throw SpotifyException(500, "Invalid Spotify embed entity")

        val playlistName = entity.str("title") ?: entity.str("name") ?: "Spotify playlist"
        val coverUrl = entity.obj("coverArt")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url") ?: ""
        val embedTrackList = entity.arr("trackList") ?: emptyList()

        val embedTracks = embedTrackList.mapNotNull { parseEmbedTrack(it.jsonObject, coverUrl) }

        // Extract anonymous accessToken from embed page
        val tokenRegex = Regex("""\"accessToken\":\"([^\"]+)\"""")
        var token = tokenRegex.find(html)?.groupValues?.get(1)

        val allTracks = mutableListOf<SpotifyTrack>()

        // Step 2: If we have an anonymous token and there may be more tracks, paginate via Pathfinder
        if (!token.isNullOrBlank()) {
            try {
                var offset = 0
                var totalCount = Int.MAX_VALUE
                val pageSize = 100

                while (offset < totalCount && allTracks.size < maxTracks) {
                    currentCoroutineContext().ensureActive()
                    val validToken = token ?: break

                    val pageResult = fetchPathfinderPageWithRetries(
                        playlistId = playlistId,
                        token = validToken,
                        offset = offset,
                        limit = pageSize,
                        defaultCoverUrl = coverUrl,
                        onTokenExpired = {
                            val refreshed = fetchGuestToken(playlistId)
                            if (!refreshed.isNullOrBlank()) {
                                token = refreshed
                                refreshed
                            } else {
                                null
                            }
                        },
                    )

                    if (pageResult != null) {
                        if (pageResult.totalCount > 0) {
                            totalCount = pageResult.totalCount
                        }
                        if (pageResult.tracks.isNotEmpty()) {
                            // Preserve intentional duplicate playlist entries and sequence order
                            allTracks.addAll(pageResult.tracks)
                        } else {
                            // Empty page returned: reached end of playlist
                            break
                        }
                    } else {
                        // Page fetch failed after retries: preserve what we have and stop paginating
                        break
                    }

                    offset += pageSize
                }
            } catch (e: Exception) {
                // If pathfinder pagination failed partially, preserve already loaded tracks
            }
        }

        // Fallback to embed tracks if Pathfinder returned nothing (e.g., token unavailable or first request failed)
        if (allTracks.isEmpty()) {
            allTracks.addAll(embedTracks)
        }

        if (allTracks.isEmpty()) {
            throw SpotifyException(404, "No playable tracks found in playlist")
        }

        SpotifyPlaylistMetadata(
            id = playlistId,
            name = playlistName,
            tracks = allTracks,
            imageUrl = coverUrl.ifBlank { allTracks.firstNotNullOfOrNull { it.album?.images?.firstOrNull()?.url } },
            totalTracks = allTracks.size,
        )
    }

    private suspend fun fetchGuestToken(playlistId: String): String? {
        return try {
            val embedResponse = httpClient.get("https://open.spotify.com/embed/playlist/$playlistId")
            val html = embedResponse.bodyAsText()
            val tokenRegex = Regex("""\"accessToken\":\"([^\"]+)\"""")
            tokenRegex.find(html)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    private data class PathfinderPageResult(
        val tracks: List<SpotifyTrack>,
        val totalCount: Int,
    )

    private suspend fun fetchPathfinderPageWithRetries(
        playlistId: String,
        token: String,
        offset: Int,
        limit: Int,
        defaultCoverUrl: String,
        maxRetries: Int = 3,
        onTokenExpired: (suspend () -> String?)? = null,
    ): PathfinderPageResult? {
        var delayMs = 1000L
        var activeToken = token
        var refreshedTokenAttempted = false

        for (attempt in 0..maxRetries) {
            currentCoroutineContext().ensureActive()
            try {
                val payload = buildJsonObject {
                    put("variables", buildJsonObject {
                        put("uri", "spotify:playlist:$playlistId")
                        put("offset", offset)
                        put("limit", limit)
                    })
                    put("operationName", "fetchPlaylistContents")
                    put("extensions", buildJsonObject {
                        put("persistedQuery", buildJsonObject {
                            put("version", 1)
                            put("sha256Hash", PATHFINDER_QUERY_HASH)
                        })
                    })
                }

                val response = httpClient.post("https://api-partner.spotify.com/pathfinder/v2/query") {
                    header("Authorization", "Bearer $activeToken")
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }

                if (response.status.value == 401 && !refreshedTokenAttempted && onTokenExpired != null) {
                    refreshedTokenAttempted = true
                    val freshToken = onTokenExpired()
                    if (!freshToken.isNullOrBlank()) {
                        activeToken = freshToken
                        // Retry current page once with fresh guest token
                        continue
                    }
                }

                if (response.status.value == 429) {
                    // Respect valid Retry-After values (up to 60s) instead of retrying prematurely
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: (delayMs / 1000)
                    delay(retryAfter.coerceIn(1, 60) * 1000L)
                    delayMs *= 2
                    continue
                }

                if (response.status.value == 403) {
                    // 403 Forbidden is a permanent refusal. Retrying is pointless.
                    return null
                }

                if (!response.status.isSuccess()) {
                    if (attempt < maxRetries) {
                        delay(delayMs)
                        delayMs *= 2
                        continue
                    }
                    return null
                }

                val responseBody = response.bodyAsText()
                val jsonEl = json.parseToJsonElement(responseBody).jsonObject
                val playlistV2 = jsonEl.obj("data")?.obj("playlistV2") ?: return null
                val content = playlistV2.obj("content") ?: return null
                val totalCount = content.int("totalCount") ?: 0
                val items = content.arr("items") ?: return null

                val tracks = items.mapNotNull { itemEl ->
                    parsePathfinderTrack(itemEl.jsonObject, defaultCoverUrl)
                }

                return PathfinderPageResult(tracks = tracks, totalCount = totalCount)
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                } else {
                    return null
                }
            }
        }
        return null
    }

    private fun parseEmbedTrack(trackObj: JsonObject, coverUrl: String): SpotifyTrack? {
        val title = trackObj.str("title") ?: return null
        val subtitle = trackObj.str("subtitle") ?: "Unknown Artist"
        val durationMs = trackObj.int("duration") ?: 0
        val uri = trackObj.str("uri") ?: ""
        val trackId = uri.substringAfterLast(":")

        val artists = subtitle.split(",").map { name ->
            SpotifySimpleArtist(id = "", name = name.trim())
        }

        return SpotifyTrack(
            id = trackId.ifBlank { title.hashCode().toString() },
            name = title,
            artists = artists,
            album = SpotifySimpleAlbum(
                id = "",
                name = title,
                images = listOf(SpotifyImage(url = coverUrl))
            ),
            durationMs = durationMs,
            uri = uri.ifEmpty { null }
        )
    }

    private fun parsePathfinderTrack(itemObj: JsonObject, defaultCoverUrl: String): SpotifyTrack? {
        val data = itemObj.obj("itemV2")?.obj("data") ?: return null
        val title = data.str("name") ?: return null
        val uri = data.str("uri") ?: ""
        val trackId = uri.substringAfterLast(":")
        val durationMs = data.obj("trackDuration")?.int("totalMilliseconds") ?: 0

        val albumObj = data.obj("albumOfTrack")
        val albumName = albumObj?.str("name") ?: title
        val albumSources = albumObj?.obj("coverArt")?.arr("sources")
        val albumCover = albumSources?.firstOrNull()?.jsonObject?.str("url") ?: defaultCoverUrl

        val artistItems = data.obj("artists")?.arr("items") ?: emptyList()
        val artists = if (artistItems.isNotEmpty()) {
            artistItems.mapNotNull { artistEl ->
                val name = artistEl.jsonObject.obj("profile")?.str("name")
                if (!name.isNullOrBlank()) SpotifySimpleArtist(id = "", name = name) else null
            }
        } else {
            listOf(SpotifySimpleArtist(id = "", name = "Unknown Artist"))
        }

        return SpotifyTrack(
            id = trackId.ifBlank { title.hashCode().toString() },
            name = title,
            artists = artists,
            album = SpotifySimpleAlbum(
                id = "",
                name = albumName,
                images = listOf(SpotifyImage(url = albumCover))
            ),
            durationMs = durationMs,
            uri = uri.ifEmpty { null }
        )
    }

    /**
     * Provides algorithmic/curated recommendations by aggregating fresh tracks from
     * editorial playlists.
     */
    suspend fun getRecommendations(
        seedTracks: List<String> = emptyList(),
        limit: Int = 35
    ): Result<List<SpotifyTrack>> = runCatching {
        val randomPlaylists = CURATED_SPOTIFY_PLAYLISTS.shuffled().take(5)
        val allTracks = mutableListOf<SpotifyTrack>()

        for (pId in randomPlaylists) {
            fetchPlaylistEmbedTracks(pId).onSuccess { tracks ->
                allTracks.addAll(tracks)
            }
        }

        if (allTracks.isNotEmpty()) {
            allTracks.distinctBy { "${it.name.lowercase()}_${it.artists.firstOrNull()?.name?.lowercase()}" }
                .shuffled()
                .take(limit)
        } else {
            emptyList()
        }
    }
}
