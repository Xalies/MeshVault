package com.xalies.meshvault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.xalies.meshvault.ModelMetadata
import com.xalies.meshvault.readModelMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.api.services.drive.DriveScopes
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// --- 1. PRESETS ---
val FOLDER_COLORS = listOf(
    0xFF49454F, // Default Grey
    0xFFB71C1C, // Red
    0xFF0D47A1, // Blue
    0xFF1B5E20, // Green
    0xFFE65100, // Orange
    0xFF4A148C, // Purple
    0xFF006064  // Cyan
)

val FOLDER_ICONS = mapOf(
    "Folder" to Icons.Default.Folder,
    "Star" to Icons.Default.Star,
    "Home" to Icons.Default.Home,
    "Work" to Icons.Default.Work,
    "Face" to Icons.Default.Face,
    "Build" to Icons.Default.Build,
    "Shop" to Icons.Default.ShoppingCart,
    "Game" to Icons.Default.Gamepad
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onItemClick: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE) }
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.modelDao()
    val scope = rememberCoroutineScope()

    // Navigation State
    var currentFolder by remember { mutableStateOf<String?>(null) }

    // Dialog States
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var folderToEdit by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var folderDeleteCount by remember { mutableStateOf<Int?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }

    // Server States
    var isServerRunning by remember { mutableStateOf(false) }
    var serverIp by remember { mutableStateOf("") }

    // Server Instance
    val wifiServer = remember { WifiServer(dao) }
    val activity = context.findActivity()

    // Data Loading
    val allFolders by dao.getAllFolders().collectAsState(initial = emptyList())

    // --- DEFAULT FOLDERS LOGIC ---
    LaunchedEffect(Unit) {
        val defaultsInitialized = preferences.getBoolean("defaults_initialized", false)

        if (!defaultsInitialized) {
            val defaults = listOf(
                FolderEntity("Household", color = FOLDER_COLORS.random(), iconName = "Home"),
                FolderEntity("Games", color = FOLDER_COLORS.random(), iconName = "Game"),
                FolderEntity("Gadgets", color = FOLDER_COLORS.random(), iconName = "Build"),
                FolderEntity("Cosplay", color = FOLDER_COLORS.random(), iconName = "Face"),
                // Subfolders for Cosplay
                FolderEntity("Cosplay/Masks", color = FOLDER_COLORS.random(), iconName = "Face"),
                FolderEntity("Cosplay/Props", color = FOLDER_COLORS.random(), iconName = "Star")
            )

            defaults.forEach { folder ->
                dao.insertFolder(folder)
            }

            preferences.edit().putBoolean("defaults_initialized", true).apply()
        }
    }

    LaunchedEffect(Unit) {
        val resyncCompleted = preferences.getBoolean("vault_resync_completed", false)

        if (!resyncCompleted) {
            resyncExistingVaultContents(dao)
            preferences.edit().putBoolean("vault_resync_completed", true).apply()
        }
    }

    // Google Drive Sign-In Launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            // Sign-in success! Start the background worker.
            val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "DriveBackup",
                ExistingPeriodicWorkPolicy.KEEP,
                backupRequest
            )
            Toast.makeText(context, "Auto-Backup Enabled!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Sign-In Failed", Toast.LENGTH_SHORT).show()
        }
    }

    // Filter folders based on view hierarchy
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

    // Back Button Logic for Subfolders
    BackHandler(enabled = currentFolder != null) {
        if (currentFolder!!.contains("/")) {
            currentFolder = currentFolder!!.substringBeforeLast("/")
        } else {
            currentFolder = null
        }
    }

    // Cleanup Server on Exit
    DisposableEffect(Unit) {
        onDispose { if (wifiServer.isAlive) wifiServer.stop() }
    }

    // --- MAIN UI ---
    Column(modifier = Modifier.fillMaxSize()) {
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
                                    if (currentFolder!!.contains("/")) currentFolder = currentFolder!!.substringBeforeLast("/")
                                    else currentFolder = null
                                }) { Icon(Icons.Default.ArrowBack, "Back") }
                            }
                        },
                        actions = {
                            // 1. Google Drive Cloud Backup (New)
                            if (currentFolder == null) {
                                IconButton(onClick = {
                                    val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                        .requestEmail()
                                        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                                        .build()
                                    val client = GoogleSignIn.getClient(context, signInOptions)
                                    signInLauncher.launch(client.signInIntent)
                                }) {
                                    Icon(Icons.Default.CloudUpload, "Backup to Drive")
                                }
                            }

                            // 2. WiFi Button
                            IconButton(onClick = {
                                if (isServerRunning) {
                                    wifiServer.stop()
                                    isServerRunning = false
                                } else {
                                    val startCount = preferences.getInt("server_start_count", 0) + 1
                                    preferences.edit().putInt("server_start_count", startCount).apply()
                                    val shouldShowAd = startCount % 3 == 0
                                    Toast.makeText(
                                        context,
                                        if (shouldShowAd) {
                                            "Starting server now. An ad will appear after startup."
                                        } else {
                                            "Starting server now."
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    try {
                                        wifiServer.start()
                                        serverIp = getLocalIpAddress()
                                        isServerRunning = true
                                        if (shouldShowAd) {
                                            InterstitialAd.load(
                                                context,
                                                "ca-app-pub-9083635854272688/5217396568",
                                                AdRequest.Builder().build(),
                                                object : InterstitialAdLoadCallback() {
                                                    override fun onAdLoaded(ad: InterstitialAd) {
                                                        activity?.let { ad.show(it) }
                                                    }

                                                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                                                        Log.w(
                                                            "LibraryScreen",
                                                            "Server start ad failed to load: ${loadAdError.message}"
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            }) {
                                Icon(
                                    if (isServerRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
                                    "PC Export",
                                    tint = if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
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

                    // 1. Server Info Banner
                    if (isServerRunning) {
                        Card(
                            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth().zIndex(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("PC Export Active", fontWeight = FontWeight.Bold)
                                Text(serverIp, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // 2. Main List Content
                    Column(modifier = Modifier.fillMaxSize()) {
                        val topPad = if (isServerRunning) 140.dp else 0.dp

                        // A. FOLDERS GRID
                        if (visibleFolders.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp + topPad, bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                items(visibleFolders) { folder ->
                                    ColorfulFolderCard(
                                        folder = folder,
                                        onClick = { currentFolder = folder.name },
                                        onLongClick = {
                                            folderToEdit = folder
                                            showEditDialog = true
                                        },
                                        onDelete = {
                                            scope.launch {
                                                val count = dao.getModelCountInHierarchy(folder.name, "${folder.name}/%")
                                                if (count > 0) {
                                                    folderToDelete = folder.name
                                                    folderDeleteCount = count
                                                    showDeleteWarning = true
                                                } else {
                                                    deleteFolderAndContents(dao, folder.name)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // B. MODELS LIST
                        if (currentFolder != null) {
                            val models by dao.getModelsInFolder(currentFolder!!).collectAsState(initial = emptyList())

                            if (models.isNotEmpty()) {
                                Text(
                                    "Models",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(models) { model ->
                                        ModelCardWithImage(
                                            model = model,
                                            onClick = { onItemClick(model.pageUrl) },
                                            onDelete = { scope.launch { deleteModelAndFiles(dao, model) } }
                                        )
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

        // 3. BANNER AD (Fixed at bottom)
        AndroidView(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            factory = { context ->
                AdView(context).apply {
                    setAdSize(AdSize.BANNER);
                    setAdUnitId("ca-app-pub-9083635854272688/1452548007");
                    loadAd(AdRequest.Builder().build())
                }
            }
        )
    }

    // --- DIALOGS ---

    // 1. CREATE FOLDER
    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        var selectedColor by remember { mutableLongStateOf(FOLDER_COLORS.random()) }
        var selectedIcon by remember { mutableStateOf("Folder") }

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(if (currentFolder == null) "New Folder" else "New Subfolder") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Name") }
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Select Color", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FOLDER_COLORS.forEach { colorVal ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorVal))
                                    .border(if (selectedColor == colorVal) 2.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { selectedColor = colorVal }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select Icon", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyVerticalGrid(columns = GridCells.Adaptive(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(FOLDER_ICONS.keys.toList()) { iconName ->
                            val icon = FOLDER_ICONS[iconName]!!
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedIcon == iconName) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { selectedIcon = iconName },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        val finalName = if (currentFolder == null) newFolderName else "$currentFolder/$newFolderName"
                        scope.launch {
                            dao.insertFolder(FolderEntity(name = finalName, color = selectedColor, iconName = selectedIcon))
                        }
                        showCreateFolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") } }
        )
    }

    // 2. EDIT FOLDER
    if (showEditDialog && folderToEdit != null) {
        val folder = folderToEdit!!
        var selectedColor by remember { mutableLongStateOf(folder.color) } // Fixed: Use mutableLongStateOf for Color Longs
        var selectedIcon by remember { mutableStateOf(folder.iconName) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit '${folder.name.substringAfterLast("/")}'") },
            text = {
                Column {
                    Text("Select Color", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FOLDER_COLORS.forEach { colorVal ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorVal))
                                    .border(if (selectedColor == colorVal) 2.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { selectedColor = colorVal }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select Icon", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyVerticalGrid(columns = GridCells.Adaptive(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(FOLDER_ICONS.keys.toList()) { iconName ->
                            val icon = FOLDER_ICONS[iconName]!!
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedIcon == iconName) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { selectedIcon = iconName },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val updated = folder.copy(color = selectedColor, iconName = selectedIcon)
                        dao.updateFolder(updated)
                        showEditDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel") } }
        )
    }

    // 3. DELETE WARNING
    if (showDeleteWarning && folderToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteWarning = false
                folderToDelete = null
                folderDeleteCount = null
            },
            title = { Text("Delete Folder?") },
            text = {
                val countText = folderDeleteCount?.let { "$it file${if (it == 1) "" else "s"} (excluding thumbnails)" }
                    ?: "files"
                Text("This folder contains $countText. Deleting will remove them from your phone, including items in subfolders.")
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        folderToDelete?.let { deleteFolderAndContents(dao, it) }
                        showDeleteWarning = false
                        folderDeleteCount = null
                        folderToDelete = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteWarning = false
                    folderToDelete = null
                    folderDeleteCount = null
                }) { Text("Cancel") }
            }
        )
    }
}

private suspend fun resyncExistingVaultContents(dao: ModelDao) {
    withContext(Dispatchers.IO) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vaultRoot = File(downloadsDir, "MeshVault")

        if (!vaultRoot.exists() || !vaultRoot.isDirectory) return@withContext

        val metadataByPath = mutableMapOf<String, ModelMetadata>()

        vaultRoot.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".meta.json")) {
                val relativePath = file.relativeTo(vaultRoot).path
                    .removeSuffix(".meta.json")
                    .replace(File.separatorChar, '/')

                readModelMetadata(file)?.let { metadata ->
                    metadataByPath[relativePath] = metadata
                }
            }
        }

        vaultRoot.walkTopDown().forEach { file ->
            if (file == vaultRoot) return@forEach

            val relativePath = file.relativeTo(vaultRoot).path.replace(File.separatorChar, '/')

            if (file.isDirectory) {
                if (dao.getFolderCount(relativePath) == 0) {
                    dao.insertFolder(
                        FolderEntity(
                            name = relativePath,
                            color = FOLDER_COLORS.random(),
                            iconName = "Folder"
                        )
                    )
                }
            } else {
                if (file.name.startsWith("thumb_", ignoreCase = true) || file.name.endsWith(".meta.json")) return@forEach

                val folderName = file.parentFile?.relativeTo(vaultRoot)?.path?.replace(File.separatorChar, '/') ?: ""
                val localPath = "MeshVault/${file.relativeTo(downloadsDir).path.replace(File.separatorChar, '/')}"

                val metadata = metadataByPath[relativePath]

                if (dao.getModelCountByLocalPath(localPath) == 0) {
                    val restoredTitle = metadata?.title?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension.ifBlank { file.name }

                    val restoredModel = ModelEntity(
                        title = restoredTitle,
                        pageUrl = metadata?.pageUrl?.takeIf { it.isNotBlank() } ?: file.toURI().toString(),
                        localFilePath = localPath,
                        folderName = folderName,
                        thumbnailUrl = metadata?.thumbnailPath
                    )

                    dao.insertModel(restoredModel)
                }
            }
        }
    }
}

private suspend fun deleteFolderAndContents(dao: ModelDao, folderName: String) {
    val folderPrefix = "$folderName/%"

    withContext(Dispatchers.IO) {
        val models = dao.getModelsInHierarchy(folderName, folderPrefix)

        models.forEach { model ->
            deleteFileIfExists(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), model.localFilePath))

            if (!model.thumbnailUrl.isNullOrBlank() && model.thumbnailUrl?.startsWith("http") == false) {
                deleteFileIfExists(
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MeshVault/${model.thumbnailUrl}"
                    )
                )
            }
        }

        dao.deleteModelsInHierarchy(folderName, folderPrefix)
        dao.deleteFolderHierarchy(folderName, folderPrefix)

        val folderDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MeshVault/$folderName"
        )
        if (folderDir.exists()) {
            folderDir.deleteRecursively()
        }
    }
}

private suspend fun deleteModelAndFiles(dao: ModelDao, model: ModelEntity) {
    withContext(Dispatchers.IO) {
        deleteFileIfExists(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), model.localFilePath))

        if (!model.thumbnailUrl.isNullOrBlank() && model.thumbnailUrl?.startsWith("http") == false) {
            deleteFileIfExists(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MeshVault/${model.thumbnailUrl}"
                )
            )
        }

        dao.deleteModel(model.id)
    }
}

private fun deleteFileIfExists(file: File) {
    if (file.exists()) {
        file.delete()
    }
}

// --- COMPONENT: COLORFUL FOLDER CARD ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorfulFolderCard(
    folder: FolderEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val displayName = folder.name.substringAfterLast("/")
    val icon = FOLDER_ICONS[folder.iconName] ?: Icons.Default.Folder

    // Ensure we convert the Long color back to a Color object
    // If color is 0 (default/legacy), use Grey
    val cardColor = if (folder.color == 0L) Color(0xFF49454F) else Color(folder.color)
    val contentColor = Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            contentColor = contentColor
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(4.dp))
                Text(displayName, fontWeight = FontWeight.Bold)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.Delete, null, tint = contentColor.copy(alpha = 0.7f))
            }
        }
    }
}

// --- COMPONENT: MODEL CARD WITH IMAGE ---
@Composable
fun ModelCardWithImage(model: ModelEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    // Resolve Image Path: Check if it's a URL or a Local File
    val imagePath = if (model.thumbnailUrl?.startsWith("http") == true) {
        model.thumbnailUrl
    } else if (!model.thumbnailUrl.isNullOrEmpty()) {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeshVault/${model.thumbnailUrl}")
    } else { null }

    Card(
        modifier = Modifier.fillMaxWidth().height(100.dp).clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (imagePath != null) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = null,
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

private fun Context.findActivity(): Activity? {
    var currentContext: Context = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}