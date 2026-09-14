package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiChunk
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.AiReply
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.PluginContext
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AiTabCompletionStreamTest {
    @Test
    fun `terminal failure clears streamed ghost text so it cannot be accepted`() = runBlocking {
        EditorAiGatewayCache.reset()
        val finish = CompletableDeferred<Unit>()
        val chunks = flow {
            emit(AiChunk.Text("unfinished("))
            finish.await()
            emit(AiChunk.Failed(IllegalStateException("provider stopped")))
        }
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = ai.rever.bosseditor.core.EditorState("", "/tmp/sample.kt")
        val service = AiTabCompletionService(context(gateway(chunks)), serviceScope)

        try {
            service.schedule(
                state,
                "/tmp/sample.kt",
                "kotlin",
                AiCompletionSettingsData(debounceMs = 0, timeoutMs = 1_000),
            )
            awaitCondition { service.suggestion.value != null }
            finish.complete(Unit)
            awaitCondition { service.suggestion.value == null }

            assertFalse(service.accept(state))
        } finally {
            serviceScope.cancel()
            EditorAiGatewayCache.reset()
        }
    }

    @Test
    fun `failed stream exposes partial text only until the terminal failure`() = runBlocking {
        val error = IllegalStateException("provider stopped")
        val published = mutableListOf<String>()

        val outcome = collectCompletionStream(
            gateway(flowOf(AiChunk.Text("if (ready) "), AiChunk.Failed(error))),
            AiRequest(),
            timeoutMs = 1_000,
            publish = published::add,
        )

        assertEquals(listOf("if (ready) "), published)
        assertEquals(error, assertIs<CompletionStreamOutcome.Failed>(outcome).error)
    }

    @Test
    fun `timed out stream is not a completed suggestion`() = runBlocking {
        val published = mutableListOf<String>()
        val chunks = flow {
            emit(AiChunk.Text("unfinished("))
            awaitCancellation()
        }

        val outcome = collectCompletionStream(gateway(chunks), AiRequest(), 20, published::add)

        assertEquals(listOf("unfinished("), published)
        assertEquals(CompletionStreamOutcome.TimedOut, outcome)
    }

    @Test
    fun `completed stream publishes the authoritative final reply`() = runBlocking {
        val published = mutableListOf<String>()

        val outcome = collectCompletionStream(
            gateway(flowOf(AiChunk.Text("par"), AiChunk.Completed(AiReply("complete")))),
            AiRequest(),
            timeoutMs = 1_000,
            publish = published::add,
        )

        assertEquals(listOf("par", "complete"), published)
        assertEquals(CompletionStreamOutcome.Completed, outcome)
    }

    private fun gateway(chunks: Flow<AiChunk>): AiGatewayAPI =
        Proxy.newProxyInstance(
            AiGatewayAPI::class.java.classLoader,
            arrayOf(AiGatewayAPI::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "stream" -> chunks
                "activeModel" -> AiModelInfo("TEST", "Test", "test-model")
                else -> defaultValue(method.returnType)
            }
        } as AiGatewayAPI

    private fun context(gateway: AiGatewayAPI): PluginContext =
        Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPluginAPI" -> gateway
                else -> defaultValue(method.returnType)
            }
        } as PluginContext

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        repeat(1_000) {
            if (predicate()) return
            delay(1)
        }
        error("condition was not reached")
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
}
