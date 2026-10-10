package app.watchaudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.SnippetPart
import app.watchaudio.app.state.SearchResult
import app.watchaudio.app.state.SearchResults

@Composable
internal fun SearchScreen(
    query: String,
    results: SearchResults?,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onOpen: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(Strings.SEARCH_PLACEHOLDER) },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text(Strings.CLEAR) }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        when {
            results == null -> Hint(Strings.SEARCH_HINT)
            results.results.isEmpty() -> Hint(Strings.SEARCH_NO_RESULTS)
            else -> {
                Text(
                    Strings.searchResultCount(results.results.size, results.limited),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(results.results, key = { it.segmentId }) { result ->
                        ResultRow(result) { onOpen(result) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val snippet = remember(result.parts, highlight) { snippetText(result.parts, SpanStyle(fontWeight = FontWeight.Bold, background = highlight)) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(result.whenLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(snippet, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

internal fun snippetText(parts: List<SnippetPart>, highlight: SpanStyle): AnnotatedString = buildAnnotatedString {
    for (part in parts) {
        if (part.highlighted) withStyle(highlight) { append(part.text) } else append(part.text)
    }
}
