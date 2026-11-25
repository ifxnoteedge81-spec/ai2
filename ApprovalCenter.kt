package com.personal.aicomputer.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ApprovalAnswer { DENY, ALLOW_ONCE, ALLOW_FOR_TASK }

data class ApprovalRequest(
    val id: String,
    val taskId: String,
    val toolName: String,
    val summary: String,
    val reason: String,
    val category: String,
)

/** Bridges the agent coroutine to the UI permission dialog (Level 2: confirmation required). */
class ApprovalCenter {
    private val mutex = Mutex()
    private var deferred: CompletableDeferred<ApprovalAnswer>? = null
    private val state = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = state.asStateFlow()

    suspend fun request(req: ApprovalRequest): ApprovalAnswer = mutex.withLock {
        val d = CompletableDeferred<ApprovalAnswer>()
        deferred = d
        state.value = req
        try {
            d.await()
        } finally {
            state.value = null
            deferred = null
        }
    }

    fun answer(id: String, answer: ApprovalAnswer) {
        if (state.value?.id == id) deferred?.complete(answer)
    }
}
