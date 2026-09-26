package com.nexvary.airadar

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.work.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        scheduleRadar()
        setContent { RadarApp { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
    }

    private fun scheduleRadar() {
        val req = PeriodicWorkRequestBuilder<RadarWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("ai-radar", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarApp(openUrl: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { RadarRepository(context) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<RadarProject>>(emptyList()) }
    var saved by remember { mutableStateOf(repo.savedIds()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { repo.fetchAll() }
                .onSuccess { projects = it }
                .onFailure { error = it.message ?: "Unable to refresh" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val categories = listOf("All","Agents","LLM","Video AI","Vision AI","Audio AI","Coding AI","Cybersecurity AI","AI Apps")
    val isArabic = remember { java.util.Locale.getDefault().language == "ar" }
    val filtered = projects.filter {
        (category == "All" || it.category == category) &&
        (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true))
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("NEXVARY AI Radar"); Text("${projects.size} discoveries", style = MaterialTheme.typography.labelSmall) } },
                    actions = {
                        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = { openUrl("https://nexvary.com/") }) { Icon(Icons.Default.Info, "About") }
                    }
                )
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text("Search AI projects") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(item) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                if (!loading && filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No projects match the current filter.") }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.id }) { p ->
                            var nexvaryExpanded by remember(p.id) { mutableStateOf(false) }
                            val nexvaryFit = remember(p) { analyzeNexvaryFit(p, isArabic) }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                                            Text("${p.source} • ${p.category}", style = MaterialTheme.typography.labelMedium)
                                        }
                                        AssistChip(onClick = {}, label = { Text("${p.score}/100") })
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        if (isArabic) "وظيفة المشروع" else "Project purpose",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (isArabic && p.purposeAr.isNotBlank()) p.purposeAr
                                        else p.purpose.ifBlank { p.description },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 5
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    ProjectInfoSection(
                                        title = if (isArabic) "كيف أستخدم هذا المشروع؟" else "How can I use it?",
                                        text = usageGuide(p, isArabic)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    ProjectInfoSection(
                                        title = if (isArabic) "هل يعمل محليًا؟" else "Can it run locally?",
                                        text = localCompatibility(p, isArabic)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    ProjectInfoSection(
                                        title = if (isArabic) "متطلبات التشغيل التقريبية" else "Approximate requirements",
                                        text = approximateRequirements(p, isArabic)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = { nexvaryExpanded = !nexvaryExpanded },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.BusinessCenter, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (isArabic) "هل يفيد NEXVARY؟" else "Useful for NEXVARY?")
                                        Spacer(Modifier.weight(1f))
                                        Icon(
                                            if (nexvaryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null
                                        )
                                    }
                                    if (nexvaryExpanded) {
                                        Spacer(Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.medium,
                                            tonalElevation = 2.dp
                                        ) {
                                            Column(Modifier.padding(12.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        nexvaryFit.level,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(Modifier.weight(1f))
                                                    AssistChip(
                                                        onClick = {},
                                                        label = { Text("${nexvaryFit.score}/100") }
                                                    )
                                                }
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    nexvaryFit.summary,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                if (nexvaryFit.useCases.isNotEmpty()) {
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        if (isArabic) "مجالات الاستفادة داخل NEXVARY" else "NEXVARY use cases",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    nexvaryFit.useCases.forEach { useCase ->
                                                        Text("• $useCase", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    if (isArabic) "ملاحظة عملية" else "Practical note",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                Spacer(Modifier.height(3.dp))
                                                Text(nexvaryFit.caution, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (p.stars > 0) Text("★ ${p.stars}")
                                        Text(if (isArabic) "الرخصة: ${p.license}" else "License: ${p.license}")
                                        if (p.isLocalFriendly) Text(if (isArabic) "• مناسب محليًا" else "• Local-friendly")
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        IconButton(onClick = { saved = repo.toggleSaved(p.id) }) {
                                            Icon(if (p.id in saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Save")
                                        }
                                        IconButton(onClick = { openUrl(p.url) }) { Icon(Icons.Default.OpenInNew, "Open") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun ProjectInfoSection(title: String, text: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(3.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun usageGuide(project: RadarProject, arabic: Boolean): String {
    if (project.source == "arXiv") {
        return if (arabic)
            "هذا بحث علمي وليس برنامجًا جاهزًا. افتح المصدر لقراءة الورقة، ثم راجع قسم الكود أو المستودع المرتبط إن وُجد لتجربة الفكرة عمليًا."
        else
            "This is a research paper, not a ready-to-run app. Open the source to read it, then follow any linked code repository if available."
    }
    if (project.source == "Hugging Face" && project.category == "AI Apps") {
        return if (arabic)
            "افتح المشروع لتجربته مباشرة داخل Hugging Face Spaces. إذا كان المستودع متاحًا يمكنك نسخه وتشغيله ذاتيًا وفق ملفات المشروع."
        else
            "Open the project to try it directly in Hugging Face Spaces. If its repository is available, you can clone and self-host it using the project instructions."
    }
    if (project.source == "Hugging Face") {
        return if (arabic)
            "يمكنك استخدام النموذج عبر Hugging Face أو تنزيله ودمجه في تطبيقك. راجع Model Card لمعرفة المكتبة المطلوبة وطريقة الاستدعاء وحجم النموذج."
        else
            "Use the model through Hugging Face or download it for integration in your own app. Check the Model Card for the required library, invocation example, and model size."
    }
    if (project.source == "GitHub") {
        return if (arabic)
            "افتح المستودع واقرأ README أولًا، ثم اتبع أوامر التثبيت والتشغيل الخاصة بالمشروع. راجع Releases إن وُجدت للحصول على نسخة جاهزة قبل البناء من المصدر."
        else
            "Open the repository and read its README first, then follow its installation and run commands. Check Releases for a ready build before compiling from source."
    }
    return if (arabic)
        "افتح المصدر واقرأ تعليمات التشغيل الرسمية للمشروع قبل التثبيت أو الدمج."
    else
        "Open the source and follow the project's official usage instructions before installing or integrating it."
}

private fun localCompatibility(project: RadarProject, arabic: Boolean): String {
    if (project.source == "arXiv") {
        return if (arabic)
            "لا ينطبق مباشرة؛ هذا بحث. إمكانية التشغيل المحلي تعتمد على وجود كود أو نموذج مرفق."
        else
            "Not directly applicable; this is research. Local execution depends on whether code or a model is published."
    }
    if (project.category == "AI Apps" && project.source == "Hugging Face") {
        return if (arabic)
            "يعمل عبر المتصفح على خادم Hugging Face. التشغيل المحلي ممكن فقط إذا كان صاحب المشروع نشر الكود والمتطلبات."
        else
            "It runs remotely in the browser on Hugging Face. Local execution is possible only if the author provides code and dependencies."
    }
    if (project.isLocalFriendly) {
        return if (arabic)
            "نعم، توجد مؤشرات على دعم التشغيل المحلي مثل GGUF أو ONNX أو quantization أو CPU/edge. يجب مراجعة حجم النموذج قبل التنزيل."
        else
            "Likely yes. The project has local-friendly signals such as GGUF, ONNX, quantization, CPU, or edge support. Check model size before downloading."
    }
    return if (arabic)
        "غير مؤكد من البيانات المتاحة. قد يعمل محليًا، لكن لا توجد حاليًا مؤشرات كافية؛ راجع README أو Model Card قبل التحميل."
    else
        "Not confirmed from the available metadata. It may run locally, but there are not enough signals yet; check the README or Model Card first."
}

private fun approximateRequirements(project: RadarProject, arabic: Boolean): String {
    val description = (project.description + " " + project.purpose).lowercase()
    if (project.source == "arXiv") {
        return if (arabic)
            "للقراءة فقط: هاتف أو كمبيوتر ومتصفح. لتنفيذ البحث عمليًا تختلف المتطلبات حسب الكود والنموذج المرفق."
        else
            "For reading: any phone/computer with a browser. Reproducing the research depends on the accompanying code and model."
    }
    if (project.category == "AI Apps" && project.source == "Hugging Face") {
        return if (arabic)
            "للاستخدام عبر Space: متصفح واتصال إنترنت. لا تحتاج GPU محليًا لأن المعالجة تتم على الخادم ما لم تشغله ذاتيًا."
        else
            "For a hosted Space: browser and internet connection. A local GPU is not required unless you self-host it."
    }
    if (project.isLocalFriendly) {
        return if (arabic)
            "تقدير أولي: يمكن أن يبدأ من CPU وذاكرة 8–16GB للمشروعات الخفيفة أو النماذج المضغوطة. النماذج الأكبر قد تحتاج GPU وVRAM أعلى؛ الحجم الفعلي يجب أخذه من صفحة المشروع."
        else
            "Initial estimate: lightweight or quantized projects may start around a CPU with 8–16 GB RAM. Larger models can require a GPU and more VRAM; use the source page for exact figures."
    }
    if (project.category == "Video AI" || "diffusion" in description || "video" in description) {
        return if (arabic)
            "غالبًا من الفئات الثقيلة: يفضّل GPU منفصل، وكمية VRAM تعتمد بشدة على النموذج والدقة. لا يعرض المصدر الحالي رقمًا موثوقًا، لذلك راجع متطلبات المشروع قبل التنزيل."
        else
            "Usually compute-heavy: a discrete GPU is commonly preferred, while VRAM depends heavily on the model and resolution. Check the project requirements for an exact figure."
    }
    if (project.category == "LLM") {
        return if (arabic)
            "تعتمد على حجم النموذج ودرجة الضغط. النماذج الصغيرة أو quantized قد تعمل على CPU/RAM، بينما النماذج الكبيرة تحتاج RAM/VRAM أكبر. راجع حجم الملفات وModel Card."
        else
            "Depends on model size and quantization. Small or quantized models may run on CPU/RAM, while larger models require more RAM/VRAM. Check file sizes and the Model Card."
    }
    return if (arabic)
        "لا توجد معلومات عتاد كافية في البيانات الحالية. راجع README أو Model Card لمعرفة نظام التشغيل وRAM وGPU ومساحة التخزين المطلوبة."
    else
        "The current metadata is insufficient for a hardware estimate. Check the README or Model Card for OS, RAM, GPU, and storage requirements."
}


private data class NexvaryFit(
    val score: Int,
    val level: String,
    val summary: String,
    val useCases: List<String>,
    val caution: String
)

private fun analyzeNexvaryFit(project: RadarProject, arabic: Boolean): NexvaryFit {
    val text = (project.name + " " + project.description + " " + project.purpose + " " + project.category).lowercase()
    var score = 12
    val useCases = mutableListOf<String>()

    fun add(points: Int, ar: String, en: String) {
        score += points
        useCases += if (arabic) ar else en
    }

    when (project.category) {
        "Video AI" -> add(
            28,
            "NEXVARY-DA: توليد الفيديو، تحسين المشاهد، أو بناء إعلانات المنتجات.",
            "NEXVARY-DA: video generation, scene enhancement, or product-ad production."
        )
        "Vision AI" -> add(
            24,
            "NEXVARY-DA: تحليل صور المنتجات وOCR والرؤية الحاسوبية.",
            "NEXVARY-DA: product-image analysis, OCR, and computer vision."
        )
        "Audio AI" -> add(
            22,
            "NEXVARY-DA: التعليق الصوتي، تحويل النص إلى صوت، أو معالجة التسجيلات.",
            "NEXVARY-DA: voice-over, text-to-speech, or audio processing."
        )
        "Coding AI" -> add(
            22,
            "تطوير البرمجيات: مساعدة الوكيل البرمجي في كتابة وتحليل واختبار الكود.",
            "Software development: assist the coding agent with writing, analysis, and testing."
        )
        "Agents" -> add(
            26,
            "الأتمتة والوكلاء: دمجه كعامل أو أداة تنفيذ داخل أنظمة NEXVARY.",
            "Automation and agents: integrate it as an execution component in NEXVARY systems."
        )
        "Cybersecurity AI" -> add(
            32,
            "قسم الأمن السيبراني: التحليل، الاكتشاف، الفرز، أو التحقيق الرقمي.",
            "Cybersecurity: analysis, detection, triage, or digital investigation."
        )
        "LLM" -> add(
            18,
            "المساعدات الداخلية والوكلاء: فهم النصوص، التلخيص، البحث، وتنسيق المهام.",
            "Internal assistants and agents: text understanding, summarization, research, and task orchestration."
        )
        "AI Apps" -> add(
            10,
            "الاستكشاف السريع: تجربة فكرة أو واجهة جاهزة قبل بناء نسخة داخلية.",
            "Rapid exploration: test an existing idea or interface before building an internal version."
        )
    }

    if (project.isLocalFriendly) {
        add(
            18,
            "تشغيل محلي/خاص: مناسب أكثر لبيئات NEXVARY التي تتطلب خصوصية وتقليل الاعتماد على السحابة.",
            "Local/private deployment: better suited to NEXVARY environments requiring privacy and lower cloud dependence."
        )
    }

    if (listOf("ocr", "document", "vision", "image-to-text").any { it in text }) {
        add(
            10,
            "OCR وتحليل المستندات والصور: قابل للاستخدام في أدوات الفحص والتحليل.",
            "OCR and document/image analysis: useful in inspection and analysis tools."
        )
    }
    if (listOf("tts", "speech", "voice", "audio").any { it in text }) {
        add(
            8,
            "الصوت: يمكن الاستفادة منه في الإعلانات والمساعدات الصوتية وتحويل المحتوى.",
            "Audio: can support ads, voice assistants, and content conversion."
        )
    }
    if (listOf("video", "diffusion", "image generation", "text-to-image").any { it in text }) {
        add(
            10,
            "الإنتاج المرئي: مرشح لخط إنتاج الصور والفيديو داخل NEXVARY-DA.",
            "Visual production: candidate for the image/video pipeline in NEXVARY-DA."
        )
    }
    if (listOf("security", "cyber", "malware", "forensic", "vulnerability").any { it in text } &&
        project.category != "Cybersecurity AI"
    ) {
        add(
            14,
            "الأمن الرقمي: توجد مؤشرات على فائدة محتملة لأدوات الفحص أو التحليل الأمني.",
            "Digital security: signals indicate possible value for security inspection or analysis."
        )
    }

    if (project.source == "arXiv") score -= 8
    if (project.source == "Hugging Face" && project.category == "AI Apps") score -= 4

    score = score.coerceIn(0, 100)

    val level = when {
        score >= 75 -> if (arabic) "ملاءمة مرتفعة لـ NEXVARY" else "High NEXVARY fit"
        score >= 50 -> if (arabic) "ملاءمة جيدة لـ NEXVARY" else "Good NEXVARY fit"
        score >= 30 -> if (arabic) "فائدة محتملة تحتاج مراجعة" else "Potential value; review needed"
        else -> if (arabic) "فائدة محدودة حاليًا" else "Limited current value"
    }

    val summary = if (useCases.isEmpty()) {
        if (arabic)
            "لا تظهر من البيانات الحالية علاقة قوية بأحد مسارات NEXVARY الأساسية. قد تكون له فائدة بحثية أو مستقبلية، لكن يلزم فتح المصدر وفحصه قبل الدمج."
        else
            "The current metadata does not show a strong match to a core NEXVARY workflow. It may still have research or future value, but the source should be reviewed before integration."
    } else {
        if (arabic)
            "تمت مطابقة المشروع مع " + useCases.size + " مسار/مسارات استخدام محتملة داخل NEXVARY استنادًا إلى نوعه ووصفه وإشارات التشغيل المحلي."
        else
            "The project matches " + useCases.size + " potential NEXVARY use case(s) based on its category, description, and local-execution signals."
    }

    val caution = when {
        project.source == "arXiv" ->
            if (arabic)
                "هذه ورقة بحثية؛ لا تعتبرها مكوّنًا جاهزًا قبل العثور على الكود، الرخصة، الاختبارات، ومتطلبات العتاد."
            else
                "This is a research paper; do not treat it as a ready component until code, licensing, tests, and hardware requirements are verified."
        project.license.equals("Unknown", true) || project.license.equals("See source", true) ->
            if (arabic)
                "تحقق من الرخصة قبل دمجه تجاريًا داخل منتجات NEXVARY، ثم اختبر الجودة والأداء فعليًا."
            else
                "Verify the license before commercial integration into NEXVARY products, then benchmark quality and performance."
        !project.isLocalFriendly ->
            if (arabic)
                "الفائدة لا تعني سهولة التشغيل المحلي. راجع متطلبات GPU/RAM، الاعتماد على APIs خارجية، والرخصة قبل الاعتماد."
            else
                "Usefulness does not imply easy local execution. Verify GPU/RAM needs, external API dependencies, and licensing before adoption."
        else ->
            if (arabic)
                "مرشح جيد للتجربة المعملية. ابدأ باختبار صغير على بيانات غير حساسة، ثم قارن الجودة والسرعة واستهلاك الموارد قبل الدمج."
            else
                "A good lab candidate. Start with non-sensitive test data, then compare quality, speed, and resource use before integration."
    }

    return NexvaryFit(
        score = score,
        level = level,
        summary = summary,
        useCases = useCases.distinct(),
        caution = caution
    )
}
