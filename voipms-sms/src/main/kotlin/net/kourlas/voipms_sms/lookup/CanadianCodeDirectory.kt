package net.kourlas.voipms_sms.lookup

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/** Fetch one public area-code table and match the exchange locally. No subscriber digits leave the device. */
internal object CanadianCodeDirectory {
    private data class Table(val time: Long, val assignments: Map<String, List<CallerFact>>)
    private val tables = object : LinkedHashMap<String, Table>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Table>) = size > 4
    }

    suspend fun search(number: String): List<CallerFact> {
        val prefix = LocalCallingGuide.prefix(number) ?: return emptyList()
        val npa = prefix.take(3)
        val time = System.currentTimeMillis()
        val previous = synchronized(tables) { tables[npa] }
        if (previous != null && time - previous.time in 0 until 86_400_000L) {
            return previous.assignments[prefix].orEmpty()
        }
        val url = "https://www.cnac.ca/data/COCodeStatus_NPA$npa.csv".toHttpUrl()
        val request = Request.Builder().url(url).header("Accept", "text/csv").build()
        val assignments = parse(npa, lookupResponse(request, 192 * 1024L))
        synchronized(tables) { tables[npa] = Table(time, assignments) }
        return assignments[prefix].orEmpty()
    }

    fun parse(npa: String, csv: String): Map<String, List<CallerFact>> {
        // Releases may add columns. Bind by header and handle quoted commas and doubled quotes.
        val lines = csv.removePrefix("\uFEFF").lineSequence().iterator()
        if (!lines.hasNext()) throw IOException("Empty Canadian numbering table")
        val header = fields(lines.next())
        val name = listOf("NPA", "CO Code (NXX)", "Status", "Exchange Area", "Province", "Company")
        val index = name.associateWith { header.indexOf(it) }
        if (index.values.any { it < 0 }) throw IOException("Unrecognized Canadian numbering table")
        val assignments = mutableMapOf<String, List<CallerFact>>()
        val ambiguous = mutableSetOf<String>()
        for (line in lines) {
            val row = fields(line)
            fun field(label: String) = row.getOrNull(index.getValue(label)).orEmpty().trim().take(120)
            if (field("NPA") != npa || field("Status") != "In Service") continue
            val nxx = field("CO Code (NXX)")
            if (!nxx.matches(exchange)) continue
            val key = npa + nxx
            val area = listOf(field("Exchange Area"), field("Province")).filter { it.isNotBlank() }.joinToString(", ")
            val facts = buildList {
                if (area.isNotEmpty()) add(CallerFact(FactKind.AREA, area))
                val holder = field("Company")
                if (holder.isNotEmpty()) add(CallerFact(FactKind.RANGE_HOLDER, holder))
            }
            if (assignments.put(key, facts)?.let { it != facts } == true) ambiguous += key
        }
        ambiguous.forEach(assignments::remove)
        return assignments
    }

    private val exchange = Regex("[2-9][0-9]{2}")
    private fun fields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i++]
            when {
                c == '"' && quoted && line.getOrNull(i) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { fields += field.toString(); field.setLength(0) }
                else -> field.append(c)
            }
        }
        fields += field.toString()
        return fields
    }
}
