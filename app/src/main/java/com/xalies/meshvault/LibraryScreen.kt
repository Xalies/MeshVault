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
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

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

    // Using the new "No-Dependency" WifiServer
    val wifiServer = remember { WifiServer() }

    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }

    val folders by dao.getAllFolders().collectAsState(initial = emptyList())

    BackHandler(enabled = currentFolder != null) {
        currentFolder = null
    }

    // Stop server when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            if (wifiServer.isAlive) {
                wifiServer.stop()
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentFolder == null) {
                TopAppBar(
                    title = { Text("My Vault") },
                    actions = {
                        IconButton(onClick = {
                            if (isServerRunning) {
                                wifiServer.stop()
                                isServerRunning = false
                            } else {
                                try {
                                    wifiServer.start()
                                    serverIp = getLocalIpAddress() // Uses NetworkUtils.kt
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
                )
            }
        },
        floatingActionButton = {
            if (currentFolder == null) {
                FloatingActionButton(onClick = { showCreateFolderDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, "New Folder")
                }
            }
        }
    ) { innerPadding ->

        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            // Server Banner
            if (isServerRunning) {
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("PC Export Active", fontWeight = FontWeight.Bold)
                        Text(serverIp, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (currentFolder == null) {
                val topPad = if (isServerRunning) 140.dp else 16.dp
                Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = topPad)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(folders) { folder ->
                            FolderCard(
                                name = folder.name,
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
            } else {
                val models by dao.getModelsInFolder(currentFolder!!).collectAsState(initial = emptyList())
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(currentFolder!!) },
                        navigationIcon = {
                            IconButton(onClick = { currentFolder = null }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        }
                    )
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(models) { model ->
                            ModelCardWithImage(model = model, onDelete = { scope.launch { dao.deleteModel(model.id) } })
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("New Folder") },
            text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("Folder Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        scope.launch { dao.insertFolder(FolderEntity(newFolderName)) }
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
            text = { Text("The folder '$folderToDelete' contains models. Deleting it will remove all models inside.") },
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

// --- CARD COMPONENTS (These must be here!) ---

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