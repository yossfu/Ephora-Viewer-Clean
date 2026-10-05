package com.lumiyaviewer.lumiya.kcheck

import java.net.ServerSocket
import java.net.Socket

fun main() {
    val results = ArrayList<String>()
    try {
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        results.add("bind OK en 127.0.0.1:" + port)
        val client = Thread {
            try {
                val socket = Socket("127.0.0.1", port)
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val line = socket.getInputStream().bufferedReader().readLine()
                results.add("cliente leyo: " + line)
                socket.close()
            } catch (error: Throwable) {
                results.add("cliente fallo: " + error.javaClass.simpleName + " " + error.message)
            }
        }
        client.isDaemon = true
        client.start()
        server.soTimeout = 5000
        val accepted = server.accept()
        accepted.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".toByteArray())
        accepted.getOutputStream().flush()
        accepted.close()
        server.close()
        client.join(3000)
        results.add("servidor acepto una conexion")
    } catch (error: Throwable) {
        results.add("SOCKET BLOQUEADO: " + error.javaClass.simpleName + " " + error.message)
    }
    println("PROBE: " + results.joinToString(" | "))
}
