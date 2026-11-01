package io.github.mangi.eta

import io.github.mangi.eta.agent.voice.SpeechOssUpload
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.data.repository.LanguageSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Застосування процесу інтерфейсу користувача модуля.
 *
 * Зареєструйте слухач [XposedServiceHelper] під час запуску процесу, і фреймворк перешле підшивку через XposedProvider.
 * Потім користувальницький інтерфейс може отримати [XposedService] і записати його в RemotePreferences, а також синхронізувати між процесами для кожного процесу-перехоплювача.
 *
 * Інтерфейс користувача записує RemotePreferences через [XposedService].
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { LanguageSettingsRepository(this).ensureDefaultLocale() }
            .onFailure { error ->
                AndroidAgentLogger.warn(
                    "Default app language unavailable: type=${error.safeLogType()}"
                )
            }
        AgentTraceFormatter.context = this
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@EtaApp).resume() }
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            BrowserSessionRegistry.releaseIdlePages()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        BrowserSessionRegistry.releaseIdlePages()
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // Очистити та відправити нуль лише тоді, коли служба, яка наразі підтримується, припиняється;
        // У сценарії з кількома фреймворками мертвий екземпляр може бути старим екземпляром, який було замінено, не впливаючи на інтерфейс користувача.
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
