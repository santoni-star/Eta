package io.github.mangi.eta.ui.components

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull

internal enum class ConversationPaneAnchor {
    Closed,
    Open,
}

internal fun conversationPaneSettledValues(
    state: AnchoredDraggableState<ConversationPaneAnchor>,
    isDragging: () -> Boolean,
): Flow<ConversationPaneAnchor> = snapshotFlow {
    val anchor = state.settledValue
    val position = state.anchors.positionOf(anchor)
    if (!isDragging() && !state.isAnimationRunning && abs(state.offset - position) < 0.5f) {
        anchor
    } else {
        null
    }
    // SnapshotFlow повинен спочатку спостерігати за рухом нуля перед фільтрацією; його також необхідно повторно синхронізувати під час повернення до вихідної точки прив’язки.
}.filterNotNull()
