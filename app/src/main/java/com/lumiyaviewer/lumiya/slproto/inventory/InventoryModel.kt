package com.lumiyaviewer.lumiya.slproto.inventory

/**
 * Builds the folder tree from the real inventory skeleton returned by the login
 * server. Folder contents still need a capability request
 * (`FetchInventoryDescendents2`), which is the next step for this module.
 */
object InventoryModel {

    /**
     * The Library is a second root owned by the grid, so it is appended as an
     * extra top-level folder with its whole subtree marked as library (its
     * contents are read through `FetchLibDescendents2`, not our own cap).
     */
    fun treeFrom(
        rootId: String,
        folders: List<InventoryFolder>,
        libraryRootId: String = ""
    ): List<InventoryItem> {
        if (folders.isEmpty()) {
            return emptyList()
        }
        val children = HashMap<String, MutableList<InventoryFolder>>()
        for (folder in folders) {
            children.getOrPut(folder.parentId) { ArrayList() }.add(folder)
        }
        for (list in children.values) {
            list.sortBy { it.name.lowercase() }
        }
        val out = ArrayList<InventoryItem>()
        val rootChildren = children[rootId] ?: emptyList()
        for (child in rootChildren) {
            append(out, child, 0, children, HashSet(), false)
        }
        if (libraryRootId.isNotEmpty()) {
            val library = folders.firstOrNull { it.id == libraryRootId }
            if (library != null) {
                out.add(libraryRow(library, 0))
                for (child in children[libraryRootId] ?: emptyList()) {
                    append(out, child, 1, children, HashSet(), true)
                }
            }
        }
        return out
    }

    private fun libraryRow(folder: InventoryFolder, depth: Int): InventoryItem {
        return InventoryItem(
            id = folder.id,
            name = folder.name.ifEmpty { "Library" },
            type = InventoryType.FOLDER,
            depth = depth,
            isLibrary = true
        )
    }

    private fun append(
        out: MutableList<InventoryItem>,
        folder: InventoryFolder,
        depth: Int,
        children: Map<String, List<InventoryFolder>>,
        seen: MutableSet<String>,
        library: Boolean
    ) {
        if (!seen.add(folder.id)) {
            return
        }
        out.add(
            InventoryItem(
                folder.id,
                folder.name,
                InventoryType.FOLDER,
                depth,
                isLibrary = library
            )
        )
        val nested = children[folder.id] ?: return
        for (child in nested) {
            append(out, child, depth + 1, children, seen, library)
        }
    }
}
