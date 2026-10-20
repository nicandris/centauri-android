package app.centauri.remote

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val TICK_MS = 1_000L
/** Simulated print seconds per real second, so a demo job visibly moves. */
private const val TIME_SCALE = 30
private const val TRANSITION_MS = 1_500L

/**
 * A simulated Centauri Carbon for trying the app without a printer: a print in progress,
 * temperatures that drift toward their targets, working controls, sample files and history.
 * The camera shows a still photo. Single-threaded on [scope], like [SdcpClient].
 */
class DemoPrinter(private val scope: CoroutineScope) : PrinterLink {
    override val printer = Printer(name = "", ip = "demo", mainboardId = "demo", connId = "")
    override val hasCamera = true
    override val stillFrame = R.drawable.demo_camera
    override val jobThumbnailRes get() = job?.thumb

    private val _connected = MutableStateFlow(false)
    private val _status = MutableStateFlow<Status?>(null)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    override val status: StateFlow<Status?> = _status.asStateFlow()
    override val videoUrl: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override val thumbnail: StateFlow<Bitmap?> = MutableStateFlow<Bitmap?>(null).asStateFlow()
    private val _name = MutableStateFlow("")
    override val name: StateFlow<String> = _name.asStateFlow()

    private val now get() = System.currentTimeMillis() / 1000
    private val files = mutableListOf(
        DemoFile("ECC_0.4_benchy_Elegoo PLA+ _0.2_1h35m.gcode", 5_700, 240, 14.8, 2.1, R.drawable.demo_thumb_benchy),
        DemoFile("ECC_0.4_cable-clip_Elegoo PLA+ _0.28_18m.gcode", 1_080, 46, 3.2, 0.4, R.drawable.demo_thumb_cable_clip),
        DemoFile("ECC_0.4_planter-hex_Elegoo Rapid PETG _0.2_6h12m.gcode", 22_320, 610, 142.0, 9.8, R.drawable.demo_thumb_planter_hex),
        DemoFile("ECC_0.4_phone-stand_Elegoo PLA+ _0.2_2h05m.gcode", 7_500, 380, 41.5, 3.3, R.drawable.demo_thumb_phone_stand),
        DemoFile("ECC_0.4_gridfinity-bin-2x3_Elegoo PLA+ _0.2_3h40m.gcode", 13_200, 420, 88.0, 5.0, R.drawable.demo_thumb_gridfinity_bin_2x3),
        DemoFile("ECC_0.4_headphone-hook_Elegoo Rapid PETG _0.28_1h10m.gcode", 4_200, 150, 27.0, 1.4, R.drawable.demo_thumb_headphone_hook),
    ).mapIndexed { i, f -> f.copy(created = now - (i + 1) * 86_400L / 2) }.toMutableList()

    private var users = 0
    private var ticker: Job? = null
    private var transition: Job? = null

    private var state = PrintState.PRINTING
    private var job: DemoFile? = files[0]
    private var elapsed = (files[0].seconds * 0.35).toInt()
    private var nozzle = 220.0
    private var bed = 60.0
    private var chamber = 31.0
    private var nozzleTarget = 220.0
    private var bedTarget = 60.0
    private var fans = Triple(100, 0, 40)
    private var speed = 100
    private var light = true
    private val history = mutableListOf(
        demoHistory(files[1], daysAgo = 1, completed = true),
        demoHistory(files[3], daysAgo = 2, completed = true),
        demoHistory(files[2], daysAgo = 3, completed = false),
        demoHistory(files[4], daysAgo = 5, completed = true),
        demoHistory(files[5], daysAgo = 6, completed = true),
    )

    override fun start() {
        if (users++ > 0) return
        _connected.value = true
        publish()
        ticker = scope.launch {
            while (isActive) { delay(TICK_MS); tick(); publish() }
        }
    }

    override fun stop() {
        if (users == 0 || --users > 0) return
        shutdown()
    }

    override fun shutdown() {
        users = 0
        ticker?.cancel()
        transition?.cancel()
        _connected.value = false
    }

    override fun reconnect() {}

    private fun tick() {
        val j = job
        if (j != null && state == PrintState.PRINTING) {
            elapsed += TIME_SCALE * speed / 100
            if (elapsed >= j.seconds) finish(PrintState.COMPLETE)
        }
        // Heaters ease toward their targets with a little sensor noise.
        nozzle += (nozzleTarget.coerceAtLeast(25.0) - nozzle) * 0.25 + Random.nextDouble(-0.3, 0.3)
        bed += (bedTarget.coerceAtLeast(24.0) - bed) * 0.15 + Random.nextDouble(-0.2, 0.2)
        chamber += ((if (bedTarget > 0) 34.0 else 24.0) - chamber) * 0.02
    }

    private fun finish(end: Int) {
        val j = job ?: return
        state = end
        history.add(0, demoHistory(j, daysAgo = 0, completed = end == PrintState.COMPLETE,
            layers = layerAt(j), duration = elapsed.toLong()))
        nozzleTarget = 0.0
        bedTarget = 0.0
    }

    private fun layerAt(j: DemoFile) = (j.layers * elapsed / j.seconds).coerceIn(0, j.layers)

    private fun publish() {
        val j = job
        _status.value = Status(
            machine = if (j != null && state != PrintState.COMPLETE && state != PrintState.STOPPED) 1 else 0,
            nozzle = nozzle, nozzleTarget = nozzleTarget, bed = bed, bedTarget = bedTarget, chamber = chamber,
            modelFan = fans.first, auxFan = fans.second, boxFan = fans.third,
            light = light, rgb = listOf(0, 0, 0), canvas = false, plate = 0,
            print = PrintInfo(
                status = if (j == null) PrintState.IDLE else state,
                layer = j?.let(::layerAt) ?: 0, totalLayers = j?.layers ?: 0,
                elapsedS = elapsed, totalS = j?.seconds ?: 0,
                progress = j?.let { (100 * elapsed / it.seconds).coerceIn(0, 100) } ?: 0,
                file = j?.path?.substringAfterLast('/') ?: "", taskId = j?.path ?: "", speedPct = speed,
            ),
        )
    }

    /** Moves through an intermediate state the way the printer does (e.g. Pausing → Paused). */
    private fun transitionTo(via: Int, end: Int, then: () -> Unit = {}): Boolean {
        transition?.cancel()
        state = via
        publish()
        transition = scope.launch { delay(TRANSITION_MS); state = end; then(); publish() }
        return true
    }

    override fun pause() = if (state == PrintState.PRINTING) transitionTo(PrintState.PAUSING, PrintState.PAUSED) else false
    override fun resume() = if (state == PrintState.PAUSED) transitionTo(PrintState.RESUMING, PrintState.PRINTING) else false
    override fun stopPrint() =
        if (job != null && !_status.value!!.idle) transitionTo(PrintState.STOPPING, PrintState.STOPPED) { finish(PrintState.STOPPED) }
        else false

    override fun setLight(on: Boolean): Boolean { light = on; publish(); return true }
    override fun setSpeed(pct: Int): Boolean { speed = pct.coerceIn(50, 160); publish(); return true }
    override fun setNozzle(c: Int): Boolean { nozzleTarget = c.coerceIn(0, 320).toDouble(); publish(); return true }
    override fun setBed(c: Int): Boolean { bedTarget = c.coerceIn(0, 110).toDouble(); publish(); return true }
    override fun setFans(model: Int, aux: Int, box: Int): Boolean {
        fans = Triple(model.coerceIn(0, 100), aux.coerceIn(0, 100), box.coerceIn(0, 100)); publish(); return true
    }
    override fun home(axis: String) = _status.value?.idle == true
    override fun video(enable: Boolean) {}

    override suspend fun renamePrinter(name: String): Boolean {
        delay(200)
        _name.value = name
        return true
    }

    override suspend fun deleteFiles(paths: List<String>): Boolean {
        delay(200)
        files.removeAll { "/local/${it.path}" in paths }
        return true
    }


    override suspend fun deleteHistory(taskIds: List<String>): Boolean {
        delay(200)
        history.removeAll { it.taskId in taskIds }
        return true
    }

    override suspend fun files(): List<PrinterFile> {
        delay(300) // feel like a network round trip
        return files.map { PrinterFile("/local/${it.path}", it.megabytes.times(1_000_000).toLong(), it.created, it.layers) }
    }

    override suspend fun fileInfo(path: String): FileInfo {
        delay(150)
        val f = files.first { "/local/${it.path}" == path }
        return FileInfo(thumbnail = "", estS = f.seconds, estGrams = f.grams, layers = f.layers, thumbnailRes = f.thumb)
    }

    override suspend fun history(): List<HistoryEntry> {
        delay(300)
        return history.toList()
    }

    override suspend fun startPrint(path: String, autoLevel: Boolean, timelapse: Boolean, plate: Int): Int {
        if (_status.value?.idle != true) return 1
        job = files.first { "/local/${it.path}" == path }
        elapsed = 0
        nozzleTarget = 220.0
        bedTarget = 60.0
        transitionTo(16 /* preheating */, PrintState.PRINTING)
        return 0
    }

    private fun demoHistory(
        f: DemoFile, daysAgo: Int, completed: Boolean,
        layers: Int = if (completed) f.layers else f.layers / 3, duration: Long = f.seconds.toLong(),
    ): HistoryEntry {
        val begin = now - daysAgo * 86_400L - duration
        return HistoryEntry(
            taskId = "demo-$begin", path = "/local/${f.path}", thumbnail = "", begin = begin, end = begin + duration,
            completed = completed, layers = layers, totalLayers = f.layers, thumbnailRes = f.thumb,
        )
    }

    private data class DemoFile(
        val path: String, val seconds: Int, val layers: Int, val grams: Double, val megabytes: Double,
        @DrawableRes val thumb: Int, val created: Long = 0,
    )
}
