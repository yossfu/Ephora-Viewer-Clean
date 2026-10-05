package com.lumiyaviewer.lumiya.ui.world

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityMovementBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.slproto.movement.MoveAction
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Movement pad plus a live mini-map. The buttons send real `AgentUpdate`
 * control flags, so the avatar walks, runs and flies for real inside Second
 * Life / OpenSim.
 */
class MovementActivity : BaseActivity() {

    private lateinit var binding: ActivityMovementBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMovementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

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
            connection.setMoveAction(MoveAction.FLY, !connection.isFlying)
        }
        binding.stopBtn.setOnClickListener {
            SLClient.connection.stopMovement()
        }

        lifecycleScope.launch {
            SLClient.connection.session.collect { session ->
                binding.minimapEl.update(
                    session.coarseAgents,
                    session.coarseYou,
                    if (session.positionKnown) session.position else null,
                    session.headingDegrees,
                    session.coarseSeen
                )
                val connected = SLClient.connection.state.value == ConnectionState.CONNECTED
                binding.overlayEl.text = buildString {
                    append("Region: ")
                    append(if (session.regionName.isEmpty()) "-" else session.regionName)
                    append("   ·   Agente: ")
                    append(if (session.agentName.isEmpty()) "-" else session.agentName)
                    append('\n')
                    append("Posicion: ")
                    if (session.positionKnown) {
                        append(
                            String.format(
                                Locale.US,
                                "%.0f, %.0f, %.0f m",
                                session.position.x,
                                session.position.y,
                                session.position.z
                            )
                        )
                    } else {
                        append("-")
                    }
                    append("   ·   Rumbo: ")
                    append(String.format(Locale.US, "%.0f°", session.headingDegrees))
                    if (session.coarseSeen) {
                        append('\n')
                        append("Avatares en la region: ")
                        append(session.coarseAgents.size)
                    }
                    if (!connected) {
                        append('\n')
                        append("Sin conexion: los controles no envian nada.")
                    }
                }
                binding.flyBtn.text = getString(
                    if (session.flying) R.string.move_fly_on else R.string.move_fly_off
                )            }
        }
    }

    private fun holdToMove(view: View, action: MoveAction) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    SLClient.connection.setMoveAction(action, true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    SLClient.connection.setMoveAction(action, false)
                    true
                }
                else -> false
            }
        }
    }
}
