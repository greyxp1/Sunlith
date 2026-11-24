package io.github.sunsind.sunlith.system

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal fun strictUtf8(bytes: ByteArray): String? =
    try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() } catch (_: CharacterCodingException) { null }

internal fun strictJsonFactory(depth: Int, maxString: Int, maxName: Int, maxDocument: Long): JsonFactory = JsonFactoryBuilder()
    .streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(depth).maxStringLength(maxString).maxNumberLength(32).maxNameLength(maxName)
        .maxDocumentLength(maxDocument).build())
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .build()

internal fun JsonFactory.parseStrictJson(bytes: ByteArray, maxArray: Int, invalid: () -> Exception): Any? {
    val text = strictUtf8(bytes) ?: throw invalid()
    try {
        createParser(text).use { parser ->
            val value = parser.readJsonValue(parser.nextToken(), maxArray, invalid)
            if (parser.nextToken() != null) throw invalid()
            return value
        }
    } catch (_: IOException) { throw invalid() }
}

/** Parser constraints enforce nesting and duplicate keys before values reach this reader. */
private fun JsonParser.readJsonValue(token: JsonToken?, maxArray: Int, invalid: () -> Exception): Any? = when (token) {
    JsonToken.START_OBJECT -> LinkedHashMap<String, Any?>().apply {
        while (nextToken() == JsonToken.FIELD_NAME) put(currentName(), readJsonValue(nextToken(), maxArray, invalid))
    }
    JsonToken.START_ARRAY -> ArrayList<Any?>().apply {
        while (true) {
            val next = nextToken()
            if (next == JsonToken.END_ARRAY) break
            if (size >= maxArray) throw invalid()
            add(readJsonValue(next, maxArray, invalid))
        }
    }
    JsonToken.VALUE_STRING -> text
    JsonToken.VALUE_NUMBER_INT -> if (numberType == JsonParser.NumberType.BIG_INTEGER) throw invalid() else longValue
    JsonToken.VALUE_NUMBER_FLOAT -> doubleValue
    JsonToken.VALUE_TRUE -> true
    JsonToken.VALUE_FALSE -> false
    JsonToken.VALUE_NULL -> null
    else -> throw invalid()
}
