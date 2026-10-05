package com.lumiyaviewer.lumiya.slproto.inventory

/**
 * One folder from the inventory skeleton the login server returns when the
 * viewer asks for the `inventory-skeleton` option.
 */
data class InventoryFolder(
    val id: String,
    val parentId: String,
    val name: String
)
