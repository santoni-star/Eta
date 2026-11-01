package io.github.mangi.eta

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.aimemory.ColorOsMemoryHooks
import io.github.mangi.eta.hook.breeno.BreenoHooks
import io.github.mangi.eta.hook.colordirect.ColorDirectHooks
import io.github.mangi.eta.hook.google.GoogleAppHooks
import io.github.mangi.eta.hook.google.GoogleEligibilityHooks
import io.github.mangi.eta.hook.hyperos.HyperOsLauncherHooks
import io.github.mangi.eta.hook.hyperos.HyperOsScreenSearchHooks
import io.github.mangi.eta.hook.system.SystemServerHooks
import io.github.mangi.eta.hook.system.SystemUiHooks
import io.github.mangi.eta.hook.xiaoai.XiaoAiHooks

class ModuleMain : XposedModule() {

    private val logger = ModuleLogger(this)
    private var currentProcessName: String? = null
    // Гаряче перезавантаження наразі не ввімкнено; дескриптор зберігається для майбутнього явного відключення/заміни, а не для збереження дії гака.
    private val hookHandles = mutableListOf<HookHandle>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        if (!shouldKeepLifecycleCallbacks(param)) {
            detach()
            return
        }
        // Віддалені параметри лише для читання, надані структурою кешу, можуть бути негайно прочитані всіма зворотними викликами перехоплення.
        // getRemotePreferences — це метод XposedInterface, який XposedModule успадковує від своєї Wrapper і може викликатися напряму.
        // Якщо виклик не вдається, історична поведінка за замовчуванням зберігається, але необхідно залишити журнали, які можна діагностувати, а синхронізацію конфігурації не можна вважати нормальною.
        val remotePreferences = try {
            getRemotePreferences(Prefs.GROUP)
        } catch (exception: Exception) {
            logger.warn("RemotePreferences немаєможевикористовувати，ВолязробитивикористовуватиодночаснийДозволитимовчазнийрозпізнатизначення: ${exception.safeLogType()}")
            null
        }
        Prefs.attachRemote(remotePreferences)
        logger.debug {
            "цвільшматоквжедодатинавантаження process=${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion"
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        recordInstallation(SystemServerHooks.install(this, logger, param.classLoader))
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES -> {
                if (param.isFirstPackage && currentProcessName == param.packageName) {
                    recordInstallation(HyperOsLauncherHooks.install(this, logger, param.classLoader))
                }
            }

            ModuleConfig.SYSTEM_UI_PACKAGE -> {
                if (currentProcessName == ModuleConfig.SYSTEM_UI_PACKAGE) {
                    recordInstallation(SystemUiHooks.install(this, logger, param.classLoader))
                }
            }

            ModuleConfig.GOOGLE_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.GOOGLE_PACKAGE)) {
                    recordInstallation(
                        HookInstallation.combine(
                            group = "Google",
                            installations = listOf(
                                GoogleEligibilityHooks.install(this, logger, param.classLoader),
                                GoogleAppHooks.install(this, logger, param.classLoader)
                            )
                        )
                    )
                }
            }

            ModuleConfig.COLOR_DIRECT_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.COLOR_DIRECT_PACKAGE)) {
                    recordInstallation(
                        ColorDirectHooks.install(this, logger, param.classLoader, param.applicationInfo)
                    )
                }
            }

            ModuleConfig.BREENO_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.BREENO_PACKAGE)) {
                    recordInstallation(
                        BreenoHooks.install(this, logger, param.classLoader, param.applicationInfo)
                    )
                }
            }

            ModuleConfig.COLOROS_MEMORY_PACKAGE -> {
                if (currentProcessName == ModuleConfig.COLOROS_MEMORY_PACKAGE) {
                    recordInstallation(
                        ColorOsMemoryHooks.install(this, logger, param.classLoader, param.applicationInfo)
                    )
                }
            }

            ModuleConfig.XIAOAI_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.XIAOAI_PACKAGE)) {
                    recordInstallation(HyperOsScreenSearchHooks.install(this, logger, param.classLoader))
                }
                if (isCurrentXiaoAiProcess()) {
                    recordInstallation(
                        XiaoAiHooks.install(
                            module = this,
                            rootLogger = logger,
                            classLoader = param.classLoader,
                        )
                    )
                }
            }
        }
    }

    private fun recordInstallation(installation: HookInstallation) {
        hookHandles += installation.handles
        logger.scoped(installation.report.group).info(installation.report.summary())
    }

    private fun isCurrentPackageProcess(packageName: String): Boolean {
        val processName = currentProcessName ?: return false
        return isPackageProcess(processName, packageName)
    }

    private fun shouldKeepLifecycleCallbacks(param: ModuleLoadedParam): Boolean {
        if (param.isSystemServer) return true
        val processName = param.processName
        return processName in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES ||
            processName == ModuleConfig.SYSTEM_UI_PACKAGE ||
            isPackageProcess(processName, ModuleConfig.GOOGLE_PACKAGE) ||
            isPackageProcess(processName, ModuleConfig.COLOR_DIRECT_PACKAGE) ||
            isPackageProcess(processName, ModuleConfig.BREENO_PACKAGE) ||
            processName == ModuleConfig.COLOROS_MEMORY_PACKAGE ||
            isPackageProcess(processName, ModuleConfig.XIAOAI_PACKAGE)
    }

    private fun isCurrentXiaoAiProcess(): Boolean {
        val processName = currentProcessName ?: return false
        return processName == ModuleConfig.XIAOAI_PACKAGE ||
            processName == ModuleConfig.XIAOAI_CORE_PROCESS
    }

    private fun isPackageProcess(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")
}
