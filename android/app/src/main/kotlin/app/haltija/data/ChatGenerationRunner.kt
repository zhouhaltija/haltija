package app.haltija.data

import app.haltija.core.provider.stream.StreamDelta
import app.haltija.core.provider.transport.ChatResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException

class SessionSaveException(cause: Exception?) : IOException("保存失败", cause)

data class GenerationCompletion(
    val session: ChatSession,
    val saved: Boolean,
    val error: Exception?,
    val emptyReply: Boolean,
)

/** 用户先落盘；结束、取消或断流均保存已有回复。IO 完成后才通知 UI 解锁。 */
class ChatGenerationRunner(private val store: ChatSessionStore) {
    suspend fun run(
        pending: ChatSession,
        userName: String,
        source: suspend () -> Flow<StreamDelta>,
        onProgress: (ChatResult) -> Unit,
        onComplete: suspend (GenerationCompletion) -> Unit,
    ) {
        var result = ChatResult.EMPTY
        var userSaved = false
        var failure: Exception? = null
        var emptyReply = false
        try {
            withContext(Dispatchers.IO) { store.save(pending) }
            userSaved = true
            source().collect { delta ->
                result += delta
                onProgress(result)
            }
            emptyReply = result.text.isEmpty() && result.reasoning.isEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        } finally {
            withContext(NonCancellable) {
                var completed = pending
                var saved = userSaved
                if (result.text.isNotEmpty() || result.reasoning.isNotEmpty()) {
                    completed = store.append(pending, result.text, false, userName, result.reasoning)
                    try {
                        withContext(Dispatchers.IO) { store.save(completed) }
                        saved = true
                    } catch (e: Exception) {
                        saved = false
                        failure = e
                    }
                }
                onComplete(GenerationCompletion(
                    completed, saved, if (saved) failure else SessionSaveException(failure), emptyReply,
                ))
            }
        }
    }
}
