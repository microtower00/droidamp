package com.droidamp.player.skin.wsz

/**
 * Pure-Kotlin RFC-8259 recursive-descent JSON parser with zero Android framework or third-party
 * dependencies (safe in unmocked local JVM unit tests where `org.json.JSONObject` is stubbed).
 */
sealed interface JsonElement

data class JsonObject(
  val entries: Map<String, JsonElement>,
) : JsonElement {
  operator fun get(key: String): JsonElement? = entries[key]

  fun containsKey(key: String): Boolean = entries.containsKey(key)
}

data class JsonArray(
  val elements: List<JsonElement>,
) : JsonElement {
  val size: Int get() = elements.size

  operator fun get(index: Int): JsonElement = elements[index]
}

data class JsonString(
  val value: String,
) : JsonElement

data class JsonNumber(
  val raw: String,
  val doubleValue: Double,
) : JsonElement {
  val floatValue: Float get() = doubleValue.toFloat()

  fun toStrictIntOrNull(): Int? {
    if (raw.contains('.') || raw.contains('e') || raw.contains('E')) return null
    return raw.toIntOrNull()
  }
}

data class JsonBoolean(
  val value: Boolean,
) : JsonElement

data object JsonNull : JsonElement

class JsonParseException(
  message: String,
  val offset: Int = -1,
) : IllegalArgumentException(if (offset >= 0) "$message (at offset $offset)" else message)

object MinimalJson {
  private const val MAX_NESTING_DEPTH = 64

  fun parse(input: String): JsonElement {
    val cleaned = input.removePrefix("\uFEFF")
    val reader = Parser(cleaned)
    val root = reader.parseValue(depth = 0)
    reader.skipWhitespace()
    if (!reader.isAtEnd()) {
      throw reader.fail("Unexpected trailing character '${reader.peek()}'")
    }
    return root
  }

  fun parseObject(input: String): JsonObject {
    val element = parse(input)
    if (element !is JsonObject) {
      throw JsonParseException("Expected top-level JSON object, got ${element::class.simpleName}")
    }
    return element
  }

  private class Parser(
    private val src: String,
  ) {
    private var pos: Int = 0

    fun isAtEnd(): Boolean = pos >= src.length

    fun peek(): Char = src[pos]

    fun fail(msg: String, at: Int = pos): JsonParseException = JsonParseException(msg, at)

    fun skipWhitespace() {
      while (pos < src.length) {
        when (src[pos]) {
          ' ', '\t', '\r', '\n' -> pos++
          else -> return
        }
      }
    }

    fun parseValue(depth: Int): JsonElement {
      if (depth > MAX_NESTING_DEPTH) {
        throw fail("JSON nesting depth exceeds maximum ($MAX_NESTING_DEPTH)")
      }
      skipWhitespace()
      if (isAtEnd()) throw fail("Unexpected end of JSON input")
      return when (val ch = src[pos]) {
        '{' -> parseObject(depth + 1)
        '[' -> parseArray(depth + 1)
        '"' -> JsonString(parseString())
        't' -> parseLiteral("true", JsonBoolean(true))
        'f' -> parseLiteral("false", JsonBoolean(false))
        'n' -> parseLiteral("null", JsonNull)
        '-', in '0'..'9' -> parseNumber()
        else -> throw fail("Unexpected character '$ch'")
      }
    }

    private fun parseObject(depth: Int): JsonObject {
      pos++ // consume '{'
      skipWhitespace()
      if (isAtEnd()) throw fail("Unterminated JSON object")
      val map = LinkedHashMap<String, JsonElement>()
      if (src[pos] == '}') {
        pos++
        return JsonObject(map)
      }
      while (true) {
        skipWhitespace()
        if (isAtEnd() || src[pos] != '"') {
          throw fail("Expected quoted object property name")
        }
        val key = parseString()
        skipWhitespace()
        if (isAtEnd() || src[pos] != ':') {
          throw fail("Expected ':' after object key \"$key\"")
        }
        pos++ // consume ':'
        val value = parseValue(depth)
        map[key] = value
        skipWhitespace()
        if (isAtEnd()) throw fail("Unterminated JSON object")
        when (src[pos]) {
          ',' -> pos++
          '}' -> {
            pos++
            return JsonObject(map)
          }
          else -> throw fail("Expected ',' or '}' in JSON object")
        }
      }
    }

    private fun parseArray(depth: Int): JsonArray {
      pos++ // consume '['
      skipWhitespace()
      if (isAtEnd()) throw fail("Unterminated JSON array")
      val list = ArrayList<JsonElement>()
      if (src[pos] == ']') {
        pos++
        return JsonArray(list)
      }
      while (true) {
        list.add(parseValue(depth))
        skipWhitespace()
        if (isAtEnd()) throw fail("Unterminated JSON array")
        when (src[pos]) {
          ',' -> pos++
          ']' -> {
            pos++
            return JsonArray(list)
          }
          else -> throw fail("Expected ',' or ']' in JSON array")
        }
      }
    }

    private fun parseString(): String {
      val startOffset = pos
      pos++ // consume opening '"'
      val sb = StringBuilder()
      while (pos < src.length) {
        val ch = src[pos++]
        when {
          ch == '"' -> return sb.toString()
          ch == '\\' -> {
            if (pos >= src.length) throw fail("Unterminated escape sequence", pos)
            when (val esc = src[pos++]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                val high = parseHexQuad()
                if (high.isHighSurrogate() &&
                  pos + 1 < src.length &&
                  src[pos] == '\\' &&
                  src[pos + 1] == 'u'
                ) {
                  val savedPos = pos
                  pos += 2
                  val low = parseHexQuad()
                  if (low.isLowSurrogate()) {
                    sb.append(high)
                    sb.append(low)
                  } else {
                    pos = savedPos
                    sb.append(high)
                  }
                } else {
                  sb.append(high)
                }
              }
              else -> throw fail("Invalid escape sequence '\\$esc'", pos - 1)
            }
          }
          ch.code < 0x20 -> throw fail("Unescaped control character in JSON string", pos - 1)
          else -> sb.append(ch)
        }
      }
      throw fail("Unterminated JSON string", startOffset)
    }

    private fun parseHexQuad(): Char {
      if (pos + 4 > src.length) throw fail("Incomplete \\uXXXX escape")
      var code = 0
      for (i in 0 until 4) {
        val digit = Character.digit(src[pos + i], 16)
        if (digit < 0) throw fail("Invalid hex digit '${src[pos + i]}' in \\uXXXX escape", pos + i)
        code = (code shl 4) or digit
      }
      pos += 4
      return code.toChar()
    }

    private fun parseLiteral(expected: String, result: JsonElement): JsonElement {
      if (!src.regionMatches(pos, expected, 0, expected.length, ignoreCase = false)) {
        throw fail("Invalid literal; expected '$expected'")
      }
      pos += expected.length
      return result
    }

    private fun parseNumber(): JsonNumber {
      val start = pos
      if (src[pos] == '-') {
        pos++
        if (isAtEnd()) throw fail("Incomplete negative number", start)
      }
      when {
        src[pos] == '0' -> {
          pos++
          if (!isAtEnd() && src[pos] in '0'..'9') {
            throw fail("Leading zeros are not permitted in JSON numbers", start)
          }
        }
        src[pos] in '1'..'9' -> {
          while (!isAtEnd() && src[pos] in '0'..'9') pos++
        }
        else -> throw fail("Invalid number", start)
      }
      if (!isAtEnd() && src[pos] == '.') {
        pos++
        if (isAtEnd() || src[pos] !in '0'..'9') {
          throw fail("Expected fractional digits after decimal point", pos)
        }
        while (!isAtEnd() && src[pos] in '0'..'9') pos++
      }
      if (!isAtEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
        pos++
        if (!isAtEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
        if (isAtEnd() || src[pos] !in '0'..'9') {
          throw fail("Expected exponent digits in JSON number", pos)
        }
        while (!isAtEnd() && src[pos] in '0'..'9') pos++
      }
      val raw = src.substring(start, pos)
      val doubleVal = raw.toDoubleOrNull()
      if (doubleVal == null || !doubleVal.isFinite()) {
        throw fail("Numeric value out of finite range: '$raw'", start)
      }
      return JsonNumber(raw = raw, doubleValue = doubleVal)
    }
  }
}
