package io.github.mangi.eta.hook.hyperos

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.safeLogType
import java.util.WeakHashMap

internal object HyperOsLegacyGesture {
    fun install(hooks: HookRegistrar, loader: ClassLoader) {
        val type = HookSupport.findClassOrNull(loader, "com.miui.home.recents.NavStubView")
        val touch = type?.let { HookSupport.findMethod(it, "onTouchEvent", MotionEvent::class.java) }
        if (type == null || !View::class.java.isAssignableFrom(type) || touch?.declaringClass != type ||
            touch.returnType != Boolean::class.javaPrimitiveType
        ) {
            hooks.missing("hyperos.legacy-touch", "NavStubView.onTouchEvent", "HyperOS: ще ніспробуйте знайтиприбутистарийверсіякерівництвовітрилодивмалюнокдотикдотиквведітьрот")
            return
        }
        if (HookSupport.findField(type, "mCheckLongPress") != null) {
            hooks.skipped("hyperos.legacy-touch", "NavStubView.onTouchEvent", "HyperOS: стіллокшинавжематидовговідповідно доперевірититесталеповерненнямелодіяще нізнати，зберегтиТримайтеОригіналнародивсярукупотенціал")
            return
        }
        // Зворотні виклики перегляду виконуються послідовно в потоці користувальницького інтерфейсу власника; слабкі ключі та слабкі посилання в значеннях разом запобігають витоку знищених представлень.
        val gestures = WeakHashMap<View, HyperOsLongPressGesture>()
        hooks.intercept("hyperos.legacy-touch", touch, "NavStubView.onTouchEvent") { chain ->
            val view = chain.getThisObject() as View
            val event = chain.getArg(0) as MotionEvent
            val gesture = gestures.getOrPut(view) {
                HyperOsLongPressGesture(view, HyperOsSearchTrigger::isEnabled) { current ->
                    val started = HyperOsSearchTrigger.trigger(current.context, hooks.logger)
                    if (started) {
                        try {
                            current.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } catch (exception: Exception) {
                            hooks.logger.warnThrottled("hyperos_haptic_failed") {
                                "HyperOS: довговідповідно допротилежністьгодувативтратитипоразка，type=${exception.safeLogType()}"
                            }
                        }
                    }
                    started
                }
            }
            if (gesture.onTouch(event)) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try {
                    chain.proceed(arrayOf<Any>(cancel))
                } finally {
                    cancel.recycle()
                }
            } else {
                val result = chain.proceed()
                if (result != true) gesture.cancel()
                result
            }
        } ?: return

        val prepare = HookSupport.findMethod(type, "startRecentsAnimationPre")
        if (prepare?.returnType == Void.TYPE) {
            hooks.intercept("hyperos.legacy-recents", prepare, "NavStubView.startRecentsAnimationPre") { chain ->
                if (HyperOsSearchTrigger.isEnabled() && gestures[chain.getThisObject()]?.pending == true) null
                else chain.proceed()
            }
        } else {
            hooks.skipped("hyperos.legacy-recents", "NavStubView.startRecentsAnimationPre", "HyperOS: ще нінестидлябільшістьзакритипризначитиобслуговуванняпопередньопочатокрухатисявведітьрот")
        }
    }
}
