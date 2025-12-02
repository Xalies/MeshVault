package com.xalies.meshvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex // Crucial Import
import androidx.compose.ui.viewinterop.AndroidView // Needed for Ads
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onItemClick: (String) -> Unit = {}) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.modelDao()
    val scope = rememberCoroutineScope()

    var currentFolder by remember { mutableStateOf<String?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }

    // Server States
    var isServerRunning by remember { mutableStateOf(false) }
    var serverIp by remember { mutableStateOf("") }

    // Pass DAO to Server
    val wifiServer = remember { WifiServer(dao) }

    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }

    // Load all folders
    val allFolders by dao.getAllFolders().collectAsState(initial = emptyList())

    // Filter folders based on view (Root vs Subfolder)
    val visibleFolders = remember(allFolders, currentFolder) {
        if (currentFolder == null) {
            // Root view: Show folders that have NO slashes
            allFolders.filter { !it.name.contains("/") }
        } else {
            // Inside folder: Show folders that start with "currentFolder/" but no extra slashes
            val prefix = "$currentFolder/"
            allFolders.filter { it.name.startsWith(prefix) && !it.name.substringAfter(prefix).contains("/") }
        }
    }

    // Custom back logic for subfolders
    BackHandler(enabled = currentFolder != null) {
        if (currentFolder!!.contains("/")) {
            // Go up one level
            currentFolder = currentFolder!!.substringBeforeLast("/")
        } else {
            // Go to root
            currentFolder = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (wifiServer.isAlive) {
                wifiServer.stop()
            }
        }
    }

    // --- MAIN LAYOUT STRUCTURE ---
    Column(modifier = Modifier.fillMaxSize()) {

        // 1. CONTENT AREA (Takes all available space)
        Box(modifier = Modifier.weight(1f)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(if (currentFolder == null) "My Vault" else currentFolder!!.substringAfterLast("/"))
                        },
                        navigationIcon = {
                            if (currentFolder != null) {
                                IconButton(onClick = {
                                    if (currentFolder!!.contains("/")) {
                                        currentFolder = currentFolder!!.substringBeforeLast("/")
                                    } else {
                                        currentFolder = null
                                    }
                                }) {
                                    Icon(Icons.Default.ArrowBack, "Back")
                                }
                            }
                        },
                        actions = {
                            // Only show Wifi Toggle at Root Level
                            if (currentFolder == null) {
                                IconButton(onClick = {
                                    if (isServerRunning) {
                                        wifiServer.stop()
                                        isServerRunning = false
                                    } else {
                                        try {
                                            wifiServer.start()
                                            serverIp = getLocalIpAddress()
                                            isServerRunning = true
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }) {
                                    Icon(
                                        if (isServerRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
                                        contentDescription = "Export to PC",
                                        tint = if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(onClick = { showCreateFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, "New Folder")
                    }
                }
            ) { innerPadding ->

                Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

                    // Server Banner
                    if (isServerRunning && currentFolder == null) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                                .fillMaxWidth()
                                .zIndex(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("PC Export Active", fontWeight = FontWeight.Bold)
                                Text(serverIp, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // Content
                    Column(modifier = Modifier.fillMaxSize()) {
                        val topPad = if (isServerRunning && currentFolder == null) 140.dp else 0.dp

                        // A. Folders
                        if (visibleFolders.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp + topPad, bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                items(visibleFolders) { folder ->
                                    val displayName = folder.name.substringAfterLast("/")
                                    FolderCard(
                                        name = displayName,
                                        onClick = { currentFolder = folder.name },
                                        onDelete = {
                                            scope.launch {
                                                val count = dao.getModelCount(folder.name)
                                                if (count > 0) {
                                                    folderToDelete = folder.name
                                                    showDeleteWarning = true
                                                } else {
                                                    dao.deleteFolder(folder.name)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // B. Models (Only if inside a folder)
                        if (currentFolder != null) {
                            val models by dao.getModelsInFolder(currentFolder!!).collectAsState(initial = emptyList())

                            if (models.isNotEmpty()) {
                                Text(
                                    "Models",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(models) { model ->
                                        ModelCardWithImage(model = model, onDelete = { scope.launch { dao.deleteModel(model.id) } })
                                    }
                                }
                            } else if (visibleFolders.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Empty Folder", color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. BANNER AD (Fixed at the bottom)
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            factory = { context ->
                AdView(context).apply {
                    setAdSize(AdSize.BANNER)
                    setAdUnitId("ca-app-pub-9083635854272688/1452548007")
                    loadAd(AdRequest.Builder().build())
                }
            }
        )
    }

    // --- DIALOGS ---

    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(if (currentFolder == null) "New Folder" else "New Subfolder") },
            text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        val finalName = if (currentFolder == null) newFolderName else "$currentFolder/$newFolderName"
                        scope.launch { dao.insertFolder(FolderEntity(finalName)) }
                        showCreateFolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteWarning && folderToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteWarning = false },
            title = { Text("Delete Folder?") },
            text = { Text("The folder '$folderToDelete' contains items. Deleting it will remove all contents.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        folderToDelete?.let { dao.deleteModelsInFolder(it); dao.deleteFolder(it) }
                        showDeleteWarning = false
                        folderToDelete = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteWarning = false }) { Text("Cancel") } }
        )
    }
}

// --- CARD COMPONENTS ---

@Composable
fun FolderCard(name: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(120.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Folder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(name, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun ModelCardWithImage(model: ModelEntity, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().height(100.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (!model.thumbnailUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = model.thumbnailUrl, contentDescription = null,
                    modifier = Modifier.width(100.dp).fillMaxHeight().background(Color.Gray),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.width(100.dp).fillMaxHeight().background(Color.LightGray)) {
                    Text("No Img", modifier = Modifier.align(Alignment.Center))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(12.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(model.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(model.localFilePath.substringAfterLast("/"), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterVertically)) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}