package com.lumiyaviewer.lumiya.slproto.inventory

import com.lumiyaviewer.lumiya.slproto.caps.Capabilities
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDUuid

/** The contents of one inventory folder, straight from the grid. */
class InventoryFolderContents(
    val folderId: String,
    val name: String,
    val subFolders: List<InventoryItem>,
    val items: List<InventoryItem>
)

/**
 * Calls the `FetchInventoryDescendents2` capability, the HTTP endpoint every
 * modern viewer uses to read inventory contents (the UDP path was removed from
 * the grid years ago).
 */
object InventoryClient {

    const val CAP_NAME = "FetchInventoryDescendents2"
    const val LIBRARY_CAP_NAME = "FetchLibDescendents2"

    fun fetchFolder(
        capabilities: Capabilities,
        folderId: String,
        ownerId: String,
        library: Boolean = false
    ): InventoryFolderContents? {
        val entry = LinkedHashMap<String, Any?>()
        entry["folder_id"] = LLSDUuid(folderId)
        entry["owner_id"] = LLSDUuid(ownerId)
        entry["fetch_folders"] = true
        entry["fetch_items"] = true
        entry["sort_order"] = 0
        val request = LinkedHashMap<String, Any?>()
        request["folders"] = listOf(entry)

        val capName = if (library) LIBRARY_CAP_NAME else CAP_NAME
        val parsed = capabilities.requestParsed(capName, request) ?: return null
        return parseFolderReply(folderId, parsed)
    }

    /**
     * The reply is an array of folder maps; each one carries `items` and
     * `categories` arrays. Both are optional on the wire.
     */
    fun parseFolderReply(folderId: String, parsed: Any?): InventoryFolderContents? {
        val list = parsed as? List<*> ?: return null
        for (element in list) {
            val map = LLSDParser.asMap(element)
            if (map.isEmpty()) {
                continue
            }
            val id = LLSDParser.asString(map["folder_id"]).ifEmpty { folderId }
            val name = LLSDParser.asString(map["name"])
            val subFolders = ArrayList<InventoryItem>()
            for (category in (map["categories"] as? List<*>).orEmptyList()) {
                val categoryMap = LLSDParser.asMap(category)
                val categoryId = LLSDParser.asString(categoryMap["category_id"])
                if (categoryId.isEmpty()) {
                    continue
                }
                subFolders.add(
                    InventoryItem(
                        id = categoryId,
                        name = LLSDParser.asString(categoryMap["name"]),
                        type = InventoryType.FOLDER,
                        assetId = categoryId,
                        childCount = categoryMap["child_count"]?.let { LLSDParser.asInt(it) } ?: -1
                    )
                )
            }
            val items = ArrayList<InventoryItem>()
            for (item in (map["items"] as? List<*>).orEmptyList()) {
                val itemMap = LLSDParser.asMap(item)
                val itemId = LLSDParser.asString(itemMap["item_id"])
                if (itemId.isEmpty()) {
                    continue
                }
                val code = if (itemMap.containsKey("inv_type")) {
                    LLSDParser.asInt(itemMap["inv_type"])
                } else {
                    LLSDParser.asInt(itemMap["type"])
                }
                items.add(
                    InventoryItem(
                        id = itemId,
                        name = LLSDParser.asString(itemMap["name"]),
                        type = InventoryType.fromCode(code),
                        assetId = LLSDParser.asString(itemMap["asset_id"]),
                        ownerId = LLSDParser.asString(itemMap["owner_id"]),
                        flags = LLSDParser.asInt(itemMap["flags"]),
                        isLink = LLSDParser.asInt(itemMap["is_linkset"]) != 0
                    )
                )
            }
            subFolders.sortBy { it.name.lowercase() }
            items.sortBy { it.name.lowercase() }
            return InventoryFolderContents(id, name, subFolders, items)
        }
        return null
    }

    private fun List<*>?.orEmptyList(): List<*> = this ?: emptyList<Any?>()
}
