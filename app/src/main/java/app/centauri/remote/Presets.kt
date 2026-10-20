package app.centauri.remote

import android.content.res.Resources
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named set of printer settings. Null fields are left as they are when applied, so a
 * material preset can set temperatures without touching fans or speed.
 */
data class Preset(
    val name: String,
    val nozzle: Int? = null,
    val bed: Int? = null,
    val speedPct: Int? = null,
    val fans: List<Int>? = null, // part cooling, auxiliary, chamber
)

/** Starting points for common materials; temperatures only. */
fun builtInPresets(res: Resources) = listOf(
    Preset(res.getString(R.string.preset_pla), nozzle = 220, bed = 60),
    Preset(res.getString(R.string.preset_petg), nozzle = 245, bed = 75),
    Preset(res.getString(R.string.preset_abs), nozzle = 255, bed = 100),
    Preset(res.getString(R.string.preset_cool_down), nozzle = 0, bed = 0),
)

/** The printer's current targets as a preset to save. */
fun presetFrom(name: String, st: Status) = Preset(
    name = name,
    nozzle = st.nozzleTarget.toInt(),
    bed = st.bedTarget.toInt(),
    speedPct = st.print.speedPct,
    fans = listOf(st.modelFan, st.auxFan, st.boxFan),
)

/** Sends every setting the preset holds; true only if all of them went out. */
fun PrinterLink.apply(p: Preset): Boolean {
    var sent = true
    p.nozzle?.let { sent = setNozzle(it) && sent }
    p.bed?.let { sent = setBed(it) && sent }
    p.speedPct?.let { sent = setSpeed(it) && sent }
    p.fans?.let { sent = setFans(it[0], it[1], it[2]) && sent }
    return sent
}

/** One line describing what a preset changes, e.g. "Nozzle 220 °C · Bed 60 °C". */
fun Preset.summary(res: Resources): String = listOfNotNull(
    nozzle?.let { if (it == 0) res.getString(R.string.preset_nozzle_off) else res.getString(R.string.preset_nozzle, it) },
    bed?.let { if (it == 0) res.getString(R.string.preset_bed_off) else res.getString(R.string.preset_bed, it) },
    speedPct?.let { res.getString(R.string.preset_speed, it) },
    fans?.let { res.getString(R.string.preset_fans, it[0], it[1], it[2]) },
).joinToString(" · ")

fun presetsToJson(presets: List<Preset>): String = JSONArray(presets.map { p ->
    JSONObject().put("name", p.name).apply {
        p.nozzle?.let { put("nozzle", it) }
        p.bed?.let { put("bed", it) }
        p.speedPct?.let { put("speed", it) }
        p.fans?.let { put("fans", JSONArray(it)) }
    }
}).toString()

/** Tolerant of anything malformed: bad entries are skipped rather than losing the rest. */
fun presetsFromJson(json: String?): List<Preset> {
    val arr = runCatching { JSONArray(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name").ifBlank { return@mapNotNull null }
        fun int(key: String) = if (o.has(key)) o.optInt(key) else null
        val fans = o.optJSONArray("fans")?.takeIf { it.length() == 3 }?.let { a -> List(3) { a.optInt(it) } }
        Preset(name, int("nozzle"), int("bed"), int("speed"), fans)
    }
}
