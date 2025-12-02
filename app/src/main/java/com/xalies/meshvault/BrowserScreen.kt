package com.xalies.meshvault

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(webView: WebView) { // Accepting the shared WebView
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.modelDao()
    val scope = rememberCoroutineScope()

    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    val folders by dao.getAllFolders().collectAsState(initial = emptyList())
    var showNewFolderInput by remember { mutableStateOf(false) }

    // Back Handler
    BackHandler(enabled = true) {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                // CRITICAL: We are reusing a view that might already have a parent.
                // We must detach it from the old parent before adding it here.
                if (webView.parent != null) {
                    (webView.parent as ViewGroup).removeView(webView)
                }

                // Re-attach listeners to ensure Context is fresh
                webView.apply {
                    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        val script = """
                            (function() {
                                var img = document.querySelector('meta[property="og:image"]')?.content || "";
                                var title = document.querySelector('meta[property="og:title"]')?.content || document.title;
                                return title + "|||" + img;
                            })();
                        """.trimIndent()

                        this.evaluateJavascript(script) { result ->
                            val cleanResult = result.replace("^\"|\"$".toRegex(), "")
                            val parts = cleanResult.split("|||")
                            val t = if (parts.isNotEmpty()) parts[0] else "Unknown"
                            val i = if (parts.size > 1) parts[1] else ""
                            pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimetype, t, i)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("blob:") || url.startsWith("javascript:")) return false

                            val isAllowed = ALLOWED_DOMAINS.any { url.contains(it, ignoreCase = true) }
                            return !isAllowed
                        }
                    }
                }
                webView
            },
            update = {
                // We don't need to load URL here anymore, it's handled in Dashboard/MainApp
            }
        )
    }

    // --- FOLDER DIALOG (Unchanged) ---
    if (pendingDownload != null) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Save to Vault") },
            text = {
                Column {
                    Text("Model: ${pendingDownload?.title ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (showNewFolderInput) {
                        OutlinedTextField(
                            value = newFolderName, onValueChange = { newFolderName = it },
                            label = { Text("New Folder Name") },
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (newFolderName.isNotBlank()) {
                                        scope.launch { dao.insertFolder(FolderEntity(newFolderName)) }
                                        newFolderName = ""
                                        showNewFolderInput = false
                                    }
                                }) { Icon(Icons.Default.Add, "Create") }
                            }
                        )
                    } else {
                        TextButton(onClick = { showNewFolderInput = true }) {
                            Icon(Icons.Default.CreateNewFolder, null); Text(" Create New Folder")
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                        items(folders) { folder ->
                            Row(modifier = Modifier.fillMaxWidth().clickable {
                                pendingDownload?.let { executeDownload(context, it, folder.name, dao, scope) }
                                pendingDownload = null
                            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null); Spacer(modifier = Modifier.width(12.dp)); Text(folder.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

// Ensure executeDownload is still in this file or imported
fun executeDownload(context: Context, download: PendingDownload, folderName: String, dao: ModelDao, scope: kotlinx.coroutines.CoroutineScope) {
    val request = DownloadManager.Request(Uri.parse(download.url))
    val filename = URLUtil.guessFileName(download.url, download.contentDisposition, download.mimetype)
    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MeshVault/$folderName/$filename")
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(request)

    scope.launch {
        dao.insertModel(ModelEntity(
            title = download.title, pageUrl = download.url, localFilePath = "MeshVault/$folderName/$filename",
            folderName = folderName, thumbnailUrl = download.imageUrl
        ))
        Toast.makeText(context, "Saved to $folderName", Toast.LENGTH_SHORT).show()
    }
}