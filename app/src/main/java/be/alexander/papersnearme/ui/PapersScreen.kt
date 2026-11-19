@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package be.alexander.papersnearme.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.alexander.papersnearme.data.FieldInfo
import be.alexander.papersnearme.data.Match

@Composable
fun PapersScreen(vm: PapersViewModel) {
    val s by vm.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = s.status != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Papers Near Me") },
                actions = { TextButton(onClick = vm::refresh, enabled = !busy) { Text("Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = s.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What are you working on?") },
                    placeholder = { Text("e.g. maternal gatekeeping in divorced families") },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FieldDropdown(s.fields, s.selectedFields, enabled = !busy, onToggle = vm::toggleField,
                        modifier = Modifier.weight(1f))
                    CityDropdown(s.cities, s.selectedCity, enabled = !busy && s.cities.isNotEmpty(),
                        onSelect = vm::onCitySelected, modifier = Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Your description stays on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { keyboard?.hide(); vm.search() },
                        enabled = !busy && s.query.isNotBlank() && !s.searching && s.loadedPaperCount > 0,
                    ) { Text("Find papers") }
                }
            }

            if (s.error != null) {
                Text(
                    s.error!!,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when {
                s.status != null -> Loading(s.status!!, s.progress)
                s.searching -> Loading("Searching…", null)
                s.selectedFields.isEmpty() -> Message(
                    "Pick your field(s) of work above to get started.\n\n" +
                        "Each field is downloaded once and kept up to date on Wi-Fi. " +
                        "Then describe your project and I'll find the closest papers — " +
                        "and the people nearby who wrote them."
                )
                s.results.isEmpty() -> Message(
                    "${"%,d".format(s.loadedPaperCount)} papers with Belgian authors since ${s.since} · " +
                        "updated ${s.updated}\n\n" +
                        "Describe your project and I'll find the closest papers — and the people nearby who wrote them."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(s.results, key = { it.paper.id }) { m -> PaperCard(m) }
                    item {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            when {
                                s.loadingMore -> CircularProgressIndicator()
                                s.canLoadMore -> OutlinedButton(onClick = vm::loadMore) { Text("Load more") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldDropdown(
    fields: List<FieldInfo>,
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val names = fields.filter { it.id in selected }.map { it.name }
    val label = when (names.size) {
        0 -> "Choose…"
        1 -> names[0]
        else -> "${names.size} fields"
    }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text("Field") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            fields.forEach { f ->
                DropdownMenuItem(
                    leadingIcon = { Checkbox(checked = f.id in selected, onCheckedChange = null) },
                    text = {
                        Column {
                            Text(f.name)
                            Text(
                                "${"%,d".format(f.count)} papers · ${"%.0f".format(f.bytes / 1e6)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    // Adding a field downloads it, so close the menu to show the progress.
                    onClick = { onToggle(f.id); if (f.id !in selected) expanded = false },
                )
            }
        }
    }
}

@Composable
private fun CityDropdown(
    cities: List<CityCount>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier) {
        OutlinedTextField(
            value = selected ?: "All of Belgium",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text("Location") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                leadingIcon = { RadioButton(selected = selected == null, onClick = null) },
                text = { Text("All of Belgium") },
                onClick = { onSelect(null); expanded = false },
            )
            cities.forEach { c ->
                DropdownMenuItem(
                    leadingIcon = { RadioButton(selected = selected == c.name, onClick = null) },
                    text = { Text("${c.name} (${"%,d".format(c.count)})") },
                    onClick = { onSelect(c.name); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun PaperCard(m: Match) {
    val uri = LocalUriHandler.current
    val p = m.paper
    Card(Modifier.fillMaxWidth().clickable { uri.openUri(p.url) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(m.score * 100).toInt()}% match", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    p.subfield.ifBlank { m.field },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (p.year > 0) Text("${p.year}", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val local = p.localAuthors.distinctBy { it.name }
            local.take(4).forEach { a ->
                Text(
                    "${a.name} · ${a.institution}, ${a.city}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            val others = p.authorCount - local.take(4).size
            if (others > 0) {
                Text("+ $others co-author${if (others > 1) "s" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            if (p.abstract.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(p.abstract, style = MaterialTheme.typography.bodySmall, maxLines = 4,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Loading(text: String, progress: Float?) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (progress != null) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(16.dp))
        Text(text)
    }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
