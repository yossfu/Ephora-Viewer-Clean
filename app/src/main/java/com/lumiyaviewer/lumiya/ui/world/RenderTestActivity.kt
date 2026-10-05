package com.lumiyaviewer.lumiya.ui.world

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityRenderTestBinding
import com.lumiyaviewer.lumiya.slscene.SLTestScene
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import com.lumiyaviewer.lumiya.ui.diag.FilamentProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The no-login rendering screen: the same [FilamentWorldView] the region uses,
 * driven through the same `Renderer` interface, but with content that needs no
 * session at all.
 *
 * It starts in [RenderContent.PROBE] — geometry built directly by the renderer,
 * with no Second Life data anywhere — which is the acceptance test that isolates
 * the graphics path (SurfaceView → SwapChain → Renderer → View → Scene → Camera
 * → present). The mode button cycles to the fixed row of prim shapes (which does
 * exercise the Second Life parameter → `slcore` → mesh path) and to the region
 * modes, which simply have nothing to show without a session.
 *
 * Every number the renderer and the scene layer know is one button away: the
 * DIAG panel shows them, and the same report goes to logcat under the tag
 * `EphoraDiag`.
 */
class RenderTestActivity : BaseActivity() {

    private lateinit var binding: ActivityRenderTestBinding
    private lateinit var worldView: FilamentWorldView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRenderTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        worldView = FilamentWorldView(this)
        worldView.content = RenderContent.PROBE
        worldView.testPattern = { SLTestScene.buildDelta() }
        binding.testViewportCtn.addView(
            worldView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.modeBtn.setOnClickListener {
            worldView.cycleContent()
            updateHud()
        }
        binding.forceBtn.setOnClickListener {
            val forced = worldView.toggleForcedObject()
            android.widget.Toast.makeText(
                this,
                if (forced) {
                    getString(R.string.world_force_on_hint, FilamentWorldView.FORCED_DISTANCE.toInt())
                } else {
                    getString(R.string.world_force_off_hint)
                },
                android.widget.Toast.LENGTH_SHORT
            ).show()
            updateHud()
        }
        binding.diagBtn.setOnClickListener { setDiagnosticsVisible(true) }
        binding.diagCloseBtn.setOnClickListener { setDiagnosticsVisible(false) }
        binding.saveReportBtn.setOnClickListener {
            val file = worldView.writeReport()
            android.widget.Toast.makeText(
                this,
                if (file == null) {
                    getString(R.string.world_diag_save_failed)
                } else {
                    getString(R.string.world_diag_saved, file.absolutePath)
                },
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        binding.probeBtn.setOnClickListener {
            startActivity(android.content.Intent(this, FilamentProbeActivity::class.java))
        }

        lifecycleScope.launch {
            while (true) {
                updateHud()
                delay(HUD_INTERVAL_MILLIS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        worldView.setActive(true)
    }

    override fun onPause() {
        worldView.setActive(false)
        super.onPause()
    }

    private fun updateHud() {
        val stats = worldView.stats
        val builder = StringBuilder(220)
        builder.append(getString(R.string.render_test_hud_backend, worldView.backendName.ifEmpty { "-" }))
        builder.append("\n").append(worldView.content.description)
        builder.append("  ·  ").append(worldView.sceneText)
        if (worldView.statusText.isNotEmpty()) {
            builder.append('\n').append(worldView.statusText)
        } else if (!worldView.ready) {
            builder.append('\n').append(getString(R.string.render_test_starting))
        }
        binding.infoEl.text = builder.toString()

        binding.fpsEl.text = String.format(
            Locale.US,
            "%1${'$'}d visibles · %2${'$'}d tris · %3${'$'}.0f FPS · %4${'$'}.1f ms CPU · %5${'$'}.1f ms GPU",
            worldView.drawnObjects,
            worldView.drawnTriangles,
            stats.framesPerSecond,
            stats.cpuFrameMillis,
            stats.gpuFrameMillis
        )

        binding.modeBtn.text = getString(
            when (worldView.content) {
                RenderContent.REGION -> R.string.world_mode_region
                RenderContent.TERRAIN -> R.string.world_mode_terrain
                RenderContent.PRIM_ROW -> R.string.world_mode_prims
                RenderContent.PROBE -> R.string.world_mode_probe
            }
        )

        if (binding.diagPanel.visibility == View.VISIBLE) {
            updateDiagnosticsPanel()
        }
    }

    private fun updateDiagnosticsPanel() {
        binding.verdictsEl.text = worldView.verdictLines().joinToString("\n")
        // HUD counters, then the start-up log with every step and every failure
        // in full (class, message, cause chain, stack trace).
        binding.diagEl.text = (worldView.hudLines() + worldView.startUpLines()).joinToString("\n")
    }

    private fun setDiagnosticsVisible(visible: Boolean) {
        binding.diagPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            worldView.writeReport()
            updateDiagnosticsPanel()
        }
    }

    private companion object {
        const val HUD_INTERVAL_MILLIS = 350L
    }
}
