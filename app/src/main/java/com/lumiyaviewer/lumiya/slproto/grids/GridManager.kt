package com.lumiyaviewer.lumiya.slproto.grids

import java.util.concurrent.CopyOnWriteArrayList

object GridManager {

    private val grids = CopyOnWriteArrayList<Grid>()

    @Synchronized
    fun bootstrap() {
        if (grids.isNotEmpty()) {
            return
        }
        grids.add(
            Grid(
                id = "agni",
                name = "Second Life (Agni)",
                loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
                isDefault = true
            )
        )
        grids.add(
            Grid(
                id = "aditi",
                name = "Second Life Beta (Aditi)",
                loginUri = "https://login.aditi.lindenlab.com/cgi-bin/login.cgi"
            )
        )
        grids.add(
            Grid(
                id = "opensim",
                name = "OpenSim local",
                loginUri = "http://127.0.0.1:9000/",
                openSim = true
            )
        )
    }

    fun all(): List<Grid> {
        bootstrap()
        return grids.toList()
    }

    fun defaultGrid(): Grid {
        bootstrap()
        return grids.firstOrNull { it.isDefault } ?: grids.first()
    }

    fun add(grid: Grid) {
        bootstrap()
        grids.add(grid)
    }
}
