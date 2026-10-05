package com.lumiyaviewer.lumiya.ui.world

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityWorldViewBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.slproto.movement.MoveAction
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The 3D world: a `SurfaceView` running the Filament renderer with the classic SL
 * overlay controls — region and position readout, mini-map, and the movement pad
 * that drives the real `AgentUpdate` control flags.
 */
class WorldViewActivity : BaseActivity() {

    private lateinit var binding: ActivityWorldViewBinding
    private lateinit var worldView: FilamentWorldView
    private val nearbyBuffer = ArrayList<SceneObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorldViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        worldView = FilamentWorldView(this)
        binding.viewportCtn.addView(
            worldView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        holdToMove(binding.forwardBtn, MoveAction.FORWARD)
        holdToMove(binding.backwardBtn, MoveAction.BACKWARD)
        holdToMove(binding.strafeLeftBtn, MoveAction.STRAFE_LEFT)
        holdToMove(binding.strafeRightBtn, MoveAction.STRAFE_RIGHT)
        holdToMove(binding.turnLeftBtn, MoveAction.TURN_LEFT)
        holdToMove(binding.turnRightBtn, MoveAction.TURN_RIGHT)
        holdToMove(binding.upBtn, MoveAction.UP)
        holdToMove(binding.downBtn, MoveAction.DOWN)
        holdToMove(binding.runBtn, MoveAction.RUN)

        binding.flyBtn.setOnClickListener {
            val connection = SLClient.connection
            connection.setFly(!connection.isFlying)
        }
        binding.stopBtn.setOnClickListener { SLClient.connection.stopMovement() }
        binding.recenterBtn.setOnClickListener { worldView.recenterCamera() }
        binding.mapBtn.setOnClickListener { startActivity(Intent(this, MovementActivity::class.java)) }
        binding.nearbyBtn.setOnClickListener { showNearby() }

        // Diagnostic controls. Each one reports what it did in the HUD, so a
        // screenshot of the screen is enough to say which state was on when the
        // picture was taken.
        binding.modeBtn.setOnClickListener {
            worldView.cycleContent()
            updateHud()
        }
        binding.frameBtn.setOnClickListener {
            worldView.cycleFraming()
            updateHud()
        }
        binding.forceBtn.setOnClickListener {
            val forced = worldView.toggleForcedObject()
            toast(
                if (forced) {
                    getString(R.string.world_force_on_hint, FilamentWorldView.FORCED_DISTANCE.toInt())
                } else {
                    getString(R.string.world_force_off_hint)
                }
            )
            updateHud()
        }

        // Fase 2.9: las dos pruebas reversibles de visibilidad. El probe dibuja
        // un cubo propio del visor delante de la camara; el bloqueo mueve solo la
        // camara hasta mirar un prim real. Ninguna de las dos toca la escena.
        binding.probeBtn.setOnClickListener {
            val on = worldView.toggleCameraProbe()
            toast(
                if (on) {
                    getString(R.string.world_camera_probe_on_hint)
                } else {
                    getString(R.string.world_camera_probe_off_hint)
                }
            )
            updateHud()
        }
        binding.lockPrimBtn.setOnClickListener {
            val on = worldView.togglePrimLock()
            toast(
                if (on) {
                    getString(R.string.world_lock_prim_on_hint)
                } else {
                    getString(R.string.world_lock_prim_off_hint)
                }
            )
            updateHud()
        }
        // TEST7: camara de prueba controlada. Solo mueve la camara; el informe
        // TEST7 registra la pose para que la comparacion sea controlada.
        binding.avatarCamBtn.setOnClickListener {
            val on = worldView.toggleAvatarCam()
            toast(
                if (on) {
                    getString(R.string.world_avatarcam_on_hint)
                } else {
                    getString(R.string.world_avatarcam_off_hint)
                }
            )
            updateHud()
        }
        // Fase 2.12: los cuatro interruptores de escalado. Cada uno se aplica en
        // el hilo de render y es reversible, para poder medir el antes y el
        // despues con el mismo binario y solo ese ajuste distinto.
        binding.cullBtn.setOnClickListener {
            val on = worldView.toggleFrustumCulling()
            toast(
                if (on) {
                    getString(R.string.world_cull_on_hint)
                } else {
                    getString(R.string.world_cull_off_hint)
                }
            )
            updateHud()
        }
        binding.distBtn.setOnClickListener {
            val on = worldView.toggleDistanceCulling()
            toast(
                if (on) {
                    getString(R.string.world_dist_on_hint)
                } else {
                    getString(R.string.world_dist_off_hint)
                }
            )
            updateHud()
        }
        binding.shadowBtn.setOnClickListener {
            val on = worldView.toggleShadows()
            toast(
                if (on) {
                    getString(R.string.world_shadow_on_hint)
                } else {
                    getString(R.string.world_shadow_off_hint)
                }
            )
            updateHud()
        }
        binding.lodBtn.setOnClickListener {
            val on = worldView.toggleShadowLod()
            toast(
                if (on) {
                    getString(R.string.world_lod_on_hint, FilamentWorldView.SHADOW_LOD_DISTANCE.toInt())
                } else {
                    getString(R.string.world_lod_off_hint)
                }
            )
            updateHud()
        }
        // Fase 2.10: seguir un objeto concreto. El localID se escribe (o se toca
        // en la lista de objetos cercanos); no hay ninguno fijado en el codigo.
        binding.focusBtn.setOnClickListener { askFocusLocalId() }
        // TEST9: secuencia por objeto con reporte unico. El informe TEST9
        // acumula todo; no hacen falta varios reportes.
        binding.test9Btn.setOnClickListener {
            val on = worldView.toggleTest9()
            toast(
                if (on) {
                    getString(R.string.world_test9_on_hint)
                } else {
                    getString(R.string.world_test9_off_hint)
                }
            )
            updateHud()
        }
        // TEST8: pruebas reversibles de culling/AABB. El informe TEST8 registra
        // que contienen y como cambia el conteo de dibujados.
        binding.test8NoCullBtn.setOnClickListener {
            val on = worldView.toggleTest8NoCull()
            toast(
                if (on) {
                    getString(R.string.world_test8nocull_on_hint)
                } else {
                    getString(R.string.world_test8nocull_off_hint)
                }
            )
            updateHud()
        }
        binding.test8BoxBtn.setOnClickListener {
            val on = worldView.toggleTest8WideBox()
            toast(
                if (on) {
                    getString(R.string.world_test8box_on_hint)
                } else {
                    getString(R.string.world_test8box_off_hint)
                }
            )
            updateHud()
        }
        binding.test10Btn.setOnClickListener {
            val on = worldView.toggleTest10()
            toast(
                if (on) {
                    getString(R.string.world_test10_on_hint)
                } else {
                    getString(R.string.world_test10_off_hint)
                }
            )
            updateHud()
        }
        binding.test11Btn.setOnClickListener {
            val on = worldView.toggleTest11()
            toast(
                if (on) {
                    getString(R.string.world_test11_on_hint)
                } else {
                    getString(R.string.world_test11_off_hint)
                }
            )
            updateHud()
        }
        binding.test12Btn.setOnClickListener {
            val on = worldView.toggleTest12()
            toast(
                if (on) {
                    getString(R.string.world_test12_on_hint)
                } else {
                    getString(R.string.world_test12_off_hint)
                }
            )
            updateHud()
        }
        binding.test13Btn.setOnClickListener {
            val on = worldView.toggleTest13()
            toast(
                if (on) {
                    getString(R.string.world_test13_on_hint)
                } else {
                    getString(R.string.world_test13_off_hint)
                }
            )
            updateHud()
        }
        binding.test14Btn.setOnClickListener {
            val on = worldView.toggleTest16()
            toast(
                if (on) {
                    getString(R.string.world_test14_on_hint)
                } else {
                    getString(R.string.world_test14_off_hint)
                }
            )
            updateHud()
        }
        binding.diagBtn.setOnClickListener { setDiagnosticsVisible(true) }
        binding.diagCloseBtn.setOnClickListener { setDiagnosticsVisible(false) }
        binding.saveReportBtn.setOnClickListener {
            val file = worldView.writeReport()
            toast(
                if (file == null) {
                    getString(R.string.world_diag_save_failed)
                } else {
                    getString(R.string.world_diag_saved, file.absolutePath)
                }
            )
        }
        binding.copyReportBtn.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ephora-report", worldView.fullReportText()))
            toast(getString(R.string.diagnostics_copied))
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
        if (SLClient.connection.state.value == ConnectionState.CONNECTED ||
            SLClient.connection.state.value == ConnectionState.ERROR) {
            com.lumiyaviewer.lumiya.ui.service.ViewerSessionService.start(this)
        }
    }

    override fun onPause() {
        worldView.setActive(false)
        super.onPause()
    }

    private fun updateHud() {
        val session = SLClient.connection.session.value
        val stats = worldView.stats
        val connection = SLClient.connection
        val builder = StringBuilder(160)
        builder.append(getString(R.string.world_region_label)).append(": ")
        builder.append(if (session.regionName.isEmpty()) "-" else session.regionName)
        builder.append("   ·   ").append(if (session.agentName.isEmpty()) "-" else session.agentName)
        builder.append('\n')
        builder.append(getString(R.string.world_position_label)).append(": ")
        if (session.positionKnown) {
            builder.append(
                String.format(
                    Locale.US,
                    "%.0f, %.0f, %.0f m",
                    session.position.x,
                    session.position.y,
                    session.position.z
                )
            )
        } else {
            builder.append("-")
        }
        builder.append("   ·   ").append(getString(R.string.world_heading_label)).append(": ")
        builder.append(String.format(Locale.US, "%.0f°", session.headingDegrees))
        builder.append('\n')
        builder.append(getString(R.string.world_water_label)).append(": ")
        builder.append(String.format(Locale.US, "%.0f m", session.waterHeight))
        builder.append("   ·   ").append(getString(R.string.world_terrain_label)).append(": ")
        if (session.terrainReady) {
            builder.append(String.format(Locale.US, "%.0f .. %.0f m", session.terrainMin, session.terrainMax))
        } else {
            builder.append("-")
        }
        builder.append('\n')
        builder.append(getString(R.string.world_objects_label)).append(": ")
        builder.append(session.objectCount).append(" (").append(session.avatarCount).append(' ')
        builder.append(getString(R.string.world_avatars_word)).append(")")
        builder.append("   ·   ").append(getString(R.string.world_drawn_label)).append(": ")
        builder.append(worldView.drawnObjects).append(" / ").append(worldView.drawnTriangles / 1000).append("k tris")
        builder.append('\n').append(worldView.sceneText)
        builder.append('\n').append(
            getString(
                R.string.world_state_line,
                worldView.content.description,
                worldView.framing.label,
                if (worldView.forceObject) getString(R.string.world_force_on) else getString(R.string.world_force_off)
            )
        )
        if (worldView.statusText.isNotEmpty()) {
            builder.append('\n').append(worldView.statusText)
        } else if (!worldView.ready) {
            builder.append('\n').append(getString(R.string.world_gl_starting))
        }
        if (worldView.cameraProbeEnabled || worldView.primLockEnabled) {
            builder.append('\n').append(
                getString(
                    R.string.world_visibility_state,
                    if (worldView.cameraProbeEnabled) {
                        getString(R.string.world_camera_probe_on)
                    } else {
                        getString(R.string.world_camera_probe_off)
                    },
                    if (worldView.primLockEnabled) {
                        getString(R.string.world_lock_prim_on)
                    } else {
                        getString(R.string.world_lock_prim_off)
                    }
                )
            )
        }
        if (worldView.focusPrimLocalId != 0) {
            builder.append('\n').append(getString(R.string.world_focus_state, worldView.focusLabel()))
        }
        builder.append('\n').append(
            getString(
                R.string.world_scale_state,
                getString(if (worldView.frustumCullingRequested) R.string.world_cull_on else R.string.world_cull_off),
                getString(if (worldView.distanceCullingRequested) R.string.world_dist_on else R.string.world_dist_off),
                getString(if (worldView.shadowsRequested) R.string.world_shadow_on else R.string.world_shadow_off),
                getString(if (worldView.shadowLodRequested) R.string.world_lod_on else R.string.world_lod_off)
            )
        )
        if (connection.state.value != ConnectionState.CONNECTED) {
            builder.append('\n').append(getString(R.string.world_signal_lost))
        }
        binding.infoEl.text = builder.toString()
        binding.fpsEl.text = getString(R.string.world_fps, stats.framesPerSecond)

        binding.modeBtn.text = getString(
            when (worldView.content) {
                RenderContent.REGION -> R.string.world_mode_region
                RenderContent.TERRAIN -> R.string.world_mode_terrain
                RenderContent.PRIM_ROW -> R.string.world_mode_prims
                RenderContent.PROBE -> R.string.world_mode_probe
            }
        )
        binding.frameBtn.text = getString(
            if (worldView.framing == CameraFraming.AGENT) {
                R.string.world_frame_agent
            } else {
                R.string.world_frame_world
            }
        )
        binding.forceBtn.text = getString(
            if (worldView.forceObject) R.string.world_force_on else R.string.world_force_off
        )
        binding.probeBtn.text = getString(
            if (worldView.cameraProbeEnabled) {
                R.string.world_camera_probe_on
            } else {
                R.string.world_camera_probe_off
            }
        )
        binding.lockPrimBtn.text = getString(
            if (worldView.primLockEnabled) R.string.world_lock_prim_on else R.string.world_lock_prim_off
        )
        binding.focusBtn.text = getString(
            if (worldView.focusPrimLocalId != 0) R.string.world_focus_on else R.string.world_focus_off,
            worldView.focusPrimLocalId
        )
        binding.cullBtn.text = getString(
            if (worldView.frustumCullingRequested) R.string.world_cull_on else R.string.world_cull_off
        )
        binding.distBtn.text = getString(
            if (worldView.distanceCullingRequested) R.string.world_dist_on else R.string.world_dist_off
        )
        binding.shadowBtn.text = getString(
            if (worldView.shadowsRequested) R.string.world_shadow_on else R.string.world_shadow_off
        )
        binding.lodBtn.text = getString(
            if (worldView.shadowLodRequested) R.string.world_lod_on else R.string.world_lod_off
        )
        val t10done = worldView.diagnostics.test10Done
        val t10total = worldView.diagnostics.test10Total
        binding.test10Btn.text = (
            if (worldView.test10Requested) getString(R.string.world_test10_on) else getString(R.string.world_test10_off)
            ) + if (t10total > 0 && t10done >= 0) " $t10done/$t10total" else ""
        binding.test10Bar.visibility = if (worldView.test10Requested && t10total > 0) View.VISIBLE else View.GONE
        if (t10total > 0) {
            binding.test10Bar.max = t10total
            binding.test10Bar.progress = t10done.coerceIn(0, t10total)
        }
        val t11done = worldView.diagnostics.test11Done
        val t11total = worldView.diagnostics.test11Total
        val t11state = worldView.diagnostics.test11State
        binding.test11Btn.text = if (worldView.test11Requested) getString(R.string.world_test11_on) else getString(R.string.world_test11_off)
        val t11show = worldView.test11Requested || t11state == "EJECUTANDO" || t11state == "TERMINADO" || t11state == "ERROR"
        binding.test11StatusEl.visibility = if (t11show) View.VISIBLE else View.GONE
        binding.test11Bar.visibility = if (t11show) View.VISIBLE else View.GONE
        if (t11show) {
            val pct = if (t11total > 0 && t11done >= 0) t11done * 100 / t11total else 0
            val barLen = 10
            val filled = if (t11total > 0 && t11done >= 0) (t11done * barLen / t11total).coerceIn(0, barLen) else 0
            val bar = StringBuilder()
            for (i in 0 until barLen) bar.append(if (i < filled) '#' else '-')
            val cur = if (t11done >= 0 && t11total > 0) "$t11done/$t11total" else "0/5"
            binding.test11StatusEl.text = "Estado: $t11state\nProgreso: $cur ($pct%)\n[$bar] $pct%"
            binding.test11Bar.max = if (t11total > 0) t11total else 5
            binding.test11Bar.progress = if (t11done >= 0) t11done.coerceIn(0, binding.test11Bar.max) else 0
        }
        val t12done = worldView.diagnostics.test12Done
        val t12total = worldView.diagnostics.test12Total
        val t12state = worldView.diagnostics.test12State
        binding.test12Btn.text = if (worldView.test12Requested) getString(R.string.world_test12_on) else getString(R.string.world_test12_off)
        binding.test12Btn.visibility = View.GONE
        val t12show = worldView.test12Requested || t12state == "EJECUTANDO" || t12state == "TERMINADO" || t12state == "ERROR"
        binding.test12StatusEl.visibility = if (t12show) View.VISIBLE else View.GONE
        binding.test12Bar.visibility = if (t12show) View.VISIBLE else View.GONE
        if (t12show) {
            val pct = if (t12total > 0 && t12done >= 0) t12done * 100 / t12total else 0
            val barLen = 10
            val filled = if (t12total > 0 && t12done >= 0) (t12done * barLen / t12total).coerceIn(0, barLen) else 0
            val bar = StringBuilder()
            for (i in 0 until barLen) bar.append(if (i < filled) '#' else '-')
            val cur = if (t12done >= 0 && t12total > 0) "$t12done/$t12total" else "0/$t12total"
            binding.test12StatusEl.text = "Estado: $t12state\nProgreso: $cur ($pct%)\n[$bar] $pct%"
            binding.test12Bar.max = 100
            binding.test12Bar.progress = pct
        }
        val t13done = worldView.diagnostics.test13Done
        val t13total = worldView.diagnostics.test13Total
        val t13state = worldView.diagnostics.test13State
        binding.test13Btn.text = if (worldView.test13Requested) getString(R.string.world_test13_on) else getString(R.string.world_test13_off)
        binding.test13Btn.visibility = View.GONE
        val t13show = worldView.test13Requested || t13state == "EJECUTANDO" || t13state == "TERMINADO" || t13state == "ERROR"
        binding.test13StatusEl.visibility = if (t13show) View.VISIBLE else View.GONE
        binding.test13Bar.visibility = if (t13show) View.VISIBLE else View.GONE
        if (t13show) {
            val pct = if (t13total > 0 && t13done >= 0) t13done * 100 / t13total else 0
            val barLen = 10
            val filled = if (t13total > 0 && t13done >= 0) (t13done * barLen / t13total).coerceIn(0, barLen) else 0
            val bar = StringBuilder()
            for (i in 0 until barLen) bar.append(if (i < filled) '#' else '-')
            val cur = if (t13done >= 0 && t13total > 0) "$t13done/$t13total" else "0/$t13total"
            binding.test13StatusEl.text = "Estado: $t13state\nProgreso: $cur ($pct%)\n[$bar] $pct%"
            binding.test13Bar.max = 100
            binding.test13Bar.progress = pct
        }
        val t14done = worldView.diagnostics.test14Done
        val t14total = worldView.diagnostics.test14Total
        val t14state = worldView.diagnostics.test14State
        binding.test14Btn.text = if (worldView.test16Requested) getString(R.string.world_test14_on) else getString(R.string.world_test14_off)
        val t14show = worldView.test16Requested || t14state == "EJECUTANDO" || t14state == "TERMINADO" || t14state == "ERROR" || t14state == "CRASH PREVIO"
        binding.test14StatusEl.visibility = if (t14show) View.VISIBLE else View.GONE
        binding.test14Bar.visibility = if (t14show) View.VISIBLE else View.GONE
        if (t14show) {
            val pct = if (t14total > 0 && t14done >= 0) t14done * 100 / t14total else 0
            val barLen = 10
            val filled = if (t14total > 0 && t14done >= 0) (t14done * barLen / t14total).coerceIn(0, barLen) else 0
            val bar = StringBuilder()
            for (i in 0 until barLen) bar.append(if (i < filled) '#' else '-')
            val cur = if (t14done >= 0 && t14total > 0) "$t14done/$t14total" else "0/$t14total"
            binding.test14StatusEl.text = "Estado: $t14state\nProgreso: $cur ($pct%)\n[$bar] $pct%"
            binding.test14Bar.max = 100
            binding.test14Bar.progress = pct
        }

        // EventQueue owns its own failure state. A texture/capability error must
        // never overwrite the queue diagnostic, and queue retries are not logouts.
        worldView.diagnostics.eventQueue = if (session.eventQueueError.isEmpty()) {
            getString(R.string.world_event_queue, session.eventCount)
        } else {
            getString(
                R.string.world_event_queue_error,
                session.eventCount,
                session.eventQueueError + " · fallos consecutivos " + session.eventQueueFailures
            )
        }

        if (binding.diagPanel.visibility == View.VISIBLE) {
            updateDiagnosticsPanel()
        }

        binding.minimapEl.update(
            session.coarseAgents,
            session.coarseYou,
            if (session.positionKnown) session.position else null,
            session.headingDegrees,
            session.coarseSeen
        )
        binding.flyBtn.text = getString(
            if (session.flying) R.string.move_fly_on else R.string.move_fly_off
        )
    }

    private fun updateDiagnosticsPanel() {
        binding.verdictsEl.text = worldView.verdictLines().joinToString("\n")
        // HUD counters, then the start-up log: the native environment, every
        // start-up step with START/SUCCESS/FAIL, and every failure in full —
        // class, message, cause chain and stack trace.
        binding.diagEl.text = (worldView.hudLines() + worldView.startUpLines()).joinToString("\n")
    }

    private fun setDiagnosticsVisible(visible: Boolean) {
        binding.diagPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            // Opening the panel is also the moment the report is written to
            // disk, so there is always a copy to send even if the app dies.
            worldView.writeReport()
            updateDiagnosticsPanel()
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Asks for the local id to follow (fase 2.10). Nothing is hard-coded: the id
     * is typed here or tapped in the nearby list, and the diagnostic report then
     * carries the whole chain of that one object.
     */
    private fun askFocusLocalId() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.world_focus_hint)
        if (worldView.focusPrimLocalId != 0) {
            input.setText(worldView.focusPrimLocalId.toString())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.world_focus_title))
            .setView(input)
            .setPositiveButton(getString(R.string.world_focus_set)) { _, _ ->
                val text = input.text.toString().trim()
                val localId = text.toIntOrNull()
                if (localId == null) {
                    toast(getString(R.string.world_focus_hint))
                    return@setPositiveButton
                }
                applyFocus(localId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyFocus(localId: Int) {
        worldView.setFocusPrim(localId)
        toast(
            if (localId == 0) {
                getString(R.string.world_focus_off_hint)
            } else {
                getString(R.string.world_focus_on_hint, localId)
            }
        )
        updateHud()
    }

    /** Objects the simulator has told us about, nearest first. */
    private fun showNearby() {
        val world = SLClient.connection.world
        val origin = world.agentPosition
        val list = world.nearest(origin, NEARBY_LIMIT, NEARBY_METRES, nearbyBuffer)
        val lines = ArrayList<String>(list.size + 1)
        for (sceneObject in list) {
            // One source of truth for the shape: this is the *same*
            // SceneObject.shapeText the scene's geometry pipeline reads
            // (SLObject.from takes it from here), so the list and the renderer
            // cannot disagree. A prim whose path/profile definition never
            // arrived reads UNKNOWN/MISSING_SHAPE — never a silent "(cylinder)"
            // from the class's placeholder defaults, which is what every object
            // in this list used to show.
            val kind = when {
                sceneObject.isAvatar -> getString(R.string.world_kind_avatar)
                sceneObject.isTree -> getString(R.string.world_kind_tree)
                else -> sceneObject.shapeText
            }
            val provenance = if (sceneObject.isPrim) " · " + sceneObject.shapeSource.label else ""
            val name = if (sceneObject.name.isEmpty()) sceneObject.uuid else sceneObject.name
            lines.add(
                String.format(
                    Locale.US,
                    "%.0f m  ·  %s  (#%d %s%s)",
                    origin.distanceTo(sceneObject.position),
                    name,
                    sceneObject.localId,
                    kind,
                    provenance
                )
            )
        }
        if (lines.isEmpty()) {
            lines.add(getString(R.string.world_nothing_nearby))
        }
        // Fase 2.10: la lista deja de ser solo informativa — tocar un objeto lo
        // pone en foco para el diagnostico dirigido (parent/child y su cadena
        // completa). El localID sale de aqui, nunca del codigo.
        val ids = ArrayList<Int>(list.size)
        for (sceneObject in list) {
            ids.add(sceneObject.localId)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.world_nearby_title, lines.size))
            .setPositiveButton("OK", null)
        if (ids.isEmpty()) {
            builder.setMessage(lines.joinToString("\n"))
        } else {
            builder.setItems(lines.toTypedArray()) { _, which -> applyFocus(ids[which]) }
        }
        builder.show()
    }

    private fun holdToMove(view: View, action: MoveAction) {
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedView.isPressed = true
                    SLClient.connection.setMoveAction(action, true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressedView.isPressed = false
                    SLClient.connection.setMoveAction(action, false)
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val HUD_INTERVAL_MILLIS = 400L
        const val NEARBY_LIMIT = 60
        const val NEARBY_METRES = 128f
    }
}
