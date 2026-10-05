package com.lumiyaviewer.lumiya.slproto.inventory

/**
 * Second Life inventory types, matching `EInventoryType` (note that 4 is unused
 * and 5 is clothing).
 */
enum class InventoryType {
    FOLDER,
    TEXTURE,
    SOUND,
    CALLING_CARD,
    LANDMARK,
    CLOTHING,
    OBJECT,
    NOTECARD,
    SCRIPT,
    SNAPSHOT,
    ATTACHMENT,
    WEARABLE,
    ANIMATION,
    GESTURE,
    MESH,
    SETTINGS,
    MATERIAL,
    UNKNOWN;

    companion object {
        fun fromCode(code: Int): InventoryType {
            return when (code) {
                0 -> TEXTURE
                1 -> SOUND
                2 -> CALLING_CARD
                3 -> LANDMARK
                5 -> CLOTHING
                6 -> OBJECT
                7 -> NOTECARD
                8, 9 -> FOLDER
                10 -> SCRIPT
                11 -> SNAPSHOT
                12 -> ATTACHMENT
                13 -> WEARABLE
                14 -> ANIMATION
                15 -> GESTURE
                16 -> MESH
                17 -> SETTINGS
                18 -> MATERIAL
                else -> UNKNOWN
            }
        }
    }
}

/** One row of the inventory tree: either a folder or an item. */
data class InventoryItem(
    val id: String,
    val name: String,
    val type: InventoryType,
    var depth: Int = 0,
    val assetId: String = "",
    val ownerId: String = "",
    val flags: Int = 0,
    val isLink: Boolean = false,
    /** -1 when unknown: folders report how many children they have. */
    var childCount: Int = -1,
    /** True when this folder's contents have already been downloaded. */
    var expanded: Boolean = false,
    /** True while `FetchInventoryDescendents2` is in flight for this folder. */
    var loading: Boolean = false,
    /** True for the public Library, which is read with `FetchLibDescendents2`. */
    var isLibrary: Boolean = false
) {
    val isFolder: Boolean
        get() = type == InventoryType.FOLDER
}
