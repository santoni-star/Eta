package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Уніфікований скелет сторінки вторинного списку Eta: мобільний телефон, що складається заголовок, широкоформатний фіксований субтитр, верхня панель матове скло, горизонтальна безпечна зона екрана,
 * Центрування широкоекранного вмісту, торкання до межі прокручування та транскордонний відскок – усе це передбачено тут.
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    compactTitle: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    EtaPreferenceTheme {
        val scrollBehavior = MiuixScrollBehavior()
        val backdrop = rememberTopBarBackdrop()
        val topBarColor = topBarContainerColor(backdrop)

        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopBarBackdrop(backdrop) {
                    // Якщо сторінка має власний основний візуал (наприклад, деталі персонажа), використовуйте компактний заголовок, щоб уникнути дублювання великого заголовка та основного візуалу.
                    if (compactTitle) {
                        SmallTopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        AdaptiveTopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { innerPadding ->
            WidePageContent { sidePadding ->
                // Зберігайте фабрику за замовчуванням поза межами, введену MiuixTheme, щоб сторінки з коротким вмістом також могли повертатися назад до області вибірки верхньої панелі.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding()
                        .captureForTopBar(backdrop)
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = sidePadding,
                        top = innerPadding.calculateTopPadding(),
                        end = sidePadding,
                    ),
                ) {
                    content()
                    item(key = "bottom_spacer") {
                        MiuixPageBottomSpacer()
                    }
                }
            }
            overlay()
        }
    }
}

/**
 * Налаштуйте низькорівневий скелет вторинних сторінок вмісту. Виклик відповідає за поєднання верхнього відступу, горизонтальної безпечної області та вкладеного прокручування
 * Доступ до власного вмісту; [sidePadding] використовується для обмеження фактичної ширини вмісту на широкому екрані, сам контейнер прокручування має залишатися повної ширини.
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    EtaPreferenceTheme {
        val scrollBehavior = MiuixScrollBehavior()
        val backdrop = rememberTopBarBackdrop()
        val topBarColor = topBarContainerColor(backdrop)

        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopBarBackdrop(backdrop) {
                    AdaptiveTopAppBar(
                        title = title,
                        color = topBarColor,
                        navigationIcon = { MiuixBackButton(onClick = onBack) },
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                WidePageContent { sidePadding ->
                    content(paddingValues, scrollBehavior, sidePadding)
                }
            }
        }
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
