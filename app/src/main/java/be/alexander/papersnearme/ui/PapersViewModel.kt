package be.alexander.papersnearme.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.alexander.papersnearme.data.IndexRepository
import be.alexander.papersnearme.data.Match
import be.alexander.papersnearme.data.PaperIndex
import be.alexander.papersnearme.ml.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val status: String? = "Starting…",
    val error: String? = null,
    val paperCount: Int = 0,
    val updated: String = "",
    val cities: List<String> = emptyList(),
    val selectedCity: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Match> = emptyList(),
    val institutionCity: (Int) -> String = { "" },
    val institutionName: (Int) -> String = { "" },
)

class PapersViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = IndexRepository(app.filesDir)
    private var index: PaperIndex? = null
    private var embedder: Embedder? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load(force = false) }

    fun refresh() { load(force = true) }

    private fun load(force: Boolean) = viewModelScope.launch {
        _state.update { it.copy(error = null, status = "Checking for new papers…") }
        try {
            withContext(Dispatchers.IO) {
                repo.sync(force) { msg -> _state.update { it.copy(status = msg) } }
                _state.update { it.copy(status = "Loading the index…") }
                val idx = PaperIndex.load(repo.papers, repo.vectors)
                if (embedder == null) embedder = Embedder(repo.model, repo.vocab)
                index = idx
                _state.update {
                    it.copy(
                        status = null,
                        paperCount = idx.papers.size,
                        updated = idx.updated.take(10),
                        cities = idx.cities,
                        institutionCity = { i -> idx.institutions[i].city },
                        institutionName = { i -> idx.institutions[i].name },
                    )
                }
            }
            if (_state.value.query.isNotBlank()) search()
        } catch (e: Exception) {
            _state.update { it.copy(status = null, error = e.message ?: e.toString()) }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun onCitySelected(city: String?) {
        _state.update { it.copy(selectedCity = city) }
        if (_state.value.query.isNotBlank()) search()
    }

    fun search() {
        val idx = index ?: return
        val emb = embedder ?: return
        val s = _state.value
        if (s.query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val results = withContext(Dispatchers.Default) {
                idx.search(emb.embed(s.query), s.selectedCity)
            }
            _state.update { it.copy(searching = false, results = results) }
        }
    }

    override fun onCleared() {
        embedder?.close()
    }
}
