package com.lumiyaviewer.lumiya.ui.settings

import android.content.Intent
import android.os.Bundle
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivitySettingsBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.login.LoginModule
import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplate
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import com.lumiyaviewer.lumiya.ui.diagnostics.DiagnosticsActivity

/**
 * Real session information: what channel/version is announced to the grid, which
 * login endpoint is in use, how many protocol templates were loaded.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val loginUri = if (SLClient.loginUri.isEmpty()) "-" else SLClient.loginUri
        binding.sessionInfoEl.text = buildString {
            append(getString(R.string.settings_value_version, LoginModule.VERSION))
            append('\n')
            append(getString(R.string.settings_value_channel, LoginModule.CHANNEL))
            append('\n')
            append(getString(R.string.settings_value_grid, SLClient.gridName.ifEmpty { "-" }))
            append('\n')
            append(loginUri)
            append('\n')
            append(getString(R.string.settings_value_templates, MessageTemplate.loadedCount))
            append('\n')
            append(getString(R.string.settings_value_state, SLClient.connection.status.value))
        }

        binding.selftestBtn.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.disconnectBtn.setOnClickListener {
            SLClient.connection.disconnect()
            finish()
        }
    }
}
