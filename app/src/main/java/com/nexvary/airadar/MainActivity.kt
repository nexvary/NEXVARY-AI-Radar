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
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (p.stars > 0) Text("★ ${p.stars}")
                                        Text("License: ${p.license}")
                                        if (p.isLocalFriendly) Text("• Local-friendly")
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
