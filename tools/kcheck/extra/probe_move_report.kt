package com.lumiyaviewer.lumiya.kcheck

import com.lumiyaviewer.lumiya.slproto.movement.AgentControlFlags
import com.lumiyaviewer.lumiya.slproto.movement.AgentUpdateBuilder
import com.lumiyaviewer.lumiya.slproto.movement.MovementAudit
import com.lumiyaviewer.lumiya.slproto.base.Vector3

fun main() {
    MovementAudit.reset()
    val basis = AgentUpdateBuilder.referenceBasis(0.7f)
    MovementAudit.noteMovementComplete(Vector3(100f, 100f, 20f))
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 100f, 20f))
    // un toque: pulsar, soltar, y el envio con AT_POS sale DESPUES de soltar
    MovementAudit.notePad("FORWARD", true)
    MovementAudit.noteHandler(true, AgentControlFlags.AT_POS, 0.7f, 0.7f)
    MovementAudit.notePad("FORWARD", false)
    MovementAudit.noteHandler(true, 0L, 0.7f, 0.7f)
    MovementAudit.noteCommandSent(1, AgentControlFlags.AT_POS, Vector3(100f, 100f, 21.5f), basis, 122, true, 0.7f, 0.7f)
    MovementAudit.noteCommandSent(2, 0L, Vector3(100f, 100f, 21.5f), basis, 122, true, 0.7f, 0.7f)
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 103f, 20f))
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 106f, 20f))
    MovementAudit.noteFocus(100f, 106f, 21.5f)
    println(MovementAudit.reportText())
}
