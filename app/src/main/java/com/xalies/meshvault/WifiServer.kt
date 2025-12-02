package com.xalies.meshvault

import android.os.Environment
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLConnection

class WifiServer {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverThread: Thread? = null

    val isAlive: Boolean
        get() = isRunning

    fun start() {
        if (isRunning) return
        isRunning = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(8080)
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept()
                        if (client != null) {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        // Socket closed or error
                        if (isRunning) e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { start() }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverThread?.interrupt()
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintStream(socket.getOutputStream())

                // 1. Read Request Line (e.g., "GET /SciFi/ship.stl HTTP/1.1")
                val requestLine = input.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread

                val method = parts[0]
                var path = parts[1]

                // Decode URL (e.g., %20 -> space)
                path = URLDecoder.decode(path, "UTF-8")

                if (method == "GET") {
                    val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault")

                    if (path == "/" || path == "/index.html") {
                        sendHtmlIndex(output, rootDir)
                    } else {
                        // Try to serve file
                        val file = File(rootDir, path)
                        if (file.exists() && file.isFile) {
                            sendFile(output, file)
                        } else {
                            // If file not found, just show index again or 404
                            send404(output)
                        }
                    }
                }

                output.close()
                input.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendHtmlIndex(output: PrintStream, rootDir: File) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: text/html\r\n\r\n")

        sb.append("<html><head><title>MeshVault</title><meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<style>body{font-family:sans-serif; padding:20px; background:#f5f5f5;} .card{background:white; padding:15px; margin-bottom:10px; border-radius:8px;} h3{margin-top:0;} a{text-decoration:none; color:#333; display:block; padding:8px; border-bottom:1px solid #eee;}</style>")
        sb.append("</head><body><h1>MeshVault Library</h1>")

        if (rootDir.exists() && rootDir.isDirectory) {
            rootDir.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
                sb.append("<div class='card'><h3>📂 ${folder.name}</h3>")
                folder.listFiles()?.filter { it.isFile }?.forEach { file ->
                    val href = "/${folder.name}/${file.name}"
                    sb.append("<a href='$href'>📄 ${file.name}</a>")
                }
                sb.append("</div>")
            }
        } else {
            sb.append("<p>No files found.</p>")
        }
        sb.append("</body></html>")

        output.print(sb.toString())
    }

    private fun sendFile(output: PrintStream, file: File) {
        try {
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val length = file.length()

            output.print("HTTP/1.1 200 OK\r\n")
            output.print("Content-Type: $mime\r\n")
            output.print("Content-Length: $length\r\n")
            output.print("\r\n") // End of headers

            val fileInput = FileInputStream(file)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fileInput.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            fileInput.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun send404(output: PrintStream) {
        output.print("HTTP/1.1 404 Not Found\r\n\r\nFile Not Found")
    }
}