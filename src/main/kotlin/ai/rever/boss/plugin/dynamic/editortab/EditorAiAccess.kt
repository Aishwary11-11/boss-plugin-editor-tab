package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiAvailability
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Readiness for editor AI features.
 *
 * The gateway owns provider-specific HTTP transport, while [PluginContext.llmProvider]
 * is the source of truth for the provider, secret-backed credential, and default model.
 */
internal fun editorAiReadiness(context: PluginContext): AiReadiness {
    return AiAvailability.check(context)
}

/**
 * Wait briefly for the asynchronously loaded gateway and provider registry.
 *
 * Secret Manager warms its credential snapshot after register() returns, and plugin
 * registration order is asynchronous. A one-shot readiness check during the first
 * keystroke therefore observes a false "no provider" and drops the request forever.
 * The gateway's active model is authoritative because it also represents a selected
 * local CLI engine, for which [PluginContext.llmProvider] is correctly null.
 */
internal suspend fun awaitEditorAiGateway(
    context: PluginContext,
    timeoutMs: Long = 5_000,
    pollMs: Long = 100,
): AiGatewayAPI? = EditorAiGatewayCache.await(context, timeoutMs, pollMs)

/**
 * Process-wide gateway wait-state cache.
 *
 * A tab-completion request is a hot path, so it must not repeat 50 reflective plugin lookups on
 * every typing pause. After the initial registration window has elapsed, later calls do one
 * lookup and one active-model check without polling. The API instance itself is never retained:
 * enabling or reloading ai-gateway takes effect on the next request and its old classloader is not
 * pinned here.
 */
internal object EditorAiGatewayCache {
    @Volatile
    private var initialWaitComplete = false
    private val resolveMutex = Mutex()
    private val generation = AtomicLong()

    fun reset() {
        generation.incrementAndGet()
        initialWaitComplete = false
    }

    suspend fun await(context: PluginContext, timeoutMs: Long, pollMs: Long): AiGatewayAPI? {
        resolveReadyEditorAiGateway(context)?.let {
            initialWaitComplete = true
            return it
        }
        if (initialWaitComplete) return null

        return resolveMutex.withLock {
            resolveReadyEditorAiGateway(context)?.let {
                initialWaitComplete = true
                return@withLock it
            }
            if (initialWaitComplete) return@withLock null
            resolve(context, timeoutMs, pollMs)
        }
    }

    private suspend fun resolve(context: PluginContext, timeoutMs: Long, pollMs: Long): AiGatewayAPI? {
        val resolvingGeneration = generation.get()
        val ready = withTimeoutOrNull(timeoutMs) {
            while (true) {
                resolveReadyEditorAiGateway(context)?.let { return@withTimeoutOrNull it }
                delay(pollMs)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

        if (generation.get() != resolvingGeneration) return null
        initialWaitComplete = true
        return ready
    }
}

/** Resolve the current gateway instance once, including local CLI-engine selection. */
internal fun resolveReadyEditorAiGateway(context: PluginContext): AiGatewayAPI? {
    val gateway = runCatching { context.getPluginAPI(AiGatewayAPI::class.java) }.getOrNull()
        ?: return null
    return gateway.takeIf { runCatching { it.activeModel() }.getOrNull() != null }
}
