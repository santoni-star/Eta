package io.github.mangi.eta.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.device.ScrollAxis
import io.github.mangi.eta.agent.device.ScrollAxisContract
import io.github.mangi.eta.agent.device.ScrollAmount
import io.github.mangi.eta.agent.device.ScrollDirection
import io.github.mangi.eta.agent.device.ScrollEvidence
import io.github.mangi.eta.agent.device.ScrollEvidenceContract
import io.github.mangi.eta.agent.device.ScrollMovementSource
import io.github.mangi.eta.agent.device.RootScrollMotionContract
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    private data class ScreenshotWindow(
        val id: Int,
        val layer: Int,
        val type: Int,
        val bounds: Rect,
        val active: Boolean,
        val focused: Boolean,
    )

    private data class NodeTraversalState(
        val maxVisitedNodes: Int,
        val activePath: MutableSet<AccessibilityNodeInfo> = hashSetOf(),
        var visitedNodes: Int = 0,
        var truncated: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = SCREENSHOT_EXECUTOR
    private val windowChangeLock = ReentrantLock()
    private val windowChanged = windowChangeLock.newCondition()
    private val scrollEventLock = ReentrantLock()
    private val scrollEventArrived = scrollEventLock.newCondition()
    private val scrollActionLock = ReentrantLock()
    private var scrollEventSequence = 0L
    private val recentScrollSignals = ArrayDeque<ScrollSignal>()
    private val scrollEventObservationGate = ScrollEventObservationGate(
        uptimeMillis = SystemClock::uptimeMillis,
    )
    // Запит віддаленого вузла може займати потік протягом кількох секунд; зберігається лише останній кандидат, і події прокручування забороняють утворювати відставання.
    private val scrollEventExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(SCROLL_EVENT_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "agent-scroll-event").apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val windowContentGenerations = mutableMapOf<Int, Long>()
    private val serviceToken = SERVICE_TOKENS.incrementAndGet()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearCurrentInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearCurrentInstance()
        scrollEventExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun clearCurrentInstance() {
        if (instance === this) instance = null
        scrollEventObservationGate.clear()
        signalWindowChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                pruneWindowContentGenerations()
                signalWindowChanged()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                bumpWindowContentGeneration(event.windowId)
                observeScrollEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                bumpWindowContentGeneration(event.windowId)
        }
    }

    override fun onInterrupt() = Unit

    /**
     * Спостереження та його маркер вузла утворюють незмінний знімок. Абонент повинен передати той самий екземпляр назад дії вузла,
     * Запобігання іншим запускам або тимчасовим спостереженням wait_for_text від перезапису значення індексу.
     */
    fun captureNodeSnapshot(maxNodes: Int): NodeSnapshot? = runOnMainSync {
        val startedAt = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return@runOnMainSync null
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val indexedNodes = mutableListOf<IndexedNode>()
        val traversal = NodeTraversalState(
            maxVisitedNodes = (nodeLimit * UI_TREE_VISIT_MULTIPLIER)
                .coerceIn(MIN_UI_TREE_VISITED_NODES, MAX_UI_TREE_VISITED_NODES),
        )
        collectNodes(
            node = root,
            out = indexedNodes,
            maxNodes = nodeLimit,
            depth = 0,
            traversal = traversal,
        )
        NodeSnapshot(
            id = "o${SNAPSHOT_IDS.incrementAndGet()}",
            serviceToken = serviceToken,
            packageName = root.packageName?.toString().orEmpty(),
            windowId = root.windowId,
            contentGeneration = windowContentGeneration(root.windowId),
            capturedAtElapsedMs = startedAt,
            truncated = traversal.truncated,
            indexedNodes = indexedNodes.toList(),
        ).also { snapshot ->
            AndroidAgentLogger.debug {
                "Agent accessibility action=observe_tree observation=${snapshot.id} " +
                    "nodes=${snapshot.nodes.size} visited=${traversal.visitedNodes} " +
                    "truncated=${traversal.truncated} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
            }
        }
    }

    /** Спеціальні запити не публікують глобальний статус вузла, застосовний до wait_for_text.*/
    fun queryNodes(maxNodes: Int): List<UiNode> =
        captureNodeSnapshot(maxNodes)?.nodes.orEmpty()

    fun currentPackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun displaySize(): Pair<Int, Int>? = runCatching {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        if (point.x > 0 && point.y > 0) point.x to point.y else null
    }.getOrNull()

    internal fun packageWindowVisibility(packageName: String): PackageWindowVisibility =
        runOnMainSync {
            val activeRoot = rootInActiveWindow
            if (activeRoot?.packageName?.toString() == packageName) {
                return@runOnMainSync PackageWindowVisibility.VISIBLE
            }
            var inspectedRoot = activeRoot != null
            var hasUnknownRelevantWindow = false
            for (window in windows.orEmpty()) {
                val root = window.root
                if (root == null) {
                    if (
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        window.isActive ||
                        window.isFocused
                    ) {
                        hasUnknownRelevantWindow = true
                    }
                    continue
                }
                inspectedRoot = true
                if (root.packageName?.toString() == packageName) {
                    return@runOnMainSync PackageWindowVisibility.VISIBLE
                }
            }
            if (inspectedRoot && !hasUnknownRelevantWindow) {
                PackageWindowVisibility.GONE
            } else {
                PackageWindowVisibility.UNKNOWN
            }
        } ?: PackageWindowVisibility.UNKNOWN

    /**
     * BACK лише означає, що система отримала дію виходу; плаваючі вікна зазвичай також виконують анімацію виходу.
     * Зачекайте, поки вікно цільового пакета фактично зникне, і стабілізується протягом двох періодів вибірки, щоб уникнути виконання наступного знімка екрана перед видаленням View.
     */
    fun awaitPackageWindowGone(
        packageName: String,
        timeoutMillis: Long = 1_000L,
        minimumWaitMillis: Long = 160L,
        stableMillis: Long = 80L,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMillis.coerceIn(200L, 2_000L)
        var absentSince = 0L
        do {
            val now = SystemClock.elapsedRealtime()
            when (packageWindowVisibility(packageName)) {
                PackageWindowVisibility.VISIBLE,
                PackageWindowVisibility.UNKNOWN -> absentSince = 0L
                PackageWindowVisibility.GONE -> {
                    if (absentSince == 0L) absentSince = now
                    if (
                        now - startedAt >= minimumWaitMillis &&
                        now - absentSince >= stableMillis
                    ) {
                        return true
                    }
                }
            }
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis > 0L) {
                awaitWindowChanged(remainingMillis.coerceAtMost(WINDOW_POLL_FALLBACK_MS))
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun signalWindowChanged() {
        windowChangeLock.lock()
        try {
            windowChanged.signalAll()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun awaitWindowChanged(timeoutMillis: Long) {
        windowChangeLock.lock()
        try {
            windowChanged.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun observeScrollEvent(event: AccessibilityEvent) {
        scrollEventObservationGate.withMatchingObservation(
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            eventTimeMillis = event.eventTime,
        ) { observation ->
            // Система може очистити кеш вузла після прокручування, а джерело має скопіювати подію та проаналізувати її у фоновому режимі.
            val eventCopy = try {
                AccessibilityEvent(event)
            } catch (error: RuntimeException) {
                AndroidAgentLogger.warnThrottled("scroll_event_copy") {
                    "Agent accessibility action=observe_scroll_event " +
                        "outcome=copy_failed error=${error.javaClass.simpleName}"
                }
                return@withMatchingObservation
            }
            scrollEventExecutor.execute {
                if (!scrollEventObservationGate.isActive(observation)) return@execute
                val signal = resolveScrollSignal(eventCopy) ?: return@execute
                if (scrollEventObservationGate.isActive(observation)) {
                    recordScrollSignal(signal)
                }
            }
        }
    }

    private fun resolveScrollSignal(event: AccessibilityEvent): ScrollSignal? {
        val source = try {
            event.getSource(0)
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warnThrottled("scroll_event_source") {
                "Agent accessibility action=resolve_scroll_event " +
                    "outcome=source_failed error=${error.javaClass.simpleName}"
            }
            null
        } ?: return null
        return ScrollSignal(
            sequence = 0L,
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            deltaX = event.scrollDeltaX,
            deltaY = event.scrollDeltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            sourceUniqueId = source.uniqueId.orEmpty(),
            sourceViewId = source.viewIdResourceName.orEmpty(),
            sourceClassName = source.className?.toString().orEmpty(),
            sourceBounds = source.bounds(),
        )
    }

    private fun recordScrollSignal(signal: ScrollSignal) {
        scrollEventLock.lock()
        try {
            scrollEventSequence++
            recentScrollSignals.addLast(signal.copy(sequence = scrollEventSequence))
            while (recentScrollSignals.size > MAX_SCROLL_SIGNALS) {
                recentScrollSignals.removeFirst()
            }
            scrollEventArrived.signalAll()
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun bumpWindowContentGeneration(windowId: Int) {
        if (windowId < 0) return
        windowContentGenerations[windowId] = windowContentGeneration(windowId) + 1L
    }

    private fun windowContentGeneration(windowId: Int): Long =
        windowContentGenerations[windowId] ?: 0L

    private fun pruneWindowContentGenerations() {
        if (windowContentGenerations.isEmpty()) return
        val liveWindowIds = windows.orEmpty().mapTo(hashSetOf()) { window -> window.id }
        windowContentGenerations.keys.retainAll(liveWindowIds)
    }

    private fun currentScrollEventSequence(): Long {
        scrollEventLock.lock()
        return try {
            scrollEventSequence
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun awaitScrollSignal(
        afterSequence: Long,
        packageName: String,
        windowId: Int,
        targetIdentity: ScrollTargetIdentity,
        timeoutMillis: Long,
    ): ScrollSignal? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        scrollEventLock.lock()
        try {
            while (true) {
                val signal = recentScrollSignals.firstOrNull { candidate ->
                    candidate.sequence > afterSequence &&
                        candidate.windowId == windowId &&
                        candidate.packageName == packageName &&
                        candidate.matchesTarget(targetIdentity)
                }
                if (
                    signal != null
                ) {
                    return signal
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                try {
                    scrollEventArrived.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        } finally {
            scrollEventLock.unlock()
        }
    }

    fun clickNode(snapshot: NodeSnapshot, index: Int): NodeActionResult =
        withValidatedIndexedNode(snapshot, index) { indexed ->
            val node = indexed.node
            val actionable = indexed.clickTarget?.resolveFor(node)
            if (indexed.clickTarget != null && actionable == null) {
                NodeActionResult.failure(
                    "STALE_ACTION_TARGET",
                    "ФестивальточказможеточкаударГоловазнаквжечерезЗміназмінити，будь ласкаВажкановийпереглянутиоглядатиекранекран",
                )
            } else if (actionable != null) {
                when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_CLICK)) {
                    ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_CLICK")
                    ActionDispatch.REJECTED ->
                        NodeActionResult.failure("ACTION_FAILED", "ГоловазнакФестивальточкавідхилитиАбсолютноточкаударрухатисяробити")
                    ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
                }
            } else {
                val bounds = clippedNodeBounds(node)
                if (bounds.isEmpty) {
                    NodeActionResult.failure("INVALID_NODE_BOUNDS", "ГоловазнакФестивальточкабезматиможеточкаударрайондомен")
                } else gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }

    fun longClickNode(
        snapshot: NodeSnapshot,
        index: Int,
        durationMs: Long,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        val node = indexed.node
        val actionable = indexed.longClickTarget?.resolveFor(node)
        if (indexed.longClickTarget != null && actionable == null) {
            NodeActionResult.failure(
                "STALE_ACTION_TARGET",
                "Фестивальточказможедовговідповідно доГоловазнаквжечерезЗміназмінити，будь ласкаВажкановийпереглянутиоглядатиекранекран",
            )
        } else if (actionable != null) {
            when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_LONG_CLICK")
                ActionDispatch.REJECTED ->
                    NodeActionResult.failure("ACTION_FAILED", "ГоловазнакФестивальточкавідхилитиАбсолютнодовговідповідно дорухатисяробити")
                ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            }
        } else {
            val bounds = clippedNodeBounds(node)
            if (bounds.isEmpty) {
                NodeActionResult.failure("INVALID_NODE_BOUNDS", "ГоловазнакФестивальточкабезматиможедовговідповідно дорайондомен")
            } else {
                gestureTap(
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    durationMs = durationMs.coerceIn(300L, 3_000L),
                ).let { result ->
                    if (result.ok) result.copy(method = "GESTURE_LONG_PRESS") else result
                }
            }
        }
    }

    internal fun scrollNode(
        snapshot: NodeSnapshot,
        index: Int,
        direction: ScrollDirection,
        amount: ScrollAmount = ScrollAmount.PAGE,
    ): ScrollActionResult {
        val validation = runOnMainSync { validateNode(snapshot, index) }
            ?: return ScrollActionResult.failure(
                direction = direction,
                code = "SERVICE_TIMEOUT",
                message = "немаєбар'єрперешкоджатиОдягобслуговуванняхостДрітПроцедуранемаєкільцевідповідь",
                targetIndex = index,
            )
        val validationNode = when (validation) {
            is NodeValidation.Invalid -> return ScrollActionResult.failure(
                direction = direction,
                code = validation.result.code,
                message = validation.result.message,
                targetIndex = index,
            )
            is NodeValidation.Valid -> validation.indexedNode
        }
        val scrollable = validationNode.scrollTarget?.resolveFor(validationNode.node)
        if (validationNode.scrollTarget != null && scrollable == null) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_ACTION_TARGET",
                message = "ФестивальточказролрухатисяДозволитипристрійвжечерезЗміназмінити，будь ласкаВажкановийпереглянутиоглядатиекранекран",
                targetIndex = index,
            )
        }
        scrollable ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NOT_SCROLLABLE",
            message = "звертатися доЗвичайноФестивальточкаіщобатькоФестивальточканемаєможеролрухатися",
            targetIndex = index,
        )
        return executeScroll(scrollable, direction, targetIndex = index, amount = amount)
    }

    internal fun scrollCurrent(direction: ScrollDirection, amount: ScrollAmount = ScrollAmount.PAGE): ScrollActionResult {
        val target = runOnMainSync {
            rootInActiveWindow?.let { root -> findBestScrollableNode(root, direction) }
        } ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NO_ACTIVE_WINDOW",
            message = "коливпереджитирухатисявікноротнемаєможевідвідатизапитати",
        )
        return executeScroll(target, direction, targetIndex = null, amount = amount)
    }

    private fun executeScroll(
        target: AccessibilityNodeInfo,
        direction: ScrollDirection,
        targetIndex: Int?,
        amount: ScrollAmount,
    ): ScrollActionResult = scrollActionLock.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val refreshed = runOnMainSync { target.refresh() } == true
        if (!refreshed || !target.isVisibleToUser || !target.isEnabled) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_NODE",
                message = "ролрухатисяГоловазнаквжечерезвтратитиефект，будь ласкаВажкановийпереглянутиоглядатиекранекран",
                targetIndex = targetIndex,
            )
        }
        if (target.exposesOnlyOppositeAxis(direction)) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "AXIS_MISMATCH",
                message = "ГоловазнакТількивідділенняутримуватиіншеодинролрухатисявісь；дляуникативідмовивсяпомилкадотикбікковзати，книгаДругосортнийще ніВиконатидобрепризначитищорухатисяробити",
                targetIndex = targetIndex,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val packageName = target.packageName?.toString().orEmpty()
        val windowId = target.windowId
        val beforeAnchors = scrollContentAnchors(target)
        val targetIdentity = ScrollTargetIdentity.from(target)
        val beforeSequence = currentScrollEventSequence()
        // Дія прокручування вузла фіксується приблизно на одному екрані; невелика прокрутка може використовувати лише жести для керування зміщенням.
        val method = if (amount == ScrollAmount.PAGE) chooseScrollMethod(target, direction) else null
        var methodName = method?.name.orEmpty()
        return withScrollEventObservation(packageName, windowId) {
            val nodeDispatch = method?.let { selected ->
                performNodeAction(target, selected.actionId, selected.args)
            }
            if (nodeDispatch == ActionDispatch.OUTCOME_UNKNOWN) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "ACTION_OUTCOME_UNKNOWN",
                    message = "ролрухатисярухатисяробитиможездатнийвженестиплатити，алеКраваткасистемаще нііснуютьгодинаобмеженняВсерединіповерненняповерненнявузолфрукти；будь ласкапершеВажкановийпереглянутиоглядати，уникативідмовивсяВажкаскладніролрухатися",
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            var accepted = nodeDispatch == ActionDispatch.ACCEPTED

            if (!accepted) {
                val boundaryBeforeGesture = runOnMainSync {
                    target.refresh() && isAtScrollBoundary(target, direction)
                } == true
                if (boundaryBeforeGesture) {
                    return ScrollActionResult.boundary(
                        direction = direction,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                val bounds = clippedNodeBounds(target)
                val gesture = direction.gestureWithin(bounds, amount)
                    ?: return ScrollActionResult.failure(
                        direction = direction,
                        code = "INVALID_NODE_BOUNDS",
                        message = "ролрухатисяГоловазнакбезматистопадоситьзможевикористовуватирайондомен",
                        targetIndex = targetIndex,
                    )
                methodName = "GESTURE_SWIPE"
                val gestureResult = gestureSwipe(
                    gesture.start.x.toFloat(),
                    gesture.start.y.toFloat(),
                    gesture.end.x.toFloat(),
                    gesture.end.y.toFloat(),
                    SCROLL_GESTURE_DURATION_MS,
                )
                if (!gestureResult.ok) {
                    return ScrollActionResult.failure(
                        direction = direction,
                        code = gestureResult.code,
                        message = gestureResult.message,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                accepted = true
            }
            if (!accepted) {
                val boundary = runOnMainSync {
                    target.refresh() && isAtScrollBoundary(target, direction)
                } == true
                if (boundary) {
                    return ScrollActionResult.boundary(
                        direction = direction,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "ACTION_FAILED",
                    message = "КраваткасистемавідхилитиАбсолютноролрухатисярухатисяробити",
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }

            val signal = awaitScrollSignal(
                afterSequence = beforeSequence,
                packageName = packageName,
                windowId = windowId,
                targetIdentity = targetIdentity,
                timeoutMillis = SCROLL_VERIFY_TIMEOUT_MS,
            )
            val afterAnchors = runOnMainSync {
                if (target.refresh()) scrollContentAnchors(target) else emptyList()
            }.orEmpty()
            val eventDelta = signal?.axisDelta(direction.axis)?.takeIf { delta -> delta != 0 }
            val anchorDelta = inferScrollAnchorDelta(beforeAnchors, afterAnchors, direction)
            val delta = eventDelta ?: anchorDelta
            val movementSource = when {
                eventDelta != null -> ScrollMovementSource.EVENT
                anchorDelta != null -> ScrollMovementSource.ANCHOR_MOTION
                else -> null
            }
            val boundary = runOnMainSync {
                target.refresh() && isAtScrollBoundary(target, direction, signal)
            } == true
            val evidence = ScrollEvidenceContract.classify(
                direction = direction,
                delta = delta,
                movementSource = movementSource,
                atBoundary = boundary,
            )
            if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "DIRECTION_MISMATCH",
                    message = "межалокшинаназустрічбудь ласкапроситиплощіназустрічзпротилежністьплощіназустрічзмінарухатися",
                    method = methodName,
                    targetIndex = targetIndex,
                    deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                    deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (
                evidence == ScrollEvidence.MOVED_BY_EVENT ||
                evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION
            ) {
                return ScrollActionResult(
                    ok = true,
                    direction = direction,
                    moved = true,
                    atBoundary = boundary,
                    method = methodName,
                    deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                    deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                    verifiedBy = if (evidence == ScrollEvidence.MOVED_BY_EVENT) {
                        "scroll_event"
                    } else {
                        "anchor_motion"
                    },
                    elapsedMs = elapsedMs,
                    targetIndex = targetIndex,
                )
            }
            if (evidence == ScrollEvidence.AT_BOUNDARY) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = elapsedMs,
                )
            }
            return ScrollActionResult.failure(
                direction = direction,
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "ролрухатисярухатисяробитивжечерезволоссяпоза，аленемаєЗаконправильнорозпізнатиВсерединіДозволитибітзміна；будь ласкапершеВажкановийпереглянутиоглядати，заборонакінецьпрямозловитиВажкаспробувати",
                method = methodName,
                targetIndex = targetIndex,
                deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                elapsedMs = elapsedMs,
            )
        }
    }

    private inline fun <T> withScrollEventObservation(
        packageName: String,
        windowId: Int,
        block: () -> T,
    ): T {
        val observation = scrollEventObservationGate.begin(
            packageName = packageName,
            windowId = windowId,
            validForMillis = SCROLL_EVENT_OBSERVATION_TIMEOUT_MS,
        )
        return try {
            block()
        } finally {
            scrollEventObservationGate.end(observation)
        }
    }

    fun inputTextFocused(text: String): NodeActionResult = runNodeActionOnMainSync {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "безматиотриматиповиннівтратитивведітьфокусточказможекомпілюватиредагуватиФестивальточка",
            )
        node.incrementalTextValidationError()?.let { error ->
            return@runNodeActionOnMainSync error
        }
        val plan = TextEditPlanner.insertAtSelection(
            currentText = node.realText(),
            insertedText = text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        ) ?: return@runNodeActionOnMainSync NodeActionResult.failure(
            "TEXT_SELECTION_UNAVAILABLE",
            "коливпередвтратитивведітькоробкаще нінестидляможеЗалежить відсвітлознакабовибратирайон；будь ласкавикористовувати replace_text нестидлязакінченовсезначення",
        )
        setNodeText(node, plan.text, plan.cursor)
    }

    fun setTextNode(
        snapshot: NodeSnapshot?,
        index: Int?,
        text: String,
    ): NodeActionResult {
        if (index != null) {
            val requiredSnapshot = snapshot
                ?: return NodeActionResult.failure("NO_OBSERVATION", "звертатися доЗвичайно index потребахочутьматиефектпереглянутиоглядатишвидкийВідповідно до")
            return withValidatedNode(requiredSnapshot, index) { node ->
                if (!node.isEditable) {
                    NodeActionResult.failure("NOT_EDITABLE", "звертатися доЗвичайноФестивальточканемаєможекомпілюватиредагувати")
                } else {
                    setNodeText(node, text, text.length)
                }
            }
        }
        return runNodeActionOnMainSync {
            val node = findFocusedEditableNode()
                ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                    "NO_FOCUSED_EDITABLE",
                    "безматиотриматиповиннівтратитивведітьфокусточказможекомпілюватиредагуватиФестивальточка",
                )
            setNodeText(node, text, text.length)
        }
    }

    /** Пріоритезуйте запис безпосередньо відповідно до вибору та повертайтеся до системної вставки лише тоді, коли ціль відхиляє SET_TEXT.*/
    fun pasteText(text: String): NodeActionResult = runNodeActionOnMainSync {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "безматиотриматиповиннівтратитивведітьфокусточказможекомпілюватиредагуватиФестивальточка",
            )
        node.incrementalTextValidationError()?.let { error ->
            return@runNodeActionOnMainSync error
        }
        val plan = TextEditPlanner.insertAtSelection(
            currentText = node.realText(),
            insertedText = text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        ) ?: return@runNodeActionOnMainSync NodeActionResult.failure(
            "TEXT_SELECTION_UNAVAILABLE",
            "коливпередвтратитивведітькоробкаще нінестидляможеЗалежить відсвітлознакабовибратирайон；будь ласкавикористовувати replace_text нестидлязакінченовсезначення",
        )
        val directResult = setNodeText(node, plan.text, plan.cursor)
        if (directResult.ok) {
            return@runNodeActionOnMainSync directResult.copy(method = "ACTION_SET_TEXT_PASTE")
        }
        if (directResult.code != "ACTION_FAILED") {
            return@runNodeActionOnMainSync directResult
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val originalClip = runCatching { clipboard.primaryClip }.getOrNull()
        val temporaryLabel = "$CLIP_LABEL:${CLIP_IDS.incrementAndGet()}"
        val temporaryClip = ClipData.newPlainText(temporaryLabel, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val copied = runCatching {
            clipboard.setPrimaryClip(temporaryClip)
        }.isSuccess
        if (!copied) {
            return@runNodeActionOnMainSync NodeActionResult.failure(
                "CLIPBOARD_WRITE_FAILED",
                "НапишітьвведітьВирізатипалкаплитавтратитипоразка",
            )
        }
        val pasteResult = try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                val verified = runCatching { node.refresh() }.getOrDefault(false) &&
                    node.realText() == plan.text
                if (verified) {
                    NodeActionResult.success(method = "ACTION_PASTE", verified = true)
                } else {
                    NodeActionResult.outcomeUnknown()
                }
            } else {
                NodeActionResult.failure("ACTION_FAILED", "втратитивведітьФестивальточкавідхилитиАбсолютнопалкапалкарухатисяробити")
            }
        } catch (_: Throwable) {
            NodeActionResult.outcomeUnknown()
        }
        val restored = restoreClipboardIfStillOwned(
            clipboard = clipboard,
            temporaryLabel = temporaryLabel,
            originalClip = originalClip,
        )
        if (!restored) {
            NodeActionResult.outcomeUnknown().copy(clipboardWritten = true)
        } else {
            pasteResult.copy(clipboardWritten = true)
        }
    }

    private fun restoreClipboardIfStillOwned(
        clipboard: ClipboardManager,
        temporaryLabel: String,
        originalClip: ClipData?,
    ): Boolean {
        val currentClip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return false
        if (currentClip.description.label?.toString() != temporaryLabel) {
            // Користувач або інша програма написала новий вміст і не може перезаписати його старим знімком.
            return true
        }
        return runCatching {
            if (originalClip != null) {
                clipboard.setPrimaryClip(originalClip)
            } else {
                clipboard.clearPrimaryClip()
            }
        }.isSuccess
    }

    fun imeEnter(): NodeActionResult = runNodeActionOnMainSync {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "безматиотриматиповиннівтратитивведітьфокусточказможекомпілюватиредагуватиФестивальточка",
            )
        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            NodeActionResult.success(method = "ACTION_IME_ENTER")
        } else {
            NodeActionResult.failure("ACTION_FAILED", "втратитивведітьФестивальточкавідхилитиАбсолютноповерненняавтомобільрухатисяробити")
        }
    }

    fun gestureTap(x: Float, y: Float, durationMs: Long = 50): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            },
            durationMs.coerceIn(1, 3_000),
            successMethod = "GESTURE_TAP",
        )

    fun gestureSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs.coerceIn(100, 3_000),
            successMethod = "GESTURE_SWIPE",
        )

    fun globalActionResult(name: String): NodeActionResult {
        val action = when (name.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return NodeActionResult.failure("INVALID_ARGUMENT", "немаєвідділенняутримуватизКраваткасистемарухатисяробити")
        }
        return runNodeActionOnMainSync {
            if (performGlobalAction(action)) {
                NodeActionResult.success(method = "GLOBAL_ACTION_${name.uppercase()}")
            } else {
                NodeActionResult.failure("ACTION_FAILED", "КраваткасистемавідхилитиАбсолютноПовнийбюрорухатисяробити")
            }
        }
    }

    fun globalAction(name: String): Boolean = globalActionResult(name).ok

    fun copyToClipboard(text: String): NodeActionResult =
        runNodeActionOnMainSync {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            NodeActionResult.success(method = "CLIPBOARD_SET")
        }

    fun readClipboard(): ClipboardReadResult = runOnMainSync {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return@runOnMainSync ClipboardReadResult.failure()
        if (clip.itemCount <= 0) return@runOnMainSync ClipboardReadResult.failure()
        ClipboardReadResult(
            ok = true,
            text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty(),
        )
    } ?: ClipboardReadResult.failure(code = "SERVICE_TIMEOUT")

    fun statusJson(): JSONObject =
        JSONObject()
            .put("available", true)
            .put("package", currentPackageName().orEmpty())

    /**
     * Зніміть поточний екран і виключіть плаваючий шар TYPE_ACCESSIBILITY_OVERLAY (світіння/куля/бульбашка/результатна картка/індикатор жестів).
     * Викликається з підпотоку середовища виконання агента; takeScreenshotOfWindow внутрішній допис до головного потоку,
     * зворотний виклик виконує копіювання растрового зображення в обмеженому фоновому потоці, блокування блокує лише робочий потік середовища виконання агента.
     */
    fun captureScreenshotExcludingOverlays(
        excludedPackages: Set<String> = emptySet(),
        onWindowsSubmitted: (() -> Unit)? = null,
    ): ScreenshotCaptureResult {
        val windowsSubmitted = AtomicBoolean(false)
        fun signalWindowsSubmitted() {
            if (windowsSubmitted.compareAndSet(false, true)) {
                runCatching { onWindowsSubmitted?.invoke() }
            }
        }
        // У старій системі немає знімків вікон за вікнами, а повноекранні знімки екрана не можна використовувати, щоб обійти вихідний пакет і контракти виключення плаваючого рівня Eta.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val startedAt = SystemClock.elapsedRealtime()
        val allWindows = windows
            ?: return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenshotCaptureResult.unavailable().also {
                allWindows.forEach { window -> runCatching { window.recycle() } }
                signalWindowsSubmitted()
            }
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        val screenW = point.x
        val screenH = point.y
        if (screenW <= 0 || screenH <= 0) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val screenBounds = Rect(0, 0, screenW, screenH)

        // Фільтрувати лише доступні накладки, які можуть бути підтверджені належністю до Eta; сторонні накладки повинні бути збережені.
        // Інакше знімок екрана буде несумісним із вікном, яке насправді отримує жест координат.
        val windowPackages = allWindows.associate { window ->
            window.id to window.root?.packageName?.toString()
        }
        val windowDecisions = allWindows.associate { window ->
            window.id to ScreenshotWindowPolicy.decide(
                isAccessibilityOverlay =
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                active = window.isActive,
                focused = window.isFocused,
                resolvedPackage = windowPackages[window.id],
                ownPackage = packageName,
                excludedPackages = excludedPackages,
            )
        }
        val unknownRelevantWindowIds = windowDecisions
            .filterValues { decision ->
                decision == ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN
            }
            .keys
            .toList()
        if (unknownRelevantWindowIds.isNotEmpty()) {
            val expectedWindows = allWindows.size
            allWindows.forEach { window -> runCatching { window.recycle() } }
            AndroidAgentLogger.warn(
                "Agent accessibility action=capture_screenshot outcome=failed " +
                    "reason=unknown_relevant_window_during_exclusion " +
                    "windows=${unknownRelevantWindowIds.size}"
            )
            return ScreenshotCaptureResult.blockedByUnknownWindow(
                expectedWindows = expectedWindows,
                windowIds = unknownRelevantWindowIds,
            ).also { signalWindowsSubmitted() }
        }
        val captureWindows = allWindows.mapNotNull { window ->
            if (windowDecisions[window.id] == ScreenshotWindowPolicy.Decision.EXCLUDE) {
                return@mapNotNull null
            }
            val bounds = Rect().also(window::getBoundsInScreen)
            if (
                bounds.width() <= 0 ||
                bounds.height() <= 0 ||
                !Rect.intersects(bounds, screenBounds)
            ) {
                return@mapNotNull null
            }
            ScreenshotWindow(
                id = window.id,
                layer = window.layer,
                type = window.type,
                bounds = bounds,
                active = window.isActive,
                focused = window.isFocused,
            )
        }.distinctBy { window -> window.id }.sortedBy { window -> window.layer }
        if (captureWindows.isEmpty()) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val topApplicationWindowId = captureWindows
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull(ScreenshotWindow::layer)
            ?.id
            ?: captureWindows.maxByOrNull(ScreenshotWindow::layer)?.id
        val criticalWindowIds = captureWindows
            .asSequence()
            .filter { window ->
                window.id == topApplicationWindowId || window.active || window.focused
            }
            .map(ScreenshotWindow::id)
            .toSet()

        val latch = CountDownLatch(captureWindows.size)
        val screenshots = mutableMapOf<Int, Pair<Bitmap, Rect>>()
        val failures = mutableMapOf<Int, Int>()
        val lock = Any()
        val acceptingResults = AtomicBoolean(true)

        for (window in captureWindows) {
            runCatching {
                takeScreenshotOfWindow(window.id, screenshotExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val sw = convertToSoftwareBitmap(screenshot)
                                ?: throw IllegalStateException("screenshot bitmap unavailable")
                            var retained = false
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    screenshots[window.id] = sw to Rect(window.bounds)
                                    retained = true
                                }
                            }
                            if (!retained && !sw.isRecycled) sw.recycle()
                        } catch (_: Exception) {
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                                }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        synchronized(lock) {
                            if (acceptingResults.get()) failures[window.id] = errorCode
                        }
                        latch.countDown()
                    }
                })
            }.onFailure {
                synchronized(lock) {
                    if (acceptingResults.get()) {
                        failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                    }
                }
                latch.countDown()
            }
        }
        // Eta може бути відображено після того, як ідентифікатор вікна буде виправлено та всі надіслані; подальше злиття та кодування більше не захоплюватиме вхідний плаваючий шар.
        signalWindowsSubmitted()
        val completed = try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        acceptingResults.set(false)
        val captured = synchronized(lock) {
            screenshots.toMap().also { screenshots.clear() }
        }
        val capturedIds = captured.keys
        val missingIds = captureWindows.map(ScreenshotWindow::id).filterNot(capturedIds::contains)
        val criticalWindowMissing = missingIds.any(criticalWindowIds::contains)
        val merged = if (captured.isEmpty() || criticalWindowMissing) {
            null
        } else {
            mergeScreenshots(captured, captureWindows, screenW, screenH)
        }
        val failureCodes = synchronized(lock) { failures.toMap() }
        val complete = completed && missingIds.isEmpty() && merged != null
        AndroidAgentLogger.debug {
            "Agent accessibility action=capture_screenshot outcome=merged " +
                "allWindows=${allWindows.size} validWindows=${captureWindows.size} " +
                "excludedPackages=${excludedPackages.size} " +
                "completed=$completed screenshots=${captured.size} " +
                "complete=$complete criticalMissing=$criticalWindowMissing " +
                "screen=${screenW}x${screenH} merged=${merged?.width}x${merged?.height} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
        }
        captured.values.forEach { (bitmap, _) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        allWindows.forEach { window -> runCatching { window.recycle() } }
        return ScreenshotCaptureResult(
            bitmap = merged,
            complete = complete,
            expectedWindows = captureWindows.size,
            capturedWindows = captured.size,
            missingWindowIds = missingIds,
            failureCodes = failureCodes,
            timedOut = !completed,
            criticalWindowMissing = criticalWindowMissing,
        )
    }

    private fun convertToSoftwareBitmap(screenshot: ScreenshotResult): Bitmap? =
        screenshot.hardwareBuffer.use { hardwareBuffer ->
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return@use null
            try {
                if (wrapped.config == Bitmap.Config.HARDWARE) {
                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    wrapped
                }
            } finally {
                if (wrapped.config == Bitmap.Config.HARDWARE && !wrapped.isRecycled) {
                    wrapped.recycle()
                }
            }
        }

    private fun mergeScreenshots(
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        sortedWindows: List<ScreenshotWindow>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        var merged: Bitmap? = null
        return try {
            val output = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            merged = output
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val occlusionPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            for (window in sortedWindows) {
                val pair = screenshots[window.id]
                if (pair == null) {
                    // Якщо захоплення верхнього вікна не вдається, його область має бути покрита, і базовий інтерфейс, який фактично заблоковано, не може бути відкритим для моделі.
                    canvas.drawRect(RectF(window.bounds), occlusionPaint)
                } else {
                    val (bmp, bounds) = pair
                    if (bmp.isRecycled) continue
                    // Змініть растрове зображення вікна до його розміру, намалюйте його та обробіть takeScreenshotOfWindow
                    // Повертає випадок, коли розміри не відповідають межам (логічні пікселі проти фізичних пікселів).
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, src, RectF(bounds), null)
                }
            }
            output
        } catch (_: Exception) {
            merged?.takeUnless(Bitmap::isRecycled)?.recycle()
            null
        }
    }

    /**
     * Фактичний вміст поля введення. Коли в порожньому полі відображається текст підказки, текст повертає текст підказки. Якщо це розглядається як існуючий вміст,
     *Додаткове введення введе підказку в результат, а повторне читання підтвердження після очищення також призведе до помилкового повідомлення про помилку.
     */
    private fun AccessibilityNodeInfo.realText(): String =
        if (isShowingHintText) "" else text?.toString().orEmpty()

    private fun setNodeText(
        node: AccessibilityNodeInfo,
        text: String,
        cursor: Int,
    ): NodeActionResult {
        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            return NodeActionResult.failure("ACTION_FAILED", "втратитивведітьФестивальточкавідхилитиАбсолютномистецтвакнигабудуватизмінитирухатисяробити")
        }
        val safeCursor = cursor.coerceIn(0, text.length)
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, safeCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, safeCursor)
        }
        val refreshed = runCatching { node.refresh() }.getOrDefault(false)
        if (!node.isPassword && (!refreshed || node.realText() != text)) {
            return NodeActionResult.outcomeUnknown()
        }
        val selectionRestored = refreshed && node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                selectionArgs,
            )
        return NodeActionResult.success(
            method = if (selectionRestored) {
                "ACTION_SET_TEXT_AND_SELECTION"
            } else {
                "ACTION_SET_TEXT"
            },
            verified = !node.isPassword,
        )
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val inputFocus = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()
        if (inputFocus?.isUsableInputTarget() == true) return inputFocus
        return findNode(root) { node -> node.isFocused && node.isUsableInputTarget() }
    }

    private fun AccessibilityNodeInfo.isUsableInputTarget(): Boolean =
        isEditable && isEnabled && isVisibleToUser

    private fun AccessibilityNodeInfo.incrementalTextValidationError(): NodeActionResult? {
        val currentText = text
        if (isPassword || currentText == null) {
            return NodeActionResult.failure(
                "TEXT_CONTENT_UNAVAILABLE",
                "коливпередвтратитивведітькоробканемаєдозволяютьтравняможеЗалежить відчитатиВиберітьвжематимистецтвакнига；будь ласкавикористовувати replace_text нестидлязакінченовсезначення",
            )
        }
        if (
            !TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = currentText.length,
                selectionStart = textSelectionStart,
                selectionEnd = textSelectionEnd,
            )
        ) {
            return NodeActionResult.failure(
                "TEXT_SELECTION_UNAVAILABLE",
                "коливпередвтратитивведітькоробкаще нінестидляможеЗалежить відсвітлознакабовибратирайон；будь ласкавикористовувати replace_text нестидлязакінченовсезначення",
            )
        }
        return null
    }

    private fun findBestScrollableNode(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection,
    ): AccessibilityNodeInfo {
        val candidates = mutableListOf<ScrollCandidate>()
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        var visitedNodes = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (
                depth > MAX_UI_TREE_DEPTH ||
                visitedNodes >= MAX_SCROLL_SEARCH_NODES ||
                !activePath.add(node)
            ) return
            visitedNodes++
            try {
                if (!node.isVisibleToUser) return
                if (node.isEnabled && node.hasScrollCapability()) {
                    val bounds = clippedNodeBounds(node)
                    val supportRank = node.directionSupportRank(direction)
                    if (
                        !node.exposesOnlyOppositeAxis(direction) &&
                        !bounds.isEmpty &&
                        direction.gestureWithin(bounds) != null
                    ) {
                        candidates += ScrollCandidate(
                            node = node,
                            supportRank = supportRank,
                            depth = depth,
                            area = bounds.width().toLong() * bounds.height().toLong(),
                        )
                    }
                }
                for (childIndex in 0 until node.childCount) {
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return candidates.maxWithOrNull(
            compareBy<ScrollCandidate> { it.area }
                .thenBy { it.supportRank }
                .thenBy { -it.depth },
        )?.node ?: root
    }

    private fun AccessibilityNodeInfo.directionSupportRank(direction: ScrollDirection): Int {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return when {
            direction.exactScrollActionId() in actions -> 4
            direction.pageActionId() in actions -> 3
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            ) && (
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions ||
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
                ) -> 2
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id in actions -> 1
            else -> 0
        }
    }

    private fun AccessibilityNodeInfo.exposesOnlyOppositeAxis(
        direction: ScrollDirection,
    ): Boolean {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return ScrollAxisContract.exposesOnlyOppositeAxis(
            requestedAxis = direction.axis,
            hasVerticalActions = hasVertical,
            hasHorizontalActions = hasHorizontal,
        )
    }

    private fun AccessibilityNodeInfo.hasScrollCapability(): Boolean {
        if (isScrollable) return true
        val actions = supportedActionIds()
        return SCROLL_ACTION_IDS.any(actions::contains)
    }

    private fun AccessibilityNodeInfo.supportedActionIds(): Set<Int> =
        actionList.mapTo(hashSetOf()) { action -> action.id }

    private fun chooseScrollMethod(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
    ): ScrollMethod? {
        if (node.exposesOnlyOppositeAxis(direction)) return null
        val actionIds = node.supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val exactAction = direction.exactScrollActionId()
        if (exactAction in actionIds) {
            return ScrollMethod(exactAction, "ACTION_SCROLL_${direction.name}")
        }
        val pageAction = direction.pageActionId()
        if (pageAction in actionIds) {
            return ScrollMethod(pageAction, "ACTION_PAGE_${direction.name}")
        }
        if (
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            )
        ) {
            val fallback = if (direction == ScrollDirection.DOWN) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (fallback in actionIds) {
                return ScrollMethod(
                    actionId = fallback,
                    name = if (direction == ScrollDirection.DOWN) {
                        "ACTION_SCROLL_FORWARD"
                    } else {
                        "ACTION_SCROLL_BACKWARD"
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val inDirection = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id
            if (inDirection in actionIds) {
                val args = Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_DIRECTION_INT,
                        direction.focusDirection(),
                    )
                }
                return ScrollMethod(inDirection, "ACTION_SCROLL_IN_DIRECTION", args)
            }
        }
        return null
    }

    private fun isAtScrollBoundary(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
        signal: ScrollSignal? = null,
    ): Boolean {
        if (signal != null && signal.axisDelta(direction.axis) != null) {
            when (direction) {
                ScrollDirection.UP -> if (
                    signal.maxScrollY > 0 && signal.scrollY <= 0
                ) return true
                ScrollDirection.DOWN -> if (
                    signal.maxScrollY > 0 && signal.scrollY >= signal.maxScrollY
                ) return true
                ScrollDirection.LEFT -> if (
                    signal.maxScrollX > 0 && signal.scrollX <= 0
                ) return true
                ScrollDirection.RIGHT -> if (
                    signal.maxScrollX > 0 && signal.scrollX >= signal.maxScrollX
                ) return true
            }
        }
        if (chooseScrollMethod(node, direction) != null) return false
        return chooseScrollMethod(node, direction.opposite()) != null
    }

    private fun clippedNodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = node.bounds()
        val size = displaySize()
        if (size != null) {
            if (!bounds.intersect(0, 0, size.first, size.second)) bounds.setEmpty()
        }
        return bounds
    }

    private fun scrollContentAnchors(root: AccessibilityNodeInfo): List<ScrollAnchor> {
        val anchors = ArrayList<ScrollAnchor>(SCROLL_ANCHOR_NODE_LIMIT)
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        val rootBounds = root.bounds()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (
                depth > MAX_UI_TREE_DEPTH ||
                anchors.size >= SCROLL_ANCHOR_NODE_LIMIT ||
                !activePath.add(node)
            ) return
            try {
                if (!node.isVisibleToUser) return
                val bounds = node.bounds()
                val key = buildString {
                    append(node.uniqueId.orEmpty())
                    append('|')
                    append(node.className?.toString().orEmpty())
                    append('|')
                    append(node.viewIdResourceName.orEmpty())
                    append('|')
                    append(node.text?.toString().orEmpty().take(80))
                    append('|')
                    append(node.contentDescription?.toString().orEmpty().take(80))
                }
                if (
                    node.uniqueId?.isNotBlank() == true ||
                    node.viewIdResourceName?.isNotBlank() == true ||
                    node.text?.isNotBlank() == true ||
                    node.contentDescription?.isNotBlank() == true
                ) {
                    anchors += ScrollAnchor(
                        key = key,
                        centerX = bounds.centerX() - rootBounds.left,
                        centerY = bounds.centerY() - rootBounds.top,
                    )
                }
                for (childIndex in 0 until node.childCount) {
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return anchors
    }

    private fun inferScrollAnchorDelta(
        before: List<ScrollAnchor>,
        after: List<ScrollAnchor>,
        direction: ScrollDirection,
    ): Int? {
        fun unique(anchors: List<ScrollAnchor>): Map<String, ScrollAnchor> = anchors
            .groupBy(ScrollAnchor::key)
            .mapNotNull { (key, matches) -> matches.singleOrNull()?.let { anchor -> key to anchor } }
            .toMap()
        val beforeUnique = unique(before)
        val afterUnique = unique(after)
        val contentDeltas = beforeUnique.mapNotNull { (key, oldAnchor) ->
            val newAnchor = afterUnique[key] ?: return@mapNotNull null
            if (direction.axis == ScrollAxis.VERTICAL) {
                newAnchor.centerY - oldAnchor.centerY
            } else {
                newAnchor.centerX - oldAnchor.centerX
            }
        }
        return RootScrollMotionContract.inferScrollDelta(contentDeltas)
    }

    private fun ScrollDirection.exactScrollActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
    }

    private fun ScrollDirection.pageActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
    }

    private fun ScrollDirection.focusDirection(): Int = when (this) {
        ScrollDirection.UP -> View.FOCUS_UP
        ScrollDirection.DOWN -> View.FOCUS_DOWN
        ScrollDirection.LEFT -> View.FOCUS_LEFT
        ScrollDirection.RIGHT -> View.FOCUS_RIGHT
    }

    private fun ScrollDirection.opposite(): ScrollDirection = when (this) {
        ScrollDirection.UP -> ScrollDirection.DOWN
        ScrollDirection.DOWN -> ScrollDirection.UP
        ScrollDirection.LEFT -> ScrollDirection.RIGHT
        ScrollDirection.RIGHT -> ScrollDirection.LEFT
    }

    private fun withValidatedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (AccessibilityNodeInfo) -> NodeActionResult,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        block(indexed.node)
    }

    private fun withValidatedIndexedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (IndexedNode) -> NodeActionResult,
    ): NodeActionResult {
        val validation = runOnMainSync { validateNode(snapshot, index) }
            ?: return NodeActionResult.failure("SERVICE_TIMEOUT", "немаєбар'єрперешкоджатиОдягобслуговуванняхостДрітПроцедуранемаєкільцевідповідь")
        return when (validation) {
            is NodeValidation.Invalid -> validation.result
            is NodeValidation.Valid -> block(validation.indexedNode)
        }
    }

    private fun validateNode(snapshot: NodeSnapshot, index: Int): NodeValidation {
        if (snapshot.serviceToken != serviceToken) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("SERVICE_RECONNECTED", "немаєбар'єрперешкоджатиОдягобслуговуваннявжеВажканавіть，будь ласкаВажкановийпереглянутиоглядатиекранекран"),
            )
        }
        val indexed = snapshot.indexedNodes.firstOrNull { it.index == index }
            ?: return NodeValidation.Invalid(
                NodeActionResult.failure("INVALID_NODE_INDEX", "переглянутиоглядатишвидкийВідповідно досерединанемаєжитиіснуютьФестивальточка index=$index"),
            )
        val activeRoot = rootInActiveWindow
            ?: return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_WINDOW", "коливпереджитирухатисявікноротнемаєможевідвідатизапитати，будь ласкаВажкановийпереглянутиоглядатиекранекран"),
            )
        val activePackage = activeRoot.packageName?.toString().orEmpty()
        if (activeRoot.windowId != snapshot.windowId || activePackage != snapshot.packageName) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_WINDOW", "житирухатисявікноротвжечерезЗміназмінити，будь ласкаВажкановийпереглянутиоглядатиекранекран"),
            )
        }
        if (
            windowContentGeneration(snapshot.windowId) != snapshot.contentGeneration &&
            !snapshot.hasUnambiguousIdentity(indexed)
        ) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_CONTENT", "вікноротВсерединіДозволитивжечерезЗміназмінити，будь ласкаВажкановийпереглянутиоглядатиекранекран"),
            )
        }
        val node = indexed.node
        if (!runCatching { node.refresh() }.getOrDefault(false)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_NODE", "ГоловазнакФестивальточкавжечерезвтратитиефект，будь ласкаВажкановийпереглянутиоглядатиекранекран"),
            )
        }
        if (!indexed.identityMatches(node)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("IDENTITY_CHANGED", "ГоловазнакФестивальточкаВсерединіДозволитиаботілочасткавжечерезЗміназмінити，будь ласкаВажкановийпереглянутиоглядатиекранекран"),
            )
        }
        if (!node.isVisibleToUser || !node.isEnabled) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("NODE_NOT_ACTIONABLE", "ГоловазнакФестивальточкаколивпереднемаєможедивабонемаєможевикористовувати"),
            )
        }
        return NodeValidation.Valid(indexed)
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val visited = hashSetOf<AccessibilityNodeInfo>()
        queue.addLast(node to 0)
        while (queue.isNotEmpty() && visited.size < MAX_FOCUS_SEARCH_NODES) {
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            if (predicate(current)) return current
            if (depth >= MAX_UI_TREE_DEPTH) continue
            for (childIndex in 0 until current.childCount) {
                val child = runCatching { current.getChild(childIndex) }.getOrNull() ?: continue
                queue.addLast(child to depth + 1)
            }
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<IndexedNode>,
        maxNodes: Int,
        depth: Int,
        traversal: NodeTraversalState,
        clickableAncestor: NodeActionTarget? = null,
        longClickableAncestor: NodeActionTarget? = null,
        scrollableAncestor: NodeActionTarget? = null,
    ) {
        if (out.size >= maxNodes) {
            traversal.truncated = true
            return
        }
        if (depth > MAX_UI_TREE_DEPTH || traversal.visitedNodes >= traversal.maxVisitedNodes) {
            traversal.truncated = true
            return
        }
        if (!traversal.activePath.add(node)) {
            traversal.truncated = true
            return
        }
        traversal.visitedNodes++
        try {
            // Нащадки невидимих ​​батьківських вузлів не стануть працездатними цілями, тому відсікайте такі великі гілки якомога раніше.
            val visible = node.isVisibleToUser
            if (depth > 0 && !visible) return

            val bounds = node.bounds()
            val text = node.realText().take(120)
            val hint = node.hintText?.toString().orEmpty().take(60)
            val desc = node.contentDescription?.toString().orEmpty().take(120)
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val scrollable = node.isScrollable
            val focused = node.isFocused
            val editable = node.isEditable
            val password = node.isPassword
            val enabled = node.isEnabled
            val clickTarget = if (clickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                clickableAncestor
            }
            val longClickTarget = if (longClickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                longClickableAncestor
            }
            val hasScrollCapability = enabled && node.hasScrollCapability()
            val scrollTarget = if (hasScrollCapability) {
                NodeActionTarget.capture(node)
            } else {
                scrollableAncestor
            }
            val useful = text.isNotBlank() ||
                desc.isNotBlank() ||
                clickable ||
                longClickable ||
                hasScrollCapability ||
                focused ||
                editable
            if (visible && bounds.width() > 2 && bounds.height() > 2 && useful) {
                out += IndexedNode(
                    index = out.size,
                    node = node,
                    uniqueId = node.uniqueId.orEmpty(),
                    windowId = node.windowId,
                    text = text,
                    desc = desc,
                    className = node.className?.toString().orEmpty(),
                    packageName = node.packageName?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    bounds = Rect(bounds),
                    clickable = clickable,
                    longClickable = longClickable,
                    scrollable = scrollTarget != null,
                    focused = focused,
                    editable = editable,
                    password = password,
                    enabled = enabled,
                    checked = node.isCheckable.takeIf { it }?.let { node.isChecked },
                    selected = node.isSelected,
                    hint = hint,
                    clickTarget = clickTarget,
                    longClickTarget = longClickTarget,
                    scrollTarget = scrollTarget,
                )
            }
            for (index in 0 until node.childCount) {
                if (
                    out.size >= maxNodes ||
                    traversal.visitedNodes >= traversal.maxVisitedNodes
                ) {
                    traversal.truncated = true
                    return
                }
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                collectNodes(
                    node = child,
                    out = out,
                    maxNodes = maxNodes,
                    depth = depth + 1,
                    traversal = traversal,
                    clickableAncestor = clickTarget,
                    longClickableAncestor = longClickTarget,
                    scrollableAncestor = scrollTarget,
                )
            }
        } finally {
            traversal.activePath.remove(node)
        }
    }

    private fun AccessibilityNodeInfo.bounds(): Rect =
        Rect().also { getBoundsInScreen(it) }

    private fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        args: Bundle? = null
    ): ActionDispatch =
        when (val result = callOnMainSync {
            if (args == null) node.performAction(action) else node.performAction(action, args)
        }) {
            is MainThreadCallResult.Completed -> {
                if (result.value == true) ActionDispatch.ACCEPTED else ActionDispatch.REJECTED
            }
            MainThreadCallResult.NOT_STARTED -> ActionDispatch.REJECTED
            MainThreadCallResult.OUTCOME_UNKNOWN -> ActionDispatch.OUTCOME_UNKNOWN
        }

    private fun dispatchGestureResult(
        path: Path,
        durationMs: Long,
        successMethod: String,
    ): NodeActionResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return NodeActionResult.failure(
                "GESTURE_NOT_DISPATCHED",
                "немаєздатнийіснуютьнемаєбар'єрперешкоджатихостДрітПроцедурате самекрокчекатилікуватирукупотенціал",
            )
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        val outcome = java.util.concurrent.atomic.AtomicReference<GestureDispatch>()
        val posted = mainHandler.post {
            if (!gate.tryStart()) {
                latch.countDown()
                return@post
            }
            val gesture = runCatching {
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
            }.getOrElse {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
                return@post
            }
            val dispatched = try {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.COMPLETED)
                            gate.finish()
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.CANCELLED)
                            gate.finish()
                            latch.countDown()
                        }
                    },
                    GESTURE_CALLBACK_HANDLER,
                )
            } catch (_: Throwable) {
                // Можливо, трансакцію Binder було доставлено; виняток не може довести, що жест не було виконано, і повторне відтворення Root заборонено.
                outcome.set(GestureDispatch.OUTCOME_UNKNOWN)
                gate.finish()
                latch.countDown()
                return@post
            }
            if (!dispatched) {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
            }
        }
        if (!posted) {
            return NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "немаєбар'єрперешкоджатихостДрітПроцедуравідхилитиАбсолютнорукупотенціалпризначитиобслуговування")
        }
        val finishedInTime = try {
            latch.await(durationMs + GESTURE_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finishedInTime) {
            if (gate.cancelIfPending()) {
                return NodeActionResult.failure(
                    "GESTURE_NOT_DISPATCHED",
                    "немаєбар'єрперешкоджатихостДрітПроцедураТрадиційнийзайнятий，рукупотенціалвжеіснуютьВиконатидобревпередВиберітьвидалити",
                )
            }
            return NodeActionResult.outcomeUnknown()
        }
        return when (outcome.get()) {
            GestureDispatch.COMPLETED -> NodeActionResult.success(successMethod)
            GestureDispatch.CANCELLED -> NodeActionResult.outcomeUnknown()
            GestureDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            GestureDispatch.NOT_DISPATCHED,
            null -> NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "КраваткасистемавідхилитиАбсолютнорукупотенціалпризначитиобслуговування")
        }
    }

    private fun runNodeActionOnMainSync(block: () -> NodeActionResult): NodeActionResult =
        when (val result = callOnMainSync(block)) {
            is MainThreadCallResult.Completed -> result.value
                ?: NodeActionResult.failure("ACTION_FAILED", "немаєбар'єрперешкоджатирухатисяробитиВиконатидобрерізнічасто")
            MainThreadCallResult.NOT_STARTED ->
                NodeActionResult.failure("SERVICE_TIMEOUT", "немаєбар'єрперешкоджатиОдягобслуговуванняхостДрітПроцедуранемаєкільцевідповідь，рухатисяробитище ніВиконатидобре")
            MainThreadCallResult.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
        }

    private fun <T> runOnMainSync(block: () -> T): T? =
        when (val result = callOnMainSync(block)) {
            is MainThreadCallResult.Completed -> result.value
            MainThreadCallResult.NOT_STARTED,
            MainThreadCallResult.OUTCOME_UNKNOWN -> null
        }

    private fun <T> callOnMainSync(block: () -> T): MainThreadCallResult<T> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                MainThreadCallResult.Completed(block())
            } catch (_: Throwable) {
                // Коли виклик фреймворку видає помилку, неможливо довести, що побічних ефектів не виникло, і абоненту заборонено повертатися та відтворювати.
                MainThreadCallResult.OUTCOME_UNKNOWN
            }
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        var value: T? = null
        var failed = false
        val posted = mainHandler.post {
            if (gate.tryStart()) {
                try {
                    value = block()
                } catch (_: Throwable) {
                    failed = true
                } finally {
                    gate.finish()
                }
            }
            latch.countDown()
        }
        if (!posted) return MainThreadCallResult.NOT_STARTED
        val completed = try {
            latch.await(MAIN_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            if (gate.cancelIfPending()) return MainThreadCallResult.NOT_STARTED
            // Побічні ефекти, що почалися, не повинні бути відкочені та перероблені абонентом; повертаються невідомі результати, і спочатку проводиться повторне спостереження моделі.
            return MainThreadCallResult.OUTCOME_UNKNOWN
        }
        if (failed) return MainThreadCallResult.OUTCOME_UNKNOWN
        return MainThreadCallResult.Completed(value)
    }

    data class NodeActionResult(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val clipboardWritten: Boolean = false,
        val verified: Boolean? = null,
    ) {
        companion object {
            fun success(method: String, verified: Boolean? = null): NodeActionResult =
                NodeActionResult(ok = true, method = method, verified = verified)

            fun failure(code: String, message: String): NodeActionResult =
                NodeActionResult(ok = false, code = code, message = message)

            fun outcomeUnknown(): NodeActionResult = failure(
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "рухатисяробитиможездатнийвжеВиконатидобре，алеКраваткасистемаще нііснуютьгодинаобмеженняВсерединіповерненняповерненнявузолфрукти；будь ласкапершеВажкановийпереглянутиоглядати，уникативідмовивсяВажкаскладніТримайтеробити",
            )
        }
    }

    private enum class ActionDispatch {
        ACCEPTED,
        REJECTED,
        OUTCOME_UNKNOWN,
    }

    private enum class GestureDispatch {
        COMPLETED,
        CANCELLED,
        NOT_DISPATCHED,
        OUTCOME_UNKNOWN,
    }

    private sealed interface MainThreadCallResult<out T> {
        data class Completed<T>(val value: T?) : MainThreadCallResult<T>
        data object NOT_STARTED : MainThreadCallResult<Nothing>
        data object OUTCOME_UNKNOWN : MainThreadCallResult<Nothing>
    }

    data class ScreenshotCaptureResult(
        val bitmap: Bitmap?,
        val complete: Boolean,
        val expectedWindows: Int,
        val capturedWindows: Int,
        val missingWindowIds: List<Int>,
        val failureCodes: Map<Int, Int>,
        val timedOut: Boolean,
        val criticalWindowMissing: Boolean,
    ) {
        val partial: Boolean get() = bitmap != null && !complete

        companion object {
            fun unavailable(): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = 0,
                capturedWindows = 0,
                missingWindowIds = emptyList(),
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = false,
            )

            fun blockedByUnknownWindow(
                expectedWindows: Int,
                windowIds: List<Int>,
            ): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = expectedWindows,
                capturedWindows = 0,
                missingWindowIds = windowIds,
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = true,
            )
        }
    }

    data class ClipboardReadResult(
        val ok: Boolean,
        val text: String = "",
        val code: String = "",
    ) {
        companion object {
            fun failure(code: String = "CLIPBOARD_UNAVAILABLE_OR_EMPTY"): ClipboardReadResult =
                ClipboardReadResult(ok = false, code = code)
        }
    }

    internal data class ScrollActionResult(
        val ok: Boolean,
        val direction: ScrollDirection,
        val moved: Boolean,
        val atBoundary: Boolean?,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val deltaX: Int? = null,
        val deltaY: Int? = null,
        val verifiedBy: String = "",
        val elapsedMs: Long = 0L,
        val targetIndex: Int? = null,
    ) {
        companion object {
            fun boundary(
                direction: ScrollDirection,
                method: String,
                targetIndex: Int?,
                elapsedMs: Long,
            ): ScrollActionResult = ScrollActionResult(
                ok = true,
                direction = direction,
                moved = false,
                atBoundary = true,
                method = method,
                verifiedBy = "action_list",
                elapsedMs = elapsedMs,
                targetIndex = targetIndex,
            )

            fun failure(
                direction: ScrollDirection,
                code: String,
                message: String,
                method: String = "",
                targetIndex: Int? = null,
                deltaX: Int? = null,
                deltaY: Int? = null,
                elapsedMs: Long = 0L,
            ): ScrollActionResult = ScrollActionResult(
                ok = false,
                direction = direction,
                moved = false,
                atBoundary = null,
                code = code,
                message = message,
                method = method,
                targetIndex = targetIndex,
                deltaX = deltaX,
                deltaY = deltaY,
                elapsedMs = elapsedMs,
            )
        }
    }

    class NodeSnapshot internal constructor(
        val id: String,
        internal val serviceToken: Long,
        val packageName: String,
        val windowId: Int,
        internal val contentGeneration: Long,
        val capturedAtElapsedMs: Long,
        val truncated: Boolean,
        internal val indexedNodes: List<IndexedNode>,
    ) {
        val nodes: List<UiNode> = indexedNodes.map(IndexedNode::toUiNode)

        internal fun hasUnambiguousIdentity(target: IndexedNode): Boolean {
            if (!target.hasStrongIdentity()) return false
            val hasUniqueId = target.uniqueId.isNotBlank()
            val matches = if (hasUniqueId) {
                indexedNodes.count { candidate -> candidate.uniqueId == target.uniqueId }
            } else {
                indexedNodes.count(target::sameSemanticIdentity)
            }
            return AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = hasUniqueId,
                snapshotTruncated = truncated,
                identityMatchCount = matches,
            )
        }
    }

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean,
        /** Стан перемикачів, прапорців і т.д.; null означає, що вузол не можна перевірити.*/
        val checked: Boolean? = null,
        val selected: Boolean = false,
        /** Підказка до поля введення має значення лише тоді, коли вміст порожній, і використовується для визначення того, «яке це поле введення».*/
        val hint: String = "",
    )

    internal data class IndexedNode(
        val index: Int,
        val node: AccessibilityNodeInfo,
        val uniqueId: String,
        val windowId: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean,
        val checked: Boolean?,
        val selected: Boolean,
        val hint: String,
        val clickTarget: NodeActionTarget?,
        val longClickTarget: NodeActionTarget?,
        val scrollTarget: NodeActionTarget?,
    ) {
        fun hasStrongIdentity(): Boolean = identity().strong

        fun sameSemanticIdentity(other: IndexedNode): Boolean =
            windowId == other.windowId &&
                packageName == other.packageName &&
                className == other.className &&
                viewId == other.viewId &&
                text == other.text &&
                desc == other.desc

        fun identityMatches(refreshed: AccessibilityNodeInfo): Boolean =
            identity().matches(refreshed.toIdentity())

        private fun identity(): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
            uniqueId = uniqueId,
            windowId = windowId,
            packageName = packageName,
            className = className,
            viewId = viewId,
            text = text,
            description = desc,
            password = password,
        )

        private fun AccessibilityNodeInfo.toIdentity(): AccessibilityNodeIdentity =
            AccessibilityNodeIdentity(
                uniqueId = uniqueId.orEmpty(),
                windowId = windowId,
                packageName = packageName?.toString().orEmpty(),
                className = className?.toString().orEmpty(),
                viewId = viewIdResourceName.orEmpty(),
                text = (if (isShowingHintText) "" else text?.toString().orEmpty()).take(120),
                description = contentDescription?.toString().orEmpty().take(120),
                password = isPassword,
            )

        fun toUiNode(): UiNode =
            UiNode(
                index = index,
                text = text,
                desc = desc,
                className = className,
                packageName = packageName,
                viewId = viewId,
                bounds = bounds,
                clickable = clickable,
                longClickable = longClickable,
                scrollable = scrollable,
                focused = focused,
                editable = editable,
                password = password,
                enabled = enabled,
                checked = checked,
                selected = selected,
                hint = hint,
            )
    }

    internal data class NodeActionTarget(
        val node: AccessibilityNodeInfo,
        val identity: AccessibilityNodeIdentity,
    ) {
        fun resolveFor(descendant: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!runCatching { node.refresh() }.getOrDefault(false)) return null
            if (!node.isVisibleToUser || !node.isEnabled) return null
            val refreshedIdentity = AccessibilityNodeIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                windowId = node.windowId,
                packageName = node.packageName?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                text = node.text?.toString().orEmpty().take(120),
                description = node.contentDescription?.toString().orEmpty().take(120),
                password = node.isPassword,
            )
            if (!identity.matches(refreshedIdentity)) return null
            var current: AccessibilityNodeInfo? = descendant
            var depth = 0
            while (current != null && depth <= MAX_UI_TREE_DEPTH) {
                if (current == node) return node
                current = current.parent
                depth++
            }
            return null
        }

        companion object {
            fun capture(node: AccessibilityNodeInfo): NodeActionTarget = NodeActionTarget(
                node = node,
                identity = AccessibilityNodeIdentity(
                    uniqueId = node.uniqueId.orEmpty(),
                    windowId = node.windowId,
                    packageName = node.packageName?.toString().orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    text = node.text?.toString().orEmpty().take(120),
                    description = node.contentDescription?.toString().orEmpty().take(120),
                    password = node.isPassword,
                ),
            )
        }
    }

    private data class ScrollCandidate(
        val node: AccessibilityNodeInfo,
        val supportRank: Int,
        val depth: Int,
        val area: Long,
    )

    private data class ScrollAnchor(
        val key: String,
        val centerX: Int,
        val centerY: Int,
    )

    private data class ScrollMethod(
        val actionId: Int,
        val name: String,
        val args: Bundle? = null,
    )

    private data class ScrollSignal(
        val sequence: Long,
        val packageName: String,
        val windowId: Int,
        val deltaX: Int,
        val deltaY: Int,
        val scrollX: Int,
        val scrollY: Int,
        val maxScrollX: Int,
        val maxScrollY: Int,
        val fromIndex: Int,
        val toIndex: Int,
        val sourceUniqueId: String,
        val sourceViewId: String,
        val sourceClassName: String,
        val sourceBounds: Rect,
    ) {
        fun axisDelta(axis: ScrollAxis): Int? = ScrollEvidenceContract.normalizeAccessibilityDelta(
            when (axis) {
            ScrollAxis.HORIZONTAL -> deltaX
            ScrollAxis.VERTICAL -> deltaY
            },
        )

        fun matchesTarget(target: ScrollTargetIdentity): Boolean {
            if (target.uniqueId.isNotBlank() && sourceUniqueId.isNotBlank()) {
                return target.uniqueId == sourceUniqueId
            }
            if (target.viewId.isNotBlank() && sourceViewId.isNotBlank()) {
                return target.viewId == sourceViewId && target.className == sourceClassName
            }
            return target.className == sourceClassName && target.bounds == sourceBounds
        }
    }

    private data class ScrollTargetIdentity(
        val uniqueId: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
    ) {
        companion object {
            fun from(node: AccessibilityNodeInfo): ScrollTargetIdentity = ScrollTargetIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = Rect().also(node::getBoundsInScreen),
            )
        }
    }

    companion object {
        private const val CLIP_LABEL = "eta_agent"
        private const val WINDOW_POLL_FALLBACK_MS = 80L
        private const val MAX_UI_TREE_DEPTH = 24
        private const val UI_TREE_VISIT_MULTIPLIER = 8
        private const val MIN_UI_TREE_VISITED_NODES = 128
        private const val MAX_UI_TREE_VISITED_NODES = 960
        private const val MAX_FOCUS_SEARCH_NODES = 512
        private const val MAX_SCROLL_SEARCH_NODES = 512
        private const val SCROLL_ANCHOR_NODE_LIMIT = 48
        private const val SCROLL_GESTURE_DURATION_MS = 300L
        private const val SCROLL_VERIFY_TIMEOUT_MS = 520L
        private const val GESTURE_CALLBACK_GRACE_MS = 1_500L
        private const val MAIN_SYNC_TIMEOUT_SECONDS = 3L
        private const val SCROLL_EVENT_OBSERVATION_TIMEOUT_MS =
            MAIN_SYNC_TIMEOUT_SECONDS * 1_000L +
                SCROLL_GESTURE_DURATION_MS +
                GESTURE_CALLBACK_GRACE_MS +
                SCROLL_VERIFY_TIMEOUT_MS
        private const val SCROLL_EVENT_QUEUE_CAPACITY = 1
        private const val MAX_SCROLL_SIGNALS = 64

        private val GESTURE_CALLBACK_THREAD = HandlerThread("agent-gesture-callback").apply {
            isDaemon = true
            start()
        }
        private val GESTURE_CALLBACK_HANDLER = Handler(GESTURE_CALLBACK_THREAD.looper)

        /**
         * Виконавець на рівні процесу не вимикається зараз, коли служба повторно підключається; інакше він був створений фреймворком.
         * ScreenshotResult, який все ще перебуває в черзі, не може ввести зворотний виклик, щоб звільнити HardwareBuffer.
         */
        private val SCREENSHOT_EXECUTOR: ExecutorService =
            Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "agent-screenshot-callback").apply { isDaemon = true }
            }

        private val SCROLL_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        ).let { actions ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                actions + AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id
            } else {
                actions
            }
        }
        private val VERTICAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
        )
        private val HORIZONTAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        )

        private val SERVICE_TOKENS = AtomicLong(0)
        private val SNAPSHOT_IDS = AtomicLong(0)
        private val CLIP_IDS = AtomicLong(0)

        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun current(): AgentAccessibilityService? = instance

        fun isAvailable(): Boolean = instance != null
    }

    private sealed interface NodeValidation {
        data class Valid(val indexedNode: IndexedNode) : NodeValidation
        data class Invalid(val result: NodeActionResult) : NodeValidation
    }
}
