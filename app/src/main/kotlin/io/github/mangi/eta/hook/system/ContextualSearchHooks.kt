package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

import io.github.mangi.eta.config.Prefs
import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.github.libxposed.api.XposedModule

internal object ContextualSearchHooks {

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ContextualSearch")
        return hooks.install {
            // Служба може бути вимкнена конфігурацією ресурсів ПЗУ; спочатку спробуйте нативний запуск, а потім збережіть кінцевий сегмент запуску, щоб завершити.
            hookContextualSearchConfig(hooks, classLoader)
            hookContextualSearchBootstrap(module, hooks, classLoader)
            hookContextualSearchPackage(hooks, classLoader)
            hookContextualSearchPermission(hooks, classLoader)
        }
    }

    private fun hookContextualSearchConfig(hooks: HookRegistrar, classLoader: ClassLoader) {
        val server = HookSupport.findClassOrNull(classLoader, ModuleConfig.SYSTEM_SERVER_CLASS)
        val resources = HookSupport.findClassOrNull(classLoader, "com.android.internal.R\$string")
        val resourceId = resources?.let {
            HookSupport.findField(it, "config_defaultContextualSearchPackageName")?.getInt(null)
        }
        val method = server?.let {
            HookSupport.findMethod(it, "deviceHasConfigString", Context::class.java, Int::class.javaPrimitiveType!!)
        }
        if (resourceId == null || resourceId == 0 || method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.skipped("system.contextual-search-config", "SystemServer.deviceHasConfigString", "ще нінестидля Contextual Search Капіталджерелопочатокрухатисясмужкаштук，зберегтиТримайтехвістчастинаремонтпочатокрухатися")
            return
        }
        hooks.intercept("system.contextual-search-config", method, "SystemServer.deviceHasConfigString") { chain ->
            if (chain.getArg(1) == resourceId) true else chain.proceed()
        }
    }

    private fun hookContextualSearchBootstrap(
        module: XposedModule,
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        // Журнал перезапуску поточного пристрою 2026-03-26 довів, що справді стабільним і ефективним є додатковий запуск наприкінці startOtherServices.
        // Тут ми безпосередньо дотримуємося кінця запуску системної служби і більше не розглядаємо deviceHasConfigString як єдину ефективну точку.
        val systemServerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.SYSTEM_SERVER_CLASS)
        val timingsClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.TIMINGS_TRACE_AND_SLOG_CLASS)
        val startOtherServicesMethod = if (systemServerClass != null && timingsClass != null) {
            HookSupport.findMethod(systemServerClass, "startOtherServices", timingsClass)
        } else {
            null
        }
        if (startOtherServicesMethod == null) {
            hooks.missing(
                id = "system.contextual-search-bootstrap",
                description = "SystemServer.startOtherServices",
                detail = "ще ніспробуйте знайтиприбути SystemServer.startOtherServices(TimingsTraceAndSlog)"
            )
            return
        }

        HookSupport.deoptimize(
            module,
            logger,
            startOtherServicesMethod,
            "SystemServer.startOtherServices(TimingsTraceAndSlog)"
        )
        hooks.intercept(
            id = "system.contextual-search-bootstrap",
            executable = startOtherServicesMethod,
            description = "SystemServer.startOtherServices"
        ) { chain ->
            val result = chain.proceed()
            ensureContextualSearchService(module, logger, classLoader, chain.getThisObject(), "startOtherServices")
            result
        }
    }

    private fun hookContextualSearchPackage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "getContextualSearchPackageName") }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-package",
                description = "ContextualSearchManagerService.getContextualSearchPackageName",
                detail = "ще ніспробуйте знайтиприбути ContextualSearchManagerService.getContextualSearchPackageName()"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-package",
            executable = method,
            description = "ContextualSearchManagerService.getContextualSearchPackageName"
        ) { ModuleConfig.GOOGLE_PACKAGE }
    }

    private fun hookContextualSearchPermission(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "enforcePermission", String::class.java) }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-permission",
                description = "ContextualSearchManagerService.enforcePermission",
                detail = "ще ніспробуйте знайтиприбути ContextualSearchManagerService.enforcePermission(String)"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-permission",
            executable = method,
            description = "ContextualSearchManagerService.enforcePermission"
        ) { chain ->
            val functionName = chain.getArg(0) as? String
            if (functionName == "startContextualSearch" && isAllowedContextualSearchUid(chain.getThisObject())) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun isAllowedContextualSearchUid(serviceInstance: Any): Boolean {
        val context = HookSupport.invokeNoArgs(serviceInstance, "getContext") as? Context
            ?: HookSupport.getFieldValue(serviceInstance, "mContext") as? Context
            ?: return false
        // IContextualSearchManager є одностороннім AIDL, а PID виклику фіксований і недоступний; сама тема дозволу Android також є UID.
        // Тому автентифікацію можна виконати лише на основі UID, а результат getPackagesForUid представляє всю межу безпеки спільного UID.
        val callingUid = Binder.getCallingUid()
        val packages = try {
            context.packageManager.getPackagesForUid(callingUid)
        } catch (_: Exception) {
            null
        } ?: return false
        return packages.contains(ModuleConfig.SYSTEM_UI_PACKAGE) ||
            packages.contains(ModuleConfig.COLOR_DIRECT_PACKAGE) ||
            ContextualSearchCallerPolicy.allowsHyperOsCaller(
                context,
                packages,
                gestureEnabled = Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH),
            )
    }

    private fun ensureContextualSearchService(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
        systemServerInstance: Any,
        source: String
    ) {
        if (isContextualSearchServiceAlive()) {
            logger.debug { "$source: contextual_search service вжежитиіснують" }
            return
        }

        val systemServiceManager = HookSupport.getFieldValue(systemServerInstance, "mSystemServiceManager")
        if (systemServiceManager == null) {
            logger.warn("$source: mSystemServiceManager длянульовий，немаєЗаконремонтпочатокрухатися contextual_search")
            return
        }

        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        if (serviceClass == null) {
            logger.warn("$source: ще ніспробуйте знайтиприбути ContextualSearchManagerService class，немаєЗаконремонтпочатокрухатися")
            return
        }

        val startServiceMethod = HookSupport.findMethod(
            systemServiceManager.javaClass,
            "startService",
            Class::class.java
        )
        if (startServiceMethod == null) {
            logger.warn("$source: ще ніспробуйте знайтиприбути SystemServiceManager.startService(Class)")
            return
        }

        try {
            module.getInvoker(startServiceMethod).invoke(systemServiceManager, serviceClass)
            if (isContextualSearchServiceAlive()) {
                logger.debug { "$source: вжеремонтпочатокрухатися ContextualSearchManagerService" }
            } else {
                logger.warn("$source: вжемелодіявикористовувати startService(Class)，але contextual_search досінемаєможевикористовувати")
            }
        } catch (exception: Exception) {
            // XposedFrameworkError є помилкою, і її потрібно продовжувати передавати фреймворку для обробки.
            logger.error("$source: ремонтпочатокрухатися ContextualSearchManagerService втратитипоразка", exception)
        }
    }

    private fun isContextualSearchServiceAlive(): Boolean =
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
            binder?.isBinderAlive == true
        }.getOrDefault(false)
}
