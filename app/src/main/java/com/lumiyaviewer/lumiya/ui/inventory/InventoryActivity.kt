package com.lumiyaviewer.lumiya.ui.inventory

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityInventoryBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryFolderContents
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryItem
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryModel
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryType
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The real inventory. The root folder is downloaded through the
 * `FetchInventoryDescendents2` capability at start-up, and every folder can be
 * expanded on tap — the contents come from the grid, not from a stub.
 *
 * When a simulator does not offer the capability, the folder skeleton returned
 * by the login server is shown instead, so the screen is never empty.
 */
class InventoryActivity : BaseActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private val rows = ArrayList<InventoryItem>()
    private lateinit var adapter: InventoryAdapter
    private var rootFolderId = ""
    private var libraryRootId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = InventoryAdapter(rows) { id -> onRowClicked(id) }
        binding.inventoryList.layoutManager = LinearLayoutManager(this)
        binding.inventoryList.adapter = adapter

        val login = SLClient.connection.lastLogin
        if (login == null || login.inventoryRoot.isEmpty()) {
            binding.countEl.text = getString(R.string.inventory_empty)
            return
        }
        rootFolderId = login.inventoryRoot
        libraryRootId = login.libraryRoot
        if (SLClient.connection.capabilities.isReady) {
            setBusy(true)
            binding.countEl.text = getString(R.string.inventory_loading)
            lifecycleScope.launch {
                val contents = SLClient.connection.loadFolder(rootFolderId)
                setBusy(false)
                if (contents == null) {
                    showSkeleton()
                    binding.countEl.text = getString(
                        R.string.inventory_open_failed,
                        SLClient.connection.capabilities.lastError
                    )
                } else {
                    rows.clear()
                    appendChildren(contents, 0)
                    addLibraryRow()
                    adapter.notifyDataSetChanged()
                    binding.countEl.text = getString(R.string.inventory_count, rows.size)
                }
            }
        } else {
            showSkeleton()
            binding.countEl.text = getString(R.string.inventory_no_caps)
        }
    }

    private fun showSkeleton() {
        val login = SLClient.connection.lastLogin ?: return
        rows.clear()
        rows.addAll(
            InventoryModel.treeFrom(login.inventoryRoot, login.inventoryFolders, libraryRootId)
        )
        adapter.notifyDataSetChanged()
    }

    /**
     * The public Library is a second root owned by the grid: it never comes back
     * as a child of our own inventory, so it gets its own top-level row.
     */
    private fun addLibraryRow() {
        if (libraryRootId.isEmpty() || rows.any { it.id == libraryRootId }) {
            return
        }
        rows.add(
            InventoryItem(
                id = libraryRootId,
                name = "Library",
                type = InventoryType.FOLDER,
                depth = 0,
                isLibrary = true
            )
        )
    }

    private fun appendChildren(contents: InventoryFolderContents, depth: Int) {
        for (folder in contents.subFolders) {
            rows.add(folder.copy(depth = depth))
        }
        for (item in contents.items) {
            rows.add(item.copy(depth = depth))
        }
    }

    private fun onRowClicked(id: String) {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) {
            return
        }
        val item = rows[index]
        if (!item.isFolder) {
            showItem(item)
            return
        }
        if (item.loading) {
            return
        }
        if (item.expanded) {
            collapse(index)
            return
        }
        if (!SLClient.connection.capabilities.isReady) {
            binding.countEl.text = getString(R.string.inventory_no_caps)
            return
        }
        item.loading = true
        setBusy(true)
        adapter.notifyDataSetChanged()
        lifecycleScope.launch {
            val contents = SLClient.connection.loadFolder(item.id, item.isLibrary)
            item.loading = false
            setBusy(false)
            if (contents == null) {
                adapter.notifyDataSetChanged()
                binding.countEl.text = getString(
                    R.string.inventory_open_failed,
                    SLClient.connection.capabilities.lastError
                )
                return@launch
            }
            val insertAt = rows.indexOfFirst { it.id == id }
            if (insertAt < 0) {
                return@launch
            }
            val children = ArrayList<InventoryItem>(contents.subFolders.size + contents.items.size)
            for (folder in contents.subFolders) {
                children.add(folder.copy(depth = item.depth + 1, isLibrary = item.isLibrary))
            }
            for (child in contents.items) {
                children.add(child.copy(depth = item.depth + 1))
            }
            rows.addAll(insertAt + 1, children)
            item.expanded = true
            item.childCount = children.size
            adapter.notifyDataSetChanged()
            binding.countEl.text = getString(R.string.inventory_count, rows.size)
        }
    }

    private fun collapse(index: Int) {
        val folder = rows[index]
        var end = index + 1
        while (end < rows.size && rows[end].depth > folder.depth) {
            end += 1
        }
        if (end > index + 1) {
            rows.subList(index + 1, end).clear()
        }
        folder.expanded = false
        adapter.notifyDataSetChanged()
        binding.countEl.text = getString(R.string.inventory_count, rows.size)
    }

    private fun showItem(item: InventoryItem) {
        val text = StringBuilder(160)
        text.append(getString(R.string.inventory_detail_type)).append(": ").append(item.type.name)
        if (item.assetId.isNotEmpty()) {
            text.append('\n').append(getString(R.string.inventory_detail_asset)).append(": ").append(item.assetId)
        }
        if (item.ownerId.isNotEmpty()) {
            text.append('\n').append(getString(R.string.inventory_detail_owner)).append(": ").append(item.ownerId)
        }
        if (item.flags != 0) {
            text.append('\n').append(getString(R.string.inventory_detail_flags)).append(": ")
                .append(String.format(Locale.US, "0x%X", item.flags))
        }
        if (item.isLink) {
            text.append('\n').append(getString(R.string.inventory_detail_link))
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(text.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setBusy(busy: Boolean) {
        binding.progressEl.visibility = if (busy) View.VISIBLE else View.GONE
    }
}
