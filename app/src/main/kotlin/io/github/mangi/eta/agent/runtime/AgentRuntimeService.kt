package io.github.mangi.eta.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Generic Agent Runtime в межах процесу модуля.
 *
 * Вхід Hook лише надсилає запити та отримує результати; виклик моделі, виконання інструментів і спливаючі вікна статусу роботи виконуються в цій службі.
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    private val runs = RuntimeRunRegistry()
    private val guiControl = GuiControlCoordinator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var destroyed = false
    @Volatile private var lastGuiOwnerRunId: String? = null
    private var displayedRun: RuntimeRunRegistry.Run? = null

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** У вікні проектується лише поточний контролер мобільного телефону; статус кожної операції зберігається в окремому записі.*/
    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val capsuleExpanded = mutableStateOf(false)
    /** Справжнє, коли жест Агента проходить крізь капсулу: вікно недоторкане, а капсула зникає.*/
    private val capsuleYielded = mutableStateOf(false)
    private val restoreCapsule = Runnable { setCapsuleYielded(false) }
    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            mainHandler.removeCallbacks(restoreCapsule)
            mainHandler.postDelayed(restoreCapsule, delayMs)
        }

        override fun cancelRestore() = mainHandler.removeCallbacks(restoreCapsule)
    }
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentOverlayClearance.attach(clearanceHost)
        serviceScope.launch { guiControl.state.collect(::applyGuiControlState) }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || runs.all().isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runs.all().isNotEmpty()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        destroyed = true
        if (activeInstance === this) activeInstance = null
        setKeepScreenOn(false)
        runs.all().forEach { run ->
            run.releaseIncoming()
            run.session.cancel("Agent Runtime ОдягобслуговуваннявжеСТІЙкінець")
        }
        displayedRun = null
        serviceScope.cancel()
        AgentExecutionService.updateRunStatus(this, null, activeRunCount = 0)
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        AgentOverlayClearance.detach(clearanceHost)
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime будь ласкапроситивідсутністьмаловидалитиінтерестіло", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime будь ласкапроситисіткаРежимнемаєефект", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime будь ласкапроситивідсутністьмало runId абовикористовуватипобутовівтратитивведіть", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo, msg.sendingUid == android.os.Process.myUid())
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "закінченовсетранспортдобревузолфруктинемаєможевикористовувати", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS -> {
                    sendActiveRuns(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
        isEtaProcess: Boolean,
    ) {
        val run = admitRun(incoming.request, replyTo, incoming) ?: return
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                run.session.controller.throwIfCancelled()
                run.awaitPredecessors()
                val request = AgentRuntimeRequestConfigResolver.constrainPresentation(
                    AgentRuntimeImageTransfer.materialize(incoming), isEtaProcess,
                )
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        if (AgentRuntimeRequestConfigResolver.usesDefaultRuntimeConfig(request)) {
                            RuntimeConfigRepository.currentRuntimeConfig()
                        } else {
                            RuntimeConfigRepository.oauthRuntimeConfigFor(
                                providerId = request.config.providerId,
                                authMode = request.config.authMode,
                                modelName = request.config.model,
                            )
                        }
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                run.releaseIncoming()
                if (destroyed || !runs.contains(run) || run.session.controller.isCancelled) return@post
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        run.prepared(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                        )
                        executePreparedRun(run)
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        failBeforeExecution(
                            run,
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime немаєЗакончитатиВиберітьмалюнокшматок"
                                is RuntimeConfigUnavailableException ->
                                    "будь ласкапершеіснують Eta серединаматчвстановитиможевикористовуватизцвільтипу"
                                else -> "Agent Runtime немаєЗакондозволяютьПриготуйтебудь ласкапросити"
                            },
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        val run = admitRun(request, replyTo) ?: return
        run.prepared(request)
        executePreparedRun(run)
    }

    private fun admitRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger?,
        incoming: AgentRuntimeWire.IncomingRunRequest? = null,
    ): RuntimeRunRegistry.Run? {
        if (destroyed) {
            incoming?.close()
            sendRequestIngestedTo(replyTo, request.runId)
            sendResultTo(replyTo, AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime ОдягобслуговуваннявжеСТІЙкінець"))
            return null
        }
        val admission = runs.admit(request) {
            lateinit var session: AgentRuntimeSession
            session = AgentRuntimeSession(
                runId = request.runId,
                operation = request.operation,
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                beforeTerminalPublish = {
                    runs.finish(request.runId, session)?.releaseIncoming()
                    AgentExecutionService.release("run:${request.runId}")
                    mainHandler.post {
                        if (!destroyed) {
                            publishExecutionState()
                            updateKeepScreenOn()
                            if (runs.all().isEmpty() && displayedRun == null && !guiControl.state.value.userPaused) stopSelf()
                        }
                    }
                },
            )
            RuntimeRunRegistry.Run(request, session, incoming) {
                sendRequestIngestedTo(replyTo, request.runId)
            }
        }
        val run = when (admission) {
            is RuntimeRunRegistry.Admission.Accepted -> admission.run
            is RuntimeRunRegistry.Admission.Duplicate -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                if (replyTo != null) {
                    val attached = admission.run.session.attach(
                        eventSink = { event -> sendEventTo(replyTo, event) },
                        resultSink = { result -> sendResultTo(replyTo, result) },
                    )
                    if (!attached) sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                        request.runId, false, "", "призначитиобслуговуваннявжевузолпучок，будь ласкавідновитискладнізакінченовсевузолфрукти",
                        contextSnapshotRef = request.runId, operation = request.operation,
                    ))
                }
                return null
            }
            is RuntimeRunRegistry.Admission.Rejected -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                AndroidAgentLogger.debug { "Agent runtime request rejected: code=${admission.code}" }
                sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                    request.runId, false, "", admission.message,
                    operation = request.operation, rewriteTargetMessageId = request.rewriteTargetMessageId,
                ))
                return null
            }
        }
        // Кореневий запис зберігає початковий життєвий цикл служби зв’язування; нещодавно доданий FGS не може стати новою попередньою авторизацією для входу виробника в серверну систему.
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { mainHandler.post { cancelRun(request.runId) } }
        if (!executionHeld && !allowBoundFallback) {
            failBeforeExecution(run, "немаєЗаконпочатокрухатисяназадвежаВиконатидобреОдягобслуговування，будь ласкаповерненняповернення Eta назадВажкаспробувати")
            return null
        }
        admission.replacedRuns.forEach { cancelRun(it.runId) }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        publishExecutionState()
        return run
    }

    private fun failBeforeExecution(run: RuntimeRunRegistry.Run, message: String) {
        run.releaseIncoming()
        val result = AgentRuntimeWire.RunResult(
            run.runId, false, "", message,
            operation = run.request.operation, rewriteTargetMessageId = run.request.rewriteTargetMessageId,
        )
        dispatchResultIo {
            run.session.complete(result) {
                runCatching { persistRunArtifacts(run.snapshotRequest(), result, emptyList()) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.error("Agent runtime preparation result persistence failed: type=${throwable.safeLogType()}")
                    }
            }
        }
    }

    private fun executePreparedRun(run: RuntimeRunRegistry.Run) {
        if (!runs.contains(run)) return
        val screenOnBinding = run.session.controller.register { mainHandler.post(::updateKeepScreenOn) }
        thread(name = "agent-runtime") {
            try {
                executeRun(run)
            } finally {
                screenOnBinding.close()
            }
        }
    }

    private fun executeRun(run: RuntimeRunRegistry.Run) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            guiControl = guiControl,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = run::snapshotRequest,
            onAcceptedEvent = { event, _ ->
                handleAcceptedRunEvent(run, event)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(run.session, run.request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            run = run,
            result = outcome.result,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    RuntimeRunRegistry.CompletedContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        run: RuntimeRunRegistry.Run,
        event: AgentEvent,
    ) {
        if (event is AgentEvent.GuiControlChanged) {
            run.guiStatus = event.status
            if (event.status == GuiControlStatus.Acquired) lastGuiOwnerRunId = run.runId
        }
        mainHandler.post {
            if (destroyed) return@post
            val guiState = guiControl.state.value
            val ownsGui = guiState.ownerRunId == run.runId
            // StateFlow може поєднувати тимчасове отримання та випуск; остаточне державне право власності все ще базується на останньому власнику послідовного запису події.
            if (event is AgentEvent.GuiControlChanged && event.status == GuiControlStatus.Acquired &&
                !guiState.userPaused && lastGuiOwnerRunId == run.runId && (ownsGui || guiState.ownerRunId == null)) {
                bindDisplayedRun(run)
            }
            if (AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, true)) {
                run.hasExecutedForegroundTool = true
            }
            runCatching {
                run.overlayState = run.overlayState.applyEvent(event)
                if (displayedRun === run && ownsGui && !run.session.isTerminal) {
                    state.value = run.overlayState
                    syncActiveRunOverlayVisibility(run)
                }
                publishExecutionState()
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // Вихідна скринька — це точка подання між кінцевим станом і контрольною точкою в дорозі; у разі відмови контрольна точка зберігається для наступного відновлення.
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        run: RuntimeRunRegistry.Run,
        result: AgentRuntimeWire.RunResult,
        completedContext: RuntimeRunRegistry.CompletedContext? = null,
    ) {
        mainHandler.post {
            if (destroyed) return@post
            run.completedContext = completedContext
            val guiState = guiControl.state.value
            if (lastGuiOwnerRunId != run.runId || guiState.userPaused ||
                (guiState.ownerRunId != null && guiState.ownerRunId != run.runId)) return@post
            bindDisplayedRun(run)
            updateKeepScreenOn()
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "вжеСТІЙкінець") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // Не маскуйте збої передавання за доставлені кінцеві стани; посилання дозволяють новим клієнтам зберігати вихідні, чекаючи повного відновлення.
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "закінченовсевузолфруктипропусквтратитивтратитипоразка，вжезберегтижитизкалендарісторіїще нівидалитивидалити。будь ласкаВажкановийбитиВІДЧИНЕНОзасіданняговоритивідновитискладні。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            val snapshots = runs.snapshots()
            val runId = snapshots.firstOrNull { it.runId == guiControl.state.value.ownerRunId }?.runId
                ?: snapshots.firstOrNull()?.runId.orEmpty()
            msg.data = AgentRuntimeWire.ackBundle(runId)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRuns(replyTo: Messenger?) {
        runCatching {
            replyTo?.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE).apply {
                data = AgentRuntimeWire.activeRunsToBundle(runs.snapshots())
            })
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_runs_delivery_failed") {
                "Agent runtime active runs delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = runs.get(runId)?.session
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
    }

    private fun requestStop(runId: String?) {
        if (runId == null || runs.get(runId) == null) {
            if (!guiControl.state.value.userPaused && displayedRun?.runId == runId &&
                guiControl.state.value.ownerRunId == null) dismissAndStop()
            return
        }
        cancelRun(runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        val run = runs.get(runId) ?: return
        if (run.preparing) {
            run.session.controller.cancel()
            failBeforeExecution(run, "вжеСТІЙкінець")
            return
        }
        val session = run.session
        if (!session.isTerminal) {
            session.controller.cancel()
            run.overlayState = run.overlayState.copy(status = AgentOverlayStatus.Stopping)
            if (displayedRun === run && !guiControl.state.value.userPaused) state.value = run.overlayState
            updateKeepScreenOn()
            publishExecutionState()
        }
    }

    private fun requestPause(runId: String?) {
        if (runId != null) guiControl.pauseForUser(runId)
    }

    private fun requestResume() {
        guiControl.resumeForUser()
    }

    private fun requestSupplement(runId: String?, text: String) {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val run = displayedRun?.takeIf { it.runId == runId } ?: return
        if (guiControl.state.value.userPaused && guiControl.state.value.ownerRunId == null) return
        setCapsuleInputMode(focusable = false)
        runs.get(run.runId)?.let { active ->
            val event = active.supplement(supplementText)
            if (event == null) {
                if (!active.session.isTerminal) {
                    state.value = state.value.copy(
                        status = AgentOverlayStatus.Finishing,
                    )
                    return
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                active.overlayState = active.overlayState.applyEvent(event)
                state.value = active.overlayState
                return
            }
        }

        val completed = run.completedContext ?: return
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun syncActiveRunOverlayVisibility(run: RuntimeRunRegistry.Run?) {
        if (AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
            )
        ) {
            removeAmbientWindows()
        } else {
            ensureOverlayVisible()
        }
    }

    private fun handleEtaUiVisibilityChanged() {
        if (destroyed) return
        val run = displayedRun ?: return
        val suppress = AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
            handoffSource = run.request.handoff?.source,
            etaUiInForeground = MainActivity.isInForeground,
        )
        if (resultCardView != null) {
            if (suppress) dismissResult(run)
            return
        }
        val guiState = guiControl.state.value
        val activeGuiRun = guiState.userPaused || (guiState.ownerRunId == run.runId && !run.session.isTerminal)
        if (!activeGuiRun) return
        if (suppress) {
            removeAmbientWindows()
        } else {
            state.value = run.overlayState
            ensureOverlayVisible()
        }
    }

    private fun updateKeepScreenOn() {
        val guiState = guiControl.state.value
        val session = guiState.ownerRunId?.let(runs::get)?.session
        setKeepScreenOn(
            !guiState.userPaused && session != null && !session.isTerminal &&
                !session.controller.isCancelled && !session.controller.isPaused,
        )
    }

    private fun applyGuiControlState(guiState: GuiControlCoordinator.State) {
        if (destroyed) return
        val owner = guiState.ownerRunId?.let(runs::get)
        if (owner != null && displayedRun !== owner) {
            bindDisplayedRun(owner)
            AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.RUN_STARTED)
        }
        when {
            guiState.userPaused -> {
                state.value = (owner?.overlayState ?: AgentOverlayState.Initial).copy(
                    phase = AgentOverlayPhase.PAUSED,
                    status = AgentOverlayStatus.Paused,
                    detailText = "рукумашинаТримайтеробитивжетимчасовоСТІЙ",
                )
                owner?.overlayState = state.value
                syncActiveRunOverlayVisibility(owner ?: displayedRun)
            }
            owner != null -> {
                if (owner.overlayState.phase == AgentOverlayPhase.PAUSED) {
                    owner.overlayState = owner.overlayState.copy(
                        phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.Continuing,
                        detailText = "",
                    )
                }
                state.value = owner.overlayState
                syncActiveRunOverlayVisibility(owner)
            }
            else -> removeAmbientWindows()
        }
        updateKeepScreenOn()
        publishExecutionState()
    }

    private fun bindDisplayedRun(run: RuntimeRunRegistry.Run) {
        if (displayedRun === run) return
        removeOverlayWindows()
        displayedRun?.completedContext = null
        displayedRun = run
        capsuleExpanded.value = false
    }

    private fun publishExecutionState() {
        val guiState = guiControl.state.value
        val activeRuns = runs.all().filterNot { it.session.isTerminal }
        val owner = activeRuns.firstOrNull { it.runId == guiState.ownerRunId }
        val status = if (owner != null && guiState.userPaused) {
            AgentOverlayStatus.Paused
        } else {
            (owner ?: activeRuns.firstOrNull())?.overlayState?.status
        }
        AgentExecutionService.updateRunStatus(
            this,
            status,
            activeRunCount = activeRuns.size,
        )
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOnState.value = enabled
        capsuleView?.keepScreenOn = enabled
    }

    private fun showOverlay() {
        if (capsuleView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY не має дозволу SYSTEM_ALERT_WINDOW; потрібно перевірити лише резервний стан (доступність не ввімкнено).
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── Вікно навколишнього освітлення: проникнення дотиком у повноекранному режимі, райдужна діафрагма, фільтрація takeScreenshotOfWindow під час створення знімків екрана ─
        val glow = createOverlayComposeView {
            AgentOverlayGlow(state = state.value, corners = screenCornerRadii())
        }
        val glowLp = glowLayoutParams()
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
        }
        glowView = glow
        glowParams = glowLp

        // ── Вікно капсули стану: у центрі під рядком стану, у закритому вигляді вкажіть речення, натисніть, щоб розгорнути елемент керування ─────
        val capsule = createOverlayComposeView {
            val controlRunId = displayedRun?.runId
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                yielded = capsuleYielded.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = { requestPause(controlRunId) },
                onResume = ::requestResume,
                onStop = { requestStop(controlRunId) },
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = { requestSupplement(controlRunId, it) },
            )
        }
        val capsuleLp = capsuleLayoutParams()
        capsule.keepScreenOn = keepScreenOn.value
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = capsuleLp
    }

    /**
     * Перемикайте сенсорне проникнення синхронно, коли поступаєтеся: знак починає діяти негайно, щоб гарантувати, що жест не перехоплюється, а візуальний перехід передається анімації в капсулі.
     * Під час відновлення спочатку зникайте, а потім відтягуйте дотик. Порядок не має значення, оскільки відновлення відбувається після завершення жесту.
     */
    private fun setCapsuleYielded(yielded: Boolean) {
        if (!yielded) mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = yielded
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_clearance_failed") {
                "Agent runtime capsule clearance update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        // Спочатку розгорніть вікно до розгорнутого файлу, а потім запустіть анімацію; капсула викличе CollapsedSettled після завершення анімації, щоб зменшити вікно.
        if (expand) setCapsuleWindowHeight(CAPSULE_CONTROLS_HEIGHT_DP)
        capsuleExpanded.value = expand
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
    }

    private fun setCapsuleWindowHeight(heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val height = dpToPx(heightDp)
        if (lp.height == height) return
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun showResultCard(wm: WindowManager) {
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            val resultRun = displayedRun
            AgentResultCard(
                state = state.value,
                onClose = { dismissResult(resultRun) },
                onOpenEta = { openEtaFromResult(resultRun) },
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
        registerResultCardBack(card)
        card.requestFocus()
    }

    /**
     * Картка результатів — це інтерфейс перебування після виконання завдання. Коли система повертається, її слід спочатку закрити, а не повертатися до програми нижче.
     * Вікно має бути фокусованим, щоб отримати повернення; зовнішня сторона картки все ще FLAG_NOT_TOUCH_MODAL, і дотик проникає на нижній рівень, як зазвичай.
     */
    /**
     * Поверніться до самої Eta та знайдіть сеанс для цього завдання. Коли вікно картки видно, це операція користувача, видима на передньому плані.
     * Дозволити запуск активності зі служби; зберігайте картку, коли запуск не вдається, щоб запобігти втраті користувачем результатів.
     */
    private fun openEtaFromResult(run: RuntimeRunRegistry.Run?) {
        val handoff = run?.request?.handoff
        val conversationId = handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        val archive = handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { item -> AgentExternalArchivePayload.from(item.payload)?.conversationKey?.let { item.source to it } }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when {
                    conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                    archive != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                    }
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissResult(run)
    }

    private fun dismissResult(run: RuntimeRunRegistry.Run?) {
        if (run != null && displayedRun === run && lastGuiOwnerRunId == run.runId &&
            guiControl.state.value.ownerRunId == null && !guiControl.state.value.userPaused) dismissAndStop()
    }

    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val resultRun = displayedRun
        val callback = OnBackInvokedCallback { dismissResult(resultRun) }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    // Деякі ПЗУ призначають програмне полотно для TYPE_ACCESSIBILITY_OVERLAY; Miuix's
                    // RuntimeShader перевіряє лише версію системи, тому системний плаваючий шар рівномірно використовує свій звичайний запасний варіант із закругленими кутами.
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        content()
                    }
                }
            }
        }

    /**
     * Вікно капсули використовує фіксовану ширину та висоту групування, а анімація виконується лише всередині вікна.
     * WRAP_CONTENT призведе до зміни розміру кожного кадру, щоб ініціювати WindowManager для перепланування всього вікна, що є джерелом затримки;
     * Але прозора область у вікні також поглинає дотики (включно з клацаннями, введеними самим Агентом), тому висота збільшується лише за потреби:
     * Розгортайте до цільового файлу перед розгортанням, а потім відтягуйте після завершення анімації згортання, див. [setCapsuleWindowHeight].
     */
    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(CAPSULE_WINDOW_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Розмістіть його в центрі під рядком стану: подалі від основної області клацання більшості програм і відповідно до звичок позиціонування системних капсул і рідинних хмар.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Нижня частина половини екрану розташована по центру, а торкайтеся проникнення за межі вікна
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // Використовуйте TYPE_ACCESSIBILITY_OVERLAY, коли доступні служби доступності (без дозволу SYSTEM_ALERT_WINDOW і знімка екрана
        // filterValidWindows (можна фільтрувати); потрібно створити з контекстом служби доступності, інакше — BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // Реальна висота екрана (включно з рядком стану + панеллю навігації), MATCH_PARENT не включає системну панель на деяких пристроях
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Повне покриття екрана (включаючи панель стану/панель навігації), проникнення дотиком не перехоплює операції на сторінці;
            // TYPE_ACCESSIBILITY_OVERLAY дозволяє відфільтрувати takeScreenshotOfWindow і зробити його прозорим для агента
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // За замовчуванням вікна, які не є програмами, зменшують віконну раму, натискаючи системну панель і викопуючи отвори. Заокруглені кути, окреслені світловою смугою, рухатимуться вниз, не суміщаючись із фізичними кутами екрана.
            // Тільки якщо явно не уникати жодної вставки, початок вікна збігатиметься з початком екрана, а радіус скруглення, який повідомляє Display, буде правильним.
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * Фізичний кутовий радіус чотирьох кутів екрана. Вікно світлового ефекту покриває весь екран і безпосередньо приймає значення, яке повідомляє дисплей;
     * Якщо деякі ПЗУ не повідомляються, використовуйте 0 і оцініть консервативне значення на основі поширених моделей із великими заокругленими кутами. Краще бути трохи меншим, ніж пропускати світлову смугу через екран.
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        setCapsuleWindowHeight(
            if (focusable) CAPSULE_SUPPLEMENT_HEIGHT_DP
            else if (capsuleExpanded.value) CAPSULE_CONTROLS_HEIGHT_DP
            else CAPSULE_COLLAPSED_HEIGHT_DP,
        )
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState) {
        state.value = finalState

        val run = displayedRun
        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = run?.hasExecutedForegroundTool == true,
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
            )
        ) {
            // Видаліть капсулу та краєве світло та відобразіть картку результатів на половину екрана; коли немає взаємодії, карта сама вийде із затримкою та передзвонить, щоб закрити.
            capsuleExpanded.value = false
            removeAmbientWindows()
            if (windowManager == null && (AgentAccessibilityService.current() != null || Settings.canDrawOverlays(this))) {
                windowManager = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            windowManager?.let(::showResultCard) ?: dismissAndStop()
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
    }

    private fun dismissAndStop() {
        // stopSelf не знищить сервіси, які все ще зв'язані клієнтом; повна історія запитів і результатів більше не зберігатиметься після закриття вікна.
        displayedRun?.completedContext = null
        displayedRun = null
        removeOverlayWindows()
        if (runs.all().isEmpty() && !guiControl.state.value.userPaused) stopSelf()
    }

    private fun removeOverlayWindows() {
        mainHandler.removeCallbacksAndMessages(hideToken)
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    companion object {
        @Volatile
        private var activeInstance: AgentRuntimeService? = null

        // Проеціюються лише наявні вікна в додатку та графічному інтерфейсі, і система відновлює екран, коли вікно стає невидимим.
        private val keepScreenOnState = MutableStateFlow(false)
        val keepScreenOn = keepScreenOnState.asStateFlow()

        internal fun onEtaUiVisibilityChanged() {
            val instance = activeInstance ?: return
            instance.mainHandler.post { instance.handleEtaUiVisibilityChanged() }
        }

        private const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        private const val HIDE_DELAY_MS = 2_500L
        private const val RESULT_REVIEW_DELAY_MS = 120_000L
        private const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        private const val CAPSULE_WINDOW_WIDTH_DP = 272
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 68
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 124
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 260
        /** Ін'єкційний клацання - це лише точка. Контроль цілі часто знаходиться близько до краю капсули, а діапазон оцінки залишається трохи далі назовні.*/
        private const val CAPSULE_CLEARANCE_SLOP_DP = 8
        private const val FALLBACK_SCREEN_CORNER_DP = 28
        private const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
