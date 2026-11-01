package io.github.mangi.eta.ui.screens.terminal

import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import io.github.mangi.eta.agent.terminal.TerminalKey
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Призначення консольного введення: текст на екрані, функціональні клавіші та попередній перегляд групи слів методу введення викликаються окремо.*/
internal interface ConsoleInputSink {
    fun onText(text: String, modifiers: TerminalModifiers)
    fun onKey(key: TerminalKey, modifiers: TerminalModifiers)
    fun onComposingChange(text: String)
}

/** Утримуваний інтерфейсом, він запитує змонтований вузол введення для відображення або приховування програмної клавіатури.*/
@Stable
internal class ConsoleKeyboardController {
    internal var node: ConsoleTextInputNode? = null

    fun show() {
        node?.requestKeyboard(show = true)
    }

    fun hide() {
        node?.requestKeyboard(show = false)
    }
}

/**
 * Зробіть область, у якій ви перебуваєте, ціллю введення терміналу: безпосередньо підключіться до методу введення після отримання фокусу, замість того, щоб покладатися на приховане поле введення.
 * Текстова модель поля введення поглинає Backspace, розглядає піньїнь у групі слів як вхідні дані та не отримуватиме клавіші зі стрілками;
 * Тут тільки підтверджений текст і натискання клавіш передаються в [sink], редактор завжди залишається порожнім.
 */
internal fun Modifier.consoleTextInput(
    sink: ConsoleInputSink,
    controller: ConsoleKeyboardController,
): Modifier = this then ConsoleTextInputElement(sink, controller) then Modifier.focusTarget()

private data class ConsoleTextInputElement(
    val sink: ConsoleInputSink,
    val controller: ConsoleKeyboardController,
) : ModifierNodeElement<ConsoleTextInputNode>() {
    override fun create() = ConsoleTextInputNode(sink, controller)

    override fun update(node: ConsoleTextInputNode) {
        node.update(sink, controller)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "consoleTextInput"
    }
}

internal class ConsoleTextInputNode(
    private var sink: ConsoleInputSink,
    private var controller: ConsoleKeyboardController,
) : Modifier.Node(),
    PlatformTextInputModifierNode,
    FocusRequesterModifierNode,
    FocusEventModifierNode,
    KeyInputModifierNode {

    private var session: Job? = null

    /** Зберігається лише останній явний і прихований запит; якщо сеанс ще не встановлено, він буде виконаний після його встановлення.*/
    private val keyboardRequests = Channel<Boolean>(Channel.CONFLATED)

    fun update(sink: ConsoleInputSink, controller: ConsoleKeyboardController) {
        this.sink = sink
        if (this.controller !== controller) {
            if (this.controller.node === this) this.controller.node = null
            this.controller = controller
            controller.node = this
        }
    }

    override fun onAttach() {
        controller.node = this
    }

    override fun onDetach() {
        if (controller.node === this) controller.node = null
        session = null
    }

    fun requestKeyboard(show: Boolean) {
        keyboardRequests.trySend(show)
        if (show) requestFocus()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == (session != null)) return
        session?.cancel()
        session = if (focusState.isFocused) startSession() else null
    }

    private fun startSession(): Job = coroutineScope.launch {
        establishTextInputSession {
            val inputMethodManager = view.context.getSystemService(InputMethodManager::class.java)
            launch {
                for (show in keyboardRequests) {
                    if (show) {
                        inputMethodManager.showSoftInput(view, 0)
                    } else {
                        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                }
            }
            startInputMethod { outAttributes ->
                configureEditorInfo(outAttributes)
                ConsoleInputConnection(view) { sink }
            }
        }
    }

    // Клавіші апаратної клавіатури розподіляються на вузол фокусування через подання; програмні клавіші клавіатури проходять через InputConnection.sendKeyEvent.
    override fun onKeyEvent(event: KeyEvent): Boolean =
        dispatchKeyEvent(event.nativeKeyEvent, sink)

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * Звичайний тип тексту замість типу пароля: тип пароля змусить більшість китайських методів введення перейти на англійську;
 * Вимкніть асоціацію та персоналізоване навчання, щоб уникнути автоматичного виправлення помилок і переписування команд, а також щоб метод введення не запам’ятовував те, що було введено в терміналі.
 * Для кількох рядків і без дії редактора клавіша Enter працює як клавіша переходу рядка та перетворюється з підключення на клавішу Enter терміналу.
 */
private fun configureEditorInfo(outAttributes: EditorInfo) {
    outAttributes.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE
    outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_ENTER_ACTION or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    outAttributes.initialSelStart = 0
    outAttributes.initialSelEnd = 0
}

/**
 * Редактор містить лише область групування слів: протягом періоду групування слів вміст залишається в редагованому буфері для перезапису методом введення.
 *Надіслати та очистити одразу після відображення екрана. Запити на видалення, коли буфер порожній, пересилаються натисканням клавіші Backspace/Delete.
 */
private class ConsoleInputConnection(
    view: View,
    private val sink: () -> ConsoleInputSink,
) : BaseInputConnection(view, true) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        editable?.clear()
        sink().onComposingChange("")
        text?.toString()?.takeIf { it.isNotEmpty() }?.let { sink().onText(it, TerminalModifiers.NONE) }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.setComposingText(text, newCursorPosition)
        sink().onComposingChange(editable?.toString().orEmpty())
        return true
    }

    override fun finishComposingText(): Boolean {
        val pending = editable?.toString().orEmpty()
        editable?.clear()
        sink().onComposingChange("")
        if (pending.isNotEmpty()) sink().onText(pending, TerminalModifiers.NONE)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!editable.isNullOrEmpty()) {
            val handled = super.deleteSurroundingText(beforeLength, afterLength)
            sink().onComposingChange(editable?.toString().orEmpty())
            return handled
        }
        repeat(beforeLength) { sink().onKey(TerminalKey.BACKSPACE, TerminalModifiers.NONE) }
        repeat(afterLength) { sink().onKey(TerminalKey.DELETE, TerminalModifiers.NONE) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: AndroidKeyEvent): Boolean {
        dispatchKeyEvent(event, sink())
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        sink().onKey(TerminalKey.ENTER, TerminalModifiers.NONE)
        return true
    }

    // Запобігайте методу введення виводити початок речення на основі порожнього буфера та автоматично використовувати його великими літерами.
    override fun getCursorCapsMode(reqModes: Int): Int = 0
}

/** Повертає чи споживати; воно надсилається лише при натисканні. Подія підйому також споживає ідентифіковані ключі, щоб уникнути ініціювання переміщення фокуса.*/
private fun dispatchKeyEvent(event: AndroidKeyEvent, sink: ConsoleInputSink): Boolean {
    val modifiers = TerminalModifiers(
        ctrl = event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
    )
    val key = TERMINAL_KEYS[event.keyCode]
    if (key != null) {
        if (event.action == AndroidKeyEvent.ACTION_DOWN) sink.onKey(key, modifiers)
        return true
    }
    // Видаліть Ctrl/Alt, щоб отримати основні символи, а потім застосуйте зміни за допомогою кодувальника, дотримуючись того самого шляху кодування, що й клавіші-модифікатори програмної клавіатури.
    val unicode = event.getUnicodeChar(event.metaState and MODIFIER_META_MASK.inv())
    if (unicode <= 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return false
    if (event.action == AndroidKeyEvent.ACTION_DOWN) {
        // Shift відображається в самому символі (велика літера, символ) і більше не передається як модифікатор.
        sink.onText(String(Character.toChars(unicode)), modifiers.copy(shift = false))
    }
    return true
}

private const val MODIFIER_META_MASK = AndroidKeyEvent.META_CTRL_MASK or AndroidKeyEvent.META_ALT_MASK

private val TERMINAL_KEYS: Map<Int, TerminalKey> = mapOf(
    AndroidKeyEvent.KEYCODE_ENTER to TerminalKey.ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER to TerminalKey.ENTER,
    AndroidKeyEvent.KEYCODE_TAB to TerminalKey.TAB,
    AndroidKeyEvent.KEYCODE_DEL to TerminalKey.BACKSPACE,
    AndroidKeyEvent.KEYCODE_FORWARD_DEL to TerminalKey.DELETE,
    AndroidKeyEvent.KEYCODE_ESCAPE to TerminalKey.ESCAPE,
    AndroidKeyEvent.KEYCODE_DPAD_UP to TerminalKey.UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN to TerminalKey.DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT to TerminalKey.LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT to TerminalKey.RIGHT,
    AndroidKeyEvent.KEYCODE_MOVE_HOME to TerminalKey.HOME,
    AndroidKeyEvent.KEYCODE_MOVE_END to TerminalKey.END,
    AndroidKeyEvent.KEYCODE_PAGE_UP to TerminalKey.PAGE_UP,
    AndroidKeyEvent.KEYCODE_PAGE_DOWN to TerminalKey.PAGE_DOWN,
    AndroidKeyEvent.KEYCODE_INSERT to TerminalKey.INSERT,
    AndroidKeyEvent.KEYCODE_F1 to TerminalKey.F1,
    AndroidKeyEvent.KEYCODE_F2 to TerminalKey.F2,
    AndroidKeyEvent.KEYCODE_F3 to TerminalKey.F3,
    AndroidKeyEvent.KEYCODE_F4 to TerminalKey.F4,
    AndroidKeyEvent.KEYCODE_F5 to TerminalKey.F5,
    AndroidKeyEvent.KEYCODE_F6 to TerminalKey.F6,
    AndroidKeyEvent.KEYCODE_F7 to TerminalKey.F7,
    AndroidKeyEvent.KEYCODE_F8 to TerminalKey.F8,
    AndroidKeyEvent.KEYCODE_F9 to TerminalKey.F9,
    AndroidKeyEvent.KEYCODE_F10 to TerminalKey.F10,
    AndroidKeyEvent.KEYCODE_F11 to TerminalKey.F11,
    AndroidKeyEvent.KEYCODE_F12 to TerminalKey.F12,
)
