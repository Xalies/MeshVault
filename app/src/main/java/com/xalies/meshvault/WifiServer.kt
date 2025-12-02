package com.xalies.meshvault

import android.os.Environment
import fi.iki.elonen.NanoHTTPd
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

class WifiServer : NanoHTTPd(8080) { // Runs on Port 8080

    override fun serve(session: IHTTPSession): Response {
        val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault")
        val uri = session.uri

        // 1. If asking for a file (e.g. /SciFi/ship.stl), send the download
        if (uri.length > 1) {
            val file = File(rootDir, uri)
            if (file.exists() && file.isFile) {
                val mime = getMimeTypeForFile(uri)
                return try {
                    val stream = FileInputStream(file)
                    newChunkedResponse(Response.Status.OK, mime, stream)
                } catch (e: Exception) {
                    newFixedLengthResponse("Error serving file")
                }
            }
        }

        // 2. Otherwise, show the HTML Index (List of Files)
        val sb = StringBuilder()
        sb.append("<html><head><title>MeshVault PC Export</title>")
        sb.append("<style>body{font-family:sans-serif; padding:20px;} .folder{font-weight:bold; color:#007AFF; margin-top:10px;} a{text-decoration:none; color:#333; display:block; padding:5px;} a:hover{background:#eee;}</style>")
        sb.append("</head><body>")
        sb.append("<h1>MeshVault Library</h1>")
        sb.append("<p>Click a file to download it to your PC.</p><hr>")

        if (rootDir.exists() && rootDir.isDirectory) {
            rootDir.listFiles()?.forEach { folder ->
                if (folder.isDirectory) {
                    sb.append("<div class='folder'>📂 ${folder.name}</div>")
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            // Link format: /FolderName/FileName
                            val href = "/${folder.name}/${file.name}"
                            sb.append("<a href='$href'>📄 ${file.name}</a>")
                        }
                    }
                }
            }
        } else {
            sb.append("<p>No files found in MeshVault folder.</p>")
        }

        sb.append("</body></html>")
        return newFixedLengthResponse(sb.toString())
    }
}