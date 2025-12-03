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

                // Parse Query Params (e.g. ?keepStructure=true)
                var keepStructure = false
                if (path.contains("?")) {
                    val query = path.substringAfter("?")
                    path = path.substringBefore("?")
                    if (query.contains("keepStructure=true")) {
                        keepStructure = true
                    }
                }

                path = URLDecoder.decode(path, "UTF-8")

                // Read Headers for POST
                var contentLength = 0
                var line = input.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = input.readLine()
                }

                val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault")

                if (method == "GET") {
                    if (path.startsWith("/zip/")) {
                        // ZIP Logic
                        val folderName = path.substring(5)
                        val folder = File(rootDir, folderName)
                        if (folder.exists() && folder.isDirectory) {
                            // If keepStructure is true, we pass the folder's parent as the 'base'
                            // so the zip entry becomes "FolderName/File.stl" instead of just "File.stl"
                            val baseDir = if (keepStructure) folder.parentFile else folder
                            sendZip(output, listOf(folder), "${folder.name}.zip", baseDir)
                        } else {
                            send404(output)
                        }
                    } else if (path == "/" || path == "/index.html") {
                        sendDashboard(output, rootDir, null)
                    } else {
                        // Folder navigation or File Download
                        val cleanPath = path.removePrefix("/")
                        val target = File(rootDir, cleanPath)

                        if (target.exists()) {
                            if (target.isDirectory) {
                                sendDashboard(output, rootDir, target)
                            } else {
                                sendFile(output, target)
                            }
                        } else {
                            send404(output)
                        }
                    }
                } else if (method == "POST" && path == "/zip-selected") {
                    if (contentLength > 0) {
                        val body = CharArray(contentLength)
                        input.read(body, 0, contentLength)
                        val bodyString = String(body)

                        val filesToZip = mutableListOf<File>()
                        val pairs = bodyString.split("&")

                        // Check if checkbox sent in POST body
                        for (pair in pairs) {
                            val kv = pair.split("=")
                            if (kv.size == 2) {
                                if (kv[0] == "files") {
                                    val filePath = URLDecoder.decode(kv[1], "UTF-8")
                                    val f = File(rootDir, filePath)
                                    if (f.exists()) filesToZip.add(f)
                                } else if (kv[0] == "keepStructure" && kv[1] == "on") {
                                    keepStructure = true
                                }
                            }
                        }

                        if (filesToZip.isNotEmpty()) {
                            // For multi-select, we usually want relative to rootDir if keeping structure
                            val baseDir = if (keepStructure) rootDir else null
                            sendZip(output, filesToZip, "selected_models.zip", baseDir)
                        } else {
                            output.print("HTTP/1.1 303 See Other\r\nLocation: /\r\n\r\n")
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

    private fun sendDashboard(output: PrintStream, rootDir: File, currentDir: File?) {
        val actualCurrentDir = currentDir ?: (rootDir.listFiles()?.find { it.isDirectory } ?: rootDir)
        val relativePath = if (actualCurrentDir == rootDir) "" else actualCurrentDir.name

        // ... (Keep HTTP Headers & HTML Setup same as before) ...
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: text/html; charset=UTF-8\r\n\r\n")

        sb.append("<!DOCTYPE html><html><head><title>MeshVault</title>")
        sb.append("<style>")
        // ... (Keep existing CSS) ...
        sb.append("body { margin: 0; font-family: 'Segoe UI', sans-serif; background: #121212; color: #fff; height: 100vh; display: flex; overflow: hidden; }")
        sb.append("a { text-decoration: none; color: inherit; }")
        sb.append(".sidebar-left { width: 250px; background: #1a1a1a; border-right: 1px solid #333; display: flex; flex-direction: column; }")
        sb.append(".main-content { flex: 1; background: #121212; display: flex; flex-direction: column; overflow: hidden; }")
        sb.append(".sidebar-right { width: 300px; background: #1a1a1a; border-left: 1px solid #333; padding: 20px; display: flex; flex-direction: column; }")
        sb.append(".app-title { padding: 20px; font-size: 1.2rem; font-weight: bold; color: #bb86fc; border-bottom: 1px solid #333; }")
        sb.append(".nav-list { list-style: none; padding: 0; margin: 0; overflow-y: auto; flex: 1; }")
        sb.append(".nav-item { padding: 15px 20px; border-bottom: 1px solid #252525; cursor: pointer; transition: background 0.2s; color: #aaa; }")
        sb.append(".nav-item:hover { background: #252525; color: #fff; }")
        sb.append(".nav-item.active { background: #333; color: #bb86fc; border-left: 3px solid #bb86fc; }")
        sb.append(".main-header { padding: 20px; border-bottom: 1px solid #333; display: flex; justify-content: space-between; align-items: center; background: #1f1f1f; }")
        sb.append(".breadcrumbs { color: #888; font-size: 0.9em; }")
        sb.append(".header-controls { display: flex; align-items: center; gap: 15px; }")
        sb.append(".toggle-label { display: flex; align-items: center; gap: 8px; font-size: 0.9em; cursor: pointer; user-select: none; }")
        sb.append(".toggle-label input { accent-color: #03dac6; transform: scale(1.2); }")
        sb.append(".grid-container { padding: 20px; overflow-y: auto; height: 100%; }")
        sb.append(".grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 15px; }")
        sb.append(".card { background: #252525; border-radius: 8px; overflow: hidden; cursor: pointer; border: 2px solid transparent; transition: all 0.2s; position: relative; }")
        sb.append(".card:hover { transform: translateY(-3px); border-color: #555; }")
        sb.append(".card.selected { border-color: #bb86fc; background: #2a2a2a; }")
        sb.append(".card-thumb { width: 100%; height: 140px; background: #000; object-fit: cover; }")
        sb.append(".card-body { padding: 10px; }")
        sb.append(".card-title { font-size: 0.9em; font-weight: bold; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }")
        sb.append(".checkbox-overlay { position: absolute; top: 8px; left: 8px; transform: scale(1.3); cursor: pointer; z-index: 10; }")
        sb.append(".details-thumb { width: 100%; height: 200px; background: #000; border-radius: 8px; margin-bottom: 20px; object-fit: cover; }")
        sb.append(".details-title { font-size: 1.4rem; font-weight: bold; margin-bottom: 10px; line-height: 1.2; }")
        sb.append(".details-meta { color: #888; font-size: 0.9em; margin-bottom: 20px; }")
        sb.append(".btn { display: block; width: 100%; padding: 12px; text-align: center; border-radius: 6px; margin-bottom: 10px; font-weight: bold; cursor: pointer; border: none; text-decoration: none; }")
        sb.append(".btn-primary { background: #bb86fc; color: #000; }")
        sb.append(".btn-secondary { background: #333; color: #fff; }")
        sb.append(".btn-outline { background: transparent; border: 1px solid #555; color: #aaa; }")
        sb.append(".empty-state { color: #666; text-align: center; margin-top: 100px; }")
        sb.append("</style>")

        sb.append("<script>")
        sb.append("function selectModel(name, size, dlLink, srcLink, imgUrl) {")
        sb.append("  document.getElementById('detail-img').src = imgUrl || '';")
        sb.append("  document.getElementById('detail-img').style.display = imgUrl ? 'block' : 'none';")
        sb.append("  document.getElementById('detail-title').innerText = name;")
        sb.append("  document.getElementById('detail-meta').innerText = size;")
        sb.append("  document.getElementById('btn-download').href = dlLink;")
        sb.append("  var srcBtn = document.getElementById('btn-source');")
        sb.append("  if(srcLink) { srcBtn.href = srcLink; srcBtn.style.display = 'block'; } else { srcBtn.style.display = 'none'; }")
        sb.append("  document.getElementById('details-panel').style.display = 'block';")
        sb.append("  document.getElementById('empty-panel').style.display = 'none';")
        sb.append("}")
        sb.append("function updateZipLinks() {")
        sb.append("  var isChecked = document.getElementById('chkStructure').checked;")
        sb.append("  var links = document.querySelectorAll('.zip-link');")
        sb.append("  links.forEach(function(a) {")
        sb.append("    var base = a.getAttribute('data-base');")
        sb.append("    a.href = base + (isChecked ? '?keepStructure=true' : '');")
        sb.append("  });")
        sb.append("}")
        sb.append("</script>")

        sb.append("</head><body>")

        // --- SIDEBAR LEFT ---
        sb.append("<nav class='sidebar-left'>")
        sb.append("<div class='app-title'>MESH VAULT</div>")
        sb.append("<ul class='nav-list'>")
        val folders = rootDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        folders.forEach { folder ->
            val isActive = folder.name == actualCurrentDir.name
            val activeClass = if (isActive) "active" else ""
            sb.append("<li class='nav-item $activeClass' onclick=\"location.href='/${folder.name}'\">📂 ${folder.name}</li>")
        }
        sb.append("</ul>")
        sb.append("</nav>")

        // --- MAIN ---
        sb.append("<main class='main-content'>")
        sb.append("<form action='/zip-selected' method='POST' style='height:100%; display:flex; flex-direction:column;'>")
        sb.append("<div class='main-header'>")
        sb.append("<div>")
        sb.append("<div class='breadcrumbs'>Library / ${actualCurrentDir.name}</div>")
        sb.append("<h2>${actualCurrentDir.name}</h2>")
        sb.append("</div>")

        sb.append("<div class='header-controls'>")
        sb.append("<label class='toggle-label'><input type='checkbox' id='chkStructure' name='keepStructure' onchange='updateZipLinks()'> Maintain Folder Structure</label>")
        if (relativePath.isNotEmpty()) {
            val zipUrl = "/zip/$relativePath"
            sb.append("<a href='$zipUrl' data-base='$zipUrl' class='btn btn-secondary zip-link' style='margin:0; width:auto; display:inline-block;'>Download Folder</a>")
        }
        sb.append("<button type='submit' class='btn btn-primary' style='margin:0; width:auto;'>Download Selected</button>")
        sb.append("</div></div>")

        sb.append("<div class='grid-container'><div class='grid'>")

        val files = actualCurrentDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val models = runBlocking { dao.getModelsInFolderList(actualCurrentDir.name) }
        val modelsMap = models.associateBy { it.localFilePath.substringAfterLast("/") }

        files.forEach { file ->
            val modelData = modelsMap[file.name]
            val dlLink = "/${actualCurrentDir.name}/${file.name}"
            val srcUrl = modelData?.pageUrl ?: ""
            var imgUrl = modelData?.thumbnailUrl ?: ""

            // LOGIC CHANGE: If imgUrl is a local path (does not start with http), prepend '/' to make it a server link
            if (imgUrl.isNotEmpty() && !imgUrl.startsWith("http")) {
                if (!imgUrl.startsWith("/")) imgUrl = "/$imgUrl"
            }

            val sizeStr = String.format("%.2f MB", file.length() / 1024.0 / 1024.0)

            sb.append("<div class='card' onclick=\"selectModel('${file.name}', '$sizeStr', '$dlLink', '$srcUrl', '$imgUrl')\">")
            sb.append("<input type='checkbox' name='files' value='${actualCurrentDir.name}/${file.name}' class='checkbox-overlay' onclick='event.stopPropagation()'>")

            if (imgUrl.isNotEmpty()) {
                sb.append("<img src='$imgUrl' class='card-thumb' onerror=\"this.style.opacity='0.3'\">")
            } else {
                sb.append("<div class='card-thumb' style='display:flex;align-items:center;justify-content:center;color:#333;'>No Preview</div>")
            }
            sb.append("<div class='card-body'>")
            sb.append("<div class='card-title'>${file.name}</div>")
            sb.append("</div></div>")
        }

        sb.append("</div></div>")
        sb.append("</form></main>")

        // --- SIDEBAR RIGHT ---
        sb.append("<aside class='sidebar-right'>")
        sb.append("<div id='empty-panel' class='empty-state'>Select a model to view details</div>")
        sb.append("<div id='details-panel' style='display:none;'>")
        sb.append("<img id='detail-img' class='details-thumb' src=''>")
        sb.append("<div id='detail-title' class='details-title'>Model Name</div>")
        sb.append("<div id='detail-meta' class='details-meta'>2.5 MB</div>")
        sb.append("<a id='btn-download' href='#' class='btn btn-primary' download>Download File</a>")
        sb.append("<a id='btn-source' href='#' target='_blank' class='btn btn-outline'>Visit Source Page</a>")
        sb.append("</div>")
        sb.append("</aside>")

        sb.append("</body></html>")
        output.print(sb.toString())
    }

    private fun sendZip(output: PrintStream, targets: List<File>, zipName: String, baseDir: File?) {
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
                    // For folders, we recurse.
                    // If baseDir is provided, we use relative paths. Otherwise flattening logic or standard name.
                    val parentPath = if (baseDir != null) target.relativeTo(baseDir).path + "/" else ""
                    addFolderToZip(zipOut, target, parentPath, buffer)
                } else if (target.isFile) {
                    val entryName = if (baseDir != null) target.relativeTo(baseDir).path else target.name
                    addToZipEntry(zipOut, target, entryName, buffer)
                }
            }
            zipOut.finish()
            zipOut.flush()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun addFolderToZip(zipOut: ZipOutputStream, folder: File, parentPath: String, buffer: ByteArray) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                addFolderToZip(zipOut, file, "$parentPath${file.name}/", buffer)
            } else {
                addToZipEntry(zipOut, file, "$parentPath${file.name}", buffer)
            }
        }
    }

    private fun addToZipEntry(zipOut: ZipOutputStream, file: File, entryName: String, buffer: ByteArray) {
        val fis = FileInputStream(file)
        val entry = ZipEntry(entryName)
        zipOut.putNextEntry(entry)
        var length: Int
        while (fis.read(buffer).also { length = it } >= 0) zipOut.write(buffer, 0, length)
        fis.close()
        zipOut.closeEntry()
    }

    private fun sendFile(output: PrintStream, file: File) {
        try {
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            output.print("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${file.length()}\r\n\r\n")
            val fis = FileInputStream(file)
            val buffer = ByteArray(4096)
            var len: Int
            while (fis.read(buffer).also { len = it } >= 0) output.write(buffer, 0, len)
            fis.close()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun send404(output: PrintStream) {
        output.print("HTTP/1.1 404 Not Found\r\n\r\nFile Not Found")
    }
}