package io.github.mangi.eta.agent.accessibility

/** Відбиток ідентичності вузла, який можна перевірити в JVM; viewId не є унікальним ідентифікатором для елемента списку.*/
internal data class AccessibilityNodeIdentity(
    val uniqueId: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewId: String,
    val text: String,
    val description: String,
    val password: Boolean,
) {
    val strong: Boolean
        get() = uniqueId.isNotBlank() || text.isNotBlank() || description.isNotBlank()

    fun matches(refreshed: AccessibilityNodeIdentity): Boolean {
        if (windowId != refreshed.windowId) return false
        if (packageName != refreshed.packageName) return false
        if (className != refreshed.className) return false
        if (password != refreshed.password) return false
        if (uniqueId != refreshed.uniqueId) return false
        if (viewId.isNotBlank() && viewId != refreshed.viewId) return false
        // uniqueId лише доводить, що це той самий віртуальний вузол, але не доводить, що він все ще виражає семантику дії під час спостереження моделі.
        if (text != refreshed.text) return false
        if (description != refreshed.description) return false
        return true
    }
}

/**
 * Після зміни вмісту вікна можна продовжувати використовувати лише ідентифікаційні дані, які є справді стабільними та доведено унікальними в межах діапазону спостереження.
 * Обрізані знімки не доводять того, що відбитки тексту/опису не дублюються в решті вікна.
 */
internal object AccessibilityIdentityFreshnessPolicy {
    fun canBypassContentChange(
        hasUniqueId: Boolean,
        snapshotTruncated: Boolean,
        identityMatchCount: Int,
    ): Boolean =
        identityMatchCount == 1 && (hasUniqueId || !snapshotTruncated)
}
