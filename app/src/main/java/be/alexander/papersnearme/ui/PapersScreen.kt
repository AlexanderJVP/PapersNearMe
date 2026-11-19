@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package be.alexander.papersnearme.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.alexander.papersnearme.data.Match

@Composable
fun PapersScreen(vm: PapersViewModel) {
    val s by vm.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Papers Near Me") },
                actions = { TextButton(onClick = vm::refresh) { Text("Refresh") } },
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
                    placeholder = { Text("e.g. self-supervised learning on sensor data") },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Your description stays on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { keyboard?.hide(); vm.search() },
                        enabled = s.status == null && s.query.isNotBlank() && !s.searching,
                    ) { Text("Find papers") }
                }
            }

            if (s.cities.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = s.selectedCity == null,
                            onClick = { vm.onCitySelected(null) }, label = { Text("All of Belgium") })
                    }
                    items(s.cities) { city ->
                        FilterChip(selected = s.selectedCity == city,
                            onClick = { vm.onCitySelected(city) }, label = { Text(city) })
                    }
                }
            }

            when {
                s.error != null -> Message("Couldn't load papers: ${s.error}\n\nCheck your connection, or that the GitHub Action has published the index.")
                s.status != null -> Loading(s.status!!)
                s.searching -> Loading("Searching…")
                s.results.isEmpty() -> Message(
                    "${"%,d".format(s.paperCount)} ML papers with Belgian authors · updated ${s.updated}\n\n" +
                        "Describe your project and I'll find the closest papers — and the people nearby who wrote them."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(s.results, key = { it.paper.id }) { m ->
                        PaperCard(m, s.institutionName, s.institutionCity)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaperCard(m: Match, instName: (Int) -> String, instCity: (Int) -> String) {
    val uri = LocalUriHandler.current
    val p = m.paper
    Card(Modifier.fillMaxWidth().clickable { uri.openUri(p.url) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(m.score * 100).toInt()}% match", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (p.year > 0) Text("${p.year}", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            p.localAuthors.distinctBy { it.name }.take(4).forEach { a ->
                Text(
                    "${a.name} · ${instName(a.institution)}, ${instCity(a.institution)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            val others = p.authorCount - p.localAuthors.distinctBy { it.name }.size
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
private fun Loading(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text)
    }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
