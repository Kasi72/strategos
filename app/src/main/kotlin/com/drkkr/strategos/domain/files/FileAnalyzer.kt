package com.drkkr.strategos.domain.files

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What the content turned out to be, whatever container it came in. */
enum class FileShape(val label: String) {
    CONVERSATION("Conversation over time"), TABLE("Table"), DOCUMENT("Document"), DECK("Slides")
}

/**
 * A file made ready for the model: [facts] is the one-line size of it ("412 messages · 3 people · 2 Mar – 9 Nov 2024")
 * and [body] is the compact text the model reads in place of the file.
 */
data class FileBrief(val name: String, val type: FileType, val shape: FileShape, val facts: String, val body: String)

sealed class IngestResult {
    data class Ready(val brief: FileBrief) : IngestResult()
    data class Failed(val reason: String) : IngestResult()
}

/** The whole path from bytes to a brief: open the file, work out what it holds, and prepare it for the model. */
object FileIngest {
    /** About 1,200 tokens: enough for the shape of a long file, small enough to sit beside the rest of the request. */
    const val BRIEF_CHARS = 4_800
    private const val NOTHING = "There is no text in this file to read."

    fun prepare(name: String, bytes: ByteArray, budget: Int = BRIEF_CHARS): IngestResult =
        when (val r = FileReader.read(name, bytes)) {
            is ReadResult.Unreadable -> IngestResult.Failed(r.reason)
            is ReadResult.Ok -> FileAnalyzer.brief(name, r.type, r.raw, budget)?.let { IngestResult.Ready(it) } ?: IngestResult.Failed(NOTHING)
        }
}

/** One thing said or logged, in order. [at] is null when the file gives no date for it. */
data class Event(val at: LocalDateTime?, val who: String, val text: String)

/**
 * Works out what a file holds and writes the brief. The point is to hand the model the *shape* first (who, how much,
 * over what time, how it unfolded) and then as much of the actual content as fits, weighted to the most recent.
 * Everything here is counted from the file; nothing is guessed, and anything cut is said to be cut.
 */
object FileAnalyzer {

    fun brief(name: String, type: FileType, raw: RawFile, budget: Int = FileIngest.BRIEF_CHARS): FileBrief? {
        val built: Built = when (raw) {
            is RawFile.Tables -> {
                val sheets = raw.sheets.filter { it.rows.isNotEmpty() }
                if (sheets.isEmpty()) return null
                val main = sheets.maxByOrNull { it.rows.size }!!
                tableEvents(main)?.let { events ->
                    val others = sheets.filter { it !== main }.map { "\"${it.name}\"" }
                    conversation(events, budget, if (others.isEmpty()) "" else "OTHER SHEETS NOT SHOWN: ${others.joinToString(", ")}\n")
                } ?: tables(sheets, budget)
            }
            is RawFile.Prose -> {
                val paras = raw.paras.map { it.copy(text = tidy(it.text)) }.filter { it.text.isNotBlank() }
                if (paras.isEmpty()) return null
                proseEvents(paras.map { it.text })?.let { conversation(it, budget, "") } ?: document(paras, budget)
            }
            is RawFile.Deck -> {
                if (raw.slides.none { it.title.isNotBlank() || it.lines.isNotEmpty() || it.notes.isNotBlank() }) return null
                deck(raw.slides, budget)
            }
        }
        return FileBrief(name, type, built.shape, built.facts, built.body.trim())
    }

    private class Built(val shape: FileShape, val facts: String, val body: String)

    // ---- Conversations and logs ----

    private val DATE_WORDS = setOf("date", "time", "timestamp", "datetime", "when", "sent", "created", "day", "received")
    private val STRONG_WHO = setOf("from", "sender", "author", "speaker", "participant")
    private val WEAK_WHO = setOf("name", "user", "username", "who", "person", "by", "contact")
    private val TEXT_WORDS = setOf("message", "text", "body", "content", "comment", "comments", "note", "notes", "said", "transcript", "remarks", "msg", "update", "description")

    /** A sheet is a log when it has a text column plus either a column of real dates or a clear "from" column. */
    internal fun tableEvents(sheet: Sheet): List<Event>? {
        val header = sheet.rows.first().map { h -> h.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet() }
        val data = sheet.rows.drop(1)
        if (data.size < 3) return null
        fun col(words: Set<String>, not: Set<Int> = emptySet()) = header.indices.firstOrNull { it !in not && header[it].any { w -> w in words } }
        val textCol = col(TEXT_WORDS) ?: return null
        val strong = col(STRONG_WHO, setOf(textCol))
        val sample = data.take(200)
        val dayFirst = Stamps.dayFirst(sample.asSequence().flatMap { it.asSequence() })
        val dateCols = header.indices.filter { i ->
            i != textCol && header[i].any { it in DATE_WORDS } &&
                sample.count { r -> r.getOrNull(i)?.let { it.length <= 40 && (Stamps.parse(it, dayFirst) != null || Stamps.time(it) != null) } == true } * 10 >= sample.size * 6
        }.take(2)
        val dated = dateCols.isNotEmpty() && sample.count { r -> Stamps.parse(dateCols.joinToString(" ") { r.getOrNull(it).orEmpty() }, dayFirst) != null } * 10 >= sample.size * 6
        if (!dated && strong == null) return null
        val whoCol = strong ?: col(WEAK_WHO, setOf(textCol) + dateCols)
        return data.mapNotNull { r ->
            val text = tidy(r.getOrNull(textCol).orEmpty())
            if (text.isEmpty()) return@mapNotNull null
            val at = if (dated) Stamps.parse(dateCols.joinToString(" ") { r.getOrNull(it).orEmpty() }, dayFirst) else null
            Event(at, whoCol?.let { tidy(r.getOrNull(it).orEmpty()) }.orEmpty(), text)
        }.takeIf { it.size >= 3 }
    }

    // "12/03/24, 9:41 pm - Ravi: hello" and "[12/03/24, 21:41:02] Ravi: hello" (chat exports), then the ISO form.
    private val CHAT_LINE = Regex("""^\[?(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}),?\s+(\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s*[APap]\.?[Mm]\.?)?)]?\s*(?:[-–]\s*)?([^:]{1,40}?):\s(.*)$""")
    private val ISO_LINE = Regex("""^\[?(\d{4}-\d{2}-\d{2})[ T](\d{1,2}:\d{2}(?::\d{2})?)[^]\s]*]?\s*(?:[-–]\s*)?([^:]{1,40}?):\s(.*)$""")
    private val STAMP_ONLY = Regex("""^\[?(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}|\d{4}-\d{2}-\d{2}),?[ T]\d{1,2}[:.]\d{2}.*""")
    // "Ravi: hello" or "00:01:23 Ravi: hello" (meeting transcripts).
    private val SPEAKER_LINE = Regex("""^(?:\[?(\d{1,2}:\d{2}(?::\d{2})?)]?\s+)?(\p{Lu}[\p{L}\d.'’\- ]{0,30}?):\s+(\S.*)$""")

    /** Running text is a conversation when most of its lines are "when, who: what" or "who: what". */
    internal fun proseEvents(lines: List<String>): List<Event>? {
        if (lines.size < 4) return null
        val stamped = lines.count { CHAT_LINE.matches(it) || ISO_LINE.matches(it) }
        if (stamped >= 3 && stamped * 3 >= lines.size) {
            val dayFirst = Stamps.dayFirst(lines.asSequence().mapNotNull { CHAT_LINE.find(it)?.groupValues?.get(1) })
            val out = ArrayList<Event>()
            for (line in lines) {
                val m = CHAT_LINE.find(line) ?: ISO_LINE.find(line)
                when {
                    m != null -> {
                        val (date, time, who, text) = m.destructured
                        out += Event(Stamps.parse("$date ${time.replace('.', ':')}", dayFirst), who.trim(), text.trim())
                    }
                    STAMP_ONLY.matches(line) -> Unit   // "Ravi added Meera" and other system lines
                    out.isNotEmpty() -> out[out.lastIndex] = out.last().let { it.copy(text = it.text + " " + line) }
                }
            }
            return out.filter { it.text.isNotBlank() }.takeIf { it.size >= 3 }
        }
        val spoken = lines.mapNotNull { SPEAKER_LINE.find(it) }
        val counts = spoken.groupingBy { it.groupValues[2].trim() }.eachCount()
        val regulars = counts.count { it.value >= 2 }
        if (spoken.size < 6 || spoken.size * 10 < lines.size * 6 || counts.size !in 2..12 || regulars < 2) return null
        val out = ArrayList<Event>()
        var day: LocalDate? = null
        for (line in lines) {
            val m = SPEAKER_LINE.find(line)
            if (m == null) {
                val heading = if (line.length <= 40) Stamps.parse(line)?.toLocalDate() else null
                if (heading != null) day = heading
                else if (out.isNotEmpty()) out[out.lastIndex] = out.last().let { it.copy(text = it.text + " " + line) }
                continue
            }
            val at = day?.let { d -> Stamps.time(m.groupValues[1])?.let { d.atTime(it.first, it.second) } ?: d.atStartOfDay() }
            out += Event(at, m.groupValues[2].trim(), m.groupValues[3].trim())
        }
        return out
    }

    private const val LINE_CHARS = 220

    private fun conversation(all: List<Event>, budget: Int, tail: String): Built {
        val allTimed = all.all { it.at != null }
        val events = if (allTimed) all.sortedBy { it.at } else all
        val timed = events.filter { it.at != null }
        val people = events.filter { it.who.isNotBlank() }.groupingBy { it.who }.eachCount().entries.sortedByDescending { it.value }
        val first = timed.firstOrNull()?.at; val last = timed.lastOrNull()?.at
        val sb = StringBuilder()
        sb.append("WHAT THIS IS: a conversation or log that unfolds over time, ").append(count(events.size, "entry", "entries")).append(".\n")
        if (people.isNotEmpty()) {
            sb.append("WHO: ").append(people.take(8).joinToString(", ") { "${it.key} (${it.value})" })
            if (people.size > 8) sb.append(", and ${people.size - 8} more")
            sb.append('\n')
        }
        if (first != null && last != null) {
            sb.append("WHEN: ").append(Stamps.show(first.toLocalDate())).append(" to ").append(Stamps.show(last.toLocalDate()))
                .append(" (").append(span(first, last)).append(")\n")
            if (allTimed) {
                phases(timed)?.let { sb.append("HOW IT UNFOLDED:\n").append(it) }
                silences(timed)?.let { sb.append("LONG SILENCES:\n").append(it) }
            }
        }
        sb.append(tail)
        val lines = events.map { e ->
            val stamp = e.at?.let { "${it.toLocalDate()} " + "%02d:%02d".format(it.hour, it.minute) + " " }.orEmpty()
            (stamp + (if (e.who.isNotBlank()) "${e.who}: " else "") + e.text).let { if (it.length > LINE_CHARS) it.take(LINE_CHARS - 1) + "…" else it }
        }
        val room = (budget - sb.length - 120).coerceAtLeast(400)
        val picked = pick(lines, room)
        sb.append(if (picked.size == lines.size) "EVERY ENTRY, oldest first:\n" else "ENTRIES, oldest first (${picked.size} of ${lines.size} shown; the newest are kept in full, older ones are sampled):\n")
        var prev = -1
        for (i in picked) {
            if (i - prev > 1) sb.append("… ").append(count(i - prev - 1, "entry", "entries")).append(" not shown …\n")
            sb.append(lines[i]).append('\n'); prev = i
        }
        if (prev < lines.lastIndex) sb.append("… ").append(count(lines.lastIndex - prev, "entry", "entries")).append(" not shown …\n")
        val facts = listOfNotNull(
            count(events.size, "message", "messages"),
            people.size.takeIf { it > 0 }?.let { count(it, "person", "people") },
            if (first != null && last != null) Stamps.range(first.toLocalDate(), last.toLocalDate()) else null
        ).joinToString(" · ")
        return Built(FileShape.CONVERSATION, facts, sb.toString())
    }

    /**
     * Which entries to show when they don't all fit: the opening (how it started), an even sample of the middle
     * (how it moved) and the most recent stretch in full (where it stands now, which matters most for the next move).
     */
    internal fun pick(lines: List<String>, room: Int): List<Int> {
        if (lines.sumOf { it.length + 1 } <= room) return lines.indices.toList()
        var n = (room / (lines.sumOf { it.length + 1 }.toDouble() / lines.size)).toInt().coerceIn(3, lines.size)
        while (true) {
            val head = (n * 0.2).toInt().coerceAtLeast(1)
            val recent = (n * 0.5).toInt().coerceAtLeast(1)
            val mid = (n - head - recent).coerceAtLeast(0)
            val set = sortedSetOf<Int>()
            set += 0 until head
            set += (lines.size - recent) until lines.size
            val lo = head; val hi = lines.size - recent
            if (mid > 0 && hi > lo) for (k in 0 until mid) set += lo + ((k + 0.5) * (hi - lo) / mid).toInt()
            val used = set.sumOf { lines[it].length + 1 } + 30 * 3
            if (used <= room || n <= 3) return set.toList()
            n = (n * 0.85).toInt().coerceAtLeast(3)
        }
    }

    private fun span(a: LocalDateTime, b: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate())
        return when {
            days == 0L -> "one day"
            days < 14 -> count(days.toInt() + 1, "day", "days")
            days < 120 -> "about ${count(Math.round(days / 7.0).toInt(), "week", "weeks")}"
            days < 730 -> "about ${count(Math.round(days / 30.4).toInt(), "month", "months")}"
            else -> "about ${count(Math.round(days / 365.25).toInt(), "year", "years")}"
        }
    }

    /** Activity per day, week, month or year (whichever gives at most twelve lines), with who was talking. */
    private fun phases(timed: List<Event>): String? {
        val days = ChronoUnit.DAYS.between(timed.first().at!!.toLocalDate(), timed.last().at!!.toLocalDate())
        if (days == 0L) return null
        val key: (LocalDate) -> Pair<LocalDate, String> = when {
            days < 12 -> { d -> d to Stamps.show(d) }
            days < 12 * 7 -> { d -> d.minusDays((d.dayOfWeek.value - 1).toLong()).let { it to "Week of ${Stamps.show(it)}" } }
            days < 12 * 30 -> { d -> d.withDayOfMonth(1).let { it to "${Stamps.month(it.monthValue)} ${it.year}" } }
            else -> { d -> d.withDayOfYear(1).let { it to "${it.year}" } }
        }
        val groups = timed.groupBy { key(it.at!!.toLocalDate()) }.entries.sortedBy { it.key.first }
        if (groups.size < 2) return null
        return groups.takeLast(12).joinToString("") { (k, es) ->
            val who = es.filter { it.who.isNotBlank() }.groupingBy { it.who }.eachCount().entries.sortedByDescending { it.value }.take(3)
            "- ${k.second}: ${es.size}" + (if (who.isNotEmpty()) " (" + who.joinToString(", ") { "${it.key} ${it.value}" } + ")" else "") + "\n"
        }
    }

    /** The three longest gaps of three days or more, with who went quiet and who spoke first afterwards. */
    private fun silences(timed: List<Event>): String? {
        val gaps = timed.zipWithNext().mapNotNull { (a, b) ->
            val d = Duration.between(a.at, b.at).toDays()
            if (d >= 3) Triple(d, a, b) else null
        }.sortedByDescending { it.first }.take(3).sortedBy { it.second.at }
        if (gaps.isEmpty()) return null
        return gaps.joinToString("") { (d, a, b) ->
            "- $d days, ${Stamps.show(a.at!!.toLocalDate())} to ${Stamps.show(b.at!!.toLocalDate())}" +
                (if (a.who.isNotBlank() && b.who.isNotBlank()) " (${a.who} spoke last, ${b.who} spoke next)" else "") + "\n"
        }
    }

    // ---- Tables ----

    private const val MAX_SHEETS = 6
    private const val MAX_COLUMNS = 24
    private const val CELL_CHARS = 40

    private fun tables(sheets: List<Sheet>, budget: Int): Built {
        val shown = sheets.take(MAX_SHEETS)
        val each = budget / shown.size
        val sb = StringBuilder("WHAT THIS IS: ")
        sb.append(if (sheets.size == 1) "a table" else "a workbook with ${sheets.size} sheets").append(".\n")
        var rowsTotal = 0
        for (s in shown) { rowsTotal += dataRows(s).size; sb.append(sheet(s, each - 40)) }
        sheets.drop(MAX_SHEETS).takeIf { it.isNotEmpty() }?.let { more ->
            sb.append("SHEETS NOT SHOWN: ").append(more.joinToString(", ") { "\"${it.name}\" (${count(it.rows.size, "row", "rows")})" }).append('\n')
            rowsTotal += more.sumOf { it.rows.size }
        }
        val facts = if (sheets.size == 1) "${count(rowsTotal, "row", "rows")} · ${count(sheets[0].rows.maxOf { it.size }, "column", "columns")}"
        else "${sheets.size} sheets · ${count(rowsTotal, "row", "rows")}"
        return Built(FileShape.TABLE, facts, sb.toString())
    }

    /** The first row is a header when none of its cells is a number and at least half of them are filled. */
    private fun hasHeader(s: Sheet): Boolean {
        val first = s.rows.first()
        return s.rows.size >= 2 && first.none { num(it) != null } && first.count { it.isNotBlank() } * 2 >= first.size
    }

    private fun dataRows(s: Sheet) = if (hasHeader(s)) s.rows.drop(1) else s.rows

    private fun sheet(s: Sheet, budget: Int): String {
        val width = s.rows.maxOf { it.size }.coerceAtMost(MAX_COLUMNS)
        val data = dataRows(s)
        val names = (0 until width).map { i -> (if (hasHeader(s)) s.rows.first().getOrNull(i) else null)?.takeIf { it.isNotBlank() } ?: "Column ${letter(i)}" }
        val sb = StringBuilder()
        sb.append(if (s.name.isNotBlank()) "SHEET \"${s.name}\": " else "SIZE: ").append(count(data.size, "row", "rows")).append(" × ").append(count(s.rows.maxOf { it.size }, "column", "columns")).append('\n')
        if (data.isEmpty()) return sb.toString()
        val dayFirst = Stamps.dayFirst(data.asSequence().take(200).flatMap { it.asSequence() })
        sb.append("COLUMNS:\n")
        for (i in 0 until width) {
            val values = data.mapNotNull { it.getOrNull(i)?.takeIf { v -> v.isNotBlank() } }
            sb.append("- ").append(names[i].take(CELL_CHARS)).append(": ").append(describe(values, dayFirst)).append('\n')
        }
        if (s.rows.maxOf { it.size } > width) sb.append("- (${s.rows.maxOf { it.size } - width} more columns not shown)\n")
        fun line(r: List<String>) = (0 until width).joinToString(" | ") { i -> tidy(r.getOrNull(i).orEmpty()).let { if (it.length > CELL_CHARS) it.take(CELL_CHARS - 1) + "…" else it } }
        val lines = data.map(::line)
        val head = line(names)
        val room = (budget - sb.length - head.length - 80).coerceAtLeast(200)
        if (lines.sumOf { it.length + 1 } <= room) {
            sb.append("ALL ROWS:\n").append(head).append('\n'); lines.forEach { sb.append(it).append('\n') }
        } else {
            val top = ArrayList<String>(); var used = 0
            for (l in lines) { if (used + l.length + 1 > room * 0.7) break; top += l; used += l.length + 1 }
            val end = ArrayList<String>()
            for (l in lines.asReversed()) { if (used + l.length + 1 > room || top.size + end.size >= lines.size) break; end += l; used += l.length + 1 }
            sb.append("ROWS (first ${top.size} and last ${end.size} of ${lines.size}):\n").append(head).append('\n')
            top.forEach { sb.append(it).append('\n') }
            sb.append("… ").append(count(lines.size - top.size - end.size, "row", "rows")).append(" not shown …\n")
            end.asReversed().forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    /** One column in a line: numbers get their range and total, dates their span, text its most common values. */
    private fun describe(values: List<String>, dayFirst: Boolean): String {
        if (values.isEmpty()) return "empty"
        val nums = values.mapNotNull(::num)
        if (nums.size * 10 >= values.size * 8) {
            return "number, ${fmt(nums.min())} to ${fmt(nums.max())}, average ${fmt(nums.average())}, total ${fmt(nums.sum())}"
        }
        val dates = values.mapNotNull { v -> if (v.length <= 40) Stamps.parse(v, dayFirst) else null }
        if (dates.size * 10 >= values.size * 8) return "date, ${Stamps.show(dates.min().toLocalDate())} to ${Stamps.show(dates.max().toLocalDate())}"
        val counts = values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        return if (counts.size <= 8 && values.size > counts.size) "one of " + counts.joinToString(", ") { "${it.key.take(30)} (${it.value})" }
        else "text, ${counts.size} different, e.g. " + counts.take(3).joinToString(", ") { it.key.take(30) }
    }

    private val NUM_NOISE = Regex("[,\\s₹$€£%]")
    private fun num(s: String): Double? {
        val t = s.trim(); if (t.isEmpty() || t.length > 24) return null
        val neg = t.startsWith("(") && t.endsWith(")")
        return NUM_NOISE.replace(if (neg) t.substring(1, t.length - 1) else t, "").toDoubleOrNull()?.takeIf { it.isFinite() }?.let { if (neg) -it else it }
    }

    private fun fmt(d: Double): String =
        if (d == Math.rint(d) && Math.abs(d) < 1e15) String.format(Locale.US, "%,d", d.toLong())
        else String.format(Locale.US, "%,.2f", d).trimEnd('0').trimEnd('.')

    private fun letter(i: Int): String = if (i < 26) ('A' + i).toString() else letter(i / 26 - 1) + ('A' + i % 26)

    // ---- Documents ----

    private fun document(paras: List<Para>, budget: Int): Built {
        val words = paras.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotEmpty() } }
        val headings = paras.filter { it.heading }
        val text = paras.joinToString("\n") { if (it.heading) "## ${it.text}" else it.text }
        val sb = StringBuilder("WHAT THIS IS: a document, ${count(words, "word", "words")}")
        if (headings.isNotEmpty()) sb.append(", ").append(count(headings.size, "section", "sections"))
        sb.append(".\n")
        if (text.length <= budget - sb.length - 40) sb.append("FULL TEXT:\n").append(text).append('\n')
        else {
            if (headings.size >= 2) {
                sb.append("SECTIONS:\n")
                headings.take(25).forEach { sb.append("- ").append(it.text.take(80)).append('\n') }
                if (headings.size > 25) sb.append("- (${headings.size - 25} more)\n")
            }
            val room = (budget - sb.length - 120).coerceAtLeast(400)
            val head = cutAtBreak(text.take((room * 0.65).toInt()), fromEnd = true)
            val end = cutAtBreak(text.takeLast((room * 0.35).toInt()), fromEnd = false)
            val cut = text.length - head.length - end.length
            sb.append("TEXT (the beginning and the end; the middle is cut):\n").append(head)
                .append("\n… about ${count(Math.round(words.toDouble() * cut / text.length).toInt(), "word", "words")} not shown …\n").append(end).append('\n')
        }
        val facts = listOfNotNull(count(words, "word", "words"), headings.size.takeIf { it > 0 }?.let { count(it, "section", "sections") }).joinToString(" · ")
        return Built(FileShape.DOCUMENT, facts, sb.toString())
    }

    /** Trims a slice back to a whole line or sentence, so the model never reads half a word. */
    private fun cutAtBreak(s: String, fromEnd: Boolean): String {
        if (fromEnd) {
            val at = maxOf(s.lastIndexOf('\n'), s.lastIndexOf(". ") + 1)
            return if (at > s.length / 2) s.substring(0, at).trimEnd() else s
        }
        val at = listOf(s.indexOf('\n'), s.indexOf(". ").let { if (it < 0) -1 else it + 1 }).filter { it >= 0 }.minOrNull() ?: -1
        return if (at in 0 until s.length / 2) s.substring(at + 1).trimStart() else s
    }

    // ---- Slides ----

    private fun deck(slides: List<Slide>, budget: Int): Built {
        val sb = StringBuilder("WHAT THIS IS: a slide deck, ${count(slides.size, "slide", "slides")}.\n")
        val each = ((budget - sb.length) / slides.size).coerceAtLeast(90)
        var cut = 0
        for (s in slides) {
            if (sb.length > budget - 60) { cut++; continue }
            val block = StringBuilder("Slide ${s.number}: ${tidy(s.title).ifBlank { "(no title)" }.take(100)}\n")
            for (l in s.lines) {
                val t = "- " + tidy(l).take(160) + "\n"
                if (block.length + t.length > each) { block.append("- (more on this slide)\n"); break }
                block.append(t)
            }
            if (s.notes.isNotBlank() && block.length + 20 < each) block.append("  Speaker notes: ").append(tidy(s.notes).take((each - block.length - 18).coerceAtLeast(40))).append('\n')
            sb.append(block)
        }
        if (cut > 0) sb.append("… ").append(count(cut, "slide", "slides")).append(" not shown …\n")
        return Built(FileShape.DECK, count(slides.size, "slide", "slides"), sb.toString())
    }

    // ---- Shared ----

    private val SPACE = Regex("[\\s\\u00A0\\u202F]+")
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    /** One clean line: invisible direction marks out (chat exports are full of them), runs of space collapsed. */
    internal fun tidy(s: String): String = SPACE.replace(INVISIBLE.replace(s, ""), " ").trim()

    internal fun count(n: Int, one: String, many: String): String = String.format(Locale.US, "%,d", n) + " " + if (n == 1) one else many
}

/** Reads the many ways people write a date and time. No clock and no locale: the same text always gives the same answer. */
internal object Stamps {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val ISO = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val NUM = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4}|\d{2})\b""")
    private val DMY = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[ \-]([A-Za-z]{3,9})\.?,?[ \-](\d{4}|\d{2})\b""")
    private val MDY = Regex("""\b([A-Za-z]{3,9})\.? (\d{1,2})(?:st|nd|rd|th)?,? (\d{4})\b""")
    private val TIME = Regex("""\b(\d{1,2}):(\d{2})(?::\d{2})?(?:\s*([AaPp])\.?[Mm]\b)?""")

    /** The date (and the time, if one follows) in [raw], or null when there is no date in it. */
    fun parse(raw: String, dayFirst: Boolean = true): LocalDateTime? {
        val s = FileAnalyzer.tidy(raw)
        var rest = s
        fun rest(m: MatchResult) { rest = s.removeRange(m.range) }
        val date: LocalDate = ISO.find(s)?.let { m -> rest(m); ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
            ?: NUM.find(s)?.let { m ->
                rest(m)
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                val swap = if (a > 12) false else if (b > 12) true else !dayFirst
                if (swap) ymd(year(m.groupValues[3]), a, b) else ymd(year(m.groupValues[3]), b, a)
            }
            ?: DMY.find(s)?.let { m -> rest(m); monthOf(m.groupValues[2])?.let { ymd(year(m.groupValues[3]), it, m.groupValues[1].toInt()) } }
            ?: MDY.find(s)?.let { m -> rest(m); monthOf(m.groupValues[1])?.let { ymd(year(m.groupValues[3]), it, m.groupValues[2].toInt()) } }
            ?: return null
        val t = time(rest)
        return if (t != null) date.atTime(t.first, t.second) else date.atStartOfDay()
    }

    /** Hour and minute from "9:41 pm" or "21:41:02". */
    fun time(s: String): Pair<Int, Int>? {
        val m = TIME.find(s) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        when (m.groupValues[3].lowercase()) { "p" -> if (h < 12) h += 12; "a" -> if (h == 12) h = 0 }
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /**
     * Whether 03/04 means 3 April. Settled by the data itself: one date that can only be read one way decides it
     * for the whole file. With no such date the answer is day-first, which is how most of the world writes it.
     */
    fun dayFirst(samples: Sequence<String>): Boolean {
        for (s in samples.take(2_000)) {
            val m = NUM.find(s) ?: continue
            if (m.groupValues[1].toInt() > 12) return true
            if (m.groupValues[2].toInt() > 12) return false
        }
        return true
    }

    private fun year(s: String): Int = s.toInt().let { if (s.length > 2) it else if (it >= 70) 1900 + it else 2000 + it }
    private fun monthOf(s: String): Int? = MONTHS.indexOf(s.lowercase().take(3)).takeIf { it >= 0 }?.plus(1)
    private fun ymd(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    fun month(m: Int): String = MONTHS[m - 1].replaceFirstChar { it.uppercase() }
    fun show(d: LocalDate): String = "${d.dayOfMonth} ${month(d.monthValue)} ${d.year}"
    /** "2 Mar – 9 Nov 2024", with the first year only when the two differ. */
    fun range(a: LocalDate, b: LocalDate): String = when {
        a == b -> show(a)
        a.year == b.year -> "${a.dayOfMonth} ${month(a.monthValue)} – ${show(b)}"
        else -> "${show(a)} – ${show(b)}"
    }
}
