package com.drkkr.strategos.domain.files

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** What kind of file the user shared. [badge] is the three-letter mark on its chip. */
enum class FileType(val label: String, val badge: String) {
    CSV("Spreadsheet", "CSV"), EXCEL("Excel workbook", "XLS"), WORD("Word document", "DOC"),
    SLIDES("PowerPoint deck", "PPT"), TEXT("Text file", "TXT")
}

data class Sheet(val name: String, val rows: List<List<String>>)
data class Para(val text: String, val heading: Boolean = false)
data class Slide(val number: Int, val title: String, val lines: List<String>, val notes: String)

/** A file's content with the container stripped away: tables, running text, or slides. */
sealed class RawFile {
    data class Tables(val sheets: List<Sheet>) : RawFile()
    data class Prose(val paras: List<Para>) : RawFile()
    data class Deck(val slides: List<Slide>) : RawFile()
}

sealed class ReadResult {
    data class Ok(val type: FileType, val raw: RawFile) : ReadResult()
    /** [reason] is a full sentence for the user. */
    data class Unreadable(val reason: String) : ReadResult()
}

/**
 * Opens CSV, Excel (.xlsx), Word (.docx), PowerPoint (.pptx) and plain text with nothing but the JDK: the Office
 * formats are zip archives of XML, read here with a SAX parser. Pure JVM, so it runs on the device and in unit tests.
 * Every size is capped, so a huge or hostile file ends in [ReadResult.Unreadable] or a truncated read, never a crash.
 */
object FileReader {
    const val MAX_BYTES = 15 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 40 * 1024 * 1024
    private const val MAX_ROWS = 50_000
    private const val MAX_COLS = 200
    private const val MAX_PARAS = 20_000
    private const val MAX_SLIDES = 300

    private const val OLD_OFFICE = "Older Office files (.xls, .doc, .ppt) can’t be read. Save it as .xlsx, .docx or .pptx and try again."
    private const val PDF = "PDFs can’t be read yet. Export it as Word or text and try again."
    private const val UNKNOWN = "This file type can’t be read. Try a CSV, Excel, Word, PowerPoint or text file."
    private const val BROKEN = "This file couldn’t be opened. It may be damaged or password-protected."
    private const val EMPTY = "This file is empty."
    private const val TOO_BIG = "This file is too large. The limit is 15 MB."

    fun read(name: String, bytes: ByteArray): ReadResult {
        if (bytes.isEmpty()) return ReadResult.Unreadable(EMPTY)
        if (bytes.size > MAX_BYTES) return ReadResult.Unreadable(TOO_BIG)
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when {
                startsWith(bytes, 0x50, 0x4B) -> office(bytes)
                startsWith(bytes, 0xD0, 0xCF, 0x11, 0xE0) -> ReadResult.Unreadable(OLD_OFFICE)
                startsWith(bytes, 0x25, 0x50, 0x44, 0x46) -> ReadResult.Unreadable(PDF)
                else -> {
                    val text = decode(bytes) ?: return ReadResult.Unreadable(UNKNOWN)
                    if (text.isBlank()) return ReadResult.Unreadable(EMPTY)
                    if (ext == "csv" || ext == "tsv") ReadResult.Ok(FileType.CSV, RawFile.Tables(listOf(Sheet("", Csv.parse(text, MAX_ROWS, MAX_COLS)))))
                    else ReadResult.Ok(FileType.TEXT, RawFile.Prose(text.lineSequence().take(MAX_PARAS).map { line ->
                        val t = line.trimEnd()
                        if (t.startsWith("#")) Para(t.trimStart('#').trim(), heading = true) else Para(t)
                    }.toList()))
                }
            }
        } catch (e: Exception) {
            ReadResult.Unreadable(BROKEN)
        } catch (e: OutOfMemoryError) {
            ReadResult.Unreadable(TOO_BIG)
        }
    }

    private fun startsWith(b: ByteArray, vararg magic: Int) = b.size >= magic.size && magic.indices.all { (b[it].toInt() and 0xFF) == magic[it] }

    /** Text with its byte-order mark honoured; null when the bytes look binary. */
    private fun decode(b: ByteArray): String? {
        val text = when {
            startsWith(b, 0xEF, 0xBB, 0xBF) -> String(b, 3, b.size - 3, Charsets.UTF_8)
            startsWith(b, 0xFF, 0xFE) -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            startsWith(b, 0xFE, 0xFF) -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            else -> String(b, Charsets.UTF_8)
        }
        val sample = text.take(4_000)
        val odd = sample.count { (it < ' ' && it != '\n' && it != '\r' && it != '\t') || it == '�' }
        return if (odd * 20 > sample.length) null else text
    }

    // ---- Office (zip of XML) ----

    private fun office(bytes: ByteArray): ReadResult {
        val wanted = Regex("^(xl/(workbook\\.xml|_rels/workbook\\.xml\\.rels|sharedStrings\\.xml|styles\\.xml|worksheets/[^/]+\\.xml)|word/document\\.xml|" +
            "ppt/(presentation\\.xml|_rels/presentation\\.xml\\.rels|slides/[^/]+\\.xml|slides/_rels/[^/]+\\.rels|notesSlides/[^/]+\\.xml))$")
        val parts = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || !wanted.matches(e.name)) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buf); if (n < 0) break
                    out.write(buf, 0, n); total += n
                    if (out.size() > MAX_ENTRY_BYTES || total > 3L * MAX_ENTRY_BYTES) return ReadResult.Unreadable(TOO_BIG)
                }
                parts[e.name] = out.toByteArray()
            }
        }
        return when {
            "xl/workbook.xml" in parts -> ReadResult.Ok(FileType.EXCEL, RawFile.Tables(excel(parts)))
            "word/document.xml" in parts -> ReadResult.Ok(FileType.WORD, RawFile.Prose(word(parts.getValue("word/document.xml"))))
            "ppt/presentation.xml" in parts -> ReadResult.Ok(FileType.SLIDES, RawFile.Deck(deck(parts)))
            else -> ReadResult.Unreadable(UNKNOWN)
        }
    }

    private val factory: SAXParserFactory by lazy {
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs and no external entities: an Office file never needs them, and they are how XML attacks work.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
    }

    private abstract class Handler : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
        protected fun name(local: String?, q: String?): String = if (!local.isNullOrEmpty()) local else q.orEmpty().substringAfter(':')
        /** A plain (un-prefixed) attribute. */
        protected fun Attributes.plain(n: String): String? {
            for (i in 0 until length) if (getQName(i) == n || (getLocalName(i) == n && getURI(i).isNullOrEmpty())) return getValue(i)
            return null
        }
        /** The relationship id (`r:id`), whatever prefix the file uses. */
        protected fun Attributes.rid(): String? {
            for (i in 0 until length) if (getQName(i) == "r:id" || (getLocalName(i) == "id" && getURI(i).orEmpty().contains("relationships"))) return getValue(i)
            return null
        }
    }

    private fun parse(bytes: ByteArray, h: DefaultHandler) = factory.newSAXParser().parse(ByteArrayInputStream(bytes), h)

    /** Relationship id → target path, resolved against [base] ("xl/", "ppt/slides/"). */
    private fun rels(bytes: ByteArray?, base: String): Map<String, String> {
        if (bytes == null) return emptyMap()
        val out = HashMap<String, String>()
        parse(bytes, object : Handler() {
            override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) {
                if (name(local, q) != "Relationship") return
                val id = a.plain("Id") ?: return
                val target = a.plain("Target") ?: return
                out[id] = resolve(base, target)
            }
        })
        return out
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val segs = ArrayList(base.trimEnd('/').split('/').filter { it.isNotEmpty() })
        for (s in target.split('/')) when (s) { "", "." -> Unit; ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.lastIndex); else -> segs += s }
        return segs.joinToString("/")
    }

    // ---- Excel ----

    private fun excel(parts: Map<String, ByteArray>): List<Sheet> {
        val shared = ArrayList<String>()
        parts["xl/sharedStrings.xml"]?.let { b ->
            parse(b, object : Handler() {
                val sb = StringBuilder(); var inT = false; var phonetic = 0
                override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) {
                    when (name(local, q)) { "si" -> sb.setLength(0); "rPh" -> phonetic++; "t" -> inT = phonetic == 0 }
                }
                override fun endElement(uri: String?, local: String?, q: String?) {
                    when (name(local, q)) { "si" -> shared += sb.toString(); "rPh" -> phonetic--; "t" -> inT = false }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) sb.append(ch, start, length) }
            })
        }
        // Which cell styles are dates: Excel stores a date as a plain number and only the style says otherwise.
        val dateStyles = ArrayList<Boolean>()
        parts["xl/styles.xml"]?.let { b ->
            val custom = HashMap<Int, String>()
            parse(b, object : Handler() {
                var inXfs = false
                override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) {
                    when (name(local, q)) {
                        "numFmt" -> a.plain("numFmtId")?.toIntOrNull()?.let { custom[it] = a.plain("formatCode").orEmpty() }
                        "cellXfs" -> inXfs = true
                        "xf" -> if (inXfs) dateStyles += isDateFormat(a.plain("numFmtId")?.toIntOrNull() ?: 0, custom)
                    }
                }
                override fun endElement(uri: String?, local: String?, q: String?) { if (name(local, q) == "cellXfs") inXfs = false }
            })
        }
        val names = ArrayList<Pair<String, String>>()   // sheet name, relationship id
        var date1904 = false
        parse(parts.getValue("xl/workbook.xml"), object : Handler() {
            override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) {
                when (name(local, q)) {
                    "sheet" -> names += (a.plain("name").orEmpty()) to (a.rid().orEmpty())
                    "workbookPr" -> date1904 = a.plain("date1904").let { it == "1" || it == "true" }
                }
            }
        })
        val targets = rels(parts["xl/_rels/workbook.xml.rels"], "xl/")
        val files = parts.keys.filter { it.startsWith("xl/worksheets/") }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        return names.mapIndexedNotNull { i, (name, rid) ->
            val path = targets[rid]?.takeIf { it in parts } ?: files.getOrNull(i) ?: return@mapIndexedNotNull null
            Sheet(name, sheetRows(parts.getValue(path), shared, dateStyles, date1904))
        }.filter { it.rows.isNotEmpty() }
    }

    private fun isDateFormat(id: Int, custom: Map<Int, String>): Boolean {
        custom[id]?.let { code ->
            val bare = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*]|\\\\."), "")
            return bare.any { it in "dDyYhHsS" } || Regex("(?i)(^|[^a-z])m{1,5}([^a-z]|$)").containsMatchIn(bare)
        }
        return id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58
    }

    private fun sheetRows(bytes: ByteArray, shared: List<String>, dateStyles: List<Boolean>, date1904: Boolean): List<List<String>> {
        val rows = ArrayList<List<String>>()
        parse(bytes, object : Handler() {
            var row = ArrayList<String>(); var col = 0; var type = ""; var style = 0
            val v = StringBuilder(); var inV = false; var inIs = false
            override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) {
                when (name(local, q)) {
                    "row" -> { row = ArrayList(); col = 0 }
                    "c" -> {
                        a.plain("r")?.let { ref -> col = columnIndex(ref) }
                        type = a.plain("t").orEmpty(); style = a.plain("s")?.toIntOrNull() ?: 0; v.setLength(0)
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inV = true
                }
            }
            override fun endElement(uri: String?, local: String?, q: String?) {
                when (name(local, q)) {
                    "v" -> inV = false
                    "t" -> if (inIs) inV = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = v.toString()
                        val value = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> number(raw.trim(), dateStyles.getOrElse(style) { false }, date1904)
                        }
                        if (col < MAX_COLS && rows.size < MAX_ROWS) {
                            while (row.size < col) row += ""
                            row += value.trim()
                        }
                        col++
                    }
                    "row" -> {
                        while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.lastIndex)
                        if (row.isNotEmpty() && rows.size < MAX_ROWS) rows += row
                    }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inV) v.append(ch, start, length) }
        })
        return rows
    }

    /** "B3" → 1. */
    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch !in 'A'..'Z' && ch !in 'a'..'z') break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return (n - 1).coerceAtLeast(0)
    }

    private fun number(raw: String, isDate: Boolean, date1904: Boolean): String {
        val d = raw.toDoubleOrNull() ?: return raw
        if (isDate && d >= 0 && d < 2_958_466) return serialDate(d, date1904)
        // Excel writes floating-point noise (0.30000000000000004); twelve significant digits is what its own grid shows.
        return runCatching { raw.toBigDecimal().round(MathContext(12)).stripTrailingZeros().toPlainString() }.getOrDefault(raw)
    }

    /** An Excel day count → "2024-03-12" or "2024-03-12 21:41" (or just "21:41" for a time of day). */
    internal fun serialDate(serial: Double, date1904: Boolean = false): String {
        val days = Math.floor(serial).toLong()
        val minutes = Math.round((serial - days) * 24 * 60).toInt()
        val time = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)
        if (days == 0L && !date1904) return time
        val base: LocalDateTime = (if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)).atStartOfDay()
        val at = base.plusDays(days).plusMinutes(minutes.toLong())
        return if (minutes == 0) at.toLocalDate().toString() else "${at.toLocalDate()} " + "%02d:%02d".format(at.hour, at.minute)
    }

    // ---- Word ----

    private fun word(bytes: ByteArray): List<Para> {
        val out = ArrayList<Para>()
        parse(bytes, object : Handler() {
            val p = StringBuilder(); var heading = false; var inT = false
            var tables = 0; val cells = ArrayList<String>(); val cell = StringBuilder()
            override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) {
                when (name(local, q)) {
                    "tbl" -> tables++
                    "tr" -> if (tables == 1) cells.clear()
                    "tc" -> if (tables == 1) cell.setLength(0)
                    "p" -> { p.setLength(0); heading = false }
                    "pStyle" -> a.getValue(a.indexOfLocal("val")).orEmpty().let { s -> if (s.startsWith("Heading", true) || s.equals("Title", true)) heading = true }
                    "t" -> inT = true
                    "tab" -> p.append('\t')
                    "br", "cr" -> p.append('\n')
                }
            }
            override fun endElement(uri: String?, local: String?, q: String?) {
                when (name(local, q)) {
                    "t" -> inT = false
                    "p" -> {
                        val text = p.toString().trim()
                        if (tables > 0) { if (text.isNotEmpty()) { if (cell.isNotEmpty()) cell.append(' '); cell.append(text) } }
                        else if (out.size < MAX_PARAS) out += Para(text, heading && text.isNotEmpty())
                    }
                    "tc" -> if (tables == 1) cells += cell.toString()
                    "tr" -> if (tables == 1 && cells.any { it.isNotBlank() } && out.size < MAX_PARAS) out += Para(cells.joinToString(" | "))
                    "tbl" -> tables--
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) p.append(ch, start, length) }
        })
        return out
    }

    private fun Attributes.indexOfLocal(n: String): Int {
        for (i in 0 until length) if (getLocalName(i) == n || getQName(i).substringAfter(':') == n) return i
        return -1
    }

    // ---- PowerPoint ----

    private fun deck(parts: Map<String, ByteArray>): List<Slide> {
        val order = ArrayList<String>()
        parse(parts.getValue("ppt/presentation.xml"), object : Handler() {
            override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) { if (name(local, q) == "sldId") a.rid()?.let { order += it } }
        })
        val targets = rels(parts["ppt/_rels/presentation.xml.rels"], "ppt/")
        val slideFiles = parts.keys.filter { it.matches(Regex("ppt/slides/[^/]+\\.xml")) }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val paths = order.mapNotNull { targets[it]?.takeIf { p -> p in parts } }.ifEmpty { slideFiles }
        return paths.take(MAX_SLIDES).mapIndexed { i, path ->
            val (title, lines) = slideText(parts.getValue(path))
            val file = path.substringAfterLast('/')
            val notesPath = rels(parts["ppt/slides/_rels/$file.rels"], "ppt/slides/").values.firstOrNull { it.startsWith("ppt/notesSlides/") }
            val notes = notesPath?.let { parts[it] }?.let { slideText(it).second.joinToString(" ") }.orEmpty()
            Slide(i + 1, title, lines, notes)
        }
    }

    /** A slide's title and its other lines of text, in reading order. Page numbers, dates and footers are left out. */
    private fun slideText(bytes: ByteArray): Pair<String, List<String>> {
        var title = ""
        val lines = ArrayList<String>()
        parse(bytes, object : Handler() {
            var role = ""; var inShape = 0; val p = StringBuilder(); var inT = false
            override fun startElement(uri: String?, local: String?, q: String?, a: Attributes) {
                when (name(local, q)) {
                    "sp" -> { inShape++; role = "" }
                    "ph" -> role = a.plain("type").orEmpty()
                    "p" -> p.setLength(0)
                    "t" -> inT = true
                    "br" -> p.append(' ')
                }
            }
            override fun endElement(uri: String?, local: String?, q: String?) {
                when (name(local, q)) {
                    "t" -> inT = false
                    "p" -> {
                        val text = p.toString().trim()
                        if (text.isEmpty() || role in SKIP_ROLES) return
                        if ((role == "title" || role == "ctrTitle") && title.isEmpty()) title = text else lines += text
                    }
                    "sp" -> { inShape--; role = "" }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) p.append(ch, start, length) }
        })
        return title to lines
    }

    private val SKIP_ROLES = setOf("sldNum", "dt", "ftr", "hdr", "sldImg")
}

/** A small CSV reader: quoted fields, doubled quotes, any line ending, and a delimiter sniffed from the first line. */
object Csv {
    fun parse(text: String, maxRows: Int = Int.MAX_VALUE, maxCols: Int = Int.MAX_VALUE): List<List<String>> {
        val d = delimiter(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { if (row.size < maxCols) row += field.toString().trim(); field.setLength(0) }
        fun endRow() {
            endField()
            while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.lastIndex)
            if (row.isNotEmpty()) rows += row
            row = ArrayList()
        }
        while (i < text.length && rows.size < maxRows) {
            val ch = text[i]
            when {
                quoted -> if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false } else field.append(ch)
                ch == '"' && field.isBlank() -> { field.setLength(0); quoted = true }
                ch == d -> endField()
                ch == '\r' -> { endRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                ch == '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (rows.size < maxRows && (field.isNotEmpty() || row.isNotEmpty())) endRow()
        return rows
    }

    /** The separator that splits the first line into the most fields, counted outside quotes. */
    private fun delimiter(text: String): Char {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var best = ','; var bestCount = 0
        for (c in charArrayOf(',', ';', '\t', '|')) {
            var n = 0; var q = false
            for (ch in line) { if (ch == '"') q = !q else if (ch == c && !q) n++ }
            if (n > bestCount) { best = c; bestCount = n }
        }
        return best
    }
}
