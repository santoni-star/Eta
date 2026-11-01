package io.github.mangi.eta.agent.device

/** Забороняється використовувати жести, коли інша вісь прокручування чітко відкрита, щоб уникнути помилкового перетворення прокручування на бокове ковзання елементів списку.*/
internal object ScrollAxisContract {
    fun exposesOnlyOppositeAxis(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean = when (requestedAxis) {
        ScrollAxis.VERTICAL -> hasHorizontalActions && !hasVerticalActions
        ScrollAxis.HORIZONTAL -> hasVerticalActions && !hasHorizontalActions
    }

    /** FORWARD/BACKWARD не має осьової семантики; вертикальна сумісність можлива лише тоді, коли є чіткі вертикальні та відсутні горизонтальні докази.*/
    fun mayTreatLegacyActionsAsVertical(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean =
        requestedAxis == ScrollAxis.VERTICAL &&
            hasVerticalActions &&
            !hasHorizontalActions
}
