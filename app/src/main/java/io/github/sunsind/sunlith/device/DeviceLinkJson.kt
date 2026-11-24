package io.github.sunsind.sunlith.device

import com.fasterxml.jackson.core.JsonGenerator
import io.github.sunsind.sunlith.system.parseStrictJson
import io.github.sunsind.sunlith.system.strictJsonFactory
import java.io.ByteArrayOutputStream

internal data class ToolCall(val tool: String, val arguments: Map<String, Any?>)

internal object DeviceLinkJson {
    const val MAX_DEPTH = 12
    const val MAX_STRING = 300_000

    private val factory = strictJsonFactory(MAX_DEPTH, MAX_STRING, 128, DeviceLinkHttp.MAX_BODY.toLong())

    fun parseCall(body: ByteArray): ToolCall {
        val root = parse(body) as? Map<*, *> ?: throw invalid()
        if (root.keys != setOf("tool", "arguments")) throw DeviceLinkError.badRequest("The request must contain only tool and arguments.")
        val tool = root["tool"] as? String ?: throw invalid()
        if (tool.length > 64) throw DeviceLinkError.badRequest("Unknown tool.")
        val arguments = root["arguments"] as? Map<*, *> ?: throw DeviceLinkError.badRequest("Tool arguments must be a JSON object.")
        @Suppress("UNCHECKED_CAST")
        return ToolCall(tool, arguments as Map<String, Any?>)
    }

    fun parse(bytes: ByteArray): Any? = factory.parseStrictJson(bytes, 10_000, ::invalid)

    fun write(value: Any?): ByteArray {
        val output = ByteArrayOutputStream()
        factory.createGenerator(output).use { writeValue(it, value) }
        return output.toByteArray()
    }

    private fun writeValue(generator: JsonGenerator, value: Any?) {
        when (value) {
            null -> generator.writeNull()
            is String -> generator.writeString(value)
            is Boolean -> generator.writeBoolean(value)
            is Int -> generator.writeNumber(value)
            is Long -> generator.writeNumber(value)
            is Map<*, *> -> {
                generator.writeStartObject()
                value.forEach { (key, item) -> generator.writeFieldName(key as String); writeValue(generator, item) }
                generator.writeEndObject()
            }
            is List<*> -> {
                generator.writeStartArray()
                value.forEach { writeValue(generator, it) }
                generator.writeEndArray()
            }
            else -> error("Unsupported JSON value.")
        }
    }

    private fun invalid() = DeviceLinkError.badRequest("The request body must be one strict UTF-8 JSON object.")
}
