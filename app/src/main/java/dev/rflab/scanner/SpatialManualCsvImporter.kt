package dev.rflab.scanner

/**
 * Import for RF Lab v0.3.1's manual-survey CSV.
 * This is a one-time median-only migration, not a reconstruction of raw scans.
 */
data class ManualCsvResult(val roomName: String, val points: Int,
                           val bssidReadings: Int, val message: String)
class SpatialManualCsvImporter {
    private data class Row(
        val point: String, val x: Float, val z: Float,
        val bssid: String, val ssid: String, val rssi: Int
    )
    private fun parseLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quote = false
        var i = 0
        while (i < line.length) {
            when (val ch = line[i]) {
                '"' -> {
                    if (quote && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"'); i++
                    } else quote = !quote
                }
                ';' -> if (quote) current.append(ch) else {
                    fields.add(current.toString()); current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        require(!quote) { "Unterminated quote" }
        fields.add(current.toString())
        return fields
    }

    fun import(home: SpatialHomeStore, csv: String): ManualCsvResult {
        require(csv.length <= 4_000_000) { "CSV too large" }
        val lines = csv.lineSequence().filter { it.isNotBlank() }.take(6001).toList()
        require(lines.size in 2..6000) { "CSV rows outside supported range" }
        val head = parseLine(lines.first())
        val required = listOf("point", "x_m", "z_m", "bssid", "ssid", "median_dbm")
        require(required.all { head.contains(it) }) { "Missing manual WiFi CSV columns" }
        fun get(fields: List<String>, key: String) = fields[head.indexOf(key)]
        val rows = lines.drop(1).map { raw ->
            val cols = parseLine(raw)
            require(cols.size == head.size) { "Invalid column count" }
            val x = get(cols, "x_m").toFloatOrNull() ?: error("Bad X value")
            val z = get(cols, "z_m").toFloatOrNull() ?: error("Bad Z value")
            val rssi = get(cols, "median_dbm").toIntOrNull() ?: error("Bad RSSI")
            val bssid = get(cols, "bssid")
            require(x.isFinite() && z.isFinite() && x >= 0f && z >= 0f &&
                x <= 30f && z <= 30f && rssi in -110..-1 &&
                bssid.isNotBlank() && bssid.length <= 50)
            Row(get(cols, "point").take(30), x, z, bssid,
                get(cols, "ssid").take(70), rssi)
        }
        val groups = rows.groupBy { Triple(it.point, it.x, it.z) }
        require(groups.size in 1..500) { "Invalid point count" }
        val width = (rows.maxOf { it.x } + .5f).coerceAtLeast(1f)
        val depth = (rows.maxOf { it.z } + .5f).coerceAtLeast(1f)
        val x = if (home.rooms.isEmpty()) 0f else home.rooms.maxOf { it.x + it.width } + 1f
        val roomName = "CSV-Import " + (home.rooms.count { it.name.startsWith("CSV-Import") } + 1)
        require(home.addRoom(roomName, x, 0f, width, depth)) {
            "No room space; adjust existing map first"
        }
        val room = home.rooms.last()
        var imported = 0
        var samples = 0
        try {
            for ((key, readings) in groups) {
                val values = readings.associate { it.bssid to it.rssi }
                if (values.size < 3) continue
                val point = home.addAnchor(room.id, x + key.second, key.third) ?: continue
                point.label = key.first.ifBlank { point.label }
                val ssids = readings.associate { it.bssid to it.ssid }
                if (home.addSweep(point.id, RfSweep(System.currentTimeMillis(),
                        values, ssids))) {
                    imported++
                    samples += values.size
                }
            }
            require(imported > 0) { "No point with at least three BSSIDs" }
            home.save()
            return ManualCsvResult(roomName, imported, samples,
                "Aggregated medians imported; original scan distribution is unavailable")
        } catch (e: Exception) {
            home.removeRoom(room.id)
            throw e
        }
    }
}
