package app.centauri.remote

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // Protocol-level ping every 30 s, same heartbeat as the HA client.
    val http: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _client = MutableStateFlow<PrinterLink?>(null)
    private val _notifications = MutableStateFlow(true)
    val client: StateFlow<PrinterLink?> = _client.asStateFlow()
    val notifications: StateFlow<Boolean> = _notifications.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(app: Application) {
        prefs = app.getSharedPreferences("printer", Context.MODE_PRIVATE)
        _notifications.value = prefs.getBoolean(KEY_NOTIFY, true)
        val ip = prefs.getString(KEY_IP, null) ?: return
        val p = Printer(
            prefs.getString(KEY_NAME, "")!!, ip, prefs.getString(KEY_ID, "")!!, prefs.getString(KEY_CONN, "")!!,
        )
        _client.value = SdcpClient(http, p, scope)
    }

    fun pick(p: Printer) {
        prefs.edit {
            putString(KEY_IP, p.ip); putString(KEY_NAME, p.name)
            putString(KEY_ID, p.mainboardId); putString(KEY_CONN, p.connId)
        }
        _client.value?.shutdown()
        _client.value = SdcpClient(http, p, scope)
    }

    /** Try the app with a simulated printer; nothing is saved, so a restart returns to the welcome screen. */
    fun startDemo() {
        _client.value?.shutdown()
        _client.value = DemoPrinter(scope)
    }

    fun forget() {
        _client.value?.shutdown()
        prefs.edit { remove(KEY_IP); remove(KEY_NAME); remove(KEY_ID); remove(KEY_CONN) }
        _client.value = null
    }

    fun setNotifications(on: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFY, on) }
        _notifications.value = on
    }

    private const val KEY_IP = "ip"
    private const val KEY_NAME = "name"
    private const val KEY_ID = "id"
    private const val KEY_CONN = "conn"
    private const val KEY_NOTIFY = "notify"
}
