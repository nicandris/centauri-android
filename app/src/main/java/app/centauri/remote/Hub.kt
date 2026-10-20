package app.centauri.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * App-wide printer connection, shared by the UI and [PrintMonitorService] so a running print
 * keeps one WebSocket whether the app is open or not.
 */
object Hub {
    // Protocol-level ping every 30 s, same heartbeat as the HA client.
    val http: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val client = MutableStateFlow<SdcpClient?>(null)
    val notifications = MutableStateFlow(true)

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("printer", Context.MODE_PRIVATE)
        notifications.value = prefs.getBoolean("notify", true)
        val ip = prefs.getString("ip", null) ?: return
        val p = Printer(prefs.getString("name", "")!!, ip, prefs.getString("id", "")!!, prefs.getString("conn", "")!!)
        client.value = SdcpClient(http, p, scope)
    }

    fun pick(p: Printer) {
        prefs.edit().putString("ip", p.ip).putString("name", p.name).putString("id", p.mainboardId)
            .putString("conn", p.connId).apply()
        client.value?.shutdown()
        client.value = SdcpClient(http, p, scope)
    }

    fun forget() {
        client.value?.shutdown()
        prefs.edit().remove("ip").remove("name").remove("id").remove("conn").apply()
        client.value = null
    }

    fun setNotifications(on: Boolean) {
        prefs.edit().putBoolean("notify", on).apply()
        notifications.value = on
    }
}
