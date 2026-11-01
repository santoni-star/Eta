package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import io.github.mangi.eta.data.repository.CommunityCatalogRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class CommunityCatalogViewModel(application: Application) : AndroidViewModel(application) {
    val store = CommunityCatalogStore(application, viewModelScope)
}

/** Сторінка перегляду каталогу та сторінка вибору моделі мають однакові дані для читання, щоб уникнути повторного запиту під час навігації.*/
internal class CommunityCatalogStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val catalogRefreshIntervalMillis = 24 * 60 * 60 * 1000L
    private val retryCooldownMillis = 60 * 60 * 1000L
    private val applicationContext = context.applicationContext
    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var lastAutomaticRefreshAttemptAt: Long? = null

    var catalog by mutableStateOf<CommunityCatalog?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadError by mutableStateOf(false)
        private set
    var refreshError by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)
        private set

    fun openCatalog() {
        if (catalog != null) {
            refreshIfNeeded()
            return
        }
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            loadLocal()
            refreshIfNeeded()
        }
    }

    fun ensureLoaded() {
        if (catalog != null || loadJob?.isActive == true) return
        loadJob = scope.launch { loadLocal() }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            refreshing = true
            refreshError = null
            try {
                val result = CommunityCatalogRepository.refresh(applicationContext)
                val refreshed = result.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    refreshError = failure.catalogErrorMessage()
                    return@launch
                }
                catalog = refreshed
                loadError = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                refreshError = failure.catalogErrorMessage()
            } finally {
                refreshing = false
            }
        }
    }

    suspend fun importProvider(provider: CommunityCatalogProvider, modelIds: Set<String>): String {
        check(!importing) { "ГоловазапискерівництвовведітьпростоіснуютьВведітьдобре" }
        importing = true
        try {
            val added = ProviderRepository.addProvider(
                CommunityCatalogRepository.newProvider(provider, modelIds),
            )
            return added.id
        } finally {
            importing = false
        }
    }

    private suspend fun loadLocal() {
        loading = true
        loadError = false
        try {
            catalog = CommunityCatalogRepository.loadLocal(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            loadError = true
        } finally {
            loading = false
        }
    }

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (catalog?.shouldAutoRefresh(now) == false) return
        val previous = lastAutomaticRefreshAttemptAt
        if (previous != null && now >= previous && now - previous < retryCooldownMillis) return
        lastAutomaticRefreshAttemptAt = now
        refresh()
    }

    private fun CommunityCatalog.shouldAutoRefresh(now: Long): Boolean = when (source) {
        CommunityCatalogSource.SNAPSHOT -> true
        CommunityCatalogSource.CACHE,
        CommunityCatalogSource.ONLINE ->
            fetchedAt == null || fetchedAt > now || now - fetchedAt >= catalogRefreshIntervalMillis
    }

    private fun Throwable.catalogErrorMessage(): String {
        val status = Regex("^ГоловазаписНавітьновийвтратитипоразка：HTTP ([0-9]{3})$")
            .matchEntire(message.orEmpty())
            ?.groupValues
            ?.get(1)
        return when {
            status != null -> "ГоловазаписОдягобслуговуванняповерненняповернення HTTP $status"
            this is SocketTimeoutException -> "навітьзловитиГоловазаписОдягобслуговуванняобганятигодина"
            this is UnknownHostException -> "немаєЗаконрозв'язатианалізуватиГоловазаписОдягобслуговуванняземлісайт"
            this is SSLException -> "ГоловазаписОдягобслуговуваннявстановитиПовнийнавітьзловитивтратитипоразка"
            message == "цвільтипуГоловазаписобганятипозавеликийМаленькийобмеженнясистема" -> "ГоловазаписмистецтваштукобганятипозавеликийМаленькийобмеженнясистема"
            this is org.json.JSONException || this is IllegalArgumentException -> "ГоловазаписсіткаРежимнемаєефект"
            this is IOException -> "чистиймережінавітьзловитиабокнигаземліповільнийжитиНапишітьвведітьвтратитипоразка"
            else -> "Навітьновийвтратитипоразка，будь ласкатрохиназадВажкаспробувати"
        }
    }
}
