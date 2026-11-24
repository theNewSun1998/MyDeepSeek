package com.hmdeepseek.app.common

import com.hmdeepseek.app.model.UsageStats
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 计价与估算。
 *
 * 单价来自官网价目表（deepseek-flash，2026-11）：
 *   输入 cache miss：峰 $0.30 / 谷 $0.15（每 1M token）
 *   输入 cache hit ：峰 $0.006 / 谷 $0.003
 *   输出          ：峰 $1.20 / 谷 $0.60
 *   搜索、抓页     ：$0.75 / 千次
 * 峰时 = 周一至周五 01:00-04:00、06:00-10:00 UTC（中国法定节假日除外，这里未扣除节假日）。
 */
object Pricing {
    private const val INPUT_MISS_PEAK = 0.30
    private const val INPUT_MISS_OFF = 0.15
    private const val INPUT_HIT_PEAK = 0.006
    private const val INPUT_HIT_OFF = 0.003
    private const val OUTPUT_PEAK = 1.20
    private const val OUTPUT_OFF = 0.60
    const val SEARCH_PER_CALL = 0.00075
    const val FETCH_PER_CALL = 0.00075

    fun isPeakNow(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in 1..3 || hour in 6..9
    }

    /**
     * 估算累计花费（美元）。
     * 注意口径：inputTokens 只算未命中缓存的输入，命中部分单列在 cacheHitTokens。
     */
    fun estimate(stats: UsageStats, peak: Boolean = isPeakNow()): Double {
        val miss = if (peak) INPUT_MISS_PEAK else INPUT_MISS_OFF
        val hit = if (peak) INPUT_HIT_PEAK else INPUT_HIT_OFF
        val out = if (peak) OUTPUT_PEAK else OUTPUT_OFF
        val tokenCost = stats.inputTokens / 1_000_000.0 * miss +
            stats.cacheHitTokens / 1_000_000.0 * hit +
            stats.outputTokens / 1_000_000.0 * out
        val toolCost = stats.searchCount * SEARCH_PER_CALL + stats.fetchCount * FETCH_PER_CALL
        return tokenCost + toolCost
    }

    /** 输入总量（未命中 + 命中） */
    fun totalInputTokens(stats: UsageStats): Long = stats.inputTokens + stats.cacheHitTokens

    fun cacheHitRatio(stats: UsageStats): Double {
        val total = totalInputTokens(stats)
        return if (total <= 0) 0.0 else stats.cacheHitTokens.toDouble() / total
    }

    fun cacheHitRatio(info: com.hmdeepseek.app.model.UsageInfo): Double {
        val total = (info.inputTokens + info.cacheHitTokens).toLong()
        return if (total <= 0) 0.0 else info.cacheHitTokens.toDouble() / total
    }

    fun percent(ratio: Double): String = String.format(Locale.US, "%.0f%%", ratio * 100)

    fun formatUsd(value: Double): String {
        if (value <= 0.0) return "$0"
        if (value < 0.01) return String.format(Locale.US, "$%.4f", value)
        if (value < 1) return String.format(Locale.US, "$%.3f", value)
        return String.format(Locale.US, "$%.2f", value)
    }

    fun formatTokens(value: Long): String {
        return when {
            value < 1000 -> value.toString()
            value < 1_000_000 -> String.format(Locale.US, "%.1fK", value / 1000.0)
            else -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}

/** 粗糙的 token 估算：中文按 1 字 ≈ 1 token，其它按 4 字符 ≈ 1 token，再按 1 条消息加 8 token 开销 */
object Tokens {
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF) {
                cjk++
            } else {
                other++
            }
        }
        return cjk + (other + 3) / 4
    }

    fun estimateMessages(texts: List<String>): Int {
        var total = 0
        for (t in texts) total += estimate(t) + 8
        return total
    }
}
