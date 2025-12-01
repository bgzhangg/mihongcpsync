package eu.kanade.tachiyomi.data.sync

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.model.ChapterUpdate

object SyncManager {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val syncPreferences: SyncPreferences = Injekt.get()
    private val pendingUpdates = mutableMapOf<String, MutableSet<Float>>()
    private var debounceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun pushProgressBulk(mangaTitle: String, chapterNumbers: List<Float>, mangaSource: String) {
        scope.launch {
            val mangaId = "${mangaSource}_${mangaTitle}".replace(" ", "_").lowercase()

            synchronized(pendingUpdates) {
                val currentSet = pendingUpdates.getOrPut(mangaId) { mutableSetOf() }
                currentSet.addAll(chapterNumbers)
            }

            debounceJob?.cancel()
            debounceJob = launch {
                delay(2000)
                flushPendingUpdates(mangaTitle, mangaSource)
            }
        }
    }

    fun pushProgress(mangaTitle: String, chapterNumber: Float, mangaSource: String) {
        pushProgressBulk(mangaTitle, listOf(chapterNumber), mangaSource)
    }

    private fun flushPendingUpdates(title: String, source: String) {
        val syncUrl = syncPreferences.syncUrl().get()
        val syncSecret = syncPreferences.syncSecret().get()
        val updatesToSend = mutableMapOf<String, Set<Float>>()

        synchronized(pendingUpdates) {
            updatesToSend.putAll(pendingUpdates)
            pendingUpdates.clear()
        }

        updatesToSend.forEach { (mangaId, chapters) ->
            try {
                val sortedChapters = chapters.toList().sorted()
                val rangeString = compressToRangeString(sortedChapters)

                val jsonObject = JSONObject()
                jsonObject.put("manga_id", mangaId)
                jsonObject.put("title", title)
                jsonObject.put("chapters_ranges", rangeString)

                if (sortedChapters.isNotEmpty()) {
                    jsonObject.put("chapter", sortedChapters.last())
                }

                val body = jsonObject.toString().toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url(syncUrl)
                    .addHeader("X-Mihon-Token", syncSecret)
                    .post(body)
                    .build()

                client.newCall(request).execute().close()
                Log.d("MihonSync", "Enviado Rango Optimizado: $rangeString para $title")

            } catch (e: Exception) {
                Log.e("MihonSync", "Error enviando: ${e.message}")
            }
        }
    }

    private fun compressToRangeString(sortedNums: List<Float>): String {
        if (sortedNums.isEmpty()) return ""
        val ranges = mutableListOf<String>()
        var start = sortedNums[0]
        var prev = sortedNums[0]

        for (i in 1 until sortedNums.size) {
            val num = sortedNums[i]
            val isConsecutive = (num - prev - 1.0f) < 0.01f &&
                (num % 1.0f == 0.0f) && (prev % 1.0f == 0.0f)

            if (isConsecutive) {
                prev = num
            } else {
                ranges.add(formatRange(start, prev))
                start = num
                prev = num
            }
        }
        ranges.add(formatRange(start, prev))
        return ranges.joinToString(", ")
    }

    private fun formatRange(start: Float, end: Float): String {
        val s = if (start % 1.0f == 0.0f) start.toInt().toString() else start.toString()
        val e = if (end % 1.0f == 0.0f) end.toInt().toString() else end.toString()
        return if (s == e) s else "$s-$e"
    }
    suspend fun pullAndApplyChanges() {
        val syncUrl = syncPreferences.syncUrl().get()
        val syncSecret = syncPreferences.syncSecret().get()
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(syncUrl).addHeader("X-Mihon-Token", syncSecret).get().build()
                val response = client.newCall(request).execute()
                val jsonStr = response.body?.string() ?: return@withContext
                val jsonResponse = JSONObject(jsonStr)
                val mangaRepo = Injekt.get<MangaRepository>()
                val chapterRepo = Injekt.get<ChapterRepository>()
                val localLibrary = mangaRepo.getLibraryManga()
                val iterator = jsonResponse.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val data = jsonResponse.getJSONObject(key)
                    val cloudTitle = data.optString("title")

                    val rangesList = mutableListOf<Pair<Float, Float>>()
                    val singleChapters = mutableSetOf<Float>()

                    val rangesStr = data.optString("chapters_ranges", "")

                    if (rangesStr.isNotEmpty()) {
                        rangesStr.split(",").forEach { part ->
                            val cleanPart = part.trim()
                            if (cleanPart.contains("-")) {
                                val r = cleanPart.split("-")
                                val start = r[0].toFloatOrNull()
                                val end = r[1].toFloatOrNull()

                                if (start != null && end != null) {
                                    rangesList.add(start to end)
                                }
                            } else {
                                cleanPart.toFloatOrNull()?.let { singleChapters.add(it) }
                            }
                        }
                    }

                    val jsonArray = data.optJSONArray("chapters_read")
                    if (jsonArray != null) {
                        for (i in 0 until jsonArray.length()) singleChapters.add(jsonArray.getDouble(i).toFloat())
                    }

                    if (cloudTitle.isEmpty() || (rangesList.isEmpty() && singleChapters.isEmpty())) continue

                    val localManga = localLibrary.find { it.manga.title == cloudTitle } ?: continue
                    val localChapters = chapterRepo.getChapterByMangaId(localManga.manga.id)

                    val chaptersToMark = localChapters.filter { ch ->
                        if (ch.read) return@filter false

                        val num = ch.chapterNumber.toFloat()
                        val isInSingles = singleChapters.any { kotlin.math.abs(num - it) < 0.001 }
                        val isInRanges = rangesList.any { (min, max) -> num >= min && num <= max }

                        isInSingles || isInRanges
                    }

                    if (chaptersToMark.isNotEmpty()) {
                        chapterRepo.updateAll(chaptersToMark.map { ChapterUpdate(id = it.id, read = true, lastPageRead = 0) })
                        Log.d("MihonSync", "Sync Pull: ${chaptersToMark.size} caps para $cloudTitle")
                    }
                }
            } catch (e: Exception) {
                Log.e("MihonSync", "Error Pull: ${e.message}")
            }
        }
    }
}
