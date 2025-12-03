package com.xalies.meshvault

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient // Added for Progress
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
import androidx.compose.ui.zIndex // Added for layering the progress bar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(webView: WebView) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.modelDao()
    val scope = rememberCoroutineScope()

    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    val folders by dao.getAllFolders().collectAsState(initial = emptyList())
    var showNewFolderInput by remember { mutableStateOf(false) }

    // Progress State (0.0 to 1.0)
    var loadProgress by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // 1. The Browser View
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                if (webView.parent != null) {
                    (webView.parent as ViewGroup).removeView(webView)
                }

                webView.apply {
                    // Attach Chrome Client for Progress Updates
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress / 100f
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        // Capture current page URL for "Source Link"
                        val currentPage = this.url ?: ""

                        // --- AGGRESSIVE SCRAPER ---
                        val script = """
                            (function() {
                                function findMainImage() {
                                    // A. Try Standard Meta Tags
                                    var img = document.querySelector('meta[property="og:image"]')?.content;
                                    if (img) return img;
                                    
                                    img = document.querySelector('meta[name="twitter:image"]')?.content;
                                    if (img) return img;
                                    
                                    // B. Try Schema.org
                                    img = document.querySelector('[itemprop="image"]')?.src;
                                    if (img) return img;
                                    
                                    // C. Try specific site selectors
                                    var galleryImg = document.querySelector('.gallery-image, .swipe-slide img, .model-preview img');
                                    if (galleryImg) return galleryImg.src;

                                    return "";
                                }
                                
                                var title = document.querySelector('meta[property="og:title"]')?.content || document.title;
                                var finalImg = findMainImage();
                                
                                return title + "|||" + (finalImg || "");
                            })();
                        """.trimIndent()

                        this.evaluateJavascript(script) { result ->
                            val cleanResult = result.replace("^\"|\"$".toRegex(), "")
                            val unescaped = cleanResult.replace("\\/", "/")

                            val parts = unescaped.split("|||")
                            val t = if (parts.isNotEmpty()) parts[0] else "Unknown"
                            val i = if (parts.size > 1) parts[1] else ""

                            pendingDownload = PendingDownload(url, currentPage, userAgent, contentDisposition, mimetype, t, i)
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
            }
        )

        // 2. Loading Indicator (Shows only when loading)
        if (loadProgress < 1.0f) {
            LinearProgressIndicator(
                progress = { loadProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(2f), // Ensure it sits on top of the WebView
            )
        }
    }

    // --- Save Dialog Logic ---
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

fun executeDownload(context: Context, download: PendingDownload, folderName: String, dao: ModelDao, scope: kotlinx.coroutines.CoroutineScope) {
    // 1. Download File
    val request = DownloadManager.Request(Uri.parse(download.url))
    val filename = URLUtil.guessFileName(download.url, download.contentDisposition, download.mimetype)
    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MeshVault/$folderName/$filename")
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(request)

    // 2. Download Image
    scope.launch(Dispatchers.IO) {
        var localImagePath = ""

        if (download.imageUrl.isNotEmpty()) {
            try {
                val imgFilename = "thumb_" + System.currentTimeMillis() + ".jpg"
                val vaultDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault/$folderName")
                if (!vaultDir.exists()) vaultDir.mkdirs()

                val imgFile = File(vaultDir, imgFilename)
                URL(download.imageUrl).openStream().use { input ->
                    FileOutputStream(imgFile).use { output ->
                        input.copyTo(output)
                    }
                }
                // Save Relative Path for portability
                localImagePath = "$folderName/$imgFilename"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Save to DB (Using Page URL, not File URL)
        val newModel = ModelEntity(
            title = download.title,
            pageUrl = download.pageUrl,
            localFilePath = "MeshVault/$folderName/$filename",
            folderName = folderName,
            thumbnailUrl = if (localImagePath.isNotEmpty()) localImagePath else download.imageUrl
        )
        dao.insertModel(newModel)

        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Saved to $folderName", Toast.LENGTH_SHORT).show()
        }
    }
}