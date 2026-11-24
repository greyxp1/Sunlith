package io.github.sunsind.sunlith.school.portal

import io.github.sunsind.sunlith.school.sync.schoolRequire
import io.github.sunsind.sunlith.school.sync.text
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Minimal, non-executing HTML reader. It never loads resources; scripts, styles and embedded content are dropped. */
internal sealed interface HtmlToken
private data class HtmlTag(val name: String, val closing: Boolean, val attributes: Map<String, String>) : HtmlToken
private data class HtmlText(val text: String) : HtmlToken

internal data class HtmlLink(val href: String, val onclick: String)
internal data class HtmlCell(val tokens: List<HtmlToken>, val links: List<HtmlLink>) {
    val text: String get() = SchoolHtml.text(tokens)
}
internal data class HtmlTable(val rows: List<List<HtmlCell>>)
internal data class HtmlForm(val action: String, val inputs: List<Map<String, String>>)

internal object SchoolHtml {
    const val MAX_HTML = 2_000_000
    private val RAW = setOf("script", "style", "noscript", "template", "iframe", "object", "embed", "svg", "math", "textarea", "title")
    private val BLOCK = setOf("div", "tr", "table", "ul", "ol", "dl", "dt", "dd", "section", "article", "header", "footer", "center", "form", "tbody", "thead")
    private val PARAGRAPH = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre")

    fun tokens(html: String, maxChars: Int? = MAX_HTML): List<HtmlToken> {
        if (maxChars != null) schoolRequire(html.length <= maxChars)
        val out = ArrayList<HtmlToken>()
        var i = 0
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { out += HtmlText(decode(text.toString())); text.clear() } }
        while (i < html.length) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }
            if (html.startsWith("<!--", i)) { flush(); i = html.indexOf("-->", i + 4).let { if (it < 0) html.length else it + 3 }; continue }
            val next = html.getOrNull(i + 1)
            if (next == '!' || next == '?') { flush(); i = html.indexOf('>', i).let { if (it < 0) html.length else it + 1 }; continue }
            val closing = next == '/'
            val nameStart = if (closing) i + 2 else i + 1
            if (html.getOrNull(nameStart)?.isLetter() != true) { text.append(c); i++; continue }
            flush()
            var j = nameStart
            while (j < html.length && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':')) j++
            val name = html.substring(nameStart, j).lowercase(Locale.ROOT)
            val attributes = linkedMapOf<String, String>()
            while (j < html.length && html[j] != '>') {
                if (html[j].isWhitespace() || html[j] == '/') { j++; continue }
                val a = j
                while (j < html.length && !html[j].isWhitespace() && html[j] != '=' && html[j] != '>' && html[j] != '/') j++
                val key = html.substring(a, j).lowercase(Locale.ROOT)
                while (j < html.length && html[j].isWhitespace()) j++
                var value = ""
                if (j < html.length && html[j] == '=') {
                    j++
                    while (j < html.length && html[j].isWhitespace()) j++
                    if (j < html.length && (html[j] == '"' || html[j] == '\'')) {
                        val quote = html[j]
                        val end = html.indexOf(quote, j + 1).let { if (it < 0) html.length else it }
                        value = html.substring(j + 1, end); j = (end + 1).coerceAtMost(html.length)
                    } else {
                        val v = j
                        while (j < html.length && !html[j].isWhitespace() && html[j] != '>') j++
                        value = html.substring(v, j)
                    }
                }
                if (key.isNotEmpty() && key !in attributes) attributes[key] = decode(value)
            }
            i = (j + 1).coerceAtMost(html.length)
            if (!closing && name in RAW) {
                val end = html.indexOf("</$name", i, ignoreCase = true)
                i = if (end < 0) html.length else html.indexOf('>', end).let { if (it < 0) html.length else it + 1 }
                if (name == "iframe") out += HtmlTag(name, false, attributes)
                continue
            }
            out += HtmlTag(name, closing, if (closing) emptyMap() else attributes)
        }
        flush()
        return out
    }

    fun text(tokens: List<HtmlToken>): String {
        val sb = StringBuilder()
        var pre = 0
        fun trailingNewlines(): Int { var n = 0; var k = sb.length - 1; while (k >= 0 && sb[k] == '\n') { n++; k-- }; return n }
        fun lineBreak(count: Int) {
            while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
            if (sb.isEmpty()) return
            repeat((count - trailingNewlines()).coerceAtLeast(0)) { sb.append('\n') }
        }
        tokens.forEach { token ->
            when (token) {
                is HtmlText -> {
                    if (pre > 0) sb.append(token.text.replace("\r\n", "\n"))
                    else {
                        val collapsed = token.text.replace(WHITESPACE, " ")
                        val atLineStart = sb.isEmpty() || sb.last() == '\n' || sb.last() == ' '
                        sb.append(if (atLineStart) collapsed.trimStart() else collapsed)
                    }
                }
                is HtmlTag -> when {
                    token.name == "br" -> { while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1); if (sb.isNotEmpty()) sb.append('\n') }
                    token.name == "hr" -> lineBreak(2)
                    token.name == "li" && !token.closing -> { lineBreak(1); sb.append("• ") }
                    token.name == "li" -> lineBreak(1)
                    token.name in PARAGRAPH -> {
                        lineBreak(2)
                        if (token.name == "pre") pre = (pre + if (token.closing) -1 else 1).coerceAtLeast(0)
                    }
                    token.name in BLOCK -> lineBreak(1)
                    token.name == "td" || token.name == "th" -> if (!token.closing && sb.isNotEmpty() && sb.last() != '\n' && sb.last() != ' ') sb.append("  ")
                    token.name == "img" -> token.attributes["alt"]?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append("[").append(it.take(200)).append("]") }
                }
            }
        }
        return sb.toString().trimEnd()
    }

    /** Only absolute web or mail links survive; javascript:, data:, relative and other schemes become plain text. */
    fun safeLink(href: String?): String? {
        val value = href?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 } ?: return null
        if (value.any { it.isISOControl() || it.isWhitespace() }) return null
        return try {
            val uri = URI(value)
            when (uri.scheme?.lowercase(Locale.ROOT)) {
                "https", "http" -> if (!uri.host.isNullOrBlank() && uri.rawUserInfo == null) value else null
                "mailto" -> value
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun tables(tokens: List<HtmlToken>): List<HtmlTable> {
        class Builder {
            val rows = ArrayList<List<HtmlCell>>()
            var row: ArrayList<HtmlCell>? = null
            var cell: ArrayList<HtmlToken>? = null
            val links = ArrayList<HtmlLink>()
            fun closeCell() {
                val tokens = cell ?: return
                (row ?: ArrayList<HtmlCell>().also { row = it }).add(HtmlCell(tokens.toList(), links.toList()))
                cell = null; links.clear()
            }
            fun closeRow() { closeCell(); row?.let { if (it.isNotEmpty()) rows += it.toList() }; row = null }
        }
        val done = ArrayList<HtmlTable>()
        val stack = ArrayList<Builder>()
        tokens.forEach { token ->
            val top = stack.lastOrNull()
            if (token is HtmlTag) when (token.name) {
                "table" -> if (!token.closing) { schoolRequire(stack.size < 16); stack += Builder(); return@forEach } else if (top != null) {
                    top.closeRow(); done += HtmlTable(top.rows); stack.removeAt(stack.lastIndex); return@forEach
                }
                "tr" -> if (top != null) { top.closeRow(); if (!token.closing) top.row = ArrayList(); return@forEach }
                "td", "th" -> if (top != null) {
                    top.closeCell()
                    if (!token.closing) { if (top.row == null) top.row = ArrayList(); top.cell = ArrayList() }
                    return@forEach
                }
                "a" -> if (top?.cell != null && !token.closing) top.links += HtmlLink(token.attributes["href"].orEmpty(), token.attributes["onclick"].orEmpty())
            }
            top?.cell?.add(token)
        }
        while (stack.isNotEmpty()) { val b = stack.removeAt(stack.lastIndex); b.closeRow(); done += HtmlTable(b.rows) }
        return done
    }

    fun forms(tokens: List<HtmlToken>): List<HtmlForm> {
        val forms = ArrayList<HtmlForm>()
        var current: Pair<String, ArrayList<Map<String, String>>>? = null
        tokens.forEach { token ->
            if (token !is HtmlTag) return@forEach
            when {
                token.name == "form" && !token.closing -> {
                    current?.let { forms += HtmlForm(it.first, it.second) }
                    current = token.attributes["action"].orEmpty() to ArrayList()
                }
                token.name == "form" -> { current?.let { forms += HtmlForm(it.first, it.second) }; current = null }
                (token.name == "input" || token.name == "button") && !token.closing -> current?.second?.add(token.attributes + ("tag" to token.name))
            }
        }
        current?.let { forms += HtmlForm(it.first, it.second) }
        return forms
    }

    fun frames(tokens: List<HtmlToken>): List<String> = tokens.filterIsInstance<HtmlTag>()
        .filter { !it.closing && (it.name == "frame" || it.name == "iframe") }.mapNotNull { it.attributes["src"]?.trim()?.takeIf(String::isNotEmpty) }

    fun attributes(tokens: List<HtmlToken>, tag: String, name: String): List<String> =
        tokens.filterIsInstance<HtmlTag>().filter { !it.closing && it.name == tag }.mapNotNull { it.attributes[name] }

    fun labeledLinks(tokens: List<HtmlToken>, label: (String) -> Boolean): List<String> {
        val matches = ArrayList<String>()
        var href: String? = null
        val content = ArrayList<HtmlToken>()
        for (token in tokens) {
            if (token is HtmlTag && token.name == "a") {
                if (token.closing && href != null && label(normalize(text(content)))) matches += href
                href = if (token.closing) null else token.attributes["href"]
                content.clear()
            } else if (href != null) content += token
        }
        return matches
    }

    /**
     * Token ranges inside each outermost [tag] whose attributes [match]. Like browsers, an inner [tag] left unclosed ends
     * with the enclosing table cell, row or table, or with the page.
     */
    fun elements(tokens: List<HtmlToken>, tag: String, match: (Map<String, String>) -> Boolean): List<IntRange> {
        val found = ArrayList<IntRange>()
        var start = -1
        var depth = 0
        val inner = HashMap<String, Int>()
        for ((index, token) in tokens.withIndex()) {
            if (token !is HtmlTag) continue
            if (depth == 0) {
                if (token.name == tag && !token.closing && match(token.attributes)) {
                    start = index + 1
                    depth = 1
                    inner.clear()
                }
                continue
            }
            val ended = when (token.name) {
                tag -> { depth += if (token.closing) -1 else 1; depth == 0 }
                in TABLE_SCOPE -> (inner.getOrDefault(token.name, 0) + if (token.closing) -1 else 1).also { inner[token.name] = it } < 0
                else -> false
            }
            if (ended) { found += start until index; depth = 0 }
        }
        if (depth > 0) found += start until tokens.size
        return found
    }

    private val TABLE_SCOPE = setOf("td", "th", "tr", "tbody", "thead", "tfoot", "table")

    /**
     * Message area of a gestcourr.asp page without CKEditor content: the last div.Panel in div#divPrincipal opened before
     * the first reply, forward or delete action, ending at the panel or that action. Login and error pages have neither.
     */
    fun classicMail(tokens: List<HtmlToken>): IntRange? {
        val main = elements(tokens, "div") { it["id"] == "divPrincipal" }.singleOrNull() ?: return null
        val action = main.firstOrNull { (tokens[it] as? HtmlTag)?.attributes?.get("id")?.matches(MAIL_ACTION) == true } ?: return null
        val panel = elements(tokens, "div") { "Panel" in it["class"].orEmpty().split(WHITESPACE) }
            .lastOrNull { it.first > main.first && it.first < action } ?: return null
        return panel.first until minOf(panel.last + 1, action)
    }

    private val MAIL_ACTION = Regex("btn(?:Repondre|Transferer|Supprimer)\\d*")

    /**
     * Plain message body from [classicMail] content: skips the "Version imprimable" link, the header table's label rows
     * ("De", "Date", "Objet", attachments), any "Message" label, and trailing toolbar/table wrapper markup.
     */
    fun classicMailBody(tokens: List<HtmlToken>): List<HtmlToken> {
        var end = tokens.size
        while (end > 0 && tokens[end - 1].let { it is HtmlTag && it.name != "img" || it is HtmlText && it.text.isBlank() }) end--
        val content = tokens.subList(0, end)
        var bodyStart = 0
        for ((table, token) in content.withIndex()) {
            if (token !is HtmlTag || token.name != "table" || token.closing) continue
            var labeled = false
            var attachments = false
            var cut = -1
            for ((start, rowEnd, cells) in rows(content, table)) {
                val label = cells.firstOrNull()?.let { normalize(text(content.subList(it + 1, cells.getOrElse(1) { rowEnd }))) }
                    ?.trimEnd(':', ' ').orEmpty()
                when {
                    label in MESSAGE_LABELS -> {
                        cut = if (cells.size >= 2) cells[1] + 1 else rowEnd
                        labeled = true
                        break
                    }
                    cells.size >= 2 && label in MAIL_LABELS -> {
                        cut = rowEnd
                        labeled = true
                        attachments = label in ATTACHMENT_LABELS
                    }
                    labeled && label.isEmpty() && (attachments || text(content.subList(start, rowEnd)).isBlank()) -> cut = rowEnd
                    else -> break
                }
            }
            if (labeled && cut > 0) {
                bodyStart = cut
                break
            }
        }
        var start = bodyStart
        while (start < content.size && content[start].let {
            (it is HtmlText && it.text.isBlank()) ||
                (it is HtmlTag && (it.name == "br" || it.name in TABLE_SCOPE || it.closing))
        }) start++
        // If a standalone "Message" label sits outside the header table, skip it and any breaks after it.
        val firstText = (start until content.size).firstOrNull { content[it] is HtmlText && (content[it] as HtmlText).text.isNotBlank() }
        if (firstText != null && normalize((content[firstText] as HtmlText).text).trimEnd(':', ' ') in MESSAGE_LABELS) {
            start = firstText + 1
            while (start < content.size && content[start].let {
                (it is HtmlText && it.text.isBlank()) ||
                    (it is HtmlTag && (it.name == "br" || it.name in TABLE_SCOPE || it.closing))
            }) start++
        }
        return content.subList(start, content.size)
    }

    /** Direct rows of the table opened at [table], as start, exclusive end and cell start indices. */
    private fun rows(tokens: List<HtmlToken>, table: Int): List<Triple<Int, Int, List<Int>>> {
        val rows = ArrayList<Triple<Int, Int, List<Int>>>()
        var start = -1
        val cells = ArrayList<Int>()
        fun close(end: Int) {
            if (start >= 0) rows += Triple(start, end, cells.toList())
            start = -1
            cells.clear()
        }
        var depth = 0
        for (index in table + 1 until tokens.size) {
            val token = tokens[index] as? HtmlTag ?: continue
            when {
                token.name == "table" && !token.closing -> depth++
                token.name == "table" -> if (depth-- == 0) { close(index); return rows }
                depth > 0 -> {}
                token.name == "tr" && token.closing -> close(index + 1)
                token.name == "tr" -> { close(index); start = index }
                (token.name == "td" || token.name == "th") && !token.closing -> { if (start < 0) start = index; cells += index }
            }
        }
        close(tokens.size)
        return rows
    }

    private val ATTACHMENT_LABELS = setOf("pieces jointes", "piece jointe", "attachments", "attachment")
    private val MESSAGE_LABELS = setOf("message", "contenu")
    private val MAIL_LABELS = ATTACHMENT_LABELS + setOf(
        "de", "a", "cc", "cci", "objet", "sujet", "date", "envoye le", "recu le", "expediteur", "destinataire", "destinataires",
        "from", "to", "bcc", "subject", "sent", "received",
    )

    /** Distinct tag#id.class shapes of a page, for diagnostics that must not include text or other attributes. */
    fun outline(tokens: List<HtmlToken>): String = tokens.asSequence().filterIsInstance<HtmlTag>()
        .filter { !it.closing && ("id" in it.attributes || "class" in it.attributes) }
        .map { tag ->
            tag.name + tag.attributes["id"]?.let { "#" + it.take(40) }.orEmpty() +
                tag.attributes["class"]?.trim()?.let { "." + it.replace(WHITESPACE, ".").take(60) }.orEmpty()
        }
        .distinct().joinToString(" ").take(3000)

    fun mailAttachments(tokens: List<HtmlToken>): List<List<HtmlToken>> {
        for (table in tables(tokens)) {
            val first = table.rows.indexOfFirst {
                it.size >= 2 && normalize(it.first().text).trimEnd(':', ' ') in setOf("pieces jointes", "attachments")
            }
            if (first < 0) continue
            val rows = table.rows.drop(first + 1).takeWhile { it.first().text.isBlank() }
            return (listOf(table.rows[first]) + rows).flatMap { it.drop(1) }
                .filter { it.text.isNotBlank() }.map { it.tokens }
        }
        return emptyList()
    }

    fun mailFooter(tokens: List<HtmlToken>, after: Int): IntRange? {
        val start = (after until tokens.size).firstOrNull {
            val token = tokens[it] as? HtmlText ?: return@firstOrNull false
            val text = normalize(token.text)
            text.startsWith("selon la loi canadienne") || text.startsWith("under canadian law")
        } ?: return null
        val following = tokens.drop(start).take(80)
        val link = following.indexOfFirst { it is HtmlTag && it.name == "a" && !it.closing }
        val end = if (link >= 0) following.drop(link + 1).indexOfFirst {
            it is HtmlTag && it.name == "a" && it.closing
        } else -1
        val label = if (end >= 0) normalize(text(following.subList(link + 1, link + 1 + end))) else ""
        return start until start + if ("desinscrire" in label || "unsubscribe" in label) link + end + 2 else 1
    }

    suspend fun emailMarkup(
        tokens: List<HtmlToken>,
        link: (String) -> String?,
        image: suspend (String) -> String?,
    ): String {
        val out = StringBuilder()
        var paragraphEnd = -1
        for ((index, token) in tokens.withIndex()) {
            if (index <= paragraphEnd) continue
            if (token is HtmlTag && token.name == "p" && !token.closing) {
                paragraphEnd = blankParagraphEnd(tokens, index + 1)
                if (paragraphEnd >= index) continue
            }
            when (token) {
                is HtmlText -> out.append(escapeMarkup(token.text))
                is HtmlTag -> {
                    val name = token.name
                    if (name !in EMAIL_TAGS) continue
                    if (token.closing) {
                        out.append("</").append(name).append('>')
                        continue
                    }
                    out.append('<').append(name)
                    for ((key, value) in token.attributes) {
                        val safe = when {
                            name == "a" && key == "href" -> link(value)
                            name == "img" && key == "src" -> image(value)
                            key == "align" && value.trim().equals("justify", ignoreCase = true) -> null
                            key == "style" -> JUSTIFIED_TEXT.replace(value, "").takeIf { it.isNotBlank() }
                            key in EMAIL_ATTRIBUTES -> value
                            else -> null
                        } ?: continue
                        out.append(' ').append(key).append("=\"").append(escapeMarkup(safe)).append('"')
                    }
                    out.append('>')
                }
            }
        }
        return out.toString()
    }

    private fun blankParagraphEnd(tokens: List<HtmlToken>, start: Int): Int {
        for (index in start until tokens.size) {
            when (val token = tokens[index]) {
                is HtmlText -> if (!token.text.isBlank()) return -1
                is HtmlTag -> {
                    if (token.name == "p" && token.closing) return index
                    if (token.name !in BLANK_PARAGRAPH_TAGS) return -1
                }
            }
        }
        return -1
    }

    private val BLANK_PARAGRAPH_TAGS = setOf("br", "span", "font", "b", "strong", "i", "em", "u")

    fun escapeMarkup(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private val EMAIL_TAGS = setOf(
        "a", "abbr", "b", "blockquote", "br", "caption", "center", "code", "col", "colgroup", "dd", "div", "dl", "dt",
        "em", "font", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "s", "small",
        "span", "strike", "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
    )
    private val EMAIL_ATTRIBUTES = setOf(
        "align", "alt", "bgcolor", "border", "cellpadding", "cellspacing", "color", "colspan", "dir", "face",
        "height", "lang", "rowspan", "size", "title", "valign", "width",
    )
    private val JUSTIFIED_TEXT = Regex("""(?:^|;)\s*text-align\s*:\s*justify\s*(?:!\s*important\s*)?(?=;|$)""", RegexOption.IGNORE_CASE)

    /** Lowercase, accent-free, single-spaced text for structural matching only (never shown). */
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "")
        .replace('’', '\'').replace('‘', '\'').replace('–', '-').replace('—', '-')
        .lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    fun decode(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '&') {
                val end = value.indexOf(';', i + 1)
                if (end in (i + 2)..(i + 12)) {
                    val name = value.substring(i + 1, end)
                    val code = when {
                        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
                        name.startsWith("#") -> name.substring(1).toIntOrNull()
                        else -> ENTITIES[name]
                    }
                    if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) {
                        out.appendCodePoint(if (code in 0x80..0x9F) WINDOWS_1252[code - 0x80] else code); i = end + 1; continue
                    }
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
    private val MARKS = Regex("\\p{Mn}+")
    private val WINDOWS_1252 = intArrayOf(0x20AC, 0x81, 0x201A, 0x192, 0x201E, 0x2026, 0x2020, 0x2021, 0x2C6, 0x2030, 0x160, 0x2039, 0x152, 0x8D, 0x17D, 0x8F,
        0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x2DC, 0x2122, 0x161, 0x203A, 0x153, 0x9D, 0x17E, 0x178)
    private val ENTITIES: Map<String, Int> = buildMap {
        putAll(mapOf("amp" to 38, "lt" to 60, "gt" to 62, "quot" to 34, "apos" to 39, "nbsp" to 160, "rsquo" to 0x2019, "lsquo" to 0x2018,
            "ldquo" to 0x201C, "rdquo" to 0x201D, "ndash" to 0x2013, "mdash" to 0x2014, "hellip" to 0x2026, "laquo" to 171, "raquo" to 187,
            "euro" to 0x20AC, "bull" to 0x2022, "copy" to 169, "reg" to 174, "deg" to 176, "middot" to 183, "oelig" to 0x153, "OElig" to 0x152, "shy" to 173))
        val latin = listOf("Agrave", "Aacute", "Acirc", "Atilde", "Auml", "Aring", "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc", "Euml",
            "Igrave", "Iacute", "Icirc", "Iuml", "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", "times", "Oslash",
            "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN", "szlig")
        latin.forEachIndexed { index, name -> put(name, 0xC0 + index); if (index != 23 && index != 31) put(name.replaceFirstChar { it.lowercase() }.let { if (name.length > 1 && name[1].isUpperCase()) name.lowercase() else it }, 0xE0 + index) }
        put("divide", 247); put("yuml", 255)
    }
}
