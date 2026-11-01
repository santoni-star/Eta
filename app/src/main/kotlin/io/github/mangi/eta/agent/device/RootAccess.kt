package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal enum class RootAccessStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED, DENIED, TIMED_OUT }

internal data class RootAccessState(
    val status: RootAccessStatus = RootAccessStatus.UNKNOWN,
    val suPresent: Boolean = false,
    val isChecking: Boolean = false,
) {
    val isGranted: Boolean get() = status == RootAccessStatus.GRANTED
}

/** Виявлення кореня належить процесу App; можливості читання не виконуватимуть авторизацію su або popup.*/
internal object RootAccess {
    private const val PREFERENCES = "eta_root_access"
    private const val AUTOMATIC_REQUEST_ATTEMPTED = "automatic_request_attempted"
    private const val LAST_GRANTED = "last_granted"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted
    private var preferences: SharedPreferences? = null
    private var probeJob: Job? = null

    fun initialize(context: Context) {
        synchronized(this) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
        refresh(context)
    }

    /** Запитати su один раз, коли його вперше виявлено; після відхилення знову буде застосовано лише явний запит.*/
    fun refresh(context: Context): Job = startProbe(context, explicit = false)

    fun request(context: Context): Job = startProbe(context, explicit = true)

    /** Викликається лише після того, як виконавець підтвердить відхилення су; невиконання звичайних команд не означає, що авторизацію скасовано.*/
    fun markDenied() {
        preferences?.edit()?.putBoolean(LAST_GRANTED, false)?.apply()
        mutableState.value = mutableState.value.copy(status = RootAccessStatus.DENIED)
    }

    private fun startProbe(context: Context, explicit: Boolean): Job = synchronized(this) {
        probeJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        mutableState.value = mutableState.value.copy(isChecking = true)
        scope.launch {
            try {
                val present = suExists()
                if (!present && !explicit) {
                    mutableState.value = RootAccessState(RootAccessStatus.UNAVAILABLE)
                    return@launch
                }
                val attempted = prefs.getBoolean(AUTOMATIC_REQUEST_ATTEMPTED, false)
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                if (!shouldRequestRoot(explicit, attempted, wasGranted)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (mutableState.value.status == RootAccessStatus.UNKNOWN) {
                            RootAccessStatus.NOT_GRANTED
                        } else {
                            mutableState.value.status
                        },
                        suPresent = true,
                    )
                    return@launch
                }
                // Не забувайте спочатку спробувати. Якщо процес завершиться під час спливаючого вікна авторизації, він більше не турбуватиме користувача під час наступного запуску.
                if (!prefs.edit().putBoolean(AUTOMATIC_REQUEST_ATTEMPTED, true).commit()) {
                    mutableState.value = RootAccessState(RootAccessStatus.NOT_GRANTED, suPresent = true)
                    AndroidAgentLogger.warn("Root access probe outcome=preferences_unavailable")
                    return@launch
                }
                val result = runInterruptible {
                    BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { true }).use {
                        it.execute("id -u", timeoutMillis = 30_000L, maxOutputBytes = 256)
                    }
                }
                val granted = result.ok && result.stdout.trim() == "0"
                prefs.edit().putBoolean(LAST_GRANTED, granted).apply()
                mutableState.value = RootAccessState(
                    status = when {
                        granted -> RootAccessStatus.GRANTED
                        result.errorCode == "ROOT_UNAVAILABLE" -> RootAccessStatus.UNAVAILABLE
                        result.timedOut -> RootAccessStatus.TIMED_OUT
                        else -> RootAccessStatus.DENIED
                    },
                    suPresent = present || granted,
                )
            } finally {
                mutableState.value = mutableState.value.copy(isChecking = false)
            }
        }.also { probeJob = it }
    }

    private fun suExists(): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .filter(String::isNotBlank)
        .any { path -> File(path, "su").let { it.isFile && it.canExecute() } }
}

internal fun shouldRequestRoot(explicit: Boolean, attempted: Boolean, wasGranted: Boolean): Boolean =
    explicit || !attempted || wasGranted
