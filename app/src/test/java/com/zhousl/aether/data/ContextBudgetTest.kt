package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {
    @Test
    fun `empty text costs nothing`() {
        assertEquals(0, estimateTokens(""))
    }

    @Test
    fun `ascii runs about four characters per token`() {
        assertEquals(1, estimateTokens("abcd"))
        assertEquals(2, estimateTokens("abcde"))
        assertEquals(1, estimateTokens("ab c"))
    }

    @Test
    fun `chinese counts about one token per character`() {
        assertEquals(2, estimateTokens("你好"))
        assertEquals(4, estimateTokens("你好世界"))
    }

    @Test
    fun `a mixed sentence counts each script separately`() {
        // "hello " is six ASCII characters (two tokens), the two hanzi are two.
        assertEquals(4, estimateTokens("hello 你好"))
    }

    @Test
    fun `emoji and other scripts are not free`() {
        assertEquals(1, estimateTokens("\uD83D\uDE00"))
        assertTrue(estimateTokens("Привет") >= 3)
    }

    @Test
    fun `a full window reserves room for the answer and a little for memory`() {
        val plan = contextBudgetPlan(128_000)

        assertEquals(16_384, plan.responseReserveTokens)
        // Eight percent would be 10,240, but injected memory is capped so it
        // never grows into noise on a large window.
        assertEquals(6_000, plan.memoryBudgetTokens)
        assertEquals(3_000, plan.skillMetadataBudgetTokens)
        assertEquals(102_616, plan.historyBudgetTokens)
    }

    @Test
    fun `a huge window does not inflate the injected-memory cap`() {
        val plan = contextBudgetPlan(1_000_000)

        assertEquals(120_000, plan.responseReserveTokens)
        assertEquals(6_000, plan.memoryBudgetTokens)
        assertEquals(3_000, plan.skillMetadataBudgetTokens)
        assertEquals(871_000, plan.historyBudgetTokens)
    }

    @Test
    fun `a small window keeps the reserve below half of it`() {
        val plan = contextBudgetPlan(4_000)

        assertEquals(2_000, plan.responseReserveTokens)
        assertTrue(plan.historyBudgetTokens > 0)
    }

    @Test
    fun `usage percent stays inside its bounds`() {
        assertEquals(0, contextUsagePercent(0, 128_000))
        assertEquals(50, contextUsagePercent(64_000, 128_000))
        assertEquals(100, contextUsagePercent(999_999, 128_000))
        assertEquals(0, contextUsagePercent(500, 0))
    }
}
