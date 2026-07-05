package dev.rocky.moonlightnat

import android.content.Context

object SettingsStore {
    private const val PREFS = "moonlight_nat_adapter"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_VIRTUAL_IP = "virtual_ip"

    const val DEFAULT_VIRTUAL_IP = "45.66.66.2"

    fun webhookUrl(context: Context): String =
        prefs(context).getString(KEY_WEBHOOK_URL, "") ?: ""

    fun virtualIp(context: Context): String =
        prefs(context).getString(KEY_VIRTUAL_IP, DEFAULT_VIRTUAL_IP) ?: DEFAULT_VIRTUAL_IP

    fun save(context: Context, webhookUrl: String, virtualIp: String) {
        prefs(context).edit()
            .putString(KEY_WEBHOOK_URL, webhookUrl.trim())
            .putString(KEY_VIRTUAL_IP, virtualIp.trim())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
