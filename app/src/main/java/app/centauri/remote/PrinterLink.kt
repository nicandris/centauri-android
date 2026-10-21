package app.centauri.remote

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the UI and the monitor service need from a printer. [SdcpClient] talks to a real
 * Centauri Carbon; [DemoPrinter] simulates one so the app can be tried without hardware.
 */
interface PrinterLink {
    val printer: Printer
    val connected: StateFlow<Boolean>
    val status: StateFlow<Status?>
    /** MJPEG URL while video is enabled and the printer agreed. */
    val videoUrl: StateFlow<String?>
    /** Why the printer refused the last video request, as a string resource; null once it agrees. */
    val videoProblem: StateFlow<Int?> get() = NO_VIDEO_PROBLEM
    /** Thumbnail of the current job, when the printer has one. */
    val thumbnail: StateFlow<Bitmap?>
    /** The attached Canvas's trays; null when there's no Canvas (or it hasn't answered yet). */
    val canvas: StateFlow<Canvas?>
    /** A fixed picture to show instead of live video (the demo printer); null for real cameras. */
    @get:DrawableRes val stillFrame: Int? get() = null
    /** A bundled picture of the current job (the demo printer); real printers use [thumbnail]. */
    @get:DrawableRes val jobThumbnailRes: Int? get() = null

    /** Ref-counted: each holder calls start() once and stop() once. */
    fun start()
    fun stop()
    /** Drop the connection regardless of holders (switching printers). */
    fun shutdown()
    /** Retry a lost connection now. */
    fun reconnect()

    /** The printer's own name; updates after [renamePrinter]. */
    val name: StateFlow<String>

    // Management calls return true when the printer accepted the change.
    suspend fun renamePrinter(name: String): Boolean
    suspend fun deleteFiles(paths: List<String>): Boolean
    suspend fun deleteHistory(taskIds: List<String>): Boolean

    suspend fun files(): List<PrinterFile>
    suspend fun fileInfo(path: String): FileInfo
    suspend fun history(): List<HistoryEntry>
    /** One job's history entry (for its error reason); null when the printer doesn't know it. */
    suspend fun taskDetails(taskId: String): HistoryEntry?
    /** Returns the printer's Ack; 0 means started. [tray] feeds the file from that Canvas tray; null lets the printer pick. */
    suspend fun startPrint(path: String, autoLevel: Boolean, timelapse: Boolean, plate: Int, tray: Tray? = null): Int

    // Commands return false when they couldn't be sent (no connection).
    fun pause(): Boolean
    fun resume(): Boolean
    fun stopPrint(): Boolean
    fun setLight(on: Boolean): Boolean
    fun setSpeed(pct: Int): Boolean
    fun setNozzle(c: Int): Boolean
    fun setBed(c: Int): Boolean
    fun setFans(model: Int, aux: Int, box: Int): Boolean
    fun home(axis: String): Boolean
    /** Moves [axis] ("X", "Y" or "Z") by [mm], relative; negative moves back. */
    fun jog(axis: String, mm: Double): Boolean
    fun skipPreheat(): Boolean
    fun stopFeed(): Boolean
    /** Ref-counted request for the camera stream. */
    fun video(enable: Boolean)
}

private val NO_VIDEO_PROBLEM: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()

/**
 * The name to show: the printer's own, or the demo/generic label when it has none. Composables
 * should collect [PrinterLink.name] and pass it, so a rename shows up at once.
 */
fun PrinterLink.displayName(res: android.content.res.Resources, current: String = name.value): String = when {
    current.isNotEmpty() -> current
    this is DemoPrinter -> res.getString(R.string.demo_printer_name)
    else -> res.getString(R.string.default_printer_name)
}
