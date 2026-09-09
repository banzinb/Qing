package com.zhousl.aether.ui.resultcard

import org.json.JSONArray
import org.json.JSONObject

/** 青的聊天结果卡片（chart / stat / list / status 四类）。 */
sealed interface QingCard {
    val title: String

    data class Status(
        override val title: String,
        val level: String,
        val message: String,
    ) : QingCard

    data class StatItem(val label: String, val value: String)

    data class Stat(
        override val title: String,
        val items: List<StatItem>,
    ) : QingCard

    data class ListRow(val title: String, val subtitle: String, val value: String)

    data class ListCard(
        override val title: String,
        val rows: List<ListRow>,
    ) : QingCard

    data class Chart(
        override val title: String,
        val optionJson: String,
    ) : QingCard
}

/** 从 assistant 回复文本里提取结果卡片，返回 (卡片, 去掉卡片后的剩余文本)。 */
object QingCardParser {

    private val validTypes = setOf("chart", "stat", "list", "status")

    fun parse(text: String): Pair<QingCard?, String> {
        if (text.isBlank()) return null to text
        val fence = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)\\n?```", RegexOption.IGNORE_CASE)
        for (match in fence.findAll(text)) {
            val card = parseCandidate(match.groupValues[1].trim()) ?: continue
            return card to text.replaceFirst(match.value, "").trim()
        }
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val card = parseCandidate(trimmed)
            if (card != null) return card to ""
        }
        return null to text
    }

    private fun parseCandidate(raw: String): QingCard? {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val type = json.optString("type").lowercase()
        if (type !in validTypes) return null
        val title = json.optString("title").ifBlank { "结果" }
        return when (type) {
            "status" -> QingCard.Status(
                title = title,
                level = json.optString("status").ifBlank { "info" },
                message = json.optString("message").ifBlank { "" },
            )
            "stat" -> {
                val items = mutableListOf<QingCard.StatItem>()
                val array = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    items.add(QingCard.StatItem(item.optString("label"), item.optString("value")))
                }
                if (items.isEmpty()) return null
                QingCard.Stat(title, items)
            }
            "list" -> {
                val rows = mutableListOf<QingCard.ListRow>()
                val array = json.optJSONArray("rows") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    rows.add(QingCard.ListRow(row.optString("title"), row.optString("subtitle"), row.optString("value")))
                }
                if (rows.isEmpty()) return null
                QingCard.ListCard(title, rows)
            }
            "chart" -> {
                val chart = json.optJSONObject("chart") ?: return null
                val option = buildChartOption(chart) ?: return null
                QingCard.Chart(title, option.toString())
            }
            else -> null
        }
    }

    /** 工具调用兜底：memory_write 成功但模型没吐 JSON 时，生成状态卡。 */
    fun fallbackStatusCard(
        toolName: String,
        argumentsJson: String,
        outputJson: String,
    ): QingCard.Status? {
        if (toolName != "memory_write") return null
        val args = try {
            JSONObject(argumentsJson)
        } catch (_: Exception) {
            return null
        }
        val out = try {
            JSONObject(outputJson)
        } catch (_: Exception) {
            return null
        }
        if (!out.optBoolean("ok", false)) return null
        val domain = args.optString("domain").trim().lowercase()
        val action = args.optString("action").trim().lowercase().ifBlank { "add" }
        return when (domain) {
            "bill" -> {
                if (action == "add") {
                    val amount = args.optString("amount")
                    val category = args.optString("category")
                    val note = args.optString("note")
                    val message = buildString {
                        append("已记 ")
                        append(amount.ifBlank { "?" })
                        append(" 元")
                        if (category.isNotBlank()) append("（").append(category).append("）")
                        if (note.isNotBlank()) append("·").append(note)
                    }
                    QingCard.Status("记账", "success", message)
                } else {
                    null
                }
            }
            "todo" -> when (action) {
                "add" -> QingCard.Status("待办", "success", "已添加「${args.optString("title")}」")
                "done" -> QingCard.Status("待办", "success", "已完成「${args.optString("title")}」")
                "delete" -> QingCard.Status("待办", "info", "已删除待办")
                else -> null
            }
            "clip" -> {
                if (action == "add") {
                    QingCard.Status("剪藏", "success", "已剪藏「${args.optString("title")}」")
                } else {
                    null
                }
            }
            else -> null
        }
    }
    /** 把友好结构（kind + x + series）转成 ECharts option。 */
    private fun buildChartOption(chart: JSONObject): JSONObject? {
        val kind = chart.optString("kind", "bar").lowercase()
        val option = JSONObject()
        option.put("tooltip", JSONObject().put("trigger", if (kind == "pie") "item" else "axis"))
        option.put("color", JSONArray().put("#4c9e6f").put("#6f9ec9").put("#e0a458").put("#c96f6f").put("#8f7ac9").put("#57b8a0"))
        when (kind) {
            "pie" -> {
                val series = JSONArray()
                val data = JSONArray()
                val items = chart.optJSONArray("series") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val point = JSONObject()
                        .put("name", item.optString("name").ifBlank { "未分类" })
                        .put("value", item.optDouble("value", 0.0))
                    data.put(point)
                }
                if (data.length() == 0) return null
                series.put(
                    JSONObject()
                        .put("type", "pie")
                        .put("radius", "65%")
                        .put("center", JSONArray().put("50%").put("50%"))
                        .put("label", JSONObject().put("formatter", "{b}\n{d}%"))
                        .put("data", data),
                )
                option.put("series", series)
            }
            "bar", "line" -> {
                val x = JSONArray()
                val categories = chart.optJSONArray("x") ?: JSONArray()
                for (i in 0 until categories.length()) x.put(categories.opt(i))
                if (x.length() == 0) return null
                val series = JSONArray()
                val rawSeries = chart.optJSONArray("series") ?: JSONArray()
                if (rawSeries.length() == 0) return null
                for (i in 0 until rawSeries.length()) {
                    val s = rawSeries.optJSONObject(i) ?: continue
                    val data = JSONArray()
                    val values = s.optJSONArray("data") ?: JSONArray()
                    for (j in 0 until values.length()) data.put(values.opt(j))
                    series.put(
                        JSONObject()
                            .put("type", kind)
                            .put("smooth", kind == "line")
                            .put("name", s.optString("name").ifBlank { "数据" })
                            .put("barMaxWidth", 28)
                            .put("data", data),
                    )
                }
                option.put("xAxis", JSONObject().put("type", "category").put("data", x))
                option.put("yAxis", JSONObject().put("type", "value"))
                option.put("grid", JSONObject().put("left", 8).put("right", 12).put("top", 24).put("bottom", 0).put("containLabel", true))
                option.put("series", series)
            }
            else -> return null
        }
        return option
    }
}