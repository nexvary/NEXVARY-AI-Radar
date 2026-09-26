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
                purpose = o.str("purpose").orEmpty(),
                purposeAr = o.str("purpose_ar").orEmpty(),
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
                        purpose = desc,
                        purposeAr = purposeArabic(categorize(name, desc, topics), desc, null),
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
                    val pipeline = o.str("pipeline_tag")
                    val desc = tags.take(5).joinToString(" • ").ifBlank { "New Hugging Face $type" }
                    val local = localFriendly(id, desc, tags)
                    val cat = if (type == "Model") categorize(id, desc, tags) else "AI Apps"
                    RadarProject(
                        id = "hf:$endpoint:$id",
                        name = id,
                        description = desc,
                        purpose = purposeEnglish(cat, desc, pipeline, type),
                        purposeAr = purposeArabic(cat, desc, pipeline),
                        url = "https://huggingface.co/" + if (endpoint == "spaces") "spaces/$id" else id,
                        source = "Hugging Face",
                        category = cat,
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

    private fun purposeEnglish(category: String, description: String, pipeline: String?, type: String): String {
        val specific = when (pipeline?.lowercase()) {
            "text-generation" -> "Generates, completes, or rewrites text from a prompt."
            "text2text-generation" -> "Transforms input text into new text, such as summarization, rewriting, or translation."
            "text-to-image" -> "Generates images from written prompts."
            "image-to-image" -> "Transforms or edits an input image using AI."
            "image-to-text" -> "Analyzes an image and produces a text description or answer."
            "image-classification" -> "Classifies images into categories."
            "object-detection" -> "Detects and locates objects inside images."
            "automatic-speech-recognition" -> "Converts spoken audio into text."
            "text-to-speech" -> "Converts written text into synthetic speech."
            "audio-classification" -> "Classifies or recognizes content in audio."
            "sentence-similarity" -> "Measures semantic similarity between pieces of text."
            "feature-extraction" -> "Converts input data into embeddings/features for search, clustering, or downstream AI tasks."
            "question-answering" -> "Answers questions from provided text or context."
            "summarization" -> "Creates shorter summaries of longer text."
            "translation" -> "Translates text between languages."
            else -> null
        }
        if (specific != null) return specific
        if (type == "Space") return "Interactive AI application hosted on Hugging Face Spaces. " + description.take(180)
        return when (category) {
            "Video AI" -> "AI project for generating, understanding, or processing video. " + description.take(180)
            "Agents" -> "AI agent project designed to automate tasks or act semi-autonomously. " + description.take(180)
            "Cybersecurity AI" -> "AI project for cybersecurity, analysis, detection, or digital investigation. " + description.take(180)
            "Audio AI" -> "AI project for speech, voice, or audio processing. " + description.take(180)
            "Vision AI" -> "AI project for image understanding, generation, OCR, or computer vision. " + description.take(180)
            "Coding AI" -> "AI project for programming, code generation, or developer assistance. " + description.take(180)
            "LLM" -> "Large-language-model project for understanding or generating text. " + description.take(180)
            else -> description.ifBlank { "General artificial-intelligence project." }
        }
    }

    private fun purposeArabic(category: String, description: String, pipeline: String?): String {
        val specific = when (pipeline?.lowercase()) {
            "text-generation" -> "نموذج لتوليد النصوص أو إكمالها أو إعادة صياغتها انطلاقًا من تعليمات المستخدم."
            "text2text-generation" -> "نموذج يحوّل النص إلى نص آخر، مثل التلخيص أو إعادة الصياغة أو الترجمة."
            "text-to-image" -> "نموذج لإنشاء الصور من الأوامر والوصف النصي."
            "image-to-image" -> "نموذج لتعديل الصور أو تحويلها بالذكاء الاصطناعي."
            "image-to-text" -> "نموذج لتحليل الصور وتحويل محتواها إلى وصف أو إجابة نصية."
            "image-classification" -> "نموذج لتصنيف الصور والتعرف على نوع محتواها."
            "object-detection" -> "نموذج لاكتشاف الأجسام داخل الصور وتحديد مواقعها."
            "automatic-speech-recognition" -> "نموذج لتحويل الكلام والتسجيلات الصوتية إلى نص."
            "text-to-speech" -> "نموذج لتحويل النص المكتوب إلى صوت اصطناعي."
            "audio-classification" -> "نموذج للتعرف على محتوى الصوت وتصنيفه."
            "sentence-similarity" -> "نموذج لقياس التشابه في المعنى بين النصوص."
            "feature-extraction" -> "نموذج لاستخراج تمثيلات وميزات رقمية تستخدم في البحث والتصنيف وتطبيقات الذكاء الاصطناعي."
            "question-answering" -> "نموذج للإجابة عن الأسئلة اعتمادًا على نص أو سياق مقدم."
            "summarization" -> "نموذج لتلخيص النصوص الطويلة إلى خلاصة أقصر."
            "translation" -> "نموذج لترجمة النصوص بين اللغات."
            else -> null
        }
        if (specific != null) return specific
        val prefix = when (category) {
            "Video AI" -> "مشروع ذكاء اصطناعي لإنشاء الفيديو أو فهمه أو معالجته."
            "Agents" -> "مشروع وكلاء ذكاء اصطناعي لأتمتة المهام وتنفيذها بصورة شبه مستقلة."
            "Cybersecurity AI" -> "مشروع يستخدم الذكاء الاصطناعي في الأمن السيبراني أو التحليل أو الكشف أو التحقيق الرقمي."
            "Audio AI" -> "مشروع لمعالجة الصوت أو الكلام أو الأصوات بالذكاء الاصطناعي."
            "Vision AI" -> "مشروع للرؤية الحاسوبية أو فهم الصور أو توليدها أو OCR."
            "Coding AI" -> "مشروع لمساعدة المطورين أو توليد وتحليل الشفرة البرمجية."
            "LLM" -> "مشروع نموذج لغوي كبير لفهم النصوص أو توليدها."
            "AI Apps" -> "تطبيق ذكاء اصطناعي تفاعلي جاهز للتجربة."
            else -> "مشروع جديد في مجال الذكاء الاصطناعي."
        }
        return prefix + " الوصف الأصلي: " + description.take(180)
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
