package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.LlmProvider
import ai.rever.boss.plugin.api.PluginContext
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class EditorAiAccessTest {
    @BeforeTest
    fun resetCache() {
        EditorAiGatewayCache.reset()
    }

    @Test
    fun `waits for the active model instead of dropping the first request`() {
        runBlocking {
            var reads = 0
            val gateway = gateway { if (++reads < 3) null else configuredModel() }

            val resolved = awaitEditorAiGateway(context(gateway, null), timeoutMs = 1_000, pollMs = 1)

            assertSame(gateway, resolved)
            assertNotNull(gateway.activeModel())
        }
    }

    @Test
    fun `times out when no shared provider becomes available`() {
        runBlocking {
            val resolved = awaitEditorAiGateway(context(gateway { null }, null), timeoutMs = 20, pollMs = 1)
            assertNull(resolved)
        }
    }

    @Test
    fun `after the initial wait a missing gateway costs one lookup per request`() {
        runBlocking {
            var lookups = 0
            val missing = context(provider = null) { lookups++; null }

            assertNull(awaitEditorAiGateway(missing, timeoutMs = 10, pollMs = 1))
            val afterFirstAttempt = lookups
            assertNull(awaitEditorAiGateway(missing, timeoutMs = 10, pollMs = 1))

            assertEquals(afterFirstAttempt + 1, lookups)
        }
    }

    @Test
    fun `a model selected later is found without another poll loop`() {
        runBlocking {
            var lookups = 0
            var model: AiModelInfo? = null
            val gateway = gateway { model }
            val context = context(provider = null) { lookups++; gateway }

            assertNull(awaitEditorAiGateway(context, timeoutMs = 10, pollMs = 1))
            val afterWarmup = lookups
            model = configuredModel()

            assertSame(gateway, awaitEditorAiGateway(context, timeoutMs = 10, pollMs = 1))
            assertEquals(afterWarmup + 1, lookups)
        }
    }

    @Test
    fun `gateway reload resolves the replacement instance`() {
        runBlocking {
            val first = gateway { configuredModel() }
            val replacement = gateway { configuredModel() }
            var current = first
            val context = context(provider = null) { current }

            assertSame(first, awaitEditorAiGateway(context, timeoutMs = 10, pollMs = 1))
            current = replacement

            assertSame(replacement, awaitEditorAiGateway(context, timeoutMs = 10, pollMs = 1))
        }
    }

    private fun configuredModel() =
        AiModelInfo(
            providerId = "OLLAMA",
            providerName = "Ollama",
            modelId = "gemma4:latest",
        )

    private fun gateway(activeModel: () -> AiModelInfo?): AiGatewayAPI =
        Proxy.newProxyInstance(
            AiGatewayAPI::class.java.classLoader,
            arrayOf(AiGatewayAPI::class.java),
        ) { _, method, _ ->
            if (method.name == "activeModel") activeModel() else defaultValue(method.returnType)
        } as AiGatewayAPI

    private fun context(gateway: AiGatewayAPI, provider: LlmProvider?): PluginContext =
        context(provider) { gateway }

    private fun context(provider: LlmProvider?, gateway: () -> AiGatewayAPI?): PluginContext =
        Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPluginAPI" -> gateway()
                "getLlmProvider" -> provider
                else -> defaultValue(method.returnType)
            }
        } as PluginContext

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
