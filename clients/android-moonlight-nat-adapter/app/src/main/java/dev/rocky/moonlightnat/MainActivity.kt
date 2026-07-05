package dev.rocky.moonlightnat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webhookEdit: EditText
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            statusText.text = AdapterStatus.renderText()
            handler.postDelayed(this, refreshIntervalMillis())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNotificationPermissionIfNeeded()
        handler.post(refreshRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android framework but enough for this PoC UI.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            setBackgroundColor(Color.rgb(245, 247, 250))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val title = TextView(this).apply {
            text = "Moonlight NAT Adapter"
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(20, 28, 38))
        }
        val subtitle = TextView(this).apply {
            text = "Fill webhook URL, start VPN, then add the host IP below in Moonlight."
            textSize = 14f
            setTextColor(Color.rgb(80, 90, 105))
            setPadding(0, 6.dp(), 0, 14.dp())
        }
        val virtualIpInfo = TextView(this).apply {
            text = "Moonlight host IP: ${SettingsStore.DEFAULT_VIRTUAL_IP}"
            textSize = 15f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(20, 28, 38))
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            background = GradientDrawable().apply {
                setColor(Color.rgb(232, 246, 236))
                setStroke(1.dp(), Color.rgb(150, 205, 164))
                cornerRadius = 8.dp().toFloat()
            }
        }
        val verboseLogsCheck = CheckBox(this).apply {
            text = "Verbose logs"
            textSize = 14f
            setTextColor(Color.rgb(44, 54, 68))
            isChecked = AdapterStatus.isVerboseLogging()
            setPadding(0, 8.dp(), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                AdapterStatus.setVerboseLogging(checked)
                statusText.text = AdapterStatus.renderText()
                handler.removeCallbacks(refreshRunnable)
                handler.postDelayed(refreshRunnable, refreshIntervalMillis())
            }
        }

        webhookEdit = EditText(this).apply {
            hint = "https://example.com/moonlight-mapping.json"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(SettingsStore.webhookUrl(this@MainActivity))
            applyFieldStyle()
        }
        val startButton = Button(this).apply {
            text = "Start VPN"
            setOnClickListener {
                saveSettings()
                val permissionIntent = VpnService.prepare(this@MainActivity)
                if (permissionIntent != null) {
                    startActivityForResult(permissionIntent, REQUEST_VPN)
                } else {
                    startVpnService()
                }
            }
        }
        val stopButton = Button(this).apply {
            text = "Stop VPN"
            setOnClickListener { stopVpnService() }
        }
        statusText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(20, 28, 38))
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1.dp(), Color.rgb(210, 216, 224))
                cornerRadius = 8.dp().toFloat()
            }
            setTextIsSelectable(true)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12.dp(), 0, 12.dp())
            addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val scroll = ScrollView(this).apply {
            addView(statusText)
        }

        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(subtitle, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(label("Webhook URL"), LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(webhookEdit, LinearLayout.LayoutParams.MATCH_PARENT, 52.dp())
        root.addView(virtualIpInfo, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(verboseLogsCheck, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(buttonRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun refreshIntervalMillis(): Long =
        if (AdapterStatus.isVerboseLogging()) 1_000L else 2_500L

    private fun label(text: String): TextView {
        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(44, 54, 68))
            setPadding(0, 8.dp(), 0, 4.dp())
        }
    }

    private fun EditText.applyFieldStyle() {
        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()
        setTextColor(Color.rgb(20, 28, 38))
        setHintTextColor(Color.rgb(130, 140, 154))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(12.dp(), 0, 12.dp(), 0)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(1.dp(), Color.rgb(172, 184, 198))
            cornerRadius = 8.dp().toFloat()
        }
    }

    private fun saveSettings() {
        SettingsStore.save(
            this,
            webhookEdit.text.toString(),
            SettingsStore.DEFAULT_VIRTUAL_IP,
        )
    }

    private fun startVpnService() {
        val webhookUrl = webhookEdit.text.toString().trim()
        if (webhookUrl.isBlank()) {
            Toast.makeText(this, "Webhook URL is required", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, NatAdapterVpnService::class.java).apply {
            action = NatAdapterVpnService.ACTION_START
            putExtra(NatAdapterVpnService.EXTRA_WEBHOOK_URL, webhookUrl)
            putExtra(NatAdapterVpnService.EXTRA_VIRTUAL_IP, SettingsStore.DEFAULT_VIRTUAL_IP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, NatAdapterVpnService::class.java).apply {
            action = NatAdapterVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val REQUEST_VPN = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
    }
}
