package org.evsyukov.shareding

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.data.BookmarkDraft
import org.evsyukov.shareding.network.Urls

data class QueueEditorState(
    val active: Boolean = false,
    val opening: Boolean = false,
    val saving: Boolean = false,
    val original: Bookmark? = null,
    val draft: BookmarkDraft? = null,
    val urlError: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val dirty: Boolean get() = original != null && draft != BookmarkDraft(original)
}

class QueueEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ShareDingApplication).container
    private val mutableState = MutableStateFlow(QueueEditorState())
    val state = mutableState.asStateFlow()
    private var owner: String? = null

    fun open(id: Long) {
        if (state.value.active) return
        val session = UUID.randomUUID().toString()
        owner = session
        mutableState.value = QueueEditorState(active = true, opening = true)
        viewModelScope.launch {
            try {
                container.scheduler.pauseForEdit(session)
                val original = container.db.bookmarks().findById(id)
                    ?: error("This bookmark has already left the queue")
                mutableState.value = QueueEditorState(active = true, original = original,
                    draft = BookmarkDraft(original))
            } catch (cancel: CancellationException) {
                releaseFailedOpening(session)
                throw cancel
            } catch (error: Exception) {
                releaseFailedOpening(session)
                owner = null
                mutableState.value = QueueEditorState(notice = error.message ?: "Cannot open bookmark")
            }
        }
    }

    private suspend fun releaseFailedOpening(session: String) = withContext(NonCancellable) {
        try {
            container.scheduler.finishEdit(session)
        } catch (error: Exception) {
            Log.e("ShareDing", "Could not resume sync after opening editor failed", error)
        }
    }

    fun update(change: (BookmarkDraft) -> BookmarkDraft) {
        val current = state.value
        if (current.opening || current.saving) return
        val draft = current.draft ?: return
        mutableState.value = current.copy(draft = change(draft), urlError = null, error = null)
    }

    fun save() {
        val current = state.value
        if (current.opening || current.saving) return
        val original = current.original ?: return
        val draft = current.draft ?: return
        val session = owner ?: return
        try {
            Urls.parse(draft.url)
        } catch (error: Exception) {
            mutableState.value = current.copy(urlError = error.message ?: "Invalid URL")
            return
        }
        mutableState.value = current.copy(saving = true, error = null, urlError = null)
        viewModelScope.launch {
            var saved = false
            try {
                val edited = draft.applyTo(original)
                container.scheduler.finishEdit(session) {
                    check(container.db.bookmarks().edit(original.id, edited.url, edited.title,
                        edited.sendTitle, edited.description, edited.tags) == 1) {
                        "This bookmark has already left the queue"
                    }
                    saved = true
                }
                owner = null
                mutableState.value = QueueEditorState(notice = "Bookmark updated")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SQLiteConstraintException) {
                mutableState.value = current.copy(urlError = "This URL is already in the queue")
            } catch (error: Exception) {
                if (saved) {
                    owner = null
                    mutableState.value = QueueEditorState(notice =
                        "Bookmark updated; cannot restart sync: ${error.message}")
                } else mutableState.value = current.copy(error = error.message ?: "Cannot save bookmark")
            }
        }
    }

    fun discard() {
        val current = state.value
        if (current.opening || current.saving) return
        val session = owner ?: return
        mutableState.value = current.copy(saving = true)
        viewModelScope.launch {
            var notice: String? = null
            try {
                container.scheduler.finishEdit(session)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                notice = "Cannot restart sync: ${error.message}"
            } finally {
                owner = null
                mutableState.value = QueueEditorState(notice = notice)
            }
        }
    }

    fun consumeNotice() { mutableState.value = state.value.copy(notice = null) }

    override fun onCleared() {
        owner?.let { container.scheduler.releaseEditor(it) }
        super.onCleared()
    }
}
