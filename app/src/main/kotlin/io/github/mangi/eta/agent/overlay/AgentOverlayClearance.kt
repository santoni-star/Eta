package io.github.mangi.eta.agent.overlay

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Уникнення координації жестів Агента та капсул статусу.
 *
 * Вікно капсули можна торкатися, і воно перехоплює жести та події «введення» Root, введені в безбар’єрний спосіб;
 * Коли жест потрапляє в діапазон дії капсули, спочатку зробіть капсулу недоторканою та зникайте, а потім відкладіть відновлення після завершення жесту.
 * Усунення дребезгу смуги відновлення: безперервні клацання та ковзання відновляться лише після останнього, і капсула не блиматиме вгору та вниз із кожним кроком.
 */
internal object AgentOverlayClearance {

    /** Реалізовано сервісом утримування вікна капсули; всі методи викликаються в основному потоці.*/
    interface Host {
        /** Діапазон дотику вікна капсули на екрані (включаючи межі краю); повертає null, якщо вікно не існує.*/
        fun capsuleTouchBounds(): Rect?

        /** Негайно відпустіть дотик і почніть зникаючу анімацію.*/
        fun yieldCapsule()

        /** Відновлення можна торкнутися та зникають; скасовується [cancelRestore], коли потрібно скасувати відновлення в черзі.*/
        fun scheduleCapsuleRestore(delayMs: Long)

        fun cancelRestore()
    }

    @Volatile
    private var host: Host? = null

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }

    /**
     * Обертайтеся до і після жесту. [область] — це діапазон екрану, охоплений жестом (клацання — це точка, ковзання — початкова та кінцева рамки).
     * Абонент виконує жест синхронно в потоці інструментів. Тут він чекатиме, поки згасання пройде наполовину, перш ніж відпустити його, дозволяючи відгуку про клацання з’явитися на чистому екрані.
     */
    fun <T> around(area: Rect, gesture: () -> T): T {
        val current = host ?: return gesture()
        if (Looper.myLooper() == Looper.getMainLooper()) return gesture()
        val yielded = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        current.post {
            val bounds = current.capsuleTouchBounds()
            if (bounds != null && Rect.intersects(bounds, area)) {
                current.cancelRestore()
                current.yieldCapsule()
                yielded.set(true)
            }
            ready.countDown()
        }
        ready.await(MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        // Дотик відпускається, коли видає капсулу; зачекайте, щоб зникнути, щоб дати візуальний простір і уникнути накладання індикації жестів і капсули.
        if (yielded.get()) Thread.sleep(FADE_HEAD_START_MS)
        return try {
            gesture()
        } finally {
            // Основний потік виконується в порядку доставки: навіть якщо час очікування та уникнення відбуваються пізніше, ніж жест, відновлення ставиться в чергу після нього.
            current.post { if (yielded.get()) current.scheduleCapsuleRestore(RESTORE_DELAY_MS) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun Host.post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private const val MAX_WAIT_MS = 300L
    private const val FADE_HEAD_START_MS = 90L
    /** Після виконання дії інтерфейс зазвичай знову спостерігатиметься та виконується наступний крок, залишаючи достатньо місця перед поверненням, щоб уникнути мерехтіння туди-сюди.*/
    private const val RESTORE_DELAY_MS = 900L
}
