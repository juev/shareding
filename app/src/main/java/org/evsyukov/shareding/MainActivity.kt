package org.evsyukov.shareding

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.data.Settings
import org.evsyukov.shareding.network.TitleFetcher
import org.evsyukov.shareding.network.TagNames
import org.evsyukov.shareding.network.Urls
import org.evsyukov.shareding.sync.NetworkRequests

class MainActivity : ComponentActivity() {
    private val container get() = (application as ShareDingApplication).container
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    @Volatile private var networksAtRegistration: Set<Network> = emptySet()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network !in networksAtRegistration) container.scheduler.enqueue(urgent = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            ShareDingTheme {
                ShareDingScreen(container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        networksAtRegistration = connectivity.allNetworks.toSet()
        connectivity.registerNetworkCallback(NetworkRequests.anyConnected(), networkCallback)
    }

    override fun onStop() {
        connectivity.unregisterNetworkCallback(networkCallback)
        super.onStop()
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareDingScreen(container: AppContainer) {
    val bookmarks by container.db.bookmarks().observeAll().collectAsState(initial = emptyList())
    val settings by container.settings.state.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Bookmark?>(null) }
    var serverTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagLoadError by remember { mutableStateOf(false) }
    var tagRefresh by remember { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(settings.serverUrl, settings.hasToken, selectedTab, showAdd, tagRefresh) {
        serverTags = emptyList()
        tagLoadError = false
        if ((!showAdd && selectedTab != 1) || settings.serverUrl.isBlank() || !settings.hasToken) {
            return@LaunchedEffect
        }
        try {
            val token = withContext(Dispatchers.IO) { container.settings.token().orEmpty() }
            serverTags = container.networkSelector.listTags(settings.serverUrl, token)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            tagLoadError = true
        }
    }

    if (showAdd) {
        AddBookmarkScreen(container, serverTags, tagLoadError,
            canRefreshTags = settings.hasToken && settings.serverUrl.isNotBlank(),
            onRefreshTags = { tagRefresh++ }, onDismiss = { showAdd = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (selectedTab == 0) "Queue" else "Settings") }, actions = {
                if (selectedTab == 0) IconButton(onClick = { container.scheduler.enqueue(urgent = true) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync now")
                }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Queue") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add bookmark")
            }
        },
    ) { padding ->
        if (selectedTab == 0) QueueScreen(bookmarks, padding,
            onAdd = { showAdd = true },
            onRetry = { bookmark ->
                scope.launch {
                    withContext(Dispatchers.IO) { container.db.bookmarks().markPending(bookmark.id) }
                    container.scheduler.enqueue(urgent = true)
                }
            },
            onDelete = { deleteTarget = it })
        else SettingsScreen(settings, bookmarks.size, padding, serverTags, tagLoadError,
            onSave = { server, token, tags, unread, archived ->
                if (server.isNotBlank()) Urls.server(server)
                withContext(Dispatchers.IO) {
                    container.settings.save(server.trim(), token, tags.trim(), unread, archived)
                }
                container.scheduler.enqueue(urgent = true)
            },
            onTest = { server, token ->
                val actualToken = token.ifBlank { container.settings.token().orEmpty() }
                container.networkSelector.checkAndSelect(server, actualToken)
            }, onSync = { container.scheduler.enqueue(urgent = true) },
            onRefreshTags = { tagRefresh++ })
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete bookmark?") },
            text = { Text(target.url) }, confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { container.db.bookmarks().delete(target.id) } }
                    deleteTarget = null
                }) { Text("Delete") }
            }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } })
    }
}

@Composable
private fun QueueScreen(bookmarks: List<Bookmark>, padding: PaddingValues,
                        onAdd: () -> Unit, onRetry: (Bookmark) -> Unit, onDelete: (Bookmark) -> Unit) {
    if (bookmarks.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.BookmarkBorder, contentDescription = null,
                modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Queue is empty", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Share a link from another app or add one here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAdd) { Text("Add bookmark") }
        }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DateUtils.MINUTE_IN_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(bookmarks, key = { it.id }) { bookmark ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(bookmark.title.ifBlank { bookmark.url }, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            val host = android.net.Uri.parse(bookmark.url).host ?: bookmark.url
                            val age = if (now - bookmark.createdAt < DateUtils.MINUTE_IN_MILLIS) "Just now"
                                else DateUtils.getRelativeTimeSpanString(bookmark.createdAt, now,
                                    DateUtils.MINUTE_IN_MILLIS)
                            Text("$host · $age", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onDelete(bookmark) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${bookmark.url}")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val failed = bookmark.status == "failed"
                    val statusText = when (bookmark.status) {
                        "failed" -> "Failed"
                        "syncing" -> "Sending"
                        else -> "Waiting to sync"
                    }
                    val statusBackground = when (bookmark.status) {
                        "failed" -> MaterialTheme.colorScheme.errorContainer
                        "syncing" -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val statusForeground = when (bookmark.status) {
                        "failed" -> MaterialTheme.colorScheme.onErrorContainer
                        "syncing" -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(50), color = statusBackground) {
                            Text(statusText, color = statusForeground,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        if (failed) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onRetry(bookmark) }) { Text("Retry") }
                        }
                    }
                    if (failed) {
                        bookmark.lastError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (bookmark.attempts > 0) Text("Tried ${bookmark.attempts} times",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBookmarkScreen(container: AppContainer, availableTags: List<String>,
                              tagLoadError: Boolean, canRefreshTags: Boolean,
                              onRefreshTags: () -> Unit,
                              onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    BackHandler { if (!saving) onDismiss() }
    val save: () -> Unit = {
        scope.launch {
            if (saving) return@launch
            saving = true
            try {
                val validUrl = Urls.parse(url).toString()
                val defaults = container.settings.state.value
                val id = withContext(Dispatchers.IO) {
                    container.db.bookmarks().insert(Bookmark(url = validUrl, title = title.trim(),
                        description = description.trim(), tags = TagNames.combine(defaults.defaultTags, tags),
                        unread = defaults.unread, archived = defaults.archived))
                }
                if (id != -1L) runCatching { container.scheduler.enqueue(urgent = true) }
                Toast.makeText(context, if (id == -1L) "Already in queue" else "Saved to queue",
                    Toast.LENGTH_SHORT).show()
                onDismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.makeText(context, error.message ?: "Cannot save bookmark", Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Add Bookmark") }, navigationIcon = {
            IconButton(onClick = onDismiss, enabled = !saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to queue")
            }
        }) },
        bottomBar = {
            Button(onClick = save, enabled = !saving && !busy,
                modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Save bookmark")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save a link now; ShareDing will send it when linkding is available.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("URL") }, singleLine = true)
            OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        title = runCatching { TitleFetcher().fetch(url).orEmpty() }.getOrDefault("")
                        busy = false
                    }
                }, enabled = !busy) { Text("Fetch title") }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp))
            }
            OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") })
            TagInput(tags, { tags = it }, "Tags", availableTags,
                "Comma-separated: reading, work notes. Defaults are added automatically.",
                tagLoadError, canRefresh = canRefreshTags, onRefreshTags = onRefreshTags)
        }
    }
}

@Composable
private fun TagInput(value: String, onValueChange: (String) -> Unit, label: String,
                     availableTags: List<String>, hint: String, tagLoadError: Boolean,
                     canRefresh: Boolean, onRefreshTags: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(),
            label = { Text(label) }, placeholder = { Text("reading, work notes") },
            singleLine = true, supportingText = { Text(hint) })
        val suggestions = TagNames.suggestions(value, availableTags)
        if (suggestions.isNotEmpty()) {
            val bringSuggestionsIntoView = remember { BringIntoViewRequester() }
            LaunchedEffect(suggestions) { bringSuggestionsIntoView.bringIntoView() }
            Text("Existing tags", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().bringIntoViewRequester(bringSuggestionsIntoView)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { name ->
                    SuggestionChip(onClick = { onValueChange(TagNames.complete(value, name)) },
                        label = { Text(name) })
                }
            }
        }
        if (canRefresh && tagLoadError) {
            Text("Tag suggestions unavailable. You can still type tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canRefresh) {
            TextButton(onClick = onRefreshTags) { Text("Refresh tags") }
        }
    }
}

@Composable
private fun SettingsScreen(settings: Settings, queueCount: Int, padding: PaddingValues,
                           availableTags: List<String>, tagLoadError: Boolean,
                           onSave: suspend (String, String?, String, Boolean, Boolean) -> Unit,
                           onTest: suspend (String, String) -> Unit, onSync: () -> Unit,
                           onRefreshTags: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val version = remember(context) { appVersion(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var server by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable(settings.defaultTags) { mutableStateOf(settings.defaultTags) }
    var unread by rememberSaveable(settings.unread) { mutableStateOf(settings.unread) }
    var archived by rememberSaveable(settings.archived) { mutableStateOf(settings.archived) }
    var testStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var testFailed by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testVersion by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    val savedConnectionIsCurrent = server.trim() == settings.serverUrl && token.isBlank()
    Column(Modifier.fillMaxSize().padding(padding)) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            SettingsGroup("Linkding") {
                OutlinedTextField(server, {
                    server = it
                    saveStatus = null
                    testVersion++
                    testStatus = null
                    testing = false
                }, modifier = Modifier.fillMaxWidth(), label = { Text("Server URL") },
                    placeholder = { Text("https://linkding.example") }, singleLine = true)
                if (server.trim().startsWith("http://", ignoreCase = true)) {
                    Text("HTTP sends your API token and bookmarks without TLS encryption.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(token, {
                    token = it
                    saveStatus = null
                    testVersion++
                    testStatus = null
                    testing = false
                }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (settings.hasToken) "API token (leave empty to keep current)" else "API token") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val version = ++testVersion
                        testStatus = "Checking connection…"
                        testFailed = false
                        testing = true
                        scope.launch {
                            val result = try {
                                onTest(server, token)
                                Result.success(Unit)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                            if (testVersion == version) {
                                testFailed = result.isFailure
                                testStatus = if (result.isSuccess) "Connection successful"
                                    else "Connection failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                                testing = false
                            }
                        }
                    }, enabled = !testing) { Text("Test Connection") }
                }
                testStatus?.let {
                    Text(it, color = if (testFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            SettingsGroup("Bookmark defaults") {
                TagInput(tags, { tags = it; saveStatus = null }, "Default tags",
                    if (savedConnectionIsCurrent) availableTags else emptyList(),
                    "Comma-separated: reading, work notes. Added to new bookmarks.",
                    tagLoadError && savedConnectionIsCurrent,
                    canRefresh = settings.hasToken && settings.serverUrl.isNotBlank() &&
                        savedConnectionIsCurrent, onRefreshTags = onRefreshTags)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Mark unread")
                    Switch(unread, { unread = it; saveStatus = null })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Archive")
                    Switch(archived, { archived = it; saveStatus = null })
                }
            }
        }
        item {
            SettingsGroup("Sync") {
                Text("Queue: $queueCount")
                Text("Last sync: " + if (settings.lastSync == 0L) "Never" else
                    DateFormat.getDateTimeInstance().format(Date(settings.lastSync)))
                if (settings.lastError.isNotBlank()) Text(settings.lastError,
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onSync) { Text("Sync Now") }
            }
        }
        item {
            SettingsGroup("About") {
                Text("ShareDing · linkding bookmark queue")
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Version")
                        Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { uriHandler.openUri("https://denis.evsyukov.org") },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Developer")
                        Text("Denis Evsyukov", color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { uriHandler.openUri("https://github.com/juev/shareding") },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Source code")
                        Text("GitHub", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                scope.launch {
                    if (saving) return@launch
                    saving = true
                    try {
                        onSave(server, token.takeIf { it.isNotBlank() }, tags, unread, archived)
                        token = ""
                        focusManager.clearFocus()
                        keyboard?.hide()
                        saveFailed = false
                        saveStatus = "Settings saved"
                        onRefreshTags()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        saveFailed = true
                        saveStatus = error.message ?: "Cannot save settings"
                    } finally {
                        saving = false
                    }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Saving…" else "Save settings")
            }
            saveStatus?.let { status ->
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (saveFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary)
            }
        }
    }
    }
}

@Suppress("DEPRECATION")
private fun appVersion(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return "${info.versionName} (${info.longVersionCode})"
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}
