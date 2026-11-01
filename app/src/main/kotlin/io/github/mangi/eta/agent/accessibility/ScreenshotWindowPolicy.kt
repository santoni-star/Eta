package io.github.mangi.eta.agent.accessibility

/** Фільтрація вікна знімка екрана має відповідати тому, що бачить модель, вікну, яке фактично отримує дотики.*/
internal object ScreenshotWindowPolicy {
    enum class Decision {
        CAPTURE,
        EXCLUDE,
        BLOCK_UNKNOWN,
    }

    fun decide(
        isAccessibilityOverlay: Boolean,
        isApplicationWindow: Boolean,
        active: Boolean,
        focused: Boolean,
        resolvedPackage: String?,
        ownPackage: String,
        excludedPackages: Set<String>,
    ): Decision {
        if (isAccessibilityOverlay && resolvedPackage == ownPackage) {
            return Decision.EXCLUDE
        }
        if (resolvedPackage in excludedPackages) return Decision.EXCLUDE
        if (
            excludedPackages.isNotEmpty() &&
            resolvedPackage.isNullOrBlank() &&
            (isApplicationWindow || active || focused)
        ) {
            return Decision.BLOCK_UNKNOWN
        }
        return Decision.CAPTURE
    }
}
