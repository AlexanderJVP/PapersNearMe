package be.alexander.papersnearme.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.alexander.papersnearme.data.CityTable
import be.alexander.papersnearme.data.FieldInfo
import be.alexander.papersnearme.data.FieldShard
import be.alexander.papersnearme.data.IndexRepository
import be.alexander.papersnearme.data.Manifest
import be.alexander.papersnearme.data.Match
import be.alexander.papersnearme.data.searchShards
import be.alexander.papersnearme.ml.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CityCount(val name: String, val count: Int)

data class UiState(
    val status: String? = "Starting…",
    val progress: Float? = null,
    val error: String? = null,
    val fields: List<FieldInfo> = emptyList(),
    val selectedFields: Set<String> = emptySet(),
    val loadedPaperCount: Int = 0,
    val since: String = "",
    val updated: String = "",
    val cities: List<CityCount> = emptyList(),
    val selectedCity: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Match> = emptyList(),
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
)

private const val PAGE = 30

class PapersViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = IndexRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutex = Mutex()  // one download/load at a time
    private var manifest: Manifest? = null
    // Replaced (never mutated) so searches on other threads always see a consistent map.
    @Volatile private var shards: Map<String, FieldShard> = emptyMap()
    private val cityTable = CityTable()
    private var embedder: Embedder? = null
    private var limit = PAGE
    private var cachedQuery: Pair<String, FloatArray>? = null
    private var searchJob: Job? = null

    private val _state = MutableStateFlow(UiState(selectedFields = savedFields()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load(force = false) }

    fun refresh() { load(force = true) }

    private fun load(force: Boolean) = viewModelScope.launch {
        mutex.withLock {
            _state.update { it.copy(error = null, status = "Checking for new papers…", progress = null) }
            try {
                withContext(Dispatchers.IO) {
                    repo.ensureModel { msg, p -> _state.update { it.copy(status = msg, progress = p) } }
                    val m = repo.manifest(force)
                    manifest = m
                    val selected = savedFields().filter { id -> m.fields.any { it.id == id } }.toSet()
                    _state.update {
                        it.copy(fields = m.fields.sortedBy { f -> f.name }, selectedFields = selected,
                            since = m.since.take(4), updated = m.updated.take(10))
                    }
                    for (id in selected) syncField(m.fields.first { it.id == id }, force)
                    if (embedder == null) {
                        _state.update { it.copy(status = "Loading the language model…", progress = null) }
                        embedder = Embedder(repo.model, repo.vocab)
                    }
                }
                _state.update { it.copy(status = null, progress = null) }
                onIndexChanged()
            } catch (e: Exception) {
                _state.update { it.copy(status = null, progress = null, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Downloads [info] if missing (or changed, on Wi-Fi / when forced) and loads it. */
    private fun syncField(info: FieldInfo, force: Boolean) {
        val present = repo.hasField(info.id)
        val outdated = present && !repo.isCurrent(info)
        if (!present || (outdated && (force || repo.isUnmetered()))) {
            val mb = "%.0f".format(info.bytes / 1e6)
            val verb = if (present) "Updating" else "Downloading"
            _state.update { it.copy(status = "$verb ${info.name} (${"%,d".format(info.count)} papers, $mb MB)…", progress = 0f) }
            try {
                repo.downloadField(info) { done, total ->
                    _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                }
                shards = shards - info.id
            } catch (e: Exception) {
                if (!present) throw e  // otherwise keep using the copy we have
            }
        }
        if (info.id !in shards) {
            _state.update { it.copy(status = "Loading ${info.name}…", progress = null) }
            val m = manifest!!
            val shard = FieldShard.load(info, repo.papersFile(info.id), repo.vectorsFile(info.id), m.dim, cityTable)
            shards = shards + (info.id to shard)
        }
    }

    fun toggleField(id: String) {
        val s = _state.value
        val adding = id !in s.selectedFields
        val selected = if (adding) s.selectedFields + id else s.selectedFields - id
        _state.update { it.copy(selectedFields = selected, error = null) }
        prefs.edit().putStringSet("fields", selected).apply()

        viewModelScope.launch {
            mutex.withLock {
                val info = manifest?.fields?.firstOrNull { it.id == id } ?: return@withLock
                try {
                    withContext(Dispatchers.IO) {
                        if (adding) syncField(info, force = false)
                        else { shards = shards - id; repo.deleteField(id) }
                    }
                } catch (e: Exception) {
                    val without = _state.value.selectedFields - id
                    prefs.edit().putStringSet("fields", without).apply()
                    _state.update { it.copy(selectedFields = without, error = e.message ?: e.toString()) }
                } finally {
                    _state.update { it.copy(status = null, progress = null) }
                }
                onIndexChanged()
            }
        }
    }

    /** Recomputes the city list for the loaded fields and reruns the current search. */
    private fun onIndexChanged() {
        val counts = HashMap<Int, Int>()
        for (shard in shards.values) for (ids in shard.paperCities) for (c in ids) counts[c] = (counts[c] ?: 0) + 1
        val cities = counts.entries
            .map { CityCount(cityTable.name(it.key), it.value) }
            .filter { it.name != "Unknown" }
            .sortedByDescending { it.count }
        _state.update { s ->
            s.copy(
                cities = cities,
                selectedCity = s.selectedCity?.takeIf { c -> cities.any { it.name == c } },
                loadedPaperCount = shards.values.sumOf { it.size },
                results = if (shards.isEmpty()) emptyList() else s.results,
            )
        }
        if (_state.value.query.isNotBlank() && _state.value.results.isNotEmpty()) search()
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun onCitySelected(city: String?) {
        _state.update { it.copy(selectedCity = city) }
        if (_state.value.query.isNotBlank() && _state.value.results.isNotEmpty()) search()
    }

    fun search() {
        limit = PAGE
        runSearch(more = false)
    }

    fun loadMore() {
        limit += PAGE
        runSearch(more = true)
    }

    private fun runSearch(more: Boolean) {
        val emb = embedder ?: return
        val s = _state.value
        if (s.query.isBlank() || shards.isEmpty()) return
        val loaded = shards.values.toList()
        val cityId = s.selectedCity?.let { cityTable.idOrNull(it) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { if (more) it.copy(loadingMore = true) else it.copy(searching = true) }
            val (results, full) = withContext(Dispatchers.Default) {
                val q = cachedQuery?.takeIf { it.first == s.query }?.second
                    ?: emb.embed(s.query).also { cachedQuery = s.query to it }
                val hits = searchShards(loaded, q, cityId, limit)
                hits.map { Match(it.shard.paper(it.row), it.shard.info.name, it.score) } to (hits.size == limit)
            }
            _state.update { it.copy(searching = false, loadingMore = false, results = results, canLoadMore = full) }
        }
    }

    private fun savedFields(): Set<String> = prefs.getStringSet("fields", emptySet())!!.toSet()

    override fun onCleared() {
        embedder?.close()
    }
}
