package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Блокування оновлення об’єднує лише мережеві запити для одного облікового запису; вихід із системи не чекає мережі та робить зворотний запис старих запитів недійсним.*/
internal class ProviderCredentialSessions(
    private val storage: ProviderCredentialStorage,
    private val oauth: ProviderOAuthClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Session {
        val stateLock = ReentrantLock()
        val refreshLock = ReentrantLock()
        var generation = 0L
        var loginGeneration = 0L
    }
    private val sessions = ConcurrentHashMap<String, Session>()

    fun account(providerId: String, authMode: String): ProviderAccountSummary? {
        val session = session(providerId)
        return session.stateLock.withLock { storage.read(providerId)?.takeIf { it.authMode == authMode }?.summary() }
    }

    fun binding(providerId: String, authMode: String): ProviderCredentialBinding {
        val session = session(providerId)
        return session.stateLock.withLock {
            requireCredential(storage.read(providerId), authMode)
            ProviderCredentialBinding(providerId, authMode, session.generation)
        }
    }

    fun isCurrent(binding: ProviderCredentialBinding): Boolean {
        val session = session(binding.providerId)
        return session.stateLock.withLock {
            val credential = storage.read(binding.providerId)
            session.generation == binding.generation && credential?.authMode == binding.authMode &&
                credential.subject != null && credential.accessToken.isNotBlank() && credential.planEnabled
        }
    }

    fun login(providerId: String, authMode: String, controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit): ProviderAccountSummary {
        val provider = OAuthProvider.fromAuthMode(authMode)
        val session = session(providerId)
        val loginGeneration = session.stateLock.withLock { ++session.loginGeneration }
        val credential = oauth.login(provider, controller, onPrompt)
        controller.throwIfCancelled()
        val saved = session.stateLock.withLock {
            controller.throwIfCancelled()
            // Якщо вхід у веб-переглядач не був успішним, оригінальний сеанс усе одно можна оновити; скасування входу не втрачає змінені облікові дані.
            requireGeneration(session.loginGeneration, loginGeneration)
            storage.write(providerId, credential)
            ++session.generation
            credential
        }
        return saved.summary()
    }

    fun accessToken(providerId: String, authMode: String, controller: AgentRunController,
        rejectedAccessToken: String? = null): String {
        OAuthProvider.fromAuthMode(authMode)
        val session = session(providerId)
        while (!session.refreshLock.tryLock(100, TimeUnit.MILLISECONDS)) controller.throwIfCancelled()
        try {
            controller.throwIfCancelled()
            val (generation, credential) = session.stateLock.withLock {
                session.generation to requireCredential(storage.read(providerId), authMode)
            }
            val rejected = rejectedAccessToken != null && rejectedAccessToken == credential.accessToken
            val expiresSoon = credential.expiresAt <= now() + REFRESH_MARGIN_MS
            if (!rejected && !expiresSoon && credential.expiresAt > now()) return credential.accessToken
            if (credential.refreshToken.isBlank()) {
                invalidate(providerId, session, generation)
                throw ProviderAuthException("oauth_session_expired", "дошказаписвжевтратитиефект，будь ласкаВажкановийдошказапис")
            }
            val refreshed = try {
                oauth.refresh(credential, controller)
            } catch (error: ProviderAuthException) {
                if (error.code == "oauth_session_expired") invalidate(providerId, session, generation)
                throw error
            }
            // Ротацію завершено на стороні сервера. Навіть якщо абонент щойно скасував, дійсні результати спочатку зберігаються, щоб уникнути втрати нового маркера оновлення.
            session.stateLock.withLock {
                requireGeneration(session.generation, generation)
                storage.write(providerId, refreshed)
            }
            controller.throwIfCancelled()
            return requireCredential(refreshed, authMode).accessToken
        } finally {
            session.refreshLock.unlock()
        }
    }

    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? {
        val session = session(providerId)
        val credential = session.stateLock.withLock {
            ++session.generation
            ++session.loginGeneration
            val current = try { storage.read(providerId) } catch (_: ProviderAuthException) {
                storage.delete(providerId)
                return false
            }
            if (current != null && current.authMode == authMode) storage.write(providerId, current.withoutTokens())
            current?.takeIf { it.authMode == authMode }
        } ?: return true
        return try { oauth.revoke(credential, controller) } catch (_: ProviderAuthException) { false }
    }

    fun forget(providerId: String) {
        val session = session(providerId)
        session.stateLock.withLock {
            ++session.generation
            ++session.loginGeneration
            storage.delete(providerId)
        }
    }

    private fun invalidate(providerId: String, session: Session, generation: Long) {
        session.stateLock.withLock {
            if (session.generation != generation) return
            storage.read(providerId)?.let { storage.write(providerId, it.withoutTokens()) }
            ++session.generation
        }
    }

    private fun requireCredential(credential: ProviderCredential?, authMode: String): ProviderCredential {
        if (credential == null || credential.subject == null || credential.authMode != authMode || credential.accessToken.isBlank()) {
            throw ProviderAuthException("oauth_login_required", "будь ласкапершедошказаписцеОдягобслуговуваннябізнесрахунокНомер")
        }
        if (!credential.planEnabled) throw ProviderAuthException("oauth_plan_permission_required", "рахунокНомерзавідповідно донемаєздатнийвикористовуватинаколивпередпорядокчитатинавітьзловити，будь ласкаВажкановийдошказапис")
        return credential
    }

    private fun session(providerId: String): Session {
        if (providerId.isBlank()) throw ProviderAuthException("oauth_provider_missing", "Одягобслуговуваннябізнеснавітьзловитинемаєжитиіснують")
        return sessions.computeIfAbsent(providerId) { Session() }
    }

    private fun requireGeneration(current: Long, expected: Long) {
        if (current != expected) throw ProviderAuthException("oauth_session_changed", "дошказаписформустанвжеЗмінаНавіть，будь ласкаВажкаспробувати")
    }

    private companion object { const val REFRESH_MARGIN_MS = 60_000L }
}
