package com.zhousl.aether.data

/**
 * A local, dependency-free token estimate.
 *
 * It never calls a model, so it can run on every keystroke or before every
 * injection. The numbers are deliberately rough and lean high, because a
 * budget that under-counts is the one that overflows:
 *
 * - CJK (Chinese, Japanese, Korean) scripts: about one token per character
 * - ASCII (letters, digits, punctuation, whitespace): about four per token
 * - everything else (emoji, other scripts): about two per token
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var ascii = 0
    var other = 0
    text.forEach { char ->
        when {
            char.isCjk() -> cjk += 1
            char.code < 0x80 -> ascii += 1
            else -> other += 1
        }
    }
    return cjk + (ascii + 3) / 4 + (other + 1) / 2
}

private fun Char.isCjk(): Boolean {
    val code = code
    return code in 0x3000..0x303F || // CJK punctuation
        code in 0x3040..0x30FF || // Hiragana and Katakana
        code in 0x3400..0x4DBF || // CJK extension A
        code in 0x4E00..0x9FFF || // CJK unified ideographs
        code in 0xAC00..0xD7AF || // Hangul syllables
        code in 0xF900..0xFAFF || // CJK compatibility ideographs
        code in 0xFF00..0xFFEF // Halfwidth and fullwidth forms
}

/**
 * How the context window is shared out. Every field is an upper bound: the
 * point is to stop one layer from eating the whole window, not to fill them.
 */
data class ContextBudgetPlan(
    val contextWindowTokens: Int,
    /** Room kept for the answer itself, so a long prompt cannot starve it. */
    val responseReserveTokens: Int,
    val memoryBudgetTokens: Int,
    val skillMetadataBudgetTokens: Int,
    /** Whatever is left for the conversation and tool results. */
    val historyBudgetTokens: Int,
)

private const val MinResponseReserveTokens = 16_384
private const val ResponseReservePercent = 12
private const val MemoryBudgetPercent = 8
private const val SkillMetadataBudgetPercent = 4

/**
 * A cap on injected memory beyond a few thousand tokens stops being helpful and
 * starts being noise, so the percentage never grows past it on huge windows.
 */
private const val MaxMemoryBudgetTokens = 6_000
private const val MaxSkillMetadataBudgetTokens = 3_000

fun contextBudgetPlan(contextWindowTokens: Int): ContextBudgetPlan {
    val window = contextWindowTokens.coerceAtLeast(1_000)
    val reserve = minOf(
        window / 2,
        maxOf(MinResponseReserveTokens, window * ResponseReservePercent / 100),
    )
    val memory = minOf(MaxMemoryBudgetTokens, window * MemoryBudgetPercent / 100)
    val skills = minOf(MaxSkillMetadataBudgetTokens, window * SkillMetadataBudgetPercent / 100)
    return ContextBudgetPlan(
        contextWindowTokens = window,
        responseReserveTokens = reserve,
        memoryBudgetTokens = memory,
        skillMetadataBudgetTokens = skills,
        historyBudgetTokens = (window - reserve - memory - skills).coerceAtLeast(0),
    )
}

/** How full the window is, for a progress line in the UI. */
fun contextUsagePercent(estimatedTokens: Int, contextWindowTokens: Int): Int {
    if (contextWindowTokens <= 0) return 0
    return ((estimatedTokens.toLong() * 100L) / contextWindowTokens).toInt().coerceIn(0, 100)
}

/**
 * Only for the moment before the kernel has told us the real window. Kept in
 * one place so the fallback is easy to find and change.
 */
const val FallbackContextWindowTokens: Int = 128_000
