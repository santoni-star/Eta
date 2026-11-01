@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.EtaOverlayDialog
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.model.ModelSearchQuery
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.DeleteOutline
import top.yukonga.miuix.kmp.squircle.squircleSurface

private val editableReasoningEfforts = listOf(
    ReasoningEffort.OFF,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

/**
 * Загальний контекст вікна зупиняється. Беручи десяткові цілі числа: модель з номінальними значеннями 262 144 і 1 048 576 буде лише трохи стиснута, якщо заповнена як 256K і 1M.
 * Не буде перевищувати справжню верхню межу.
 */
internal val contextWindowPresets = listOf(128_000, 200_000, 256_000, 1_000_000)

private val modelDialogBodyMaxHeight = 520.dp
private val modelDialogChromeHeight = 112.dp

private fun Modifier.modelDialogScrollableBody(): Modifier = layout { measurable, constraints ->
    // Звільніть місце для заголовка, інтервалу та панелі дій із фактичних обмежень спливаючого вікна, щоб запобігти видавлюванню нижньої кнопки за межі вмістом, коли екран горизонтальний.
    val fallbackMaxHeight = modelDialogBodyMaxHeight.roundToPx()
    val reservedHeight = modelDialogChromeHeight.roundToPx()
    val maxHeight = if (constraints.hasBoundedHeight) {
        (constraints.maxHeight - reservedHeight)
            .coerceAtLeast(1)
            .coerceAtMost(fallbackMaxHeight)
    } else {
        fallbackMaxHeight
    }
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = constraints.minHeight.coerceAtMost(maxHeight),
            maxHeight = maxHeight,
        ),
    )
    layout(placeable.width, placeable.height) {
        placeable.place(0, 0)
    }
}

internal fun contextWindowInputError(
    value: String,
    errorMessage: String = "Context window must be a positive integer",
): String? {
    val normalized = value.trim()
    return if (normalized.toIntOrNull()?.let { it > 0 } == true) {
        null
    } else {
        errorMessage
    }
}

internal fun filterProviderModels(models: List<Model>, query: String): List<Model> {
    val search = ModelSearchQuery(query)
    return models
        .sortedBy { it.sortOrder }
        .filter { model -> search.matches(model.displayName, model.modelId) }
}

@Composable
internal fun ProviderModelsTab(
    provider: ProviderSetting,
    scope: CoroutineScope,
    scrollBehavior: ScrollBehavior,
    contentSidePadding: Dp,
) {
    val context = LocalContext.current
    val fetchScope = rememberCoroutineScope()
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    var isFetching by remember { mutableStateOf(false) }
    var isMutatingModel by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var isCreatingModel by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var modelPendingDelete by remember { mutableStateOf<Model?>(null) }
    var selectionMode by remember(provider.id) { mutableStateOf(false) }
    // Вибраний набір не буде очищено під час виходу з множинного вибору: панель редагування та кнопка видалення все ще знаходяться в анімації виходу, і після їх очищення число блимає на 0.
    // Наступного разу, коли ви ввімкнете мультивибір, уся модель буде замінена моделлю, яка довго натискалася в той час, і стара колекція не буде перенесена в новий раунд вибору.
    var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var modelSearchQuery by remember(provider.id) { mutableStateOf("") }
    var pendingImport by remember(provider.id) { mutableStateOf<RemoteModelImport?>(null) }
    val normalizedModelSearchQuery = modelSearchQuery.trim()
    val filteredModels = remember(provider.models, modelSearchQuery) {
        filterProviderModels(provider.models, modelSearchQuery)
    }

    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = contentSidePadding,
                end = contentSidePadding,
            ),
            overscrollEffect = null,
        ) {
            // Коли ви вибираєте декілька елементів, «Керування моделлю» згортається й замінюється панеллю редагування на стелі: тут розміщуються кількість і вибір, а внизу залишається лише видалення.
            // Два записи існують завжди, і лише анімація розміру виконується внутрішньо: список нижче постійно рухається вгору зі зміною висоти,
            // Замість того, щоб спочатку стрибати, а потім заповнювати зміщення, коли запис додається або видаляється.
            stickyHeader(key = "selection_header", contentType = "selection_header") {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = expandVertically(selectionMotion(), expandFrom = Alignment.Top) +
                        fadeIn(selectionFade(delayMillis = SelectionMotionMillis / 3)),
                    exit = shrinkVertically(selectionMotion(), shrinkTowards = Alignment.Top) +
                        fadeOut(selectionFade()),
                ) {
                    val visibleIds = filteredModels.mapTo(mutableSetOf()) { it.id }
                    val allVisibleSelected = visibleIds.isNotEmpty() && selectedModelIds.containsAll(visibleIds)
                    ModelSelectionHeader(
                        selectedCount = selectedModelIds.size,
                        allVisibleSelected = allVisibleSelected,
                        enabled = !isFetching && !isMutatingModel && visibleIds.isNotEmpty(),
                        // Вибрати все застосовується лише до поточних результатів пошуку: спочатку виконайте пошук, а потім виберіть усе, ви можете очистити один тип моделі за раз.
                        onToggleAll = {
                            selectedModelIds = if (allVisibleSelected) {
                                selectedModelIds - visibleIds
                            } else {
                                selectedModelIds + visibleIds
                            }
                        },
                        onExit = {
                            selectionMode = false
                        },
                    )
                }
            }
            item(key = "actions", contentType = "section") {
                AnimatedVisibility(
                    visible = !selectionMode,
                    // Картка спочатку зникає, а потім зменшується у висоті, щоб уникнути враження, що вміст здавлений; коли він повертається, він спочатку розширюється, а потім зникає.
                    enter = expandVertically(selectionMotion(), expandFrom = Alignment.Top) +
                        fadeIn(selectionFade(delayMillis = SelectionMotionMillis / 3)),
                    exit = shrinkVertically(selectionMotion(), shrinkTowards = Alignment.Top) +
                        fadeOut(selectionFade()),
                ) {
                    ProviderSection(title = stringResource(R.string.ui_model_management_183414)) {
                        EtaArrowPreference(
                            title = if (isFetching) context.getString(R.string.page_retrieving_a880c9)
                            else context.getString(R.string.page_automatically_pull_from_remote_f883d0),
                            summary = stringResource(R.string.provider_models_endpoint_summary, provider.baseUrl),
                            enabled = !isFetching && !isMutatingModel,
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.CloudDownload,
                                    enabled = !isFetching && !isMutatingModel,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = {
                                fetchScope.launch {
                                    isFetching = true
                                    message = null
                                    try {
                                        val catalog = RemoteModelFetcher.fetchCatalog(provider).getOrElse { throwable ->
                                            message = context.getString(
                                                R.string.provider_error,
                                                throwable.message ?: throwable.javaClass.simpleName,
                                            )
                                            return@launch
                                        }
                                        if (ProviderAuthModes.isOAuth(provider.authMode)) {
                                            val sync = ModelRepository.syncRemoteCatalog(catalog)
                                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                            message = "вжете самекрок ${sync.fetchedCount} індивідуальнийдалекокінецьцвільтипу"
                                            return@launch
                                        }
                                        val candidates = ModelRepository.newRemoteModels(provider.id, catalog.models)
                                        if (candidates.isNotEmpty()) {
                                            pendingImport = RemoteModelImport(catalog, candidates)
                                            return@launch
                                        }
                                        // Якщо немає нових моделей, синхронізація все одно виконується один раз, метадані доданих моделей оновлюються, а віддалені автономні моделі видаляються.
                                        val sync = ModelRepository.syncRemoteCatalog(catalog, emptySet())
                                        if (sync.applied) {
                                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                        }
                                        message = if (sync.applied) {
                                            context.getString(R.string.provider_models_up_to_date)
                                        } else {
                                            context.getString(R.string.page_the_remote_end_did_not_return_a_usable_conversation__781487)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (throwable: Throwable) {
                                        message = context.getString(
                                            R.string.provider_error,
                                            throwable.message ?: context.getString(R.string.provider_sync_failed),
                                        )
                                    } finally {
                                        isFetching = false
                                    }
                                }
                            },
                        )

                        EtaArrowPreference(
                            title = stringResource(R.string.ui_add_custom_model_a5ddc0),
                            summary = stringResource(R.string.ui_manually_fill_in_the_display_name_and_model_id_077a7b),
                            enabled = !isFetching && !isMutatingModel,
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Add,
                                    enabled = !isFetching && !isMutatingModel,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = {
                                editorError = null
                                isCreatingModel = true
                                editingModel = Model(
                                    id = "",
                                    modelId = "",
                                    displayName = context.getString(R.string.page_custom_model_25be0f),
                                )
                            },
                        )
                        message?.let {
                            EtaPreferenceDivider(hasLeading = false)
                            Text(
                                text = it,
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (it.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            item(key = "model_search", contentType = "search") {
                InputField(
                    query = modelSearchQuery,
                    onQueryChange = { modelSearchQuery = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.ui_search_model_df5586),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 8.dp),
                )
            }

            val modelListTitle = if (normalizedModelSearchQuery.isBlank()) {
                context.getString(R.string.provider_model_list_count, provider.models.size)
            } else {
                context.getString(
                    R.string.provider_model_list_matches,
                    filteredModels.size,
                    provider.models.size,
                )
            }
            if (provider.models.isEmpty() || filteredModels.isEmpty()) {
                item(key = "models_empty", contentType = "empty") {
                    ProviderSection(
                        title = modelListTitle,
                        // Відповідно до інтервалу 24 dp у кінці непорожнього списку: група має 16 dp плюс 8 dp.
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (provider.models.isEmpty()) {
                                    context.getString(R.string.page_there_is_no_model_yet_please_pull_it_from_the_remote_ced865)
                                } else {
                                    context.getString(R.string.page_no_matching_model_found_ae7e96)
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            } else {
                item(key = "models_title", contentType = "section_title") {
                    EtaPreferenceGroupTitle(modelListTitle)
                }
                itemsIndexed(
                    items = filteredModels,
                    key = { _, model -> "model:${model.id}" },
                    contentType = { _, _ -> "model" },
                ) { index, model ->
                    EtaPreferenceGroupItem(
                        isFirst = index == 0,
                        isLast = index == filteredModels.lastIndex,
                    ) {
                        ModelListItem(
                            model = model,
                            enabled = !isFetching && !isMutatingModel,
                            isSelected = model.id == selectedModelId,
                            selectionMode = selectionMode,
                            checked = model.id in selectedModelIds,
                            onToggleChecked = {
                                selectedModelIds = if (model.id in selectedModelIds) {
                                    selectedModelIds - model.id
                                } else {
                                    selectedModelIds + model.id
                                }
                            },
                            onEnterSelection = {
                                selectionMode = true
                                selectedModelIds = setOf(model.id)
                            },
                            onEdit = {
                                editorError = null
                                isCreatingModel = false
                                editingModel = model
                            },
                            onSetCurrent = {
                                scope.launch {
                                    try {
                                        RuntimeConfigRepository.setSelectedModelId(model.id)
                                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: ProviderAuthException) {
                                        message = context.getString(
                                            R.string.provider_error,
                                            failure.message ?: context.getString(R.string.state_ui_model_switching_failed_please_try_again_later_4af439),
                                        )
                                    } catch (_: Exception) {
                                        message = context.getString(
                                            R.string.provider_error,
                                            context.getString(R.string.state_ui_model_switching_failed_please_try_again_later_4af439),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                item(key = "models_section_gap", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                // Коли панель операцій із множинним вибором висить унизу, зарезервуйте висоту, щоб уникнути блокування останнього елемента списку; в інших випадках дотримуйтесь відстані від нижнього краю великого закругленого екрана.
                Spacer(
                    modifier = Modifier
                        .height(if (selectionMode) 88.dp else 24.dp)
                        .navigationBarsPadding(),
                )
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(selectionMotion()) { it } + fadeIn(selectionFade()),
            exit = slideOutVertically(selectionMotion()) { it } + fadeOut(selectionFade()),
        ) {
            ModelDeleteBar(
                selectedCount = selectedModelIds.size,
                enabled = !isFetching && !isMutatingModel,
                onDelete = { showBatchDeleteDialog = true },
            )
        }
    }

    editingModel?.let { model ->
        ModelEditDialog(
            nativeGemini = provider is GeminiProviderSetting,
            model = model,
            isNew = isCreatingModel,
            isSaving = isMutatingModel,
            error = editorError,
            onDismiss = {
                if (!isMutatingModel) editingModel = null
            },
            onSubmit = { updated ->
                if (isMutatingModel) return@ModelEditDialog
                scope.launch {
                    isMutatingModel = true
                    editorError = null
                    try {
                        val saved = ModelRepository.saveModel(provider.id, updated)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        editingModel = null
                        message = context.getString(R.string.provider_model_saved, saved.displayName)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        editorError = throwable.message ?: context.getString(R.string.page_save_failed_40525a)
                    } finally {
                        isMutatingModel = false
                    }
                }
            },
            onDelete = if (isCreatingModel) null else {
                {
                    modelPendingDelete = model
                    editingModel = null
                }
            },
        )
    }

    modelPendingDelete?.let { model ->
        EtaOverlayDialog(
            show = true,
            title = stringResource(R.string.ui_delete_model_cf24da),
            summary = stringResource(R.string.provider_model_delete_summary, model.displayName),
            onDismissRequest = { if (!isMutatingModel) modelPendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = if (isMutatingModel) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isMutatingModel,
                confirmEnabled = !isMutatingModel,
                destructive = true,
                onCancel = { modelPendingDelete = null },
                onConfirm = {
                    scope.launch {
                        isMutatingModel = true
                        try {
                            ModelRepository.deleteModel(provider.id, model.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            message = context.getString(R.string.provider_model_deleted, model.displayName)
                            modelPendingDelete = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            message = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            modelPendingDelete = null
                        } finally {
                            isMutatingModel = false
                        }
                    }
                },
            )
        }
    }

    if (showBatchDeleteDialog) {
        EtaOverlayDialog(
            show = true,
            title = stringResource(R.string.ui_delete_model_cf24da),
            summary = pluralStringResource(
                R.plurals.provider_selected_delete_summary,
                selectedModelIds.size,
                selectedModelIds.size,
            ),
            onDismissRequest = { if (!isMutatingModel) showBatchDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isMutatingModel) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isMutatingModel,
                confirmEnabled = !isMutatingModel,
                destructive = true,
                onCancel = { showBatchDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        val deletedCount = selectedModelIds.size
                        isMutatingModel = true
                        try {
                            ModelRepository.deleteModels(provider.id, selectedModelIds)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            message = context.resources.getQuantityString(
                                R.plurals.provider_models_deleted,
                                deletedCount,
                                deletedCount,
                            )
                            showBatchDeleteDialog = false
                            selectionMode = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            message = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            showBatchDeleteDialog = false
                        } finally {
                            isMutatingModel = false
                        }
                    }
                },
            )
        }
    }

    RemoteModelImportSheet(
        pending = pendingImport,
        onDismissRequest = { pendingImport = null },
        onConfirm = { pending, adopted ->
            pendingImport = null
            scope.launch {
                isMutatingModel = true
                try {
                    val sync = ModelRepository.syncRemoteCatalog(pending.catalog, adopted)
                    if (sync.applied) {
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    }
                    message = when {
                        !sync.applied -> context.getString(R.string.page_the_remote_end_did_not_return_a_usable_conversation__781487)
                        adopted.isEmpty() -> context.getString(R.string.provider_models_up_to_date)
                        else -> context.resources.getQuantityString(
                            R.plurals.provider_models_added,
                            sync.addedCount,
                            sync.addedCount,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    message = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_sync_failed),
                    )
                } finally {
                    isMutatingModel = false
                }
            }
        },
    )
}

/** Уніфікований ритм для входу та виходу з кількох виділень: підкреслена крива уповільнення з різким початком і м’яким кінцем, з кількома анімаціями однакової тривалості.*/
private const val SelectionMotionMillis = 360
private val SelectionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun <T> selectionMotion() = tween<T>(SelectionMotionMillis, easing = SelectionEasing)

private fun selectionFade(delayMillis: Int = 0) =
    tween<Float>(SelectionMotionMillis / 2, delayMillis = delayMillis, easing = LinearEasing)

/**
 * Панель редагування стелі в режимі множинного вибору: скасування ліворуч, вибрана кількість у центрі та вибір усіх праворуч.
 * Використовуйте текст з обох боків і вирівнюйте тіло списку та прапорці за тим самим лівим і правим краями (згрупуйте зовнішні поля плюс внутрішні поля лінії);
 * Область натискання розширюється назовні, а візуальні краї залишаються незмінними.
 */
@Composable
private fun ModelSelectionHeader(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val edge = EtaPreferenceDefaults.SidePadding + 16.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = edge - SelectionHeaderTouchInset, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionHeaderAction(
            text = stringResource(R.string.action_cancel),
            color = MiuixTheme.colorScheme.onSurface,
            enabled = true,
            onClick = onExit,
        )
        Text(
            text = pluralStringResource(R.plurals.provider_models_selected, selectedCount, selectedCount),
            style = MiuixTheme.textStyles.headline1,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        SelectionHeaderAction(
            text = stringResource(if (allVisibleSelected) R.string.page_select_none_ba20eb else R.string.page_select_all_3e44b2),
            color = MiuixTheme.colorScheme.primary,
            enabled = enabled,
            onClick = onToggleAll,
        )
    }
}

@Composable
private fun SelectionHeaderAction(
    text: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1,
        color = if (enabled) color else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = SelectionHeaderTouchInset, vertical = 10.dp),
    )
}

private val SelectionHeaderTouchInset = 8.dp

/** Розташуйте лише одну дію видалення внизу режиму множинного вибору та вкажіть кількість, щоб уникнути нечіткої видимості діапазону під час помилкового видалення.*/
@Composable
private fun ModelDeleteBar(
    selectedCount: Int,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    val active = enabled && selectedCount > 0
    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 32.dp)
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .squircleSurface(
                color = if (active) {
                    MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
                cornerRadius = 26.dp,
            )
            .clickable(enabled = active, onClick = onDelete)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (active) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.disabledOnSecondaryVariant
        Icon(
            imageVector = Icons.Rounded.DeleteOutline,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (selectedCount > 0) {
                pluralStringResource(R.plurals.provider_delete_models_action, selectedCount, selectedCount)
            } else {
                stringResource(R.string.provider_delete_models_pick_hint)
            },
            style = MiuixTheme.textStyles.button,
            color = tint,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelListItem(
    model: Model,
    enabled: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
    onEdit: () -> Unit,
    onSetCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val rowTint by animateColorAsState(
        targetValue = if (selectionMode && checked) {
            MiuixTheme.colorScheme.primary.copy(alpha = 0.06f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "model_row_tint",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawRect(rowTint) }
            .combinedClickable(
                enabled = enabled,
                onClick = if (selectionMode) onToggleChecked else onSetCurrent,
                onLongClick = {
                    if (selectionMode) onToggleChecked() else onEnterSelection()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MiuixTheme.textStyles.headline1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.modelId,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                capabilityTags(model).forEach { tag ->
                    TagChip(text = tag)
                }
                if (isSelected) {
                    TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
                }
            }
        }
        if (selectionMode) {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggleChecked,
                enabled = enabled,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = stringResource(R.string.ui_edit_model_parameters_ba4864),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                IconButton(onClick = onSetCurrent, enabled = enabled) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.Check
                            else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) context.getString(R.string.page_current_model_a0af8f) else context.getString(R.string.page_set_as_current_model_183d7d),
                        tint = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelEditDialog(
    nativeGemini: Boolean,
    model: Model,
    isNew: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (Model) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    var displayName by remember(model.id, isNew) { mutableStateOf(model.displayName) }
    var modelId by remember(model.id, isNew) { mutableStateOf(model.modelId) }
    var contextWindowOverrideText by remember(model.id, isNew) {
        // Моделі, які були отримані раніше, але не записані у вікно, також мають віддалені анотації, які попередньо заповнюються безпосередньо під час відкриття для редагування.
        mutableStateOf((model.contextWindowOverride ?: model.contextWindow)?.toString().orEmpty())
    }
    var reasoningOverrideActive by remember(model.id, isNew) {
        mutableStateOf(
            model.reasoningOverride != null || model.reasoningCapabilitiesOverride != null
        )
    }
    var reasoningEnabled by remember(model.id, isNew) {
        mutableStateOf(model.supportsReasoning)
    }
    var selectedReasoningEfforts by remember(model.id, isNew) {
        mutableStateOf(
            model.effectiveReasoningCapabilities
                ?.selectableEfforts
                ?.toSet()
                .orEmpty() + ReasoningEffort.DEFAULT
        )
    }
    val contextError = contextWindowInputError(contextWindowOverrideText)
    val geminiCapabilities = if (nativeGemini) GeminiModelCapabilities.requestReasoningCapabilities(modelId) else null

    fun resetAutomaticReasoning() {
        reasoningOverrideActive = false
        reasoningEnabled = model.reasoning == true
        selectedReasoningEfforts = model.reasoningCapabilities
            ?.selectableEfforts
            ?.toSet()
            .orEmpty() + ReasoningEffort.DEFAULT
    }

    fun updated(): Model = model.copy(
        displayName = displayName.trim(),
        modelId = modelId.trim(),
        contextWindowOverride = contextWindowOverrideText.trim()
            .takeIf(String::isNotEmpty)
            ?.toInt(),
        reasoningOverride = reasoningEnabled.takeIf { reasoningOverrideActive && geminiCapabilities == null },
        reasoningCapabilitiesOverride = if (reasoningOverrideActive && reasoningEnabled && geminiCapabilities == null) {
            val canDisable = ReasoningEffort.OFF in selectedReasoningEfforts
            (model.effectiveReasoningCapabilities ?: ModelReasoningCapabilities()).copy(
                supportedEfforts = editableReasoningEfforts.filter { effort ->
                    effort != ReasoningEffort.OFF && effort in selectedReasoningEfforts
                },
                defaultEffort = model.effectiveReasoningCapabilities
                    ?.defaultEffort
                    ?.takeIf { it in selectedReasoningEfforts },
                defaultEnabled = true,
                mandatory = !canDisable,
                canDisable = canDisable,
            )
        } else {
            null
        },
    )

    EtaOverlayDialog(
        show = true,
        title = if (isNew) context.getString(R.string.page_add_model_532a64) else context.getString(R.string.page_edit_model_29e31e),
        onDismissRequest = { if (!isSaving) onDismiss() },
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .modelDialogScrollableBody()
                    .scrollEndHaptic()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = stringResource(R.string.ui_display_name_ed16be),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = "Model ID",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = contextWindowOverrideText,
                    onValueChange = { value -> contextWindowOverrideText = value.filter(Char::isDigit) },
                    label = stringResource(R.string.ui_context_length_tokens_227860),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                val contextValue = contextWindowOverrideText.toIntOrNull()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    contextWindowPresets.forEach { preset ->
                        EtaChoiceChip(
                            text = formatCompactTokenCount(preset),
                            selected = contextValue == preset,
                            onClick = { contextWindowOverrideText = preset.toString() },
                            enabled = !isSaving,
                        )
                    }
                }
                Text(
                    text = when {
                        contextWindowOverrideText.isBlank() ->
                            stringResource(R.string.provider_context_window_hint)
                        contextError != null ->
                            stringResource(R.string.provider_context_window_invalid)
                        contextValue != null && contextValue !in contextWindowPresets ->
                            stringResource(R.string.provider_context_window_value, formatCompactTokenCount(contextValue))
                        else -> stringResource(R.string.provider_context_window_usage)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (contextError != null && contextWindowOverrideText.isNotBlank()) {
                        StatusError
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                // Картки більше не вкладаються у спливаюче вікно: картка та спливаюче вікно мають однаковий колір і не мають видимої межі, а відступ лише зміщує цей розділ від лівого краю.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isSaving && geminiCapabilities == null) {
                            reasoningOverrideActive = true
                            reasoningEnabled = !reasoningEnabled
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (geminiCapabilities != null && !geminiCapabilities.mandatory) "думаюТестфайлибітзробитивикористовувати Default"
                                else stringResource(R.string.ui_support_thinking_5b9e4c),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (geminiCapabilities != null) {
                                if (geminiCapabilities.mandatory) "СлідцвільтипуповиненОбов'язководумаюТест，немаєвідділенняутримувати Off；Default зробитивикористовуватицвільтипумовчазнийрозпізнатифайлибіт。"
                                else "досіще ніправильнорозпізнатиСлідцвільтипуздумаюТестфайлибіт；тількизробитивикористовувати Default，немаєволоссядоставитиоскількиЗвичайноправеднийдумаюТестфайлибіт。"
                            } else if (reasoningOverrideActive) {
                                stringResource(R.string.page_covered_model_automatic_capabilities_3fa7d4)
                            } else {
                                stringResource(
                                    if (model.reasoning == true) {
                                        R.string.provider_auto_reasoning_detected
                                    } else {
                                        R.string.provider_auto_reasoning_unknown
                                    },
                                )
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    EtaSwitch(
                        checked = geminiCapabilities != null || reasoningEnabled,
                        onCheckedChange = { enabled ->
                            reasoningOverrideActive = true
                            reasoningEnabled = enabled
                        },
                        enabled = !isSaving && geminiCapabilities == null,
                    )
                }
                if (geminiCapabilities != null || reasoningEnabled) {
                    // За замовчуванням завжди доступний (залишається на вибір моделі або постачальника) і не відображається як варіант.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        (geminiCapabilities?.supportedEfforts ?: editableReasoningEfforts).forEach { effort ->
                            val checked = geminiCapabilities != null || effort in selectedReasoningEfforts
                            EtaChoiceChip(
                                text = effort.displayName,
                                selected = checked,
                                onClick = {
                                    reasoningOverrideActive = true
                                    selectedReasoningEfforts = if (checked) {
                                        selectedReasoningEfforts - effort
                                    } else {
                                        selectedReasoningEfforts + effort
                                    }
                                },
                                enabled = !isSaving && geminiCapabilities == null,
                            )
                        }
                    }
                }
                Text(
                    text = if (nativeGemini) "Gemini ОригіналнародивсянавітьзловитиТількиволоссядоставитивжеправильнорозпізнативідділенняутримуватиздумаюТестфайлибіт。"
                        else stringResource(R.string.ui_only_check_the_ranges_actually_supported_by_the_mode_2c343d),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                error?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = StatusError,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (reasoningOverrideActive || onDelete != null) {
                    // Текстові посилання зберігають легкий вигляд, але область натискання розширена до 40 dp у висоту, щоб уникнути випадкових дотиків у нижній частині спливаючого вікна.
                    Row(
                        modifier = Modifier.padding(top = 4.dp).offset(x = (-4).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (reasoningOverrideActive) {
                            Text(
                                text = stringResource(R.string.ui_restore_automatic_8d4e1e),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(
                                        enabled = !isSaving,
                                        onClick = ::resetAutomaticReasoning,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        }
                        onDelete?.let { delete ->
                            Text(
                                text = stringResource(R.string.ui_delete_model_cf24da),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !isSaving, onClick = delete)
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        MiuixDialogActions(
            confirmText = if (isSaving) context.getString(R.string.page_saving_d70d42) else context.getString(R.string.page_save_fadf24),
            confirmEnabled = !isSaving &&
                displayName.isNotBlank() &&
                modelId.isNotBlank() &&
                contextError == null,
            cancelEnabled = !isSaving,
            onCancel = onDismiss,
            onConfirm = { onSubmit(updated()) },
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun capabilityTags(model: Model): List<String> {
    val context = LocalContext.current
    return buildList {
    add(
        model.effectiveContextWindow?.let { contextWindow ->
            stringResource(
                R.string.provider_context_tag,
                formatCompactTokenCount(contextWindow),
            )
        } ?: context.getString(R.string.page_context_unknown_b6ae7b)
    )
    if (model.supportsReasoning) add(context.getString(R.string.page_support_thinking_5b9e4c))
    }
}
