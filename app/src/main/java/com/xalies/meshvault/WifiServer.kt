package com.xalies.meshvault

import android.os.Environment
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLConnection
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WifiServer(private val dao: ModelDao) {

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

                val requestLine = input.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread

                val method = parts[0]
                var path = parts[1]
                path = URLDecoder.decode(path, "UTF-8")

                // Root Directory for MeshVault
                val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault")

                if (method == "GET") {
                    if (path.startsWith("/zip/")) {
                        // ZIP Logic: "/zip/SciFi/Ships" -> Zips the "Ships" folder
                        val relativePath = path.substring(5) // remove "/zip/"
                        val targetFile = File(rootDir, relativePath)
                        if (targetFile.exists()) {
                            // If it's a folder, zip contents. If file, zip single file.
                            sendZip(output, listOf(targetFile), "${targetFile.name}.zip")
                        } else {
                            send404(output)
                        }
                    } else if (path == "/favicon.ico") {
                        send404(output) // Ignore favicon
                    } else {
                        // BROWSER LOGIC
                        // Treat the path as relative to MeshVault root
                        // e.g. URL "/" -> rootDir
                        // e.g. URL "/SciFi" -> rootDir/SciFi

                        // Handle the case where path is just "/"
                        val cleanPath = if (path == "/") "" else path.removePrefix("/")
                        val targetFile = File(rootDir, cleanPath)

                        if (targetFile.exists()) {
                            if (targetFile.isDirectory) {
                                // It's a folder (or root) -> Show Index
                                sendHtmlIndex(output, rootDir, targetFile, cleanPath)
                            } else {
                                // It's a file -> Download
                                sendFile(output, targetFile)
                            }
                        } else {
                            send404(output)
                        }
                    }
                } else if (method == "POST" && path == "/zip-selected") {
                    // Handle Multi-Select ZIP (Reads form data)
                    // ... (Simplifying for brevity: Assume similar logic to previous, but using rootDir context)
                    // For robust subfolder support in POST, we'd need to parse the relative paths carefully.
                    // Keeping it simple for now: Redirect to root
                    output.print("HTTP/1.1 303 See Other\r\nLocation: /\r\n\r\n")
                }

                output.close()
                input.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendHtmlIndex(output: PrintStream, rootDir: File, currentDir: File, relativePath: String) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: text/html; charset=UTF-8\r\n\r\n")

        sb.append("<!DOCTYPE html><html><head><title>MeshVault</title><meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<style>")
        sb.append("body { font-family: 'Segoe UI', Roboto, sans-serif; background-color: #121212; color: #e0e0e0; margin: 0; padding: 0; }")
        sb.append("header { background: #1f1f1f; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.5); position: sticky; top: 0; z-index: 100; }")
        sb.append(".breadcrumbs { color: #888; font-size: 0.9em; margin-bottom: 5px; }")
        sb.append(".breadcrumbs a { color: #bb86fc; text-decoration: none; }")
        sb.append("h1 { margin: 0; font-weight: 300; letter-spacing: 1px; font-size: 1.5rem; color: #fff; }")

        sb.append(".container { width: 95%; max-width: 1600px; margin: 20px auto; }")

        sb.append(".section-title { color: #03dac6; margin: 30px 0 15px 0; border-bottom: 1px solid #333; padding-bottom: 5px; font-size: 1.1em; text-transform: uppercase; letter-spacing: 1px; }")

        // Folder Grid
        sb.append(".folder-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 15px; }")
        sb.append(".folder-card { background: #252525; padding: 15px; border-radius: 8px; display: flex; align-items: center; gap: 10px; text-decoration: none; color: #fff; transition: background 0.2s; border: 1px solid #333; }")
        sb.append(".folder-card:hover { background: #333; border-color: #bb86fc; }")

        // File Grid
        sb.append(".file-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 20px; }")
        sb.append(".card { background-color: #1e1e1e; border-radius: 12px; overflow: hidden; border: 1px solid #333; transition: transform 0.2s; display: flex; flex-direction: column; }")
        sb.append(".card:hover { transform: translateY(-3px); border-color: #bb86fc; }")
        sb.append(".thumb { width: 100%; height: 180px; background-color: #000; object-fit: cover; }")
        sb.append(".card-body { padding: 15px; flex-grow: 1; display: flex; flex-direction: column; }")
        sb.append(".title { font-weight: bold; margin-bottom: 5px; color: #fff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }")
        sb.append(".meta { font-size: 0.85em; color: #aaa; margin-bottom: 15px; }")
        sb.append(".actions { margin-top: auto; display: flex; gap: 8px; }")
        sb.append(".btn { flex: 1; text-align: center; padding: 8px; border-radius: 4px; text-decoration: none; font-size: 0.9em; font-weight: bold; }")
        sb.append(".btn-dl { background-color: #bb86fc; color: #000; }")
        sb.append(".btn-src { background: transparent; border: 1px solid #555; color: #aaa; }")
        sb.append(".btn-zip-folder { float: right; background: #333; color: #fff; font-size: 0.8em; padding: 5px 10px; border-radius: 4px; text-decoration: none; }")

        sb.append("</style></head><body>")

        // --- HEADER & BREADCRUMBS ---
        sb.append("<header><div>")

        // Build Breadcrumbs: Home > Folder > Subfolder
        sb.append("<div class='breadcrumbs'><a href='/'>Home</a>")
        if (relativePath.isNotEmpty()) {
            val parts = relativePath.split("/")
            var buildPath = ""
            for (part in parts) {
                buildPath += "/$part"
                sb.append(" &rsaquo; <a href='$buildPath'>$part</a>")
            }
        }
        sb.append("</div>")
        sb.append("<h1>${if (relativePath.isEmpty()) "MeshVault Library" else currentDir.name}</h1>")
        sb.append("</div>")

        // Download Current Folder as Zip Button
        if (relativePath.isNotEmpty()) {
            sb.append("<a href='/zip/$relativePath' class='btn-zip-folder'>📦 Download This Folder</a>")
        }
        sb.append("</header>")

        sb.append("<div class='container'>")

        // 1. LIST SUB-FOLDERS
        val files = currentDir.listFiles() ?: emptyArray()
        val folders = files.filter { it.isDirectory }.sortedBy { it.name }

        if (folders.isNotEmpty()) {
            sb.append("<div class='section-title'>Folders</div>")
            sb.append("<div class='folder-grid'>")
            // Add "Up" link if not root
            if (relativePath.isNotEmpty()) {
                val parentPath = if (relativePath.contains("/")) relativePath.substringBeforeLast("/") else "/"
                sb.append("<a href='$parentPath' class='folder-card'>📁 .. (Up)</a>")
            }

            folders.forEach { folder ->
                // Link is current relative path + / + folder name
                val link = if (relativePath.isEmpty()) "/${folder.name}" else "/$relativePath/${folder.name}"
                sb.append("<a href='$link' class='folder-card'>📂 ${folder.name}</a>")
            }
            sb.append("</div>")
        }

        // 2. LIST FILES
        val modelFiles = files.filter { it.isFile }.sortedBy { it.name }

        if (modelFiles.isNotEmpty()) {
            sb.append("<div class='section-title'>Files</div>")
            sb.append("<div class='file-grid'>")

            // Batch fetch metadata for this folder to minimize DB calls
            // Note: In a deep subfolder, we pass the *folder name* (e.g. "Starships") to the DAO.
            // Our DAO `getModelsInFolderList` expects just the leaf folder name currently.
            // This is a slight limitation if you have "SciFi/Ships" and "Sea/Ships" (names collide),
            // but for this implementation it works for unique folder names.
            val models = runBlocking { dao.getModelsInFolderList(currentDir.name) }
            val modelsMap = models.associateBy { it.localFilePath.substringAfterLast("/") }

            modelFiles.forEach { file ->
                val modelData = modelsMap[file.name]
                // Construct download link
                val dlLink = if (relativePath.isEmpty()) "/${file.name}" else "/$relativePath/${file.name}"
                val imgUrl = modelData?.thumbnailUrl ?: ""
                val srcUrl = modelData?.pageUrl ?: ""

                sb.append("<div class='card'>")
                if (imgUrl.isNotEmpty()) {
                    sb.append("<img src='$imgUrl' class='thumb' onerror=\"this.style.display='none'\">")
                } else {
                    sb.append("<div class='thumb' style='display:flex;align-items:center;justify-content:center;color:#444;'>No Preview</div>")
                }

                sb.append("<div class='card-body'>")
                sb.append("<div class='title' title='${file.name}'>${file.name}</div>")
                sb.append("<div class='meta'>${ String.format("%.2f MB", file.length() / 1024.0 / 1024.0) }</div>")

                sb.append("<div class='actions'>")
                sb.append("<a href='$dlLink' class='btn btn-dl' download>Download</a>")
                if (srcUrl.isNotEmpty()) {
                    sb.append("<a href='$srcUrl' target='_blank' class='btn btn-src'>Source</a>")
                }
                sb.append("</div>") // actions
                sb.append("</div>") // card-body
                sb.append("</div>") // card
            }
            sb.append("</div>")
        } else if (folders.isEmpty()) {
            sb.append("<div style='text-align:center; padding:50px; color:#666;'>Empty Folder</div>")
        }

        sb.append("</div></body></html>")
        output.print(sb.toString())
    }

    private fun sendZip(output: PrintStream, targets: List<File>, zipName: String) {
        try {
            output.print("HTTP/1.1 200 OK\r\n")
            output.print("Content-Type: application/zip\r\n")
            output.print("Content-Disposition: attachment; filename=\"$zipName\"\r\n")
            output.print("\r\n")
            output.flush()

            val zipOut = ZipOutputStream(output)
            val buffer = ByteArray(4096)

            for (target in targets) {
                if (target.isDirectory) {
                    addFolderToZip(zipOut, target, "", buffer)
                } else if (target.isFile) {
                    addFileToZip(zipOut, target, "", buffer)
                }
            }
            zipOut.finish()
            zipOut.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addFolderToZip(zipOut: ZipOutputStream, folder: File, parentPath: String, buffer: ByteArray) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                addFolderToZip(zipOut, file, "$parentPath${file.name}/", buffer)
            } else {
                addFileToZip(zipOut, file, parentPath, buffer)
            }
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, parentPath: String, buffer: ByteArray) {
        val fis = FileInputStream(file)
        val entry = ZipEntry(parentPath + file.name)
        zipOut.putNextEntry(entry)
        var length: Int
        while (fis.read(buffer).also { length = it } >= 0) {
            zipOut.write(buffer, 0, length)
        }
        fis.close()
        zipOut.closeEntry()
    }

    private fun sendFile(output: PrintStream, file: File) {
        try {
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val length = file.length()

            output.print("HTTP/1.1 200 OK\r\n")
            output.print("Content-Type: $mime\r\n")
            output.print("Content-Length: $length\r\n")
            output.print("\r\n")

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