package io.github.mangi.eta.agent.accessibility

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Сторона програми лише виражає перемикання захисту та запити на відновлення; Безпечні параметри завжди підтримуються серверною частиною system_server.
 */
internal object AccessibilityProtectionClient {
    private const val PREFERENCES_NAME = "accessibility_protection"
    private const val PREFERENCE_ENABLED = "enabled"
    private const val CONTROL_TIMEOUT_MS = 2_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun isEnabled(context: Context): Boolean {
        if (!isSupported()) return false
        val appContext = context.applicationContext
        val fallback = appContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                PREFERENCE_ENABLED,
                AccessibilityProtectionProtocol.DEFAULT_ENABLED,
            )
        return try {
            Settings.Global.getInt(
                appContext.contentResolver,
                AccessibilityProtectionProtocol.SETTING_NAME,
                if (fallback) 1 else 0,
            ) == 1
        } catch (_: RuntimeException) {
            fallback
        }
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
        onResult: (ControlResult) -> Unit,
    ) {
        sendRequest(
            context = context.applicationContext,
            action = AccessibilityProtectionProtocol.ACTION_SET,
            enabled = enabled,
            scheduler = mainHandler,
        ) { result ->
            if (result.status == ControlStatus.APPLIED) {
                context.applicationContext
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREFERENCE_ENABLED, result.enabled)
                    .apply()
            }
            onResult(result)
        }
    }

    fun requestRecoveryBlocking(context: Context): ControlStatus {
        if (!isSupported() || Looper.myLooper() == Looper.getMainLooper()) {
            return ControlStatus.UNAVAILABLE
        }
        val latch = CountDownLatch(1)
        var status = ControlStatus.UNAVAILABLE
        sendRequest(
            context = context.applicationContext,
            action = AccessibilityProtectionProtocol.ACTION_RECOVER,
            enabled = true,
            scheduler = mainHandler,
        ) { result ->
            status = result.status
            latch.countDown()
        }
        val completed = runCatching {
            latch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        return if (completed) status else ControlStatus.UNAVAILABLE
    }

    private fun sendRequest(
        context: Context,
        action: String,
        enabled: Boolean,
        scheduler: Handler,
        onResult: (ControlResult) -> Unit,
    ) {
        // Сервер захисту покладається на ідентифікатор відправника API 34, і застарілі системи не можуть замінити перевірку UID іменами пакетів або додатковими полями.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            scheduler.post { onResult(ControlResult(ControlStatus.UNAVAILABLE, enabled = false)) }
            return
        }
        val intent = Intent(action)
            .setPackage(AccessibilityProtectionProtocol.RECEIVER_PACKAGE)
            .putExtra(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                AccessibilityProtectionProtocol.VERSION,
            )
            .putExtra(AccessibilityProtectionProtocol.EXTRA_ENABLED, enabled)
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent?) {
                val actualEnabled = getResultExtras(false)?.getBoolean(
                    AccessibilityProtectionProtocol.EXTRA_ENABLED,
                    isEnabled(context),
                ) ?: isEnabled(context)
                onResult(
                    ControlResult(
                        status = resultCode.toControlStatus(),
                        enabled = actualEnabled,
                    ),
                )
            }
        }

        try {
            // Починаючи з Android 14, за замовчуванням трансляції не передають дані про відправника; сервер захисту повинен отримати справжній UID перед тим, як прийняти запит.
            val options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            context.sendOrderedBroadcast(
                intent,
                null,
                options,
                resultReceiver,
                scheduler,
                AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                null,
                null,
            )
        } catch (_: RuntimeException) {
            scheduler.post {
                onResult(
                    ControlResult(
                        status = ControlStatus.UNAVAILABLE,
                        enabled = isEnabled(context),
                    ),
                )
            }
        }
    }

    data class ControlResult(
        val status: ControlStatus,
        val enabled: Boolean,
    )

    enum class ControlStatus {
        APPLIED,
        UNAVAILABLE,
        REJECTED,
    }

    private fun Int.toControlStatus(): ControlStatus = when (this) {
        AccessibilityProtectionProtocol.RESULT_APPLIED -> ControlStatus.APPLIED
        AccessibilityProtectionProtocol.RESULT_REJECTED -> ControlStatus.REJECTED
        else -> ControlStatus.UNAVAILABLE
    }
}
