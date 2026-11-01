package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.ConsoleSessionController
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalKey
import io.github.mangi.eta.agent.terminal.TerminalKeyEncoder
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.ptySupported
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Один кадр консолі: знімок рядка незмінний, рівень візуалізації повторно використовує записи за ідентифікатором і визначає, чи потрібно перемальовувати за версією.*/
@Immutable
internal data class ConsoleFrame(
    val lines: List<TerminalScreenBuffer.RenderLine> = emptyList(),
    val screenRows: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
)

@Immutable
internal data class ConsoleSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val exited: Boolean = false,
)

@Immutable
internal data class ConsoleUiState(
    val sessions: List<ConsoleSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** Вибір вкладки середовища; це узгоджується з поточним середовищем сеансу. Якщо сеансу немає, це вказує на цільове середовище нового сеансу.*/
    val environment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val connected: Boolean = false,
    val exited: Boolean = false,
    /** null = зондування; якщо false, запис повертається до терміналу блоку.*/
    val ptySupported: Boolean? = null,
    val failMessage: String? = null,
    /** Екран поточної сесії; екранний буфер кожного сеансу зберігається окремо.*/
    val frame: ConsoleFrame = ConsoleFrame(),
)

/**
 * Власник стану на рівні програми для сторінок консолі: підтримує кілька сеансів PTY з окремими екранними буферами,
 * Потік байтів подається в [TerminalScreenBuffer] у потоці введення-виведення, а кадри поточного сеансу публікуються в інтерфейсі користувача відповідно до ритму дроселювання.
 * Сеанс залишається активним після виходу зі сторінки; звичайні сеанси проводяться службою виконання на передньому плані та повторюються після зупинки користувача.
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        /** Верхня межа призупиненої публікації кадрів під час виведення синхронізації; коли програма пропускає позначку кінця, екран не зависне назавжди.*/
        const val SYNCHRONIZED_OUTPUT_TIMEOUT_MS = 200L
        const val SCROLLBACK_LINES = 1000
    }

    /** Натискання клавіш і відповіді на запити мають бути записані в стандартний вхід у порядку їх генерації; єдине одночасне планування гарантує FIFO, не займаючи основний потік.*/
    private val inputDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = ConsoleSessionController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val bufferLock = Any()
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    /** Незалежний екранний буфер для кожного сеансу; запис може виконуватися лише за допомогою потоку вводу-виводу, який утримує блокування, і потоку інтерфейсу користувача, який утримує блокування для читання знімка.*/
    private val buffers = mutableMapOf<String, TerminalScreenBuffer>()

    /** Поточний колір переднього/фонового плану за замовчуванням інтерфейсу, щоб TUI запитував темні та світлі теми; оновлено для всіх сеансів із системою перемикання темного та світлого кольорів.*/
    private var themeColors = 0xFF000000L to 0xFFFFFFFFL
    private val sessionSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
        )
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    // І потік читання PTY, і основний потік запускають кадри публікації.
    /**
     * Під час запуску сеансу (авторизація root і підготовка chroot можуть зайняти кілька секунд) розмір сітки все ще змінюватиметься за допомогою програмної клавіатури;
     * Протягом цього періоду буде записано та синхронізовано лише останній розмір, щоб уникнути запуску нового сеансу кожного разу, коли розмір змінюється. Читати і писати тільки в головній темі.
     */
    private var creatingSession = false

    @Volatile private var lastFlushMs = 0L
    @Volatile private var flushScheduled = false
    private var lastCols = 0
    private var lastRows = 0

    /** Виявлення передумови PTY; результат кешується в стані, а запис повертається до терміналу блоку, якщо він не підтримується.*/
    fun probePtySupport() {
        scope.launch {
            val supported = withContext(Dispatchers.IO) { ptySupported() }
            _uiState.update { it.copy(ptySupported = supported) }
        }
    }

    fun refreshLinuxEnvironment() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        _uiState.update { state ->
            state.copy(
                linuxEnvironment = selected,
                environment = if (state.environment.isLinux) selected else state.environment,
            )
        }
    }

    /**
     * Переконайтеся, що на даний момент є збережений сеанс; коли розмір сітки змінюється та вводиться повторно, лише розмір буде синхронізовано, а сеанс не буде перебудовано.
     * [середовище] використовується лише для нового створення, якщо немає повторних сеансів.
     */
    fun open(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        val state = _uiState.value
        val activeId = state.activeSessionId
        if (activeId != null && controller.sessionAlive(activeId)) {
            resizeSession(activeId, cols, rows)
            return
        }
        if (creatingSession) return
        if (activeId == null) {
            val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
            if (reusable != null && controller.sessionAlive(reusable.id)) {
                switchSession(reusable.id)
                return
            }
        }
        createSession(environment, cols, rows)
    }

    /** Створіть новий сеанс із поточним середовищем і останнім розміром сітки.*/
    fun newSession() {
        if (lastCols <= 0 || lastRows <= 0) return
        createSession(_uiState.value.environment, lastCols, lastRows)
    }

    fun switchSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        _uiState.update { state ->
            state.copy(
                activeSessionId = sessionId,
                environment = session.environment,
                connected = !session.exited,
                exited = session.exited,
                failMessage = null,
            )
        }
        // Сітка може змінитися (горизонтальний і вертикальний екран, програмна клавіатура), коли сеанс триває у фоновому режимі. При зворотному перемиканні він буде синхронізований з поточною сіткою.
        if (lastCols > 0 && lastRows > 0) resizeSession(sessionId, lastCols, lastRows)
        flushFrame(sessionId)
    }

    /** Закрити вказаний сеанс; під час закриття поточного сеансу переключитися на останній сеанс, що залишився, якщо ні, повернутися до порожнього стану.*/
    fun closeSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            val sessions = state.sessions.filterNot { it.id == sessionId }
            if (state.activeSessionId != sessionId) {
                state.copy(sessions = sessions)
            } else {
                val next = sessions.lastOrNull()
                state.copy(
                    sessions = sessions,
                    activeSessionId = next?.id,
                    environment = next?.environment ?: state.environment,
                    connected = next != null && !next.exited,
                    exited = next?.exited == true,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
        _uiState.value.activeSessionId?.let { flushFrame(it) }
    }

    /** Перезапустіть вказаний сеанс: після завершення перезапустіть відповідно до початкового середовища та початкового розміру сітки.*/
    fun restartSession(sessionId: String) {
        if (creatingSession) return
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val size = sessionSizes[sessionId] ?: (lastCols to lastRows)
        if (size.first <= 0 || size.second <= 0) return
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            state.copy(sessions = state.sessions.filterNot { it.id == sessionId })
        }
        createSession(session.environment, size.first, size.second)
    }

    /** Вкладка «Змінити середовище»: змінювати лише цільове середовище; якщо в цьому середовищі є збережений сеанс, він буде переключений на нього, інакше він буде створений зворотним викликом сітки.*/
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            _uiState.update {
                it.copy(
                    environment = environment,
                    activeSessionId = null,
                    connected = false,
                    exited = false,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
    }

    /** Повторне підключення з тим же середовищем і розміром сітки після відключення поточного сеансу.*/
    fun reconnect() {
        val activeId = _uiState.value.activeSessionId ?: return
        restartSession(activeId)
    }

    fun write(text: String) {
        if (text.isEmpty()) return
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(inputDispatcher) { controller.write(sessionId, text) }
    }

    /** Функціональні клавіші кодуються відповідно до режиму клавіш курсора поточного сеансу, і після того, як програма перемикається на DECCKM, не потрібно знати інтерфейс.*/
    fun sendKey(key: TerminalKey, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        val applicationCursorKeys = activeBuffer { it.applicationCursorKeys } ?: return
        write(TerminalKeyEncoder.key(key, modifiers, applicationCursorKeys))
    }

    fun sendText(text: String, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        write(TerminalKeyEncoder.text(text, modifiers))
    }

    fun paste(text: String) {
        val bracketed = activeBuffer { it.bracketedPaste } ?: return
        write(TerminalKeyEncoder.paste(text, bracketed))
    }

    fun setThemeColors(foreground: Long, background: Long) {
        synchronized(bufferLock) {
            themeColors = foreground to background
            buffers.values.forEach { it.applyTheme() }
        }
    }

    private fun TerminalScreenBuffer.applyTheme() {
        defaultForeground = themeColors.first
        defaultBackground = themeColors.second
    }

    private fun <T> activeBuffer(read: (TerminalScreenBuffer) -> T): T? {
        val sessionId = _uiState.value.activeSessionId ?: return null
        return synchronized(bufferLock) { buffers[sessionId]?.let(read) }
    }

    /** Екранний буфер і PTY змінюються синхронно; потім ядро ​​сповіщає програму про перемальовування, і буфер спочатку приймає старий екран відповідно до нового розміру.*/
    private fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        if (sessionSizes[sessionId] == (cols to rows)) return
        sessionSizes[sessionId] = cols to rows
        synchronized(bufferLock) { buffers[sessionId]?.resize(cols, rows) }
        scope.launch(inputDispatcher) { controller.resize(sessionId, cols, rows) }
        flushFrame(sessionId)
    }

    fun close() {
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun createSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || creatingSession) return
        lastCols = cols
        lastRows = rows
        creatingSession = true
        _uiState.update { it.copy(connected = false, exited = false, failMessage = null) }
        scope.launch(Dispatchers.IO) {
            try {
                startSession(environment, cols, rows)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { creatingSession = false }
            }
            withContext(Dispatchers.Main) {
                // Сітка могла змінитися під час запуску, синхронізуйте її один раз з останнім розміром.
                _uiState.value.activeSessionId?.let { resizeSession(it, lastCols, lastRows) }
            }
        }
    }

    private fun startSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
        val lease = if (identity == "user") {
            TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } } ?: run {
                _uiState.update { it.copy(failMessage = appContext.getString(R.string.capability_background_failed)) }
                return
            }
        } else null
        val result = try {
            controller.open(
                environment = environment,
                identity = identity,
                cols = cols,
                rows = rows,
                onOutput = ::onOutput,
                onExit = { sessionId ->
                    lease?.release()
                    onExit(sessionId)
                },
            )
        } catch (failure: Throwable) {
            lease?.release()
            throw failure
        }
        when (result) {
            is ConsoleSessionController.OpenResult.Ready -> {
                if (lease != null) {
                    sessionLeases[result.sessionId] = lease
                    if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                        controller.closeSession(result.sessionId)
                        sessionLeases.remove(result.sessionId)?.release()
                        _uiState.update { it.copy(failMessage = appContext.getString(R.string.terminal_session_closed)) }
                        return
                    }
                }
                synchronized(bufferLock) {
                    buffers[result.sessionId] = TerminalScreenBuffer(cols, rows, SCROLLBACK_LINES).apply { applyTheme() }
                }
                sessionSizes[result.sessionId] = cols to rows
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions + ConsoleSessionUi(result.sessionId, environment),
                        activeSessionId = result.sessionId,
                        environment = environment,
                        connected = true,
                        exited = false,
                        frame = ConsoleFrame(),
                    )
                }
            }
            is ConsoleSessionController.OpenResult.Failed -> {
                lease?.release()
                _uiState.update { it.copy(connected = false, failMessage = result.message) }
            }
        }
    }

    private fun onOutput(sessionId: String, chunk: ByteArray) {
        var responses: String? = null
        var synchronizing = false
        synchronized(bufferLock) {
            val buffer = buffers[sessionId] ?: return
            buffer.feed(chunk)
            responses = buffer.drainResponses()
            synchronizing = buffer.synchronizedOutput
        }
        // Відповідь на запит не проходить через інтерфейс, і на фоновий сеанс потрібно відповісти вчасно, інакше програма буде заблокована в очікуванні.
        responses?.let { reply -> scope.launch(inputDispatcher) { controller.write(sessionId, reply) } }
        if (sessionId != _uiState.value.activeSessionId) return
        val now = System.currentTimeMillis()
        when {
            synchronizing -> scheduleFlush(sessionId, SYNCHRONIZED_OUTPUT_TIMEOUT_MS)
            now - lastFlushMs >= FLUSH_INTERVAL_MS -> flushFrame(sessionId)
            else -> scheduleFlush(sessionId, FLUSH_INTERVAL_MS)
        }
    }

    private fun scheduleFlush(sessionId: String, delayMs: Long) {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(delayMs)
            flushScheduled = false
            flushFrame(sessionId)
        }
    }

    private fun flushFrame(sessionId: String) {
        val frame = synchronized(bufferLock) {
            val current = buffers[sessionId] ?: return
            ConsoleFrame(
                lines = current.renderLines(),
                screenRows = current.rows,
                cursorRow = current.cursorRow,
                cursorCol = current.cursorCol,
                cursorVisible = current.cursorVisible,
            )
        }
        if (sessionId != _uiState.value.activeSessionId) return
        lastFlushMs = System.currentTimeMillis()
        _uiState.update { it.copy(frame = frame) }
    }

    private fun onExit(sessionId: String) {
        sessionLeases.remove(sessionId)?.release()
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(exited = true) else it
            }
            if (state.activeSessionId == sessionId) {
                state.copy(sessions = sessions, connected = false, exited = true)
            } else {
                state.copy(sessions = sessions)
            }
        }
        flushFrame(sessionId)
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false
}
