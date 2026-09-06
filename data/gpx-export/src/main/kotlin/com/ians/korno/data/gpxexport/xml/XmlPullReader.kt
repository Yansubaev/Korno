package com.ians.korno.data.gpxexport.xml

internal class XmlParseException(message: String) : Exception(message)

internal sealed interface XmlEvent {
    /** Имя — локальное: префикс пространства имён отброшен, см. [localName]. */
    data class StartElement(
        val name: String,
        val attributes: Map<String, String>,
    ) : XmlEvent

    data class EndElement(val name: String) : XmlEvent

    data class Text(val value: String) : XmlEvent

    data object EndDocument : XmlEvent
}

/**
 * Потоковый XML-ридер: выдаёт события по одному, не строя дерево документа.
 *
 * Треки — это десятки тысяч точек, и DOM целого файла стоил бы в разы больше памяти,
 * чем результат разбора. Полноценный XML здесь и не нужен: GPX и TCX не пользуются
 * ни внутренними сущностями, ни обработкой пространств имён сверх префиксов, которые
 * ридер просто отбрасывает — совпадение по локальному имени делает разбор одинаково
 * терпимым к файлам Garmin, Strava и нашим собственным.
 */
internal class XmlPullReader(private val source: String) {

    private var index = 0
    private var pendingEndElement: String? = null

    fun next(): XmlEvent {
        pendingEndElement?.let { name ->
            pendingEndElement = null
            return XmlEvent.EndElement(name)
        }

        while (true) {
            if (index >= source.length) return XmlEvent.EndDocument

            if (source[index] != '<') {
                val text = readText()
                if (text.isBlank()) continue
                return XmlEvent.Text(text)
            }

            when {
                source.startsWith("<?", index) -> skipUntil("?>")
                source.startsWith("<!--", index) -> skipUntil("-->")
                source.startsWith("<![CDATA[", index) -> return XmlEvent.Text(readCData())
                source.startsWith("<!", index) -> skipDoctype()
                source.startsWith("</", index) -> return readEndElement()
                else -> return readStartElement()
            }
        }
    }

    private fun readText(): String {
        val end = source.indexOf('<', index).takeIf { it >= 0 } ?: source.length
        val raw = source.substring(index, end)
        index = end
        return decodeEntities(raw)
    }

    private fun readCData(): String {
        val start = index + "<![CDATA[".length
        val end = source.indexOf("]]>", start)
        if (end < 0) throw XmlParseException("Unterminated CDATA section")
        index = end + "]]>".length
        return source.substring(start, end)
    }

    private fun skipUntil(terminator: String) {
        val end = source.indexOf(terminator, index)
        if (end < 0) throw XmlParseException("Missing '$terminator'")
        index = end + terminator.length
    }

    /** DOCTYPE может нести внутреннее подмножество в квадратных скобках — его тоже пропускаем. */
    private fun skipDoctype() {
        var cursor = index + "<!".length
        var bracketDepth = 0
        while (cursor < source.length) {
            when (source[cursor]) {
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                '>' -> if (bracketDepth <= 0) {
                    index = cursor + 1
                    return
                }
            }
            cursor++
        }
        throw XmlParseException("Unterminated declaration")
    }

    private fun readEndElement(): XmlEvent.EndElement {
        val end = source.indexOf('>', index)
        if (end < 0) throw XmlParseException("Unterminated closing tag")
        val name = source.substring(index + "</".length, end).trim()
        if (name.isEmpty()) throw XmlParseException("Empty closing tag")
        index = end + 1
        return XmlEvent.EndElement(localName(name))
    }

    private fun readStartElement(): XmlEvent.StartElement {
        index++
        val name = readName()
        if (name.isEmpty()) throw XmlParseException("Empty element name at $index")

        val attributes = mutableMapOf<String, String>()
        while (true) {
            skipWhitespace()
            if (index >= source.length) throw XmlParseException("Unterminated tag <$name")

            when {
                source.startsWith("/>", index) -> {
                    index += 2
                    pendingEndElement = localName(name)
                    return XmlEvent.StartElement(localName(name), attributes)
                }
                source[index] == '>' -> {
                    index++
                    return XmlEvent.StartElement(localName(name), attributes)
                }
                else -> {
                    val attributeName = readName()
                    if (attributeName.isEmpty()) {
                        throw XmlParseException("Malformed attribute in <$name>")
                    }
                    skipWhitespace()
                    if (index >= source.length || source[index] != '=') {
                        throw XmlParseException("Attribute '$attributeName' without value in <$name>")
                    }
                    index++
                    skipWhitespace()
                    attributes[localName(attributeName)] = readAttributeValue(name)
                }
            }
        }
    }

    private fun readAttributeValue(elementName: String): String {
        if (index >= source.length) throw XmlParseException("Unterminated tag <$elementName")
        val quote = source[index]
        if (quote != '"' && quote != '\'') {
            throw XmlParseException("Unquoted attribute value in <$elementName>")
        }
        val end = source.indexOf(quote, index + 1)
        if (end < 0) throw XmlParseException("Unterminated attribute value in <$elementName>")
        val raw = source.substring(index + 1, end)
        index = end + 1
        return decodeEntities(raw)
    }

    private fun readName(): String {
        val start = index
        while (index < source.length) {
            val character = source[index]
            if (character.isWhitespace() || character == '/' || character == '>' || character == '=') break
            index++
        }
        return source.substring(start, index)
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }
}

internal fun localName(qualifiedName: String): String = qualifiedName.substringAfterLast(':')

/**
 * Незнакомая сущность оставляется как есть: в чужом файле это скорее опечатка в
 * названии активности, чем повод не импортировать трек целиком.
 */
internal fun decodeEntities(raw: String): String {
    if (!raw.contains('&')) return raw
    return buildString(raw.length) {
        var cursor = 0
        while (cursor < raw.length) {
            val character = raw[cursor]
            if (character != '&') {
                append(character)
                cursor++
                continue
            }
            val semicolon = raw.indexOf(';', cursor + 1)
            if (semicolon < 0 || semicolon - cursor > MAX_ENTITY_LENGTH) {
                append(character)
                cursor++
                continue
            }
            val entity = raw.substring(cursor + 1, semicolon)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                append(character)
                cursor++
            } else {
                append(decoded)
                cursor = semicolon + 1
            }
        }
    }
}

private const val MAX_ENTITY_LENGTH = 12

private fun decodeEntity(entity: String): String? = when {
    entity == "amp" -> "&"
    entity == "lt" -> "<"
    entity == "gt" -> ">"
    entity == "quot" -> "\""
    entity == "apos" -> "'"
    entity.startsWith("#x") || entity.startsWith("#X") ->
        entity.drop(2).toIntOrNull(radix = 16)?.toCodePointString()
    entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.toCodePointString()
    else -> null
}

// Своя сборка суррогатной пары вместо Character.toChars: модуль не должен зависеть
// от JVM-класса ради одной ссылки на символ.
private fun Int.toCodePointString(): String? = when {
    this < 0 || this > 0x10FFFF -> null
    this in 0xD800..0xDFFF -> null
    this <= 0xFFFF -> Char(this).toString()
    else -> {
        val offset = this - 0x10000
        charArrayOf(
            Char(0xD800 + (offset shr 10)),
            Char(0xDC00 + (offset and 0x3FF)),
        ).concatToString()
    }
}
