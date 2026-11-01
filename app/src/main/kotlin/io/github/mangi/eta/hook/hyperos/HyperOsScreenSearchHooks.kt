package io.github.mangi.eta.hook.hyperos

import android.app.Service
import android.content.Intent
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

internal object HyperOsScreenSearchHooks {
    private const val SERVICE_CLASS = "com.xiaomi.voiceassistant.VoiceService"

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsScreenSearch")
        return hooks.install {
            val serviceClass = HookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
            val method = serviceClass?.let {
                HookSupport.findMethod(
                    it,
                    "onStartCommand",
                    Intent::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
            }
            if (serviceClass == null || !Service::class.java.isAssignableFrom(serviceClass) ||
                method == null || method.declaringClass != serviceClass ||
                method.returnType != Int::class.javaPrimitiveType
            ) {
                missing(
                    "hyperos.screen-search-service",
                    "VoiceService.onStartCommand",
                    "HyperOS: ще ніспробуйте знайтиприбутиматчматчзнакназвазМаленькийякзнанняекранОдягобслуговуваннявведітьрот",
                )
                return@install
            }
            intercept(
                "hyperos.screen-search-service",
                method,
                "VoiceService.onStartCommand",
            ) { chain ->
                if (!HyperOsSearchTrigger.isEnabled()) return@intercept chain.proceed()
                val intent = chain.getArg(0) as? Intent
                val matches = try {
                    HyperOsScreenSearchRequest.matches(intent)
                } catch (exception: Exception) {
                    hooks.logger.warnThrottled("hyperos_screen_search_invalid_intent") {
                        "HyperOS: немаєЗакончитатиВиберітьзнанняекранбудь ласкапросити，зберегтиТримайтеОригіналлогікаредагувати，type=${exception.safeLogType()}"
                    }
                    false
                }
                if (!matches) return@intercept chain.proceed()
                val service = chain.getThisObject() as Service
                if (!HyperOsSearchTrigger.trigger(service, hooks.logger)) {
                    hooks.logger.warnThrottled("hyperos_screen_search_dispatch_failed") {
                        "HyperOS: керівництвовітрилознанняекранпошукОтжебудь ласкапроситище ніволоссяпоза，поверненнявідступМаленькийякзнанняекран"
                    }
                    return@intercept chain.proceed()
                }
                finishHandledStart(service, chain.getArg(2) as Int, hooks.logger)
                hooks.logger.debug { "HyperOS: керівництвовітрилознанняекранбудь ласкапроситивжеплатитиДайте contextual_search" }
                Service.START_NOT_STICKY
            }
        }
    }

    private fun finishHandledStart(service: Service, startId: Int, logger: ModuleLogger) {
        try {
            // Служба очищається лише тоді, коли це останній запуск. Безумовний stopSelf не можна використовувати для відхилення наступних запитів.
            if (service.stopSelfResult(startId)) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
        } catch (exception: Exception) {
            // Пошук було запущено, очищення не вдалося, і початковий запис на екрані більше не можна виконати, що призвело до подвійного збудження.
            logger.warnThrottled("hyperos_screen_search_cleanup_failed") {
                "HyperOS: знанняекранОдягобслуговуванняпочатокрухатисяяснопричинавтратитипоразка，type=${exception.safeLogType()}"
            }
        }
    }
}
