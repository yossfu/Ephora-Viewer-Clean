package com.lumiyaviewer.lumiya.slproto.login

import com.lumiyaviewer.lumiya.slproto.inventory.InventoryFolder

class LoginResult(
    val success: Boolean,
    val message: String,
    val reason: String,
    val agentId: String,
    val sessionId: String,
    val secureSessionId: String,
    val circuitCode: Int,
    val simIp: String,
    val simPort: Int,
    val regionX: Int,
    val regionY: Int,
    val seedCapability: String,
    val firstName: String,
    val lastName: String,
    val startLocation: String,
    val inventoryRoot: String = "",
    val inventoryFolders: List<InventoryFolder> = emptyList(),
    /** The public Library is a second inventory root with its own owner. */
    val libraryRoot: String = "",
    val libraryOwner: String = ""
) {
    companion object {
        fun failure(reason: String, message: String): LoginResult {
            return LoginResult(
                success = false,
                message = message,
                reason = reason,
                agentId = "",
                sessionId = "",
                secureSessionId = "",
                circuitCode = 0,
                simIp = "",
                simPort = 0,
                regionX = 0,
                regionY = 0,
                seedCapability = "",
                firstName = "",
                lastName = "",
                startLocation = ""
            )
        }
    }
}
