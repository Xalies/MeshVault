package com.xalies.meshvault

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse // Added Import
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.xalies.meshvault.adjustDownloadUrlForSite
import com.xalies.meshvault.adjustFilenameForSite
import com.xalies.meshvault.metadataScriptForPage
import com.xalies.meshvault.pageLoadScriptForPage
import com.xalies.meshvault.resizeThumbnailBytes
import com.xalies.meshvault.writeMetadataForModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val LOG_TAG = "BrowserScreen"

@OptIn(ExperimentalFoundationApi::class)
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

    // Subfolder Creation State
    var showSubfolderDialog by remember { mutableStateOf(false) }
    var targetParentFolder by remember { mutableStateOf<FolderEntity?>(null) }

    // Progress State
    var loadProgress by remember { mutableFloatStateOf(0f) }

    // Make sure starter folders exist even if the user hasn't visited the Library tab yet.
    LaunchedEffect(Unit) {
        ensureDefaultFoldersInitialized(context, dao)
    }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                if (webView.parent != null) {
                    (webView.parent as ViewGroup).removeView(webView)
                }

                webView.apply {
                    setBackgroundColor(android.graphics.Color.BLACK)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress / 100f
                        }

                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                            message?.let {
                                Log.d(LOG_TAG, "[JS] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                            }
                            return super.onConsoleMessage(message)
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        val currentPage = this.url ?: ""
                        val adjustedUrl = adjustDownloadUrlForSite(currentPage, url)

                        Log.d(LOG_TAG, "Download requested: original=$url currentPage=$currentPage adjusted=$adjustedUrl")

                        // --- ROBUST SCRAPER ---
                        val script = metadataScriptForPage(currentPage)

                        this.evaluateJavascript(script) { result ->
                            val cleanResult = result.replace("^\"|\"$".toRegex(), "")
                            val unescaped = cleanResult.replace("\\/", "/")
                            val parts = unescaped.split("|||")

                            val parsedTitle = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                            val parsedImage = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                            val parsedPageUrl = parts.getOrNull(2)?.takeIf { it.isNotBlank() }

                            val fallbackTitle = this.title?.takeIf { it.isNotBlank() }
                                ?: URLUtil.guessFileName(adjustedUrl, contentDisposition, mimetype)

                            val title = parsedTitle ?: fallbackTitle ?: "Unknown"
                            val image = parsedImage ?: ""
                            val pageUrl = parsedPageUrl ?: currentPage

                            pendingDownload = PendingDownload(adjustedUrl, pageUrl, userAgent, contentDisposition, mimetype, title, image)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        // --- NEW: AD BLOCKING INTERCEPTOR ---
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString()
                            if (!isPrintables(url) && AdBlocker.shouldBlock(url)) {
                                Log.d(LOG_TAG, "Blocking Ad: ${request?.url}")
                                return AdBlocker.createEmptyResource()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("blob:") || url.startsWith("javascript:")) return false
                            val isAllowed = ALLOWED_DOMAINS.any { url.contains(it, ignoreCase = true) }
                            return !isAllowed
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)

                            // --- NEW: INJECT COOKIE HIDER ---
                            if (!isPrintables(url)) {
                                view?.evaluateJavascript(AdBlocker.HIDE_ANNOYANCES_SCRIPT, null)
                            }

                            url?.let { currentUrl ->
                                pageLoadScriptForPage(currentUrl)?.let { script ->
                                    Log.d(LOG_TAG, "Running page-load script for $currentUrl")
                                    view?.evaluateJavascript(script, null)
                                }
                            }

                            if (url?.contains("thingiverse.com/thing:") != true) return

                            Log.d(LOG_TAG, "Thingiverse page detected, installing download-all hook on $url")

                            val script = """
                                (function() {
                                    const textMatches = (node) => {
                                        if (!node) return false;
                                        const text = (node.textContent || '').toLowerCase();
                                        const aria = (node.getAttribute('aria-label') || '').toLowerCase();
                                        return text.includes('download all files') || aria.includes('download all files');
                                    };

                                    const updateUrl = () => {
                                        const pageUrl = window.location.href.split('#')[0].replace(/\/$/, '');
                                        const normalized = pageUrl.replace(/\/files(?:\/)?$/, '');
                                        const zipUrl = normalized.endsWith('/zip') ? normalized : normalized + '/zip';
                                        console.log('[MeshVault] Redirecting to zip: ' + zipUrl);
                                        window.location.href = zipUrl;
                                    };

                                    const attachListener = (element) => {
                                        if (!element || element.dataset.meshvaultDownloadAllHooked) return;
                                        element.dataset.meshvaultDownloadAllHooked = 'true';
                                        element.addEventListener('click', function(event) {
                                            event.preventDefault();
                                            event.stopPropagation();
                                            console.log('[MeshVault] Intercepted Download All Files click');
                                            updateUrl();
                                            return false;
                                        }, true);
                                        console.log('[MeshVault] Hooked Download All Files control');
                                    };

                                    const scan = () => {
                                        const candidates = Array.from(document.querySelectorAll('a, button'));
                                        candidates.forEach((el) => {
                                            if (textMatches(el)) {
                                                attachListener(el);
                                            }
                                        });
                                    };

                                    // Initial scan for server-rendered nodes
                                    scan();

                                    console.log('[MeshVault] Installed Download All Files observer');

                                    // Observe SPA updates so we catch the button when it appears later
                                    const observer = new MutationObserver(() => scan());
                                    observer.observe(document.body, { childList: true, subtree: true });
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(script, null)
                        }
                    }
                }
                webView
            }
        )

        if (loadProgress < 1.0f) {
            LinearProgressIndicator(
                progress = { loadProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }

    // --- MAIN DOWNLOAD DIALOG ---
    if (pendingDownload != null) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Save to Vault") },
            text = {
                // Use a fixed-size Column to prevent layout jumps
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Model: ${pendingDownload?.title ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                    Text("Tip: Long press a folder to create a subfolder inside it.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (showNewFolderInput) {
                        val createFolder: () -> Unit = {
                            val folderName = newFolderName.trim()
                            if (folderName.isNotBlank()) {
                                scope.launch {
                                    dao.insertFolder(
                                        FolderEntity(
                                            folderName,
                                            color = FOLDER_COLORS.random(),
                                            iconName = "Folder"
                                        )
                                    )
                                    newFolderName = ""
                                    showNewFolderInput = false
                                }
                            }
                        }

                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("New Folder Name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { createFolder() }),
                            trailingIcon = {
                                IconButton(onClick = { createFolder() }) { Icon(Icons.Default.Add, "Create") }
                            }
                        )
                    } else {
                        TextButton(onClick = { showNewFolderInput = true }) {
                            Icon(Icons.Default.CreateNewFolder, null); Text(" Create New Folder")
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    // 1. FIXED HEIGHT CONTAINER FOR LIST
                    val listState = rememberLazyListState()
                    Box(
                        modifier = Modifier
                            .height(300.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .simpleVerticalScrollbar(listState),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(folders) { folder ->
                                val isSubfolder = folder.name.contains("/")
                                val indent = if (isSubfolder) 32.dp else 0.dp

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                pendingDownload?.let { executeDownload(context, it, folder.name, dao, scope) }
                                                pendingDownload = null
                                            },
                                            onLongClick = {
                                                targetParentFolder = folder
                                                showSubfolderDialog = true
                                            }
                                        )
                                        .padding(vertical = 12.dp, horizontal = 8.dp)
                                        .padding(start = indent), // Add indentation
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Colorful Icon
                                    val icon = FOLDER_ICONS[folder.iconName] ?: Icons.Default.Folder
                                    val color = if (folder.color == 0L) Color(0xFF49454F) else Color(folder.color)

                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(color),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(icon, null, tint = Color.White)
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        folder.name.substringAfterLast("/"),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // --- SUBFOLDER CREATION DIALOG ---
    if (showSubfolderDialog && targetParentFolder != null) {
        var subfolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubfolderDialog = false },
            title = { Text("Create Subfolder") },
            text = {
                Column {
                    Text("Create inside '${targetParentFolder!!.name.substringAfterLast("/")}'")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = subfolderName,
                        onValueChange = { subfolderName = it },
                        label = { Text("Subfolder Name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (subfolderName.isNotBlank()) {
                        val newPath = "${targetParentFolder!!.name}/$subfolderName"
                        scope.launch {
                            // Inherit color/icon or use default
                            dao.insertFolder(FolderEntity(newPath, color = FOLDER_COLORS.random(), iconName = "Folder"))
                        }
                        showSubfolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showSubfolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// Custom Modifier to draw a simple vertical scrollbar
fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp
): Modifier = composed {
    val targetAlpha = if (state.isScrollInProgress) 1f else 0.3f
    val duration = if (state.isScrollInProgress) 150 else 500

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration), label = ""
    )

    drawWithContent {
        drawContent()

        val firstVisibleElementIndex = state.firstVisibleItemIndex
        val elementHeight = this.size.height / state.layoutInfo.totalItemsCount
        val scrollbarOffsetY = firstVisibleElementIndex * elementHeight
        val scrollbarHeight = state.layoutInfo.visibleItemsInfo.size * elementHeight

        drawRect(
            color = Color.Gray.copy(alpha = alpha),
            topLeft = Offset(this.size.width - width.toPx(), scrollbarOffsetY),
            size = Size(width.toPx(), scrollbarHeight),
            alpha = alpha
        )
    }
}

private fun isPrintables(url: String?): Boolean {
    val lower = url?.lowercase(Locale.ROOT) ?: return false
    return lower.contains("printables.com") || lower.contains("prusa3d.com")
}

fun executeDownload(context: Context, download: PendingDownload, folderName: String, dao: ModelDao, scope: kotlinx.coroutines.CoroutineScope) {
    val request = DownloadManager.Request(Uri.parse(download.url))
    val guessedFilename = URLUtil.guessFileName(download.url, download.contentDisposition, download.mimetype)
    val filename = adjustFilenameForSite(download, guessedFilename)
    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MeshVault/$folderName/$filename")
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(request)

    scope.launch(Dispatchers.IO) {
        val baseModel = ModelEntity(
            title = download.title,
            pageUrl = download.pageUrl,
            localFilePath = "MeshVault/$folderName/$filename",
            folderName = folderName,
            thumbnailUrl = download.imageUrl,
            thumbnailData = null
        )

        val (localImagePath, thumbnailBytes) = runCatching {
            if (download.imageUrl.isEmpty()) {
                "" to null
            } else {
                val imgFilename = "thumb_" + System.currentTimeMillis() + ".jpg"
                val vaultDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault/$folderName")
                if (!vaultDir.exists()) vaultDir.mkdirs()

                val imgFile = File(vaultDir, imgFilename)

                val url = URL(download.imageUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", download.userAgent)
                connection.connect()

                val savedBytes = connection.inputStream.use { input ->
                    val bytes = input.readBytes()
                    resizeThumbnailBytes(bytes) ?: bytes
                }

                FileOutputStream(imgFile).use { output ->
                    output.write(savedBytes)
                }

                "$folderName/$imgFilename" to savedBytes
            }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Thumbnail processing failed for ${download.url}", error)
            "" to null
        }

        val newModel = baseModel.copy(
            thumbnailUrl = if (localImagePath.isNotEmpty()) localImagePath else baseModel.thumbnailUrl,
            thumbnailData = thumbnailBytes
        )

        val destinationFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MeshVault/$folderName/$filename"
        )
        destinationFile.parentFile?.mkdirs()

        val metadataResult = runCatching { writeMetadataForModel(newModel) }
        val insertResult = runCatching { dao.insertModel(newModel) }

        withContext(Dispatchers.Main) {
            when {
                metadataResult.isFailure && insertResult.isFailure -> {
                    Toast.makeText(context, "Download saved but metadata failed", Toast.LENGTH_LONG).show()
                    Log.e(LOG_TAG, "Metadata and DB insert failed for ${download.url}", metadataResult.exceptionOrNull())
                }
                insertResult.isFailure -> {
                    Toast.makeText(context, "Download saved but library update failed", Toast.LENGTH_LONG).show()
                    Log.e(LOG_TAG, "DB insert failed for ${download.url}", insertResult.exceptionOrNull())
                }
                metadataResult.isFailure -> {
                    Toast.makeText(context, "Saved to $folderName (metadata limited)", Toast.LENGTH_LONG).show()
                    Log.w(LOG_TAG, "Metadata write failed for ${download.url}", metadataResult.exceptionOrNull())
                }
                else -> {
                    Toast.makeText(context, "Saved to $folderName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
