package com.drkkr.strategos.ui.calm.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drkkr.strategos.StrategosApplication
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.domain.analysis.MomentumStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.ScenarioStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Projects root (board 04). Combines the saved scenarios with each one's recent momentum into the
 * card list — the "Projects combined read" the UX-1 backend left to the view model. Draft survives
 * in the VM; sending creates a project and names it from the first line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as StrategosApplication).container
    private val scenarios = container.scenarioRepository
    private val trajectory = container.trajectoryRepository
    private val conversations = container.database?.let { ConversationRepository(it) }

    private val draft = MutableStateFlow("")

    /** The composer text, kept out of the slow list pipeline so typing never waits on database reads. */
    val draftText: StateFlow<String> = draft
    private var hasKey = true

    val state: StateFlow<ProjectsUiState> =
        scenarios.scenarios
            .mapLatest { list -> build(list, "") }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    /** Archived projects (id, short name), newest first. */
    val archived: StateFlow<List<Pair<String, String>>> = scenarios.scenarios
        .mapLatest { all -> all.filter { it.status == ScenarioStatus.ARCHIVED }.sortedByDescending { it.updatedAt }.map { it.id to ProjectsUi.displayName(it.title) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(id: String) = viewModelScope.launch {
        scenarios.find(id)?.let { scenarios.save(it.copy(status = ScenarioStatus.ACTIVE)) }
    }

    init {
        viewModelScope.launch {
            hasKey = runCatching { container.keyVault?.has() ?: false }.getOrDefault(false)
            scenarios.load()
        }
    }

    fun setDraft(text: String) { draft.value = text }

    /** Reads the vault now (not the value cached at start), so a key just saved in Settings counts. */
    suspend fun keyReady(): Boolean = runCatching { container.keyVault?.has() ?: false }.getOrDefault(false).also { hasKey = it }

    /** Creates a project from the draft, named from the first line, and returns its id (or null if blank). */
    suspend fun createProject(): String? {
        val text = draft.value.trim().ifEmpty { return null }
        val name = ProjectsUi.displayName(text)
        val saved = scenarios.save(Scenario(title = name, description = text))
        draft.value = ""
        return (saved as? SaveResult.Success)?.saved?.id
    }

    fun archive(id: String) = viewModelScope.launch {
        scenarios.find(id)?.let { scenarios.save(it.copy(status = ScenarioStatus.ARCHIVED)) }
    }

    fun delete(id: String) = viewModelScope.launch { scenarios.delete(id) }

    fun rename(id: String, name: String) = viewModelScope.launch {
        scenarios.find(id)?.let { scenarios.save(it.copy(title = name.trim().ifEmpty { it.title })) }
    }

    private suspend fun build(all: List<Scenario>, draftText: String): ProjectsUiState {
        val active = all.filter { it.status != ScenarioStatus.ARCHIVED }
        val now = System.currentTimeMillis()
        val cards = active.map { s -> card(s, now) }.sortedByDescending { it.lastActivityAt }
        return ProjectsUiState(
            projects = cards.map { it.ui },
            archivedCount = all.count { it.status == ScenarioStatus.ARCHIVED },
            hasKey = hasKey,
            draft = draftText,
            loading = false
        )
    }

    private data class Carded(val ui: ProjectCardUi, val lastActivityAt: Long)

    private suspend fun card(s: Scenario, now: Long): Carded {
        val threads = conversations?.listThreads(s.id).orEmpty()
        val points = trajectory?.recent(s.id, 12).orEmpty()
        val trust = points.map { it.trustScore.toFloat() }
        val tension = points.map { it.tensionScore.toFloat() }
        val status = MomentumStatus.of(points.map { it.trustScore }, points.map { it.tensionScore })
        val lastAt = maxOf(s.updatedAt, points.lastOrNull()?.timestamp ?: 0L, threads.maxOfOrNull { it.updatedAt } ?: 0L)
        return Carded(
            ProjectCardUi(s.id, ProjectsUi.displayName(s.title), ProjectsUi.subtitle(lastAt, now, threads.size), status, trust, tension),
            lastAt
        )
    }
}
