package com.lumiyaviewer.lumiya.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityDiagnosticsBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.selftest.ProtocolSelfTest
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import kotlinx.coroutines.launch

class DiagnosticsActivity : BaseActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.copyBtn.setOnClickListener {
            val text = binding.logEl.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("lumiya-log", text))
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
        binding.clearBtn.setOnClickListener {
            SLClient.connection.clearLog()
        }
        binding.testBtn.setOnClickListener {
            binding.testBtn.isEnabled = false
            SLClient.connection.clearLog()
            SLClient.connection.addLogLine(getString(R.string.diagnostics_running))
            lifecycleScope.launch {
                val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    ProtocolSelfTest.run(this@DiagnosticsActivity)
                }
                for (line in report.lines) {
                    SLClient.connection.addLogLine(line)
                }
                SLClient.connection.addLogLine("")
                SLClient.connection.addLogLine(
                    if (report.ok) {
                        "Auto-test completado: " + report.passed + " pruebas correctas."
                    } else {
                        "Auto-test con fallos: " + report.passed + " correctas, " + report.failed + " fallidas."
                    }
                )
                binding.testBtn.isEnabled = true
                Toast.makeText(
                    this@DiagnosticsActivity,
                    if (report.ok) "Auto-test correcto" else "Auto-test con fallos",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        lifecycleScope.launch {
            SLClient.connection.log.collect { lines ->
                binding.logEl.text = if (lines.isEmpty()) {
                    getString(R.string.diagnostics_empty)
                } else {
                    lines.joinToString("\n")
                }
                binding.logScroll.post {
                    binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }
    }
}
