package org.evsyukov.shareding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import org.evsyukov.shareding.network.BookmarkTitles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueEditorScreen(state: QueueEditorState, editor: QueueEditorViewModel,
                               availableTags: List<String>, tagLoadError: Boolean,
                               canRefreshTags: Boolean, onRefreshTags: () -> Unit) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = state.opening || state.saving
    val goBack: () -> Unit = {
        if (!busy) {
            if (state.dirty) confirmDiscard = true else editor.discard()
        }
    }
    BackHandler(onBack = goBack)
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Edit bookmark") }, navigationIcon = {
                IconButton(onClick = goBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to queue")
                }
            })
        },
        bottomBar = {
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); editor.save() },
                enabled = !busy && state.draft != null,
                modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(if (state.saving) "Saving…" else "Save changes")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.opening) "Stopping sync…" else "Queue sync is paused while you edit.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            if (state.opening) CircularProgressIndicator()
            state.draft?.let { draft ->
                OutlinedTextField(draft.url, { value -> editor.update { it.copy(url = value) } },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("URL") }, singleLine = true, isError = state.urlError != null,
                    supportingText = state.urlError?.let { message -> { Text(message) } })
                val sharedTitle = state.original?.sendTitle == false && draft.title == state.original.title
                OutlinedTextField(draft.title, { value ->
                    editor.update { it.copy(title = BookmarkTitles.limit(value.replace(Regex("[\r\n]+"), " "))) }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("Title") }, minLines = 1, maxLines = 4,
                    supportingText = {
                        Text(if (sharedTitle && draft.title.isNotBlank())
                            "Shared title is kept locally. Edit it to send a custom title."
                        else "Optional · up to 512 characters. Leave empty for linkding to fetch it.")
                    })
                OutlinedTextField(draft.description, { value -> editor.update { it.copy(description = value) } },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("Description") }, maxLines = 6)
                TagInput(draft.tags, { value -> editor.update { it.copy(tags = value) } },
                    "Tags", availableTags, "Comma-separated: reading, work notes.", tagLoadError,
                    canRefresh = canRefreshTags && !busy, onRefreshTags = onRefreshTags)
            }
            state.error?.let { message ->
                val errorIntoView = remember { BringIntoViewRequester() }
                LaunchedEffect(message) { errorIntoView.bringIntoView() }
                Text(message, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.bringIntoViewRequester(errorIntoView))
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard changes?") }, text = { Text("The queued bookmark will stay unchanged.") },
        confirmButton = {
            TextButton(onClick = { confirmDiscard = false; editor.discard() }) { Text("Discard") }
        }, dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
        })
}
