package com.ians.korno.data.gpxexport.xml

/**
 * Минимальный построитель XML.
 *
 * GPX и TCX — это несколько вложенных элементов с текстом и атрибутами, поэтому
 * своя сборка строки короче и предсказуемее любой внешней библиотеки: нет ни
 * зависимости от платформы, ни настроек сериализатора, влияющих на вывод.
 */
internal class XmlWriter(private val indentStep: String = "  ") {

    private val builder = StringBuilder()
    private val openElements = ArrayDeque<String>()

    fun declaration() {
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    }

    /** Элемент с вложенным содержимым. */
    fun element(
        name: String,
        attributes: List<Pair<String, String>> = emptyList(),
        body: XmlWriter.() -> Unit,
    ) {
        indent()
        builder.append('<').append(name)
        appendAttributes(attributes)
        builder.append(">\n")
        openElements.addLast(name)
        body()
        openElements.removeLast()
        indent()
        builder.append("</").append(name).append(">\n")
    }

    /** Элемент с текстом; `null` не выводится вовсе — пустой тег хуже отсутствующего. */
    fun text(
        name: String,
        value: String?,
        attributes: List<Pair<String, String>> = emptyList(),
    ) {
        if (value == null) return
        indent()
        builder.append('<').append(name)
        appendAttributes(attributes)
        builder.append('>').append(escapeText(value)).append("</").append(name).append(">\n")
    }

    fun build(): String {
        check(openElements.isEmpty()) { "Unclosed elements: $openElements" }
        return builder.toString()
    }

    private fun appendAttributes(attributes: List<Pair<String, String>>) {
        attributes.forEach { (key, value) ->
            builder.append(' ').append(key).append("=\"").append(escapeAttribute(value)).append('"')
        }
    }

    private fun indent() {
        repeat(openElements.size) { builder.append(indentStep) }
    }
}

internal fun escapeText(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when {
            character == '&' -> append("&amp;")
            character == '<' -> append("&lt;")
            character == '>' -> append("&gt;")
            character.isForbiddenInXml() -> Unit
            else -> append(character)
        }
    }
}

internal fun escapeAttribute(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when {
            character == '&' -> append("&amp;")
            character == '<' -> append("&lt;")
            character == '>' -> append("&gt;")
            character == '"' -> append("&quot;")
            character == '\'' -> append("&apos;")
            // В атрибуте перевод строки и табуляция нормализуются парсером в пробел,
            // поэтому сохраняем их ссылками, а не как есть.
            character == '\n' -> append("&#10;")
            character == '\r' -> append("&#13;")
            character == '\t' -> append("&#9;")
            character.isForbiddenInXml() -> Unit
            else -> append(character)
        }
    }
}

/**
 * Управляющие символы XML 1.0 не описывает вовсе — ни как символ, ни как ссылку.
 * Название активности приходит от пользователя, поэтому такие символы выбрасываем:
 * иначе получится файл, который не примет ни один парсер, включая наш собственный.
 */
private fun Char.isForbiddenInXml(): Boolean =
    (this < ' ' && this != '\t' && this != '\n' && this != '\r') ||
        this == '\u007F' ||
        this in '\uFFFE'..'\uFFFF'
