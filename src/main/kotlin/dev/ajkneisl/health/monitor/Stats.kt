package dev.ajkneisl.health.monitor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun extractStats(body: JsonElement?, path: String): List<Stat> {
    val node = resolvePath(body ?: return emptyList(), path) as? JsonObject ?: return emptyList()
    val stats = mutableListOf<Stat>()
    flatten(node, emptyList(), stats)
    return stats
}

private fun resolvePath(root: JsonElement, path: String): JsonElement? {
    if (path.isBlank()) return root
    var current: JsonElement? = root
    for (segment in path.split('.')) {
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
}

private fun flatten(element: JsonElement, prefix: List<String>, out: MutableList<Stat>) {
    if (prefix.size > 4 || out.size > 60) return

    when (element) {
        is JsonObject -> element.forEach { (key, value) -> flatten(value, prefix + key, out) }

        is JsonArray -> {
            val primitives = element.all { it is JsonPrimitive }
            if (primitives) {
                out +=
                    Stat(
                        label(prefix),
                        element.joinToString(", ") { render(it as JsonPrimitive) },
                    )
            } else {
                element.forEachIndexed { index, item ->
                    flatten(
                        item,
                        prefix.dropLast(1) + "${prefix.lastOrNull() ?: "item"}[$index]",
                        out,
                    )
                }
            }
        }

        is JsonPrimitive -> {
            if (element is JsonNull) {
                out += Stat(label(prefix), "—")
            } else {
                val number = element.content.toDoubleOrNull().takeIf { !element.isString }
                out += Stat(label(prefix), render(element), number)
            }
        }
    }
}

private fun render(primitive: JsonPrimitive): String {
    if (primitive is JsonNull) return "—"
    if (primitive.isString) return primitive.content
    val number = primitive.content.toDoubleOrNull() ?: return primitive.content
    return formatNumber(number)
}

fun formatNumber(value: Double): String =
    when {
        value.isNaN() || value.isInfinite() -> value.toString()
        value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15 ->
            "%,d".format(value.toLong())

        kotlin.math.abs(value) < 0.01 -> "%.4f".format(value)
        else -> "%,.2f".format(value)
    }

/** `queueDepth` -> `Queue depth`, `memory.used_mb` -> `Memory › Used mb`. */
private fun label(path: List<String>): String = path.joinToString(" › ") { humanize(it) }

private fun humanize(key: String): String {
    val words =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    if (words.isEmpty()) return key

    // Sentence case, but acronyms such as CPU or IO keep their shape.
    return words
        .mapIndexed { index, word ->
            when {
                word.length > 1 && word.all { it.isUpperCase() } -> word
                index == 0 -> word.replaceFirstChar { it.uppercase() }
                else -> word.replaceFirstChar { it.lowercase() }
            }
        }
        .joinToString(" ")
}
