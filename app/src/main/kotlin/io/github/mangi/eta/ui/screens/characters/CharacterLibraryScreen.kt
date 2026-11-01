package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val CharacterCardPadding = 16.dp
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
    MiuixScaffoldPage(
        title = "рігколір",
        onBack = onBack,
        actions = {
            IconButton(onClick = importCard, enabled = !store.busy) {
                Icon(Icons.Rounded.FileUpload, contentDescription = "керівництвовведітьрігколіркартка")
            }
            IconButton(onClick = createCharacter, enabled = !store.busy) {
                Icon(Icons.Rounded.Add, contentDescription = "створитивстановитирігколір")
            }
        },
    ) {
        item(key = "search") {
            SearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding)
                    .padding(top = 4.dp, bottom = 12.dp),
                // Видаліть горизонтальну прокладку, яка постачається з компонентом, і вирівняйте поле пошуку ліворуч і праворуч із карткою нижче.
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
                        label = "пошукОтженазвасказатиабознакзнак",
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
                        title = "поверненнябезматирігколір",
                        summary = "створитивстановитиодиніндивідуальнийрігколір，абокерівництвовведіть PNG、JSON рігколіркарткаВІДЧИНЕНОпочатокправильноговорити",
                        icon = Icons.Rounded.TheaterComedy,
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                EtaTextButton(
                                    text = "відновитискладнімовчазнийрозпізнатирігколір",
                                    onClick = { store.restoreDefaultCharacter() },
                                    enabled = !store.busy,
                                )
                                EtaTextButton(
                                    text = "створитивстановитирігколір",
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
                        title = "безматиспробуйте знайтиприбутиматчматчзрігколір",
                        summary = "Змінаіндивідуальнийзакритиключсловоспробуватиспробувати，пошукОтжезасіданняматчматчназвасказатиізнакзнак",
                        icon = Icons.Rounded.SearchOff,
                    )
                }
            }
        }
        // Два стовпці вертикальних карток: по одному ледачому елементу на рядок, довгі списки все ще згруповані в рядки. Якщо пошук не здійснюється, нова картка додається в кінці.
        // Це не тільки заповнює прогалини в непарних числах, але також робить створення та імпорт доступними в сітці.
        val showAddTile = store.query.isBlank() && store.characters.isNotEmpty()
        val tiles: List<CharacterProfile?> = store.filteredCharacters + if (showAddTile) listOf(null) else emptyList()
        val rows = tiles.chunked(2)
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
        item(key = "persona-title") { EtaPreferenceGroupTitle("яз") }
        item(key = "persona") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "язлюдейналаштувати",
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Face, tint = EtaPreferenceColors.Blue) },
                    summary = "рігколірправильновизсказативикликівиіснуютьтомурічсерединазтілочастка",
                    onClick = { onNavigate(AppRoute.CharacterPersona) },
                )
            }
        }
    }
}

@Composable
internal fun CharacterPageMessage(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
