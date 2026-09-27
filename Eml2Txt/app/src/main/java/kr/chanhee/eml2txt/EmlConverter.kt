package kr.chanhee.eml2txt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Turns an .eml file into plain text.
 *
 *  - A real e-mail (MIME) becomes: header lines (From, To, Cc, Date, Subject, Attachments),
 *    the message text (HTML is converted to text), and the contents of text attachments
 *    (.txt, .csv … also inside .zip files and forwarded mails). [Options] choose which go in.
 *  - A file that is only *named* .eml but is really plain text (some apps export chats this
 *    way) is returned exactly as it is.
 *
 * Handles base64 / quoted-printable, RFC 2047 / 2231 encoded names, UTF-8 and legacy Korean
 * encodings (EUC-KR / CP949), truncated mails and damaged parts.
 * Pure Kotlin/JVM (no Android classes), so it can be unit-tested on a computer.
 */
object EmlConverter {

    enum class Reason { EMPTY_FILE, NOT_TEXT }

    class ConversionException(val reason: Reason) : Exception(reason.name)

    /** What goes into the .txt made from an e-mail. */
    data class Options(val header: Boolean = true, val body: Boolean = true, val attachments: Boolean = true)

    sealed class Document

    /** Not an e-mail: plain text, saved unchanged. */
    class PlainText(val text: String) : Document()

    class Email(val message: Message) : Document()

    class Message(
        /** From, To, Cc, Date, Subject — those present, decoded. */
        val headers: List<Pair<String, String>>,
        /** The readable message text. */
        val body: String,
        /** Every attachment, for the "Attachments:" line. */
        val attachments: List<Attachment>,
        /** Attachments whose contents are text, and forwarded mails. */
        val sections: List<Section>,
    ) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val subject: String? get() = header("Subject")
        val from: String? get() = header("From")
    }

    class Attachment(val name: String, val size: Int)

    /** A text attachment ([text]) or a forwarded mail ([message]). */
    class Section(val title: String, val text: String?, val message: Message?)

    // ================================================================ public API

    /** Reads an .eml file. Throws [ConversionException] for empty or binary (non-text) files. */
    fun parse(bytes: ByteArray): Document {
        if (bytes.isEmpty()) throw ConversionException(Reason.EMPTY_FILE)
        val trimmed = skipBomAndBlankLines(bytes) // some tools add a BOM or blank line before the headers
        if (trimmed.isEmpty()) throw ConversionException(Reason.EMPTY_FILE)
        if (looksLikeEmail(trimmed)) return Email(parseMessage(stripMboxLine(latin1(trimmed)), depth = 0))
        if (isProbablyBinary(bytes)) throw ConversionException(Reason.NOT_TEXT)
        return PlainText(decodeText(bytes, null))
    }

    /** The text to save, for the chosen [options] (options only matter for e-mails). */
    fun render(doc: Document, options: Options): String = when (doc) {
        is PlainText -> doc.text
        is Email -> renderMessage(doc.message, options).let { if (it.isEmpty()) "" else it + "\n" }
    }

    /** A good default name for the .txt: the input's name, or the subject / first line if that name says nothing. */
    fun suggestFileName(inputName: String?, doc: Document): String {
        val base = inputName.orEmpty().substringAfterLast('/').substringBeforeLast('.').trim()
        val generic = base.none { it.isLetterOrDigit() } || base.all { it.isDigit() } ||
            base.lowercase() in setOf("original_msg", "message", "mail", "email", "attachment", "noname", "untitled")
        val fallback = when (doc) {
            is Email -> doc.message.subject
            is PlainText -> doc.text.lineSequence().map { it.trim().removePrefix("﻿") }.firstOrNull { it.isNotEmpty() }
        }
        val chosen = if (!generic) base else fallback.orEmpty().take(60)
        val safe = chosen.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trim('.').trim().take(100)
        return safe.ifEmpty { "email" } + ".txt"
    }

    fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.lineSequence().count() - (if (text.endsWith("\n")) 1 else 0)

    // ================================================================ rendering

    private fun renderMessage(m: Message, o: Options): String {
        val blocks = mutableListOf<String>()
        if (o.header) {
            val lines = m.headers.map { (k, v) -> "$k: $v" }.toMutableList()
            if (m.attachments.isNotEmpty()) {
                lines += "Attachments: " + m.attachments.joinToString(", ") { "${it.name} (${formatSize(it.size)})" }
            }
            if (lines.isNotEmpty()) blocks += lines.joinToString("\n")
        }
        if (o.body && m.body.isNotBlank()) blocks += m.body
        if (o.attachments) {
            val contents = m.sections.mapNotNull { s ->
                val c = if (s.message != null) renderMessage(s.message, o) else s.text.orEmpty().trim('\n').trimEnd()
                if (c.isBlank()) null else s.title to c
            }
            // A single attachment on its own is written as-is; otherwise each gets a title line.
            val labelled = blocks.isNotEmpty() || contents.size > 1
            for ((title, c) in contents) blocks += if (labelled) "===== $title =====\n$c" else c
        }
        return blocks.joinToString("\n\n")
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    // ================================================================ e-mail structure

    private class Builder {
        val body = mutableListOf<String>()
        val attachments = mutableListOf<Attachment>()
        val sections = mutableListOf<Section>()
    }

    private val shownHeaders = listOf("From", "To", "Cc", "Date", "Subject")

    private fun parseMessage(raw: String, depth: Int): Message {
        val entity = parseEntity(raw)
        val b = Builder()
        walk(entity, b, depth, bodyAllowed = true)
        val headers = shownHeaders.mapNotNull { name ->
            entity.header(name)?.let { name to decodeHeaderText(it).replace(Regex("""\s+"""), " ").trim() }
        }.filter { it.second.isNotEmpty() }
        val body = b.body.map { normalize(it).trim('\n').trimEnd() }.filter { it.isNotBlank() }.joinToString("\n\n")
        return Message(headers, body, b.attachments, b.sections)
    }

    /**
     * Visits one MIME part. [bodyAllowed] is false inside the alternatives that were not chosen
     * (e.g. the HTML copy of a plain-text mail): their text is skipped, their attachments kept.
     */
    private fun walk(e: Entity, b: Builder, depth: Int, bodyAllowed: Boolean) {
        if (depth > 20) return
        val ct = parseHeaderValue(e.header("Content-Type"))
        val type = ct.value.ifEmpty { "text/plain" }
        val disposition = parseHeaderValue(e.header("Content-Disposition"))
        val fileName = extendedParam(disposition.params, "filename") ?: extendedParam(ct.params, "name")
        val isAttachment = disposition.value == "attachment" || fileName != null

        if (type.startsWith("multipart/")) {
            val boundary = ct.params["boundary"]
            if (!boundary.isNullOrEmpty()) {
                val parts = splitMultipart(e.body, boundary).map { parseEntity(it) }
                val chosen = if (type == "multipart/alternative") pickAlternative(parts) else null
                for (part in parts) {
                    try {
                        walk(part, b, depth + 1, bodyAllowed && (chosen == null || part === chosen))
                    } catch (ex: Exception) {
                        // one damaged part must not sink the rest
                    }
                }
                return
            }
        }

        val data = decodeTransfer(e.body, e.header("Content-Transfer-Encoding"))
        val charset = ct.params["charset"]
        val lowerName = fileName?.lowercase().orEmpty()

        // A forwarded mail, attached as a message or as an .eml file
        if (type == "message/rfc822" || (lowerName.endsWith(".eml") && looksLikeEmail(skipBomAndBlankLines(data)))) {
            if (fileName != null) b.attachments += Attachment(fileName, data.size)
            val nested = parseMessage(stripMboxLine(latin1(skipBomAndBlankLines(data))), depth + 1)
            b.sections += Section(if (fileName != null) "Forwarded message ($fileName)" else "Forwarded message", null, nested)
            return
        }

        // The message text itself
        if (!isAttachment && (type == "text/plain" || type == "text/html")) {
            if (bodyAllowed) {
                val text = decodeText(data, charset)
                b.body += if (type == "text/html") htmlToText(text) else text
            }
            return
        }

        // Pictures embedded in an HTML mail (logos, signatures) aren't real attachments
        if (type.startsWith("image/") && disposition.value != "attachment" &&
            (fileName == null || e.header("Content-ID") != null)
        ) return

        val name = fileName ?: defaultName(type)
        b.attachments += Attachment(name, data.size)
        val lower = name.lowercase()
        when {
            isZip(data) -> collectZip(name, data, b, depth)
            isTextFile(type, lower, data) -> {
                val text = decodeText(data, charset)
                val html = type == "text/html" || lower.endsWith(".html") || lower.endsWith(".htm")
                b.sections += Section(name, normalize(if (html) htmlToText(text) else text), null)
            }
        }
    }

    /** From a multipart/alternative: plain text if it has any, otherwise HTML, otherwise the richest part. */
    private fun pickAlternative(parts: List<Entity>): Entity? {
        fun typeOf(p: Entity) = parseHeaderValue(p.header("Content-Type")).value.ifEmpty { "text/plain" }
        fun hasText(p: Entity) = decodeText(decodeTransfer(p.body, p.header("Content-Transfer-Encoding")), null).isNotBlank()
        return parts.firstOrNull { typeOf(it) == "text/plain" && hasText(it) }
            ?: parts.lastOrNull { typeOf(it) == "text/html" }
            ?: parts.lastOrNull { typeOf(it).startsWith("multipart/") }
            ?: parts.lastOrNull()
    }

    private val textExtensions = setOf(
        "txt", "csv", "tsv", "md", "log", "json", "xml", "ics", "vcf", "srt", "htm", "html", "eml", "ini", "yaml", "yml",
    )

    private fun isTextFile(type: String, lowerName: String, data: ByteArray): Boolean {
        val ext = lowerName.substringAfterLast('.', "")
        val textual = type.startsWith("text/") || ext in textExtensions ||
            type == "application/json" || type == "application/xml"
        return textual && data.isNotEmpty() && !isProbablyBinary(data)
    }

    private fun defaultName(type: String): String = when (type) {
        "text/plain" -> "attachment.txt"
        "text/html" -> "attachment.html"
        "text/calendar" -> "invite.ics"
        else -> "attachment"
    }

    // ================================================================ zip attachments

    private fun collectZip(zipName: String, data: ByteArray, b: Builder, depth: Int) {
        // Entry names are UTF-8 in newer zips but CP949 in some Korean ones; try each in turn.
        for (nameCharset in listOf(Charsets.UTF_8, koreanCharset, Charsets.ISO_8859_1)) {
            val found = mutableListOf<Section>()
            var garbledName = false
            var damaged = false
            try {
                ZipInputStream(ByteArrayInputStream(data), nameCharset).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name
                        // Newer Android decodes bad UTF-8 names leniently (U+FFFD) instead of throwing.
                        if (name.contains('�')) garbledName = true
                        val lower = name.lowercase()
                        val leaf = lower.substringAfterLast('/')
                        if (lower.startsWith("__macosx/") || leaf.startsWith("._")) continue
                        if (leaf.substringAfterLast('.', "") !in textExtensions) continue
                        val bytes = zip.readBytes()
                        val title = "$zipName > $name"
                        when {
                            leaf.endsWith(".eml") && looksLikeEmail(skipBomAndBlankLines(bytes)) ->
                                found += Section(title, null, parseMessage(stripMboxLine(latin1(skipBomAndBlankLines(bytes))), depth + 1))
                            !isProbablyBinary(bytes) -> {
                                val text = decodeText(bytes, null)
                                val html = leaf.endsWith(".html") || leaf.endsWith(".htm")
                                found += Section(title, normalize(if (html) htmlToText(text) else text), null)
                            }
                        }
                    }
                }
            } catch (ex: IllegalArgumentException) {
                continue // an entry name isn't valid in this charset
            } catch (ex: Exception) {
                damaged = true // keep whatever was read
            }
            if (garbledName && !damaged && nameCharset != Charsets.ISO_8859_1) continue
            // Natural order, so split files come out as part-1, part-2 … part-10
            b.sections += found.sortedWith { x, y -> naturalCompareFileNames(x.title, y.title) }
            return
        }
    }

    internal fun naturalCompareFileNames(a: String, b: String): Int {
        val byStem = naturalCompare(a.substringBeforeLast('.'), b.substringBeforeLast('.'))
        return if (byStem != 0) byStem else naturalCompare(a, b)
    }

    private val chunkPattern = Regex("""[0-9]+|[^0-9]+""")

    private fun naturalCompare(a: String, b: String): Int {
        val x = chunkPattern.findAll(a).map { it.value }.toList()
        val y = chunkPattern.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0] in '0'..'9' && q[0] in '0'..'9') {
                val ps = p.trimStart('0')
                val qs = q.trimStart('0')
                if (ps.length != qs.length) ps.length - qs.length else ps.compareTo(qs)
            } else {
                p.compareTo(q, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return x.size - y.size
    }

    // ================================================================ MIME parsing

    private val headerNameLine = Regex("""^[!-9;-~]+:""")
    private val knownHeaders = setOf(
        "from", "to", "cc", "subject", "date", "mime-version", "content-type", "received",
        "return-path", "message-id", "delivered-to", "reply-to", "x-mailer",
    )

    internal fun looksLikeEmail(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 64 * 1024), Charsets.ISO_8859_1)
        val lines = head.lines().map { it.trimEnd('\r') }
        var i = if (lines.firstOrNull()?.startsWith("From ") == true) 1 else 0
        var sawKnownHeader = false
        var headerCount = 0
        while (i < lines.size) {
            val line = lines[i++]
            if (line.isEmpty()) break
            if (line[0] == ' ' || line[0] == '\t') continue // folded continuation
            if (!headerNameLine.containsMatchIn(line)) return false
            headerCount++
            if (line.substringBefore(':').lowercase() in knownHeaders) sawKnownHeader = true
        }
        return headerCount > 0 && sawKnownHeader
    }

    private fun stripMboxLine(raw: String): String =
        if (raw.startsWith("From ")) raw.substringAfter('\n', "") else raw

    private fun skipBomAndBlankLines(b: ByteArray): ByteArray {
        var i = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3
        while (i < b.size && (b[i] == '\r'.code.toByte() || b[i] == '\n'.code.toByte())) i++
        return if (i == 0) b else b.copyOfRange(i, b.size)
    }

    private class Entity(val headers: List<Pair<String, String>>, val body: String) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private fun parseEntity(raw: String): Entity {
        val lines = mutableListOf<String>()
        var pos = 0
        var bodyStart = raw.length
        while (pos < raw.length) {
            val nl = raw.indexOf('\n', pos)
            val end = if (nl < 0) raw.length else nl
            val line = raw.substring(pos, end).removeSuffix("\r")
            pos = if (nl < 0) raw.length else nl + 1
            if (line.isEmpty()) { bodyStart = pos; break }
            if (lines.isEmpty() && !headerNameLine.containsMatchIn(line)) {
                return Entity(emptyList(), raw) // no header block at all
            }
            lines += line
        }
        val headers = mutableListOf<Pair<String, String>>()
        for (line in lines) {
            if ((line[0] == ' ' || line[0] == '\t') && headers.isNotEmpty()) {
                val (k, v) = headers.removeAt(headers.size - 1)
                headers += k to (v + line)
            } else {
                headers += line.substringBefore(':').trim() to line.substringAfter(':').trim()
            }
        }
        return Entity(headers, if (bodyStart >= raw.length) "" else raw.substring(bodyStart))
    }

    /** Splits a multipart body on its boundary lines and returns the raw parts. */
    private fun splitMultipart(body: String, boundary: String): List<String> {
        val delimiter = "--$boundary"
        val parts = mutableListOf<String>()
        var searchFrom = 0
        var partStart = -1
        var closed = false
        while (true) {
            val idx = body.indexOf(delimiter, searchFrom)
            if (idx < 0) break
            if (idx != 0 && body[idx - 1] != '\n') { searchFrom = idx + delimiter.length; continue }
            var after = idx + delimiter.length
            val isClose = body.startsWith("--", after)
            if (isClose) after += 2
            val nl = body.indexOf('\n', after)
            val lineEnd = if (nl < 0) body.length else nl
            if (body.substring(after, lineEnd).isNotBlank()) { searchFrom = after; continue }
            if (partStart >= 0) {
                var end = idx
                if (end > partStart && body[end - 1] == '\n') end-- // line break before a boundary
                if (end > partStart && body[end - 1] == '\r') end-- // belongs to the boundary
                parts += body.substring(partStart, end)
            }
            if (isClose) { closed = true; break }
            partStart = if (nl < 0) body.length else nl + 1
            searchFrom = partStart
        }
        if (!closed && partStart in 0 until body.length) parts += body.substring(partStart) // truncated mail
        return parts
    }

    private class HeaderValue(val value: String, val params: Map<String, String>)

    private fun parseHeaderValue(raw: String?): HeaderValue {
        if (raw.isNullOrBlank()) return HeaderValue("", emptyMap())
        val tokens = splitOutsideQuotes(raw, ';')
        val params = LinkedHashMap<String, String>()
        for (token in tokens.drop(1)) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val key = token.substring(0, eq).trim().lowercase()
            var value = token.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            if (key !in params) params[key] = value
        }
        return HeaderValue(tokens.first().trim().lowercase(), params)
    }

    private fun splitOutsideQuotes(s: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && inQuotes && i + 1 < s.length -> { sb.append(c).append(s[i + 1]); i++ }
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c == separator && !inQuotes -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Reads a parameter such as filename, including RFC 2231 (`filename*=`) and RFC 2047 forms. */
    private fun extendedParam(params: Map<String, String>, name: String): String? {
        params["$name*"]?.let { return decodeRfc2231(it) }

        val segment = Regex("^" + Regex.escape(name) + """\*(\d+)(\*?)$""")
        val segments = params.entries.mapNotNull { (k, v) ->
            segment.find(k)?.let { m -> Triple(m.groupValues[1].toInt(), m.groupValues[2] == "*", v) }
        }.sortedBy { it.first }
        if (segments.isNotEmpty()) {
            var charset = "UTF-8"
            val bytes = ByteArrayOutputStream()
            for ((index, encoded, value) in segments) {
                if (encoded) {
                    var v = value
                    if (index == 0 && v.count { it == '\'' } >= 2) {
                        charset = v.substringBefore('\'').ifEmpty { "UTF-8" }
                        v = v.substringAfter('\'').substringAfter('\'')
                    }
                    bytes.write(percentDecode(v))
                } else {
                    bytes.write(value.toByteArray(Charsets.ISO_8859_1))
                }
            }
            return decodeBytes(bytes.toByteArray(), charset)
        }

        return params[name]?.let { decodeHeaderText(it) }
    }

    private fun decodeRfc2231(value: String): String {
        if (value.count { it == '\'' } < 2) return decodeBytes(percentDecode(value), "UTF-8")
        val charset = value.substringBefore('\'').ifEmpty { "UTF-8" }
        val encoded = value.substringAfter('\'').substringAfter('\'')
        return decodeBytes(percentDecode(encoded), charset)
    }

    private val encodedWord = Regex("""=\?([^?\s]+)\?([BbQq])\?([^?\s]*)\?=""")

    /** Decodes RFC 2047 encoded words (`=?UTF-8?B?...?=`) and raw 8-bit header text. */
    internal fun decodeHeaderText(raw: String): String {
        val sb = StringBuilder()
        val pending = ByteArrayOutputStream()
        var pendingCharset: String? = null
        fun flush() {
            if (pending.size() > 0) sb.append(decodeBytes(pending.toByteArray(), pendingCharset ?: "UTF-8"))
            pending.reset()
            pendingCharset = null
        }
        var last = 0
        for (m in encodedWord.findAll(raw)) {
            val between = raw.substring(last, m.range.first)
            val adjacentToPrevious = pending.size() > 0 && between.isBlank()
            if (!adjacentToPrevious) { flush(); sb.append(decode8bit(between)) }
            val charset = m.groupValues[1].substringBefore('*')
            val bytes = if (m.groupValues[2].equals("B", ignoreCase = true)) base64Decode(m.groupValues[3])
            else qDecode(m.groupValues[3])
            if (pendingCharset != null && !pendingCharset.equals(charset, ignoreCase = true)) flush()
            pendingCharset = charset
            pending.write(bytes)
            last = m.range.last + 1
        }
        flush()
        sb.append(decode8bit(raw.substring(last)))
        return sb.toString()
    }

    private fun decode8bit(s: String): String =
        if (s.all { it.code < 0x80 }) s else decodeText(s.toByteArray(Charsets.ISO_8859_1), null)

    // ================================================================ transfer encodings

    private fun decodeTransfer(body: String, encoding: String?): ByteArray =
        when (encoding?.trim()?.lowercase()) {
            "base64" -> base64Decode(body)
            "quoted-printable" -> qpDecode(body)
            else -> body.toByteArray(Charsets.ISO_8859_1)
        }

    internal fun base64Decode(s: String): ByteArray {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') sb.append(c)
            else if (c == '-') sb.append('+') // tolerate URL-safe alphabet
            else if (c == '_') sb.append('/')
        }
        if (sb.length % 4 == 1) sb.setLength(sb.length - 1)
        while (sb.length % 4 != 0) sb.append('=')
        return try { Base64.getDecoder().decode(sb.toString()) } catch (e: IllegalArgumentException) { ByteArray(0) }
    }

    internal fun qpDecode(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=') {
                when {
                    s.startsWith("\r\n", i + 1) -> { i += 3; continue } // soft line break
                    s.startsWith("\n", i + 1) -> { i += 2; continue }
                    hex(s.getOrNull(i + 1)) >= 0 && hex(s.getOrNull(i + 2)) >= 0 -> {
                        out.write(hex(s[i + 1]) * 16 + hex(s[i + 2])); i += 3; continue
                    }
                }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    private fun qDecode(s: String): ByteArray = qpDecode(s.replace('_', ' '))

    private fun percentDecode(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && hex(s.getOrNull(i + 1)) >= 0 && hex(s.getOrNull(i + 2)) >= 0) {
                out.write(hex(s[i + 1]) * 16 + hex(s[i + 2])); i += 3
            } else {
                out.write(c.code and 0xFF); i++
            }
        }
        return out.toByteArray()
    }

    private fun hex(c: Char?): Int = when (c) {
        null -> -1
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    // ================================================================ text decoding

    private fun isZip(b: ByteArray) =
        b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
            ((b[2].toInt() == 3 && b[3].toInt() == 4) || (b[2].toInt() == 5 && b[3].toInt() == 6))

    private fun hasUtf16Bom(d: ByteArray) = d.size >= 2 &&
        ((d[0] == 0xFF.toByte() && d[1] == 0xFE.toByte()) || (d[0] == 0xFE.toByte() && d[1] == 0xFF.toByte()))

    /** True for pictures, PDFs, archives …: a NUL byte or more than 1 % control characters. */
    private fun isProbablyBinary(data: ByteArray): Boolean {
        if (hasUtf16Bom(data)) return false
        val n = minOf(data.size, 64 * 1024)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val c = data[i].toInt() and 0xFF
            if (c == 0) return true
            if (c < 0x20 && c != 9 && c != 10 && c != 12 && c != 13 && c != 27) control++
        }
        return control * 100 > n
    }

    private val koreanCharset: Charset by lazy {
        for (name in listOf("x-windows-949", "windows-949", "MS949", "EUC-KR")) {
            try {
                if (Charset.isSupported(name)) return@lazy Charset.forName(name)
            } catch (e: Exception) { /* try next */ }
        }
        Charsets.ISO_8859_1
    }

    /**
     * Bytes → text. BOM first; 7-bit data only needs its declared charset for 7-bit encodings
     * (ISO-2022-JP …); otherwise valid UTF-8 wins (mislabelled mails are common), then the
     * declared charset, then CP949 / EUC-KR.
     */
    internal fun decodeText(data: ByteArray, declared: String?): String {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            return String(data, 3, data.size - 3, Charsets.UTF_8)
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
            return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        }
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        }
        val declaredCharset = charsetOrNull(declared)
        if (data.none { it < 0 }) { // 7-bit only
            val sevenBitEncoding = declared?.trim()?.lowercase()?.startsWith("iso-2022") == true
            if (sevenBitEncoding && declaredCharset != null) strictDecode(data, declaredCharset)?.let { return it }
            return String(data, Charsets.ISO_8859_1)
        }
        strictDecode(data, Charsets.UTF_8)?.let { return it.removePrefix("﻿") }
        if (declaredCharset != null && declaredCharset != Charsets.UTF_8) {
            strictDecode(data, declaredCharset)?.let { return it }
        }
        strictDecode(data, koreanCharset)?.let { return it }
        // UTF-8 with a stray bad byte or two: keep it as UTF-8 rather than garbling everything.
        val lenientUtf8 = String(data, Charsets.UTF_8)
        val bad = lenientUtf8.count { it == '�' }
        if (declaredCharset == null && bad <= maxOf(3, lenientUtf8.length / 1000)) return lenientUtf8.removePrefix("﻿")
        return String(data, declaredCharset ?: koreanCharset)
    }

    private fun decodeBytes(data: ByteArray, charsetName: String): String {
        val cs = charsetOrNull(charsetName)
        if (cs != null) strictDecode(data, cs)?.let { return it }
        return decodeText(data, charsetName)
    }

    private fun charsetOrNull(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        val n = name.trim().lowercase()
        if (n == "ks_c_5601-1987" || n == "ks_c_5601" || n == "euc-kr" || n == "cp949" || n == "ms949") {
            return koreanCharset // Outlook labels Korean text this way; CP949 is a superset of EUC-KR
        }
        return try { Charset.forName(name.trim()) } catch (e: Exception) { null }
    }

    private fun strictDecode(data: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun normalize(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')

    private fun latin1(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    internal fun htmlToText(html: String): String {
        var s = html.replace(Regex("""(?is)<(script|style|head)\b[^>]*>.*?</\1\s*>"""), "")
        s = s.replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|tr|li|h[1-6]|blockquote)\s*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
        s = s.replace(Regex("""&#(\d{1,7});""")) { m ->
            m.groupValues[1].toInt().takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
        }.replace(Regex("""(?i)&#x([0-9a-f]{1,6});""")) { m ->
            m.groupValues[1].toInt(16).takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
        }
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        s = normalize(s)
        return s.replace(Regex("""[ \t ]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }
}
