package com.lumiyaviewer.lumiya.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityMainBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.ui.chat.ChatNewActivity
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import com.lumiyaviewer.lumiya.ui.diagnostics.DiagnosticsActivity
import com.lumiyaviewer.lumiya.ui.inventory.InventoryActivity
import com.lumiyaviewer.lumiya.ui.service.ViewerSessionService
import com.lumiyaviewer.lumiya.ui.settings.SettingsActivity
import com.lumiyaviewer.lumiya.ui.world.RenderTestActivity
import com.lumiyaviewer.lumiya.ui.world.MovementActivity
import com.lumiyaviewer.lumiya.ui.world.WorldViewActivity
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.chatBtn.setOnClickListener { openScreen(ChatNewActivity::class.java) }
        binding.diagnosticsBtn.setOnClickListener { openScreen(DiagnosticsActivity::class.java) }
        binding.worldBtn.setOnClickListener { openScreen(WorldViewActivity::class.java) }
        binding.mapBtn.setOnClickListener { openScreen(MovementActivity::class.java) }
        binding.inventoryBtn.setOnClickListener { openScreen(InventoryActivity::class.java) }
        binding.renderTestBtn.setOnClickListener { openScreen(RenderTestActivity::class.java) }
        binding.settingsBtn.setOnClickListener { openScreen(SettingsActivity::class.java) }
        binding.disconnectBtn.setOnClickListener {
            SLClient.connection.disconnect()
            finish()
        }

        requestNotificationPermission()

        // The session has to survive this activity (and the user switching
        // apps), so the live connection is owned by a foreground service.
        lifecycleScope.launch {
            SLClient.connection.state.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED, ConnectionState.ERROR ->
                        ViewerSessionService.start(this@MainActivity)
                    ConnectionState.DISCONNECTED -> ViewerSessionService.stop(this@MainActivity)
                    else -> Unit
                }
            }
        }

        lifecycleScope.launch {
            SLClient.connection.session.collect { session ->
                binding.statusEl.text = SLClient.connection.status.value
                binding.regionEl.text = getString(R.string.main_region_label) + ": " +
                    (if (session.regionName.isEmpty()) "-" else session.regionName) +
                    "  (" + session.simIp + ":" + session.simPort + ")"
                binding.agentEl.text = getString(R.string.main_agent_label) + ": " +
                    (if (session.agentName.isEmpty()) "-" else session.agentName)
                val position = session.position
                val positionText = if (session.positionKnown) {
                    String.format(
                        Locale.US,
                        "%.1f, %.1f, %.1f",
                        position.x,
                        position.y,
                        position.z
                    )
                } else {
                    "-"
                }
                binding.positionEl.text = getString(R.string.main_position_label) + ": " + positionText
                binding.pingEl.text = getString(R.string.main_ping_label) + ": " +
                    session.roundTripMillis + " ms / " + session.pendingAcks + " pendientes"
                binding.trafficEl.text = getString(R.string.main_traffic_label) + ": " +
                    (session.bytesIn / 1024) + " KB in / " + (session.bytesOut / 1024) + " KB out"
            }
        }
    }

    private fun openScreen(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }

    /** Android 13+ hides the session notification unless the user allows it. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}
