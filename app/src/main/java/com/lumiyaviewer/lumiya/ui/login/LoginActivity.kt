package com.lumiyaviewer.lumiya.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityLoginBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.grids.Grid
import com.lumiyaviewer.lumiya.slproto.grids.GridManager
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import com.lumiyaviewer.lumiya.ui.diag.FilamentProbeActivity
import com.lumiyaviewer.lumiya.ui.diagnostics.DiagnosticsActivity
import com.lumiyaviewer.lumiya.ui.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding

    private var navigatedToMain = false

    private lateinit var grids: List<Grid>

    private val prefs by lazy {
        getSharedPreferences("lumiya_login", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        grids = GridManager.all()
        val labels = ArrayList<String>()
        for (grid in grids) {
            labels.add(grid.name)
        }
        labels.add(getString(R.string.login_other_grid))

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.gridSpinner.adapter = adapter

        val savedGrid = prefs.getString("grid", "")
        val savedUri = prefs.getString("grid_uri", "") ?: ""
        val gridIndex = grids.indexOfFirst { grid -> grid.id == savedGrid }
        if (gridIndex >= 0) {
            binding.gridSpinner.setSelection(gridIndex)
        } else if (savedGrid == CUSTOM_ID) {
            binding.gridSpinner.setSelection(labels.size - 1)
        } else {
            binding.gridSpinner.setSelection(grids.indexOfFirst { grid -> grid.isDefault }.coerceAtLeast(0))
        }
        binding.gridUriInput.setText(savedUri)
        binding.firstNameInput.setText(prefs.getString("first", "") ?: "")
        binding.lastNameInput.setText(prefs.getString("last", "") ?: "")
        updateCustomVisibility()

        binding.gridSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCustomVisibility()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                updateCustomVisibility()
            }
        }

        binding.connectBtn.setOnClickListener {
            val index = binding.gridSpinner.selectedItemPosition
            val custom = index < 0 || index >= grids.size
            val grid = if (custom) {
                val uri = binding.gridUriInput.text.toString().trim()
                if (uri.isEmpty()) {
                    showStatus(getString(R.string.login_need_grid_uri))
                    return@setOnClickListener
                }
                Grid(id = CUSTOM_ID, name = "Personalizado", loginUri = uri, openSim = true)
            } else {
                grids[index]
            }
            val first = binding.firstNameInput.text.toString().trim()
            val last = binding.lastNameInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()

            if (first.isEmpty() || password.isEmpty()) {
                showStatus(getString(R.string.login_missing_fields))
                return@setOnClickListener
            }

            prefs.edit()
                .putString("first", first)
                .putString("last", last)
                .putString("grid", grid.id)
                .putString("grid_uri", if (custom) grid.loginUri else "")
                .apply()

            SLClient.gridName = grid.name
            SLClient.loginUri = grid.loginUri
            SLClient.connection.connect(grid, first, last, password)
        }

        binding.diagnosticsBtn.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.probeBtn.setOnClickListener {
            startActivity(Intent(this, FilamentProbeActivity::class.java))
        }

        lifecycleScope.launch {
            SLClient.connection.state.collect { state ->
                binding.statusEl.text = SLClient.connection.status.value
                when (state) {
                    ConnectionState.LOGGING_IN, ConnectionState.CONNECTING -> {
                        binding.progressEl.visibility = View.VISIBLE
                        binding.statusEl.visibility = View.VISIBLE
                        binding.connectBtn.isEnabled = false
                    }
                    ConnectionState.CONNECTED -> {
                        binding.progressEl.visibility = View.GONE
                        binding.connectBtn.isEnabled = true
                        if (!navigatedToMain) {
                            navigatedToMain = true
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        }
                    }
                    else -> {
                        binding.progressEl.visibility = View.GONE
                        binding.connectBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun updateCustomVisibility() {
        val index = binding.gridSpinner.selectedItemPosition
        val custom = index < 0 || index >= grids.size
        binding.gridUriLayout.visibility = if (custom) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: String) {
        binding.statusEl.text = text
        binding.statusEl.visibility = View.VISIBLE
    }

    private companion object {
        const val CUSTOM_ID = "custom"
    }
}
