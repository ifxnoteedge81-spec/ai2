package com.personal.aicomputer.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Owns the task queue (one task at a time) and exposes pause / resume / cancel to the UI. */
class AgentController(
    private val appContext: Context,
    private val engine: AgentEngine,
    private val repo: TaskRepository,
    private val scope: CoroutineScope,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queued = AtomicInteger(0)
    private var runningJob: Job? = null

    private val active = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = active.asStateFlow()

    private val selected = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = selected.asStateFlow()

    val isPaused: StateFlow<Boolean> get() = engine.isPaused

    init {
        scope.launch {
            for (id in queue) {
                runOne(id)
                if (queued.decrementAndGet() == 0) AgentService.stop(appContext)
            }
        }
    }

    fun select(id: String?) { selected.value = id }

    fun submit(goal: String): String {
        val task = repo.create(goal)
        selected.value = task.id
        if (queued.getAndIncrement() == 0) AgentService.start(appContext)
        queue.trySend(task.id)
        return task.id
    }

    private suspend fun runOne(id: String) {
        val t = repo.get(id) ?: return
        if (t.state == TaskState.CANCELLED) return
        active.value = id
        val job = scope.launch { engine.execute(id) }
        runningJob = job
        job.join()
        runningJob = null
        active.value = null
    }

    fun pause() = engine.setPaused(true)
    fun resume() = engine.setPaused(false)

    fun cancel(id: String) {
        if (active.value == id) {
            engine.setPaused(false)
            runningJob?.cancel()
        } else {
            repo.get(id)?.let { if (!it.state.isTerminal) repo.update(id) { t -> t.copy(state = TaskState.CANCELLED, error = "Cancelled by the user.") } }
        }
    }
}
