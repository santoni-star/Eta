package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val CharacterCardPadding = 12.dp
private val CharacterGridSpacing = 12.dp

@Composable
internal fun CharacterLibraryScreen(
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) store.importCard(uri) { onNavigate(AppRoute.CharacterDetail(it)) }
    }
    val importCard = {
        importer.launch(arrayOf("image/png", "application/json", "text/plain", "application/octet-stream"))
    }
    val createCharacter = {
        store.discardEditor()
        onNavigate(AppRoute.CharacterEditor())
    }
    val showAddTile = store.query.isBlank() && store.characters.isNotEmpty()
    val tiles: List<CharacterProfile?> = store.filteredCharacters + if (showAddTile) listOf(null) else emptyList()
    val rows = tiles.chunked(2)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = stringResource(R.string.route_characters),
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.FileUpload,
                        contentDescription = stringResource(R.string.char_import_card),
                        enabled = !store.busy,
                        onClick = importCard,
                    )
                    CouiTopAppBarIconButton(
                        icon = CouiIcons.Outlined.Add,
                        contentDescription = stringResource(R.string.char_create),
                        enabled = !store.busy,
                        onClick = createCharacter,
                    )
                },
            ) {
                item(key = "search") {
                    CouiSearchBarBox(
                        query = store.query,
                        onQueryChange = { store.query = it },
                        placeholder = stringResource(R.string.char_search_hint),
                    )
                }
                when {
                    store.busy && store.characters.isEmpty() -> {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 28.dp)
                            }
                        }
                    }
                    store.characters.isEmpty() -> {
                        item(key = "empty-library") {
                            ListEmptyState(
                                title = stringResource(R.string.char_empty_title),
                                summary = stringResource(R.string.char_empty_summary),
                                icon = Icons.Rounded.SportsBar,
                                action = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CouiButton(
                                            text = stringResource(R.string.char_restore_default),
                                            onClick = { store.restoreDefaultCharacter() },
                                            enabled = !store.busy,
                                        )
                                        CouiButton(
                                            text = stringResource(R.string.char_create),
                                            onClick = createCharacter,
                                            role = CouiButtonRole.Primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                    store.filteredCharacters.isEmpty() -> {
                        item(key = "empty-search") {
                            ListEmptyState(
                                title = stringResource(R.string.char_no_match_title),
                                summary = stringResource(R.string.char_no_match_summary),
                                icon = Icons.Rounded.SearchOff,
                            )
                        }
                    }
                }
                items(rows, key = { row -> row.joinToString("|") { it?.id ?: "add" } }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = CharacterGridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(CharacterGridSpacing),
                    ) {
                        row.forEach { profile ->
                            if (profile == null) {
                                CharacterAddTile(
                                    enabled = !store.busy,
                                    onCreate = createCharacter,
                                    onImport = importCard,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                CharacterPortraitCard(
                                    profile = profile,
                                    onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
                                    onToggleFavorite = { store.toggleFavorite(profile.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "persona") {
                    Group(title = stringResource(R.string.char_section_mine)) {
                        CouiPreference(
                            title = stringResource(R.string.route_character_persona),
                            summary = stringResource(R.string.char_persona_summary),
                            onClick = { onNavigate(AppRoute.CharacterPersona) },
                        )
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.route_characters),
                onBack = onBack,
                actions = {
                    IconButton(onClick = importCard, enabled = !store.busy) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = stringResource(R.string.char_import_card))
                    }
                    IconButton(onClick = createCharacter, enabled = !store.busy) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.char_create))
                    }
                },
            ) {
                item(key = "search") {
                    SearchBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CharacterCardPadding)
                            .padding(top = 4.dp, bottom = 12.dp),
                        insideMargin = DpSize(0.dp, 0.dp),
                        expanded = false,
                        onExpandedChange = {},
                        inputField = {
                            InputField(
                                query = store.query,
                                onQueryChange = { store.query = it },
                                onSearch = { store.query = it },
                                expanded = false,
                                onExpandedChange = {},
                                label = stringResource(R.string.char_search_hint),
                            )
                        },
                        content = {},
                    )
                }
                when {
                    store.busy && store.characters.isEmpty() -> {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 28.dp)
                            }
                        }
                    }
                    store.characters.isEmpty() -> {
                        item(key = "empty-library") {
                            ListEmptyState(
                                title = stringResource(R.string.char_empty_title),
                                summary = stringResource(R.string.char_empty_summary),
                                icon = Icons.Rounded.SportsBar,
                                action = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TextButton(
                                            text = stringResource(R.string.char_restore_default),
                                            onClick = { store.restoreDefaultCharacter() },
                                            enabled = !store.busy,
                                        )
                                        TextButton(
                                            text = stringResource(R.string.char_create),
                                            onClick = createCharacter,
                                            colors = ButtonDefaults.textButtonColorsPrimary(),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    store.filteredCharacters.isEmpty() -> {
                        item(key = "empty-search") {
                            ListEmptyState(
                                title = stringResource(R.string.char_no_match_title),
                                summary = stringResource(R.string.char_no_match_summary),
                                icon = Icons.Rounded.SearchOff,
                            )
                        }
                    }
                }
                items(rows, key = { row -> row.joinToString("|") { it?.id ?: "add" } }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CharacterCardPadding)
                            .padding(bottom = CharacterGridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(CharacterGridSpacing),
                    ) {
                        row.forEach { profile ->
                            if (profile == null) {
                                CharacterAddTile(
                                    enabled = !store.busy,
                                    onCreate = createCharacter,
                                    onImport = importCard,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                CharacterPortraitCard(
                                    profile = profile,
                                    onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
                                    onToggleFavorite = { store.toggleFavorite(profile.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "persona") {
                    SmallTitle(stringResource(R.string.char_section_mine))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.route_character_persona),
                            summary = stringResource(R.string.char_persona_summary),
                            onClick = { onNavigate(AppRoute.CharacterPersona) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CharacterPageMessage(text: String) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Footer(text = text)
        }
        AppearanceUiStyle.MIUIX -> {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
