package app.centauri.remote

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CentauriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}

/**
 * Process-wide printer connection, shared by the UI and [PrintMonitorService] so a running print
 * keeps one WebSocket whether the app is open or not. Initialised once by [CentauriApp]; it owns
 * no Activity or View references, so it can't leak one.
 */
object Hub {
    private val lanTracker = LanTracker()
    /** The network the printer is on when the default route would miss it (see [pickLan]); null for the default. */
    val lan get() = lanTracker.network

    // Protocol-level ping every 30 s, same heartbeat as the HA client. No redirects: the printer's
    // thumbnail and video URLs must not lead the app to another host. Everything goes to the one
    // printer, so OkHttp's 5-per-host limit would queue thumbnails behind the camera; 8 keeps them
    // ahead of it without crowding the printer's small web server.
    val http: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        .socketFactory(LanSocketFactory { lan }).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _client = MutableStateFlow<PrinterLink?>(null)
    private val _notifications = MutableStateFlow(true)
    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val client: StateFlow<PrinterLink?> = _client.asStateFlow()
    val notifications: StateFlow<Boolean> = _notifications.asStateFlow()
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()
    private val _labs = MutableStateFlow<Set<Lab>>(emptySet())
    /** Untested features the user switched on in Labs; none by default. */
    val labs: StateFlow<Set<Lab>> = _labs.asStateFlow()
    private val _order = MutableStateFlow(ListOrder())
    /** How Files and History are sorted. */
    val order: StateFlow<ListOrder> = _order.asStateFlow()
    private val _clockWarning = MutableStateFlow(true)
    /** Whether the status screen warns while the printer's clock is stuck at 1970. */
    val clockWarning: StateFlow<Boolean> = _clockWarning.asStateFlow()
    private val _remindBefore = MutableStateFlow(DEFAULT_REMIND)
    /** Minutes before the end of a print for the time-left alert (one of [REMIND_CHOICES]), when that's on. */
    val remindBefore: StateFlow<Int> = _remindBefore.asStateFlow()
    private val _notices = MutableStateFlow(Notice.defaults)
    /** The kinds of notification the user has on, under the [notifications] master switch. */
    val notices: StateFlow<Set<Notice>> = _notices.asStateFlow()

    private val _dynamicColor = MutableStateFlow(false)
    /** Material You colours from the wallpaper (Android 12+) instead of the GitHub palette; off by default. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()
    private val _language = MutableStateFlow("")
    /** The in-app language on Android 12 and older (one of [LANGUAGES]); Android 13+ keeps its own per-app setting. */
    val language: StateFlow<String> = _language.asStateFlow()

    private val _searching = MutableStateFlow(false)
    /** [relocate] is looking for the printer. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    /** The camera frame the monitor took when a job finished, with that job's task id; cleared once the next one runs. */
    val finishFrame = MutableStateFlow<Pair<String, Bitmap>?>(null)

    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private var nameSaver: Job? = null

    private fun use(c: PrinterLink?) {
        _client.value = c
        nameSaver?.cancel()
        // Saves the printer's own name, also after a rename on its touchscreen; the demo isn't saved.
        if (c is SdcpClient) nameSaver = scope.launch { c.name.collect(::rememberName) }
    }

    fun init(app: Application) {
        this.app = app
        prefs = app.getSharedPreferences("printer", Context.MODE_PRIVATE)
        createChannels(app)
        app.getSystemService(ConnectivityManager::class.java)?.let(lanTracker::register)
        _notifications.value = prefs.getBoolean(KEY_NOTIFY, true)
        _presets.value = presetsFromJson(prefs.getString(KEY_PRESETS, null))
        // Names no longer in Lab (a feature that graduated or was dropped) are ignored.
        _labs.value = prefs.getStringSet(KEY_LABS, emptySet())!!.mapNotNull { n -> Lab.entries.firstOrNull { it.name == n } }.toSet()
        _clockWarning.value = prefs.getBoolean(KEY_CLOCK_WARNING, true)
        _remindBefore.value = prefs.getInt(KEY_REMIND, DEFAULT_REMIND)
        _notices.value = prefs.getStringSet(KEY_NOTICES, null)
            ?.mapNotNull { n -> Notice.entries.firstOrNull { it.name == n } }?.toSet() ?: Notice.defaults
        _dynamicColor.value = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        _language.value = languageChoice(prefs.getString(KEY_LANGUAGE, "")!!)
        _order.value = ListOrder(
            SortBy.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SortBy.NEWEST,
            prefs.getBoolean(KEY_UNKNOWN_FIRST, true),
        )
        val ip = prefs.getString(KEY_IP, null) ?: return
        val p = Printer(
            prefs.getString(KEY_NAME, "")!!, ip, prefs.getString(KEY_ID, "")!!, prefs.getString(KEY_CONN, "")!!,
        )
        use(SdcpClient(http, p, scope))
    }

    fun pick(p: Printer) {
        prefs.edit {
            putString(KEY_IP, p.ip); putString(KEY_NAME, p.name)
            putString(KEY_ID, p.mainboardId); putString(KEY_CONN, p.connId)
        }
        _client.value?.shutdown()
        use(SdcpClient(http, p, scope))
    }

    /**
     * The saved printer stopped answering: look for its mainboard id on the network and switch to its
     * new address (DHCP gave it another). Returns where it answered; null when it didn't, or for the demo.
     */
    suspend fun relocate(find: suspend () -> List<Printer> = ::scan): Printer? {
        val c = _client.value as? SdcpClient
        if (c == null || _searching.value) return null
        _searching.value = true
        try {
            val p = relocated(c.printer, find()) ?: return null
            if (_client.value !== c) return null // switched printers meanwhile
            if (p.ip == c.printer.ip) c.reconnect() else pick(p)
            return p
        } finally {
            _searching.value = false
        }
    }

    private suspend fun scan() = discover(broadcastTargets(app)) { lanDatagramSocket(lan) }

    /** Keep the saved printer's name in step after a rename (the demo isn't saved). */
    fun rememberName(name: String) {
        if (prefs.contains(KEY_IP)) prefs.edit { putString(KEY_NAME, name) }
    }

    /** Try the app with a simulated printer; nothing is saved, so a restart returns to the welcome screen. */
    fun startDemo() {
        _client.value?.shutdown()
        use(DemoPrinter(scope))
    }

    fun forget() {
        _client.value?.shutdown()
        prefs.edit { remove(KEY_IP); remove(KEY_NAME); remove(KEY_ID); remove(KEY_CONN) }
        use(null)
    }

    /** User-saved presets (built-ins live in [builtInPresets]); kept on this phone. */
    fun savePreset(p: Preset) {
        // Saving under an existing name replaces that preset.
        val list = _presets.value.filterNot { it.name.equals(p.name, ignoreCase = true) } + p
        prefs.edit { putString(KEY_PRESETS, presetsToJson(list)) }
        _presets.value = list
    }

    fun deletePreset(p: Preset) {
        val list = _presets.value - p
        prefs.edit { putString(KEY_PRESETS, presetsToJson(list)) }
        _presets.value = list
    }

    fun setNotifications(on: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFY, on) }
        _notifications.value = on
    }

    fun setClockWarning(on: Boolean) {
        prefs.edit { putBoolean(KEY_CLOCK_WARNING, on) }
        _clockWarning.value = on
    }

    fun setRemindBefore(minutes: Int) {
        prefs.edit { putInt(KEY_REMIND, minutes) }
        _remindBefore.value = minutes
    }

    fun setNotice(notice: Notice, on: Boolean) {
        val set = if (on) _notices.value + notice else _notices.value - notice
        prefs.edit { putStringSet(KEY_NOTICES, set.map { it.name }.toSet()) }
        _notices.value = set
    }

    fun setDynamicColor(on: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, on) }
        _dynamicColor.value = on
    }

    fun setLanguage(tag: String) {
        prefs.edit { putString(KEY_LANGUAGE, tag) }
        _language.value = tag
    }

    fun setOrder(order: ListOrder) {
        prefs.edit { putString(KEY_SORT, order.by.name); putBoolean(KEY_UNKNOWN_FIRST, order.unknownFirst) }
        _order.value = order
    }

    /** The start form's last choices on the printer with [mainboardId]; null before its first start from here. */
    fun startChoices(mainboardId: String) = StartChoices.decode(prefs.getString(KEY_START + mainboardId, null))

    fun rememberStart(mainboardId: String, choices: StartChoices) {
        prefs.edit { putString(KEY_START + mainboardId, choices.encode()) }
    }

    fun setLab(lab: Lab, on: Boolean) {
        val set = if (on) _labs.value + lab else _labs.value - lab
        prefs.edit { putStringSet(KEY_LABS, set.map { it.name }.toSet()) }
        _labs.value = set
    }

    private const val KEY_LABS = "labs"
    private const val KEY_DYNAMIC_COLOR = "dynamicColor"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_START = "start:"
    private const val KEY_SORT = "sort"
    private const val KEY_CLOCK_WARNING = "clockWarning"
    private const val KEY_REMIND = "remindBefore"
    private const val KEY_NOTICES = "notices"
    private const val DEFAULT_REMIND = 10
    private const val KEY_UNKNOWN_FIRST = "unknownFirst"
    private const val KEY_IP = "ip"
    private const val KEY_NAME = "name"
    private const val KEY_ID = "id"
    private const val KEY_CONN = "conn"
    private const val KEY_NOTIFY = "notify"
    private const val KEY_PRESETS = "presets"
}
