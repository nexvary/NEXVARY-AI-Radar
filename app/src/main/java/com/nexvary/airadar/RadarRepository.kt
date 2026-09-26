package com.nexvary.airadar

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

class RadarRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("radar", Context.MODE_PRIVATE)

    suspend fun fetchAll(): List<RadarProject> = coroutineScope {
        val base = BuildConfig.RADAR_API_BASE.trim().trimEnd('/')
        if (base.isNotBlank()) {
            val server = runCatching { fetchServer(base) }.getOrElse { emptyList() }
            if (server.isNotEmpty()) return@coroutineScope server.sortedByDescending { it.score }
        }
        val github = async { fetchGitHub() }
        val models = async { fetchHuggingFace("models", "Model") }
        val spaces = async { fetchHuggingFace("spaces", "Space") }
        (github.await() + models.await() + spaces.await()).distinctBy { it.url }.sortedByDescending { it.score }
    }

    private suspend fun fetchServer(base: String): List<RadarProject> = withContext(Dispatchers.IO) {
        json.parseToJsonElement(get("$base/projects?limit=250")).jsonArray.mapNotNull { item ->
            val o = item.jsonObject
            val id = o.str("id") ?: return@mapNotNull null
            RadarProject(
                id = id,
                name = o.str("name") ?: id,
                description = o.str("description").orEmpty(),
                url = o.str("url").orEmpty(),
                source = o.str("source") ?: "Radar Server",
                category = o.str("category") ?: "General AI",
                stars = o.int("popularity"),
                publishedAt = o.str("published_at").orEmpty(),
                license = "See source",
                score = o.int("score"),
                isLocalFriendly = o.int("local_friendly") == 1
            )
        }
    }

    fun savedIds(): Set<String> = prefs.getStringSet("saved", emptySet()) ?: emptySet()

    fun toggleSaved(id: String): Set<String> {
        val next = savedIds().toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet("saved", next).apply()
        return next
    }

    private suspend fun fetchGitHub(): List<RadarProject> = withContext(Dispatchers.IO) {
        val since = Instant.now().minus(7, ChronoUnit.DAYS).toString().substring(0, 10)
        val queries = listOf(
            "topic:artificial-intelligence created:>=${since}",
            "topic:llm created:>=${since}",
            "topic:generative-ai created:>=${since}",
            "topic:ai-agents created:>=${since}"
        )
        queries.flatMap { q ->
            runCatching {
                val api = "https://api.github.com/search/repositories?q=" +
                    URLEncoder.encode(q, "UTF-8") + "&sort=stars&order=desc&per_page=25"
                val root = json.parseToJsonElement(get(api)).jsonObject
                root["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                    val o = item.jsonObject
                    val name = o.str("full_name") ?: return@mapNotNull null
                    val desc = o.str("description").orEmpty().ifBlank { "New AI project on GitHub" }
                    val stars = o.int("stargazers_count")
                    val pushed = o.str("pushed_at").orEmpty()
                    val license = o["license"]?.jsonObject?.str("spdx_id") ?: "Unknown"
                    val topics = o["topics"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    val local = localFriendly(name, desc, topics)
                    RadarProject(
                        id = "gh:" + (o["id"]?.jsonPrimitive?.contentOrNull ?: name),
                        name = name,
                        description = desc,
                        url = o.str("html_url") ?: "https://github.com/$name",
                        source = "GitHub",
                        category = categorize(name, desc, topics),
                        stars = stars,
                        publishedAt = o.str("created_at").orEmpty(),
                        license = license,
                        score = score(stars, pushed, local),
                        isLocalFriendly = local
                    )
                }
            }.getOrElse { emptyList() }
        }
    }

    private suspend fun fetchHuggingFace(endpoint: String, type: String): List<RadarProject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val api = "https://huggingface.co/api/$endpoint?sort=lastModified&direction=-1&limit=50"
                json.parseToJsonElement(get(api)).jsonArray.mapNotNull { item ->
                    val o = item.jsonObject
                    val id = o.str("id") ?: return@mapNotNull null
                    val tags = o["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    val likes = o.int("likes")
                    val desc = tags.take(5).joinToString(" • ").ifBlank { "New Hugging Face $type" }
                    val local = localFriendly(id, desc, tags)
                    RadarProject(
                        id = "hf:$endpoint:$id",
                        name = id,
                        description = desc,
                        url = "https://huggingface.co/" + if (endpoint == "spaces") "spaces/$id" else id,
                        source = "Hugging Face",
                        category = if (type == "Model") categorize(id, desc, tags) else "AI Apps",
                        stars = likes,
                        publishedAt = o.str("lastModified").orEmpty(),
                        license = tags.firstOrNull { it.startsWith("license:") }?.substringAfter(":") ?: "Unknown",
                        score = score(likes * 2, o.str("lastModified").orEmpty(), local),
                        isLocalFriendly = local
                    )
                }
            }.getOrElse { emptyList() }
        }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "NEXVARY-AI-Radar/0.1")
        return try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun categorize(name: String, desc: String, tags: List<String>): String {
        val s = (name + " " + desc + " " + tags.joinToString(" ")).lowercase()
        return when {
            listOf("video","diffusion","motion").any { it in s } -> "Video AI"
            listOf("agent","agentic","autonomous").any { it in s } -> "Agents"
            listOf("security","cyber","malware","forensic").any { it in s } -> "Cybersecurity AI"
            listOf("audio","voice","speech","tts").any { it in s } -> "Audio AI"
            listOf("vision","image","ocr").any { it in s } -> "Vision AI"
            listOf("code","coding","developer").any { it in s } -> "Coding AI"
            listOf("llm","language-model","transformer").any { it in s } -> "LLM"
            else -> "General AI"
        }
    }

    private fun localFriendly(name: String, desc: String, tags: List<String>): Boolean {
        val s = (name + " " + desc + " " + tags.joinToString(" ")).lowercase()
        return listOf("gguf","onnx","quantized","local","cpu","edge","mobile","llama.cpp").any { it in s }
    }

    private fun score(popularity: Int, timestamp: String, local: Boolean): Int {
        val freshness = runCatching {
            val hours = ChronoUnit.HOURS.between(Instant.parse(timestamp), Instant.now()).coerceAtLeast(0)
            (45 - (hours / 6).toInt()).coerceIn(0, 45)
        }.getOrDefault(15)
        val momentum = when {
            popularity >= 5000 -> 40
            popularity >= 1000 -> 34
            popularity >= 250 -> 27
            popularity >= 50 -> 20
            popularity >= 10 -> 12
            else -> 5
        }
        return (freshness + momentum + if (local) 15 else 5).coerceIn(0, 100)
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0
}
