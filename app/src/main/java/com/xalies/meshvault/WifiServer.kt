package com.xalies.meshvault

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLConnection
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.relativeTo

class WifiServer(private val context: Context, private val dao: ModelDao) {

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
                val input = BufferedInputStream(socket.getInputStream())
                val reader = BufferedReader(InputStreamReader(input))
                val output = PrintStream(socket.getOutputStream())

                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread

                val method = parts[0]
                var path = parts[1]

                // Parse Query Params
                var keepStructure = false
                if (path.contains("?")) {
                    val query = path.substringAfter("?")
                    path = path.substringBefore("?")
                    if (query.contains("keepStructure=true")) {
                        keepStructure = true
                    }
                }

                path = URLDecoder.decode(path, "UTF-8")

                // Read Headers (needed for POST body)
                var contentLength = 0
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }

                val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault")

                if (method == "POST") {
                    // Improved Body Reading Loop for large files
                    val bodyString = if (contentLength > 0) {
                        val buffer = CharArray(contentLength)
                        var totalRead = 0
                        while (totalRead < contentLength) {
                            val read = reader.read(buffer, totalRead, contentLength - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        String(buffer)
                    } else ""

                    if (path == "/zip-selected") {
                        handleZipSelected(output, bodyString, rootDir, keepStructure)
                    } else if (path == "/api/edit") {
                        handleEdit(output, bodyString, rootDir)
                    } else if (path == "/api/delete") {
                        handleDelete(output, bodyString, rootDir)
                    }
                } else if (method == "GET") {
                    if (path == "/favicon.png") {
                        sendFavicon(output)
                    } else if (path.startsWith("/zip/")) {
                        val folderName = path.substring(5)
                        val folder = File(rootDir, folderName)
                        if (folder.exists() && folder.isDirectory) {
                            val baseDir = if (keepStructure) folder.parentFile else folder
                            sendZip(output, listOf(folder), "${folder.name}.zip", baseDir)
                        } else {
                            send404(output)
                        }
                    } else if (path == "/" || path == "/index.html") {
                        sendDashboard(output, rootDir, null)
                    } else {
                        val cleanPath = path.removePrefix("/")
                        val folderIndex = runBlocking { dao.getAllFoldersList() }
                        val requestedFolderKnown = folderMatches(cleanPath, folderIndex)
                        val target = File(rootDir, cleanPath)

                        if (target.exists()) {
                            if (target.isDirectory) {
                                sendDashboard(output, rootDir, target)
                            } else {
                                sendFile(output, target)
                            }
                        } else if (requestedFolderKnown) {
                            sendDashboard(output, rootDir, target)
                        } else {
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

    private fun sendFavicon(output: PrintStream) {
        try {
            val drawable = context.resources.getDrawable(R.mipmap.ic_launcher, context.theme)
            val bitmap = drawable.toBitmap()
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()

            output.print("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\n\r\n")
            output.write(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            send404(output)
        }
    }

    private fun handleZipSelected(output: PrintStream, body: String, rootDir: File, defaultKeepStructure: Boolean) {
        val filesToZip = mutableListOf<File>()
        val pairs = body.split("&")
        var keepStructure = defaultKeepStructure

        for (pair in pairs) {
            val kv = pair.split("=")
            if (kv.size == 2) {
                val key = URLDecoder.decode(kv[0], "UTF-8")
                val value = URLDecoder.decode(kv[1], "UTF-8")

                if (key == "files") {
                    val f = File(rootDir, value)
                    if (f.exists()) filesToZip.add(f)
                } else if (key == "keepStructure" && value == "on") {
                    keepStructure = true
                }
            }
        }

        if (filesToZip.isNotEmpty()) {
            val baseDir = if (keepStructure) rootDir else null
            sendZip(output, filesToZip, "selected_models.zip", baseDir)
        } else {
            redirectHome(output)
        }
    }

    private fun handleEdit(output: PrintStream, body: String, rootDir: File) {
        val params = parseParams(body)
        val filePath = params["filePath"]
        val newTitle = params["title"]
        val newUrl = params["url"]
        val thumbnailData = params["thumbnailData"] // Base64 encoded

        if (filePath != null && newTitle != null) {
            runBlocking {
                val allModels = dao.getAllModels()
                val model = allModels.find { it.localFilePath == "MeshVault/$filePath" }

                if (model != null) {
                    var updatedModel = model.copy(
                        title = newTitle,
                        pageUrl = newUrl ?: model.pageUrl
                    )

                    // Handle Thumbnail Update
                    if (!thumbnailData.isNullOrBlank()) {
                        try {
                            val rawBytes = Base64.decode(thumbnailData, Base64.DEFAULT)
                            val processedBytes = resizeThumbnailBytes(rawBytes) ?: rawBytes

                            val folderPath = if (model.folderName.isNotBlank()) "MeshVault/${model.folderName}" else "MeshVault"
                            val thumbnailDir = File(rootDir, if (model.folderName.isNotBlank()) model.folderName else "")
                            if (!thumbnailDir.exists()) thumbnailDir.mkdirs()

                            val thumbnailName = "thumb_custom_${System.currentTimeMillis()}.jpg"
                            val thumbnailFile = File(thumbnailDir, thumbnailName)

                            FileOutputStream(thumbnailFile).use { fos ->
                                fos.write(processedBytes)
                            }

                            // Delete old thumbnail if local
                            model.thumbnailUrl?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { currentPath ->
                                val existing = File(rootDir, currentPath.removePrefix("MeshVault/"))
                                if (existing.exists() && existing != thumbnailFile) existing.delete()
                            }

                            val relativePath = if (model.folderName.isNotBlank()) "${model.folderName}/$thumbnailName" else thumbnailName
                            updatedModel = updatedModel.copy(
                                thumbnailUrl = relativePath,
                                thumbnailData = processedBytes
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    dao.updateModel(updatedModel)
                    writeMetadataForModel(updatedModel)
                }
            }
        }
        redirectHome(output)
    }

    private fun handleDelete(output: PrintStream, body: String, rootDir: File) {
        val params = parseParams(body)
        val filePath = params["filePath"]

        if (filePath != null) {
            runBlocking {
                val allModels = dao.getAllModels()
                val model = allModels.find { it.localFilePath == "MeshVault/$filePath" }

                if (model != null) {
                    val file = File(rootDir, filePath)
                    if (file.exists()) file.delete()

                    val metaFile = File(file.parentFile, "${file.name}.meta.json")
                    if (metaFile.exists()) metaFile.delete()

                    if (!model.thumbnailUrl.isNullOrBlank() && !model.thumbnailUrl.startsWith("http")) {
                        val cleanThumbPath = model.thumbnailUrl.removePrefix("MeshVault/").removePrefix("/")
                        val thumbFile = File(rootDir, cleanThumbPath)
                        if (thumbFile.exists()) thumbFile.delete()
                    }

                    if (model.googleDriveId != null) {
                        dao.softDeleteModel(model.id)
                    } else {
                        dao.hardDeleteModel(model.id)
                    }
                }
            }
        }
        redirectHome(output)
    }
    private fun parseParams(body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        body.split("&").forEach { pair ->
            val kv = pair.split("=")
            if (kv.size == 2) {
                // Handle potential Base64 plus signs which URLDecoder might turn into spaces
                val key = URLDecoder.decode(kv[0], "UTF-8")
                var value = URLDecoder.decode(kv[1], "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun redirectHome(output: PrintStream) {
        output.print("HTTP/1.1 303 See Other\r\nLocation: /\r\n\r\n")
    }

    // --- DASHBOARD UI ---
    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun sendDashboard(output: PrintStream, rootDir: File, currentDir: File?) {
        val allFolders = runBlocking { dao.getAllFoldersList() }
        val showAllModels = currentDir == null
        val relativePath = if (!showAllModels && currentDir != null && currentDir != rootDir) {
            currentDir.toPath().toAbsolutePath().normalize()
                .relativeTo(rootDir.toPath().toAbsolutePath().normalize()).toString()
        } else {
            ""
        }

        val currentTitle = if (showAllModels) "All Models" else (relativePath.ifEmpty { rootDir.name })

        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n")
        sb.append("<!DOCTYPE html><html><head><title>MeshVault</title>")
        sb.append("<link rel='icon' href='/favicon.png' type='image/png'>")
        sb.append("<style>")
        sb.append("body { margin: 0; font-family: 'Segoe UI', sans-serif; background: #121212; color: #fff; height: 100vh; display: flex; overflow: hidden; }")
        sb.append("a { text-decoration: none; color: inherit; }")
        sb.append(".sidebar-left { width: 250px; background: #1a1a1a; border-right: 1px solid #333; display: flex; flex-direction: column; }")
        sb.append(".main-content { flex: 1; background: #121212; display: flex; flex-direction: column; overflow: hidden; position: relative; }")
        sb.append(".sidebar-right { width: 300px; background: #1a1a1a; border-left: 1px solid #333; padding: 20px; display: flex; flex-direction: column; }")
        sb.append(".app-title { padding: 20px; font-size: 1.2rem; font-weight: bold; color: #bb86fc; border-bottom: 1px solid #333; }")
        sb.append(".nav-list { list-style: none; padding: 0; margin: 0; overflow-y: auto; flex: 1; }")
        sb.append(".nav-item { padding: 15px 20px; border-bottom: 1px solid #252525; cursor: pointer; transition: background 0.2s; color: #aaa; }")
        sb.append(".nav-item:hover { background: #252525; color: #fff; }")
        sb.append(".nav-item.active { background: #333; color: #bb86fc; border-left: 3px solid #bb86fc; }")
        sb.append(".main-header { padding: 20px; border-bottom: 1px solid #333; display: flex; justify-content: space-between; align-items: center; background: #1f1f1f; }")
        sb.append(".grid-container { padding: 20px; overflow-y: auto; height: 100%; }")
        sb.append(".grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 15px; }")
        sb.append(".card { background: #252525; border-radius: 8px; overflow: hidden; cursor: pointer; border: 2px solid transparent; transition: all 0.2s; position: relative; }")
        sb.append(".card:hover { transform: translateY(-3px); border-color: #555; }")
        sb.append(".card-thumb { width: 100%; height: 140px; background: #000; object-fit: cover; }")
        sb.append(".card-body { padding: 10px; }")
        sb.append(".card-title { font-size: 0.9em; font-weight: bold; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }")
        sb.append(".card-meta { color: #888; font-size: 0.8em; margin-top: 4px; }")
        sb.append(".checkbox-overlay { position: absolute; top: 8px; left: 8px; transform: scale(1.3); cursor: pointer; z-index: 10; }")
        sb.append(".btn { display: block; width: 100%; padding: 12px; text-align: center; border-radius: 6px; margin-bottom: 10px; font-weight: bold; cursor: pointer; border: none; text-decoration: none; font-size: 0.9em; }")
        sb.append(".btn-primary { background: #bb86fc; color: #000; }")
        sb.append(".btn-secondary { background: #333; color: #fff; }")
        sb.append(".btn-danger { background: #cf6679; color: #000; }")
        sb.append(".btn-outline { background: transparent; border: 1px solid #555; color: #aaa; }")
        sb.append(".modal-overlay { display: none; position: absolute; top:0; left:0; width:100%; height:100%; background: rgba(0,0,0,0.8); z-index: 999; justify-content: center; align-items: center; }")
        sb.append(".modal { background: #222; padding: 25px; border-radius: 12px; width: 400px; border: 1px solid #444; }")
        sb.append(".modal h3 { margin-top:0; color: #bb86fc; }")
        sb.append(".form-group { margin-bottom: 15px; }")
        sb.append(".form-group label { display: block; margin-bottom: 5px; color: #aaa; font-size: 0.9em; }")
        sb.append(".form-group input { width: 100%; padding: 8px; background: #333; border: 1px solid #444; color: #fff; border-radius: 4px; box-sizing: border-box; }")
        sb.append(".thumb-preview { width: 100px; height: 100px; object-fit: cover; background: #000; border-radius: 4px; display: block; margin-top: 5px; }")
        sb.append("</style>")

        sb.append("<script>")
        sb.append("var currentModelPath = '';")
        sb.append("function selectModel(card) {")
        sb.append("  var ds = card.dataset;")
        sb.append("  currentModelPath = ds.path;")
        sb.append("  document.getElementById('detail-img').src = ds.img || '';")
        sb.append("  document.getElementById('detail-img').style.display = ds.img ? 'block' : 'none';")
        sb.append("  document.getElementById('detail-title').innerText = ds.title;")
        sb.append("  document.getElementById('detail-meta').innerText = ds.size;")
        sb.append("  document.getElementById('btn-download').href = ds.dl;")
        sb.append("  var srcBtn = document.getElementById('btn-source');")
        sb.append("  if(ds.src) { srcBtn.href = ds.src; srcBtn.style.display = 'block'; srcBtn.dataset.url = ds.src; } else { srcBtn.style.display = 'none'; srcBtn.dataset.url = ''; }")
        sb.append("  document.getElementById('details-panel').style.display = 'block';")
        sb.append("  document.getElementById('empty-panel').style.display = 'none';")
        sb.append("}")
        sb.append("function openEditModal() {")
        sb.append("  if(!currentModelPath) return;")
        sb.append("  document.getElementById('edit-filePath').value = currentModelPath;")
        sb.append("  document.getElementById('edit-title').value = document.getElementById('detail-title').innerText;")
        sb.append("  document.getElementById('edit-url').value = document.getElementById('btn-source').dataset.url || '';")
        sb.append("  document.getElementById('edit-modal').style.display = 'flex';")
        sb.append("  document.getElementById('edit-thumb-input').value = '';")
        sb.append("  document.getElementById('edit-thumb-data').value = '';")
        sb.append("  document.getElementById('edit-thumb-preview').style.display = 'none';")
        sb.append("}")
        sb.append("function closeEditModal() { document.getElementById('edit-modal').style.display = 'none'; }")
        sb.append("function handleThumbSelect(input) {")
        sb.append("  if (input.files && input.files[0]) {")
        sb.append("    var reader = new FileReader();")
        sb.append("    reader.onload = function(e) {")
        sb.append("      document.getElementById('edit-thumb-preview').src = e.target.result;")
        sb.append("      document.getElementById('edit-thumb-preview').style.display = 'block';")
        sb.append("      var b64 = e.target.result.split(',')[1];")
        sb.append("      document.getElementById('edit-thumb-data').value = encodeURIComponent(b64);")
        sb.append("    };")
        sb.append("    reader.readAsDataURL(input.files[0]);")
        sb.append("  }")
        sb.append("}")
        sb.append("function confirmDelete() {")
        sb.append("  if(!currentModelPath) return;")
        sb.append("  if(confirm('Are you sure you want to delete this model?')) {")
        sb.append("    var form = document.createElement('form');")
        sb.append("    form.method = 'POST'; form.action = '/api/delete';")
        sb.append("    var input = document.createElement('input');")
        sb.append("    input.type = 'hidden'; input.name = 'filePath'; input.value = currentModelPath;")
        sb.append("    form.appendChild(input);")
        sb.append("    document.body.appendChild(form); form.submit();")
        sb.append("  }")
        sb.append("}")
        sb.append("</script>")
        sb.append("</head><body>")

        // -- LEFT NAV --
        sb.append("<nav class='sidebar-left'><div class='app-title'>MESH VAULT</div><ul class='nav-list'>")
        val folderTree = buildFolderTree(allFolders.map { it.name })
        val allActiveClass = if (showAllModels) "active" else ""
        sb.append("<li class='nav-item $allActiveClass' onclick=\"location.href='/'\">📁 All Models</li>")
        appendFolderNav(sb, folderTree, currentPath = relativePath, depth = 0)
        sb.append("</ul></nav>")

        // -- MAIN CONTENT --
        sb.append("<main class='main-content'>")
        sb.append("<form action='/zip-selected' method='POST' style='height:100%; display:flex; flex-direction:column;'>")
        sb.append("<div class='main-header'><div><div style='color:#888;font-size:0.9em'>Library / $currentTitle</div><h2>$currentTitle</h2></div>")
        sb.append("<div style='display:flex;gap:15px;align-items:center;'>")
        sb.append("<label style='font-size:0.9em;cursor:pointer'><input type='checkbox' name='keepStructure'> Maintain Folder Structure</label>")
        if (relativePath.isNotEmpty()) {
            val encodedPath = encodePath(relativePath)
            sb.append("<a href='/zip/$encodedPath' class='btn btn-secondary' style='margin:0;width:auto;display:inline-block'>Download Folder</a>")
        }
        sb.append("<button type='submit' class='btn btn-primary' style='margin:0;width:auto;'>Download Selected</button>")
        sb.append("</div></div>")

        sb.append("<div class='grid-container'><div class='grid'>")
        val dbModels = if (showAllModels) runBlocking { dao.getAllModels() } else runBlocking { dao.getModelsInFolderList(relativePath) }
        val validModels = dbModels.filter {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), it.localFilePath).exists()
        }

        if (validModels.isEmpty()) {
            sb.append("<div style='grid-column:1/-1;text-align:center;color:#666;margin-top:50px;'>No models found</div>")
        } else {
            validModels.forEach { model ->
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), model.localFilePath)
                val sizeStr = String.format("%.2f MB", file.length() / 1024.0 / 1024.0)
                val dlLink = "/${encodePath(model.localFilePath.trimStart('/'))}"
                val relativeFilePath = model.localFilePath.trimStart('/').replace("MeshVault/", "")

                val inlineThumb = model.thumbnailData?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                var imgUrl = inlineThumb?.let { "data:image/jpeg;base64,$it" } ?: model.thumbnailUrl ?: ""
                if (inlineThumb == null && imgUrl.isNotEmpty() && !imgUrl.startsWith("http") && !imgUrl.startsWith("/")) imgUrl = "/$imgUrl"

                // --- CHANGED: Use data- attributes for safe handling of special chars ---
                sb.append("<div class='card' onclick='selectModel(this)'")
                sb.append(" data-title=\"${escapeHtml(model.title)}\"")
                sb.append(" data-size=\"${escapeHtml(sizeStr)}\"")
                sb.append(" data-dl=\"${escapeHtml(dlLink)}\"")
                sb.append(" data-src=\"${escapeHtml(model.pageUrl)}\"")
                sb.append(" data-img=\"${escapeHtml(imgUrl)}\"")
                sb.append(" data-path=\"${escapeHtml(relativeFilePath)}\">")

                sb.append("<input type='checkbox' name='files' value=\"${escapeHtml(relativeFilePath)}\" class='checkbox-overlay' onclick='event.stopPropagation()'>")
                if (imgUrl.isNotEmpty()) sb.append("<img src=\"${escapeHtml(imgUrl)}\" class='card-thumb'>")
                else sb.append("<div class='card-thumb' style='display:flex;align-items:center;justify-content:center;color:#333'>No Preview</div>")
                sb.append("<div class='card-body'><div class='card-title'>${escapeHtml(model.title)}</div><div class='card-meta'>$sizeStr</div></div></div>")
            }
        }
        sb.append("</div></div></form>")

        // -- EDIT MODAL --
        sb.append("<div id='edit-modal' class='modal-overlay'><div class='modal'>")
        sb.append("<h3>Edit Model</h3>")
        sb.append("<form action='/api/edit' method='POST'>")
        sb.append("<input type='hidden' id='edit-filePath' name='filePath'>")
        sb.append("<input type='hidden' id='edit-thumb-data' name='thumbnailData'>")
        sb.append("<div class='form-group'><label>Title</label><input type='text' id='edit-title' name='title' required></div>")
        sb.append("<div class='form-group'><label>Source URL</label><input type='text' id='edit-url' name='url'></div>")
        sb.append("<div class='form-group'><label>Update Thumbnail</label><input type='file' id='edit-thumb-input' accept='image/*' onchange='handleThumbSelect(this)'>")
        sb.append("<img id='edit-thumb-preview' class='thumb-preview' style='display:none'></div>")
        sb.append("<button type='submit' class='btn btn-primary'>Save Changes</button>")
        sb.append("<button type='button' class='btn btn-secondary' onclick='closeEditModal()'>Cancel</button>")
        sb.append("</form></div></div>")

        sb.append("</main>")

        // -- RIGHT SIDEBAR --
        sb.append("<aside class='sidebar-right'>")
        sb.append("<div id='empty-panel' style='color:#666;text-align:center;margin-top:100px'>Select a model</div>")
        sb.append("<div id='details-panel' style='display:none'>")
        sb.append("<img id='detail-img' style='width:100%;height:200px;background:#000;border-radius:8px;object-fit:cover;margin-bottom:20px'>")
        sb.append("<div id='detail-title' style='font-size:1.4rem;font-weight:bold;margin-bottom:10px;line-height:1.2'></div>")
        sb.append("<div id='detail-meta' style='color:#888;font-size:0.9em;margin-bottom:20px'></div>")
        sb.append("<a id='btn-download' class='btn btn-primary'>Download File</a>")
        sb.append("<a id='btn-source' target='_blank' class='btn btn-outline'>Visit Source</a>")
        sb.append("<div style='height:20px'></div>")
        sb.append("<button onclick='openEditModal()' class='btn btn-secondary'>Edit Details</button>")
        sb.append("<button onclick='confirmDelete()' class='btn btn-danger'>Delete Model</button>")
        sb.append("</div></aside>")

        sb.append("</body></html>")
        output.print(sb.toString())
    }

    // --- UTILS ---
    private fun folderMatches(requestedPath: String, folders: List<FolderEntity>): Boolean {
        val req = requestedPath.trimEnd('/').trimEnd()
        return folders.any {
            val f = it.name.trimEnd('/').trimEnd()
            f == req || f.startsWith("$req/")
        }
    }

    private fun encodePath(path: String) = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private data class FolderNode(val name: String, val fullPath: String, val children: MutableList<FolderNode> = mutableListOf())

    private fun buildFolderTree(paths: List<String>): List<FolderNode> {
        val roots = mutableListOf<FolderNode>()
        paths.sorted().forEach { path ->
            val parts = path.split("/")
            var currentList = roots
            var currentPath = ""
            parts.forEach { part ->
                currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                val existing = currentList.find { it.name == part }
                val node = existing ?: FolderNode(part, currentPath).also { currentList.add(it) }
                currentList = node.children
            }
        }
        return roots
    }

    private fun appendFolderNav(sb: StringBuilder, nodes: List<FolderNode>, currentPath: String, depth: Int) {
        val normCurrent = currentPath.trimEnd('/')
        nodes.forEach { node ->
            val active = normCurrent == node.fullPath.trimEnd('/')
            val activeClass = if (active) "active" else ""
            val indent = depth * 12 + 20
            sb.append("<li class='nav-item $activeClass' style='padding-left:${indent}px' onclick=\"location.href='/${encodePath(node.fullPath)}'\">📂 ${node.name}</li>")
            if (node.children.isNotEmpty()) appendFolderNav(sb, node.children, currentPath, depth + 1)
        }
    }

    private fun sendZip(output: PrintStream, targets: List<File>, zipName: String, baseDir: File?) {
        try {
            output.print("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"$zipName\"\r\n\r\n")
            val zipOut = ZipOutputStream(output)
            val buffer = ByteArray(4096)
            for (target in targets) {
                if (target.isDirectory) addFolderToZip(zipOut, target, if (baseDir != null) target.relativeTo(baseDir).path + "/" else "", buffer)
                else addToZipEntry(zipOut, target, if (baseDir != null) target.relativeTo(baseDir).path else target.name, buffer)
            }
            zipOut.finish(); zipOut.flush()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun addFolderToZip(zipOut: ZipOutputStream, folder: File, parentPath: String, buffer: ByteArray) {
        folder.listFiles()?.forEach {
            if (it.isDirectory) addFolderToZip(zipOut, it, "$parentPath${it.name}/", buffer)
            else addToZipEntry(zipOut, it, "$parentPath${it.name}", buffer)
        }
    }

    private fun addToZipEntry(zipOut: ZipOutputStream, file: File, entryName: String, buffer: ByteArray) {
        try {
            val fis = FileInputStream(file)
            zipOut.putNextEntry(ZipEntry(entryName))
            var len: Int
            while (fis.read(buffer).also { len = it } >= 0) zipOut.write(buffer, 0, len)
            fis.close(); zipOut.closeEntry()
        } catch (e: Exception) { e.printStackTrace() }
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