package com.xalies.meshvault

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.WebView
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.util.Locale
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailScreen(
    modelId: Int,
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getDatabase(context).modelDao() }
    val scope = rememberCoroutineScope()

    var showEditDialog by remember { mutableStateOf(false) }
    var editedTitle by remember { mutableStateOf("") }
    var editedUrl by remember { mutableStateOf("") }

    var pendingThumbnailModel by remember { mutableStateOf<ModelEntity?>(null) }

    val thumbnailPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val model = pendingThumbnailModel
        if (uri != null && model != null) {
            scope.launch {
                val updated = updateModelThumbnailFromUri(context, dao, model, uri)
                withContext(Dispatchers.Main) {
                    if (updated != null) {
                        Toast.makeText(context, "Thumbnail updated", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Unable to update thumbnail", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        pendingThumbnailModel = null
    }

    val modelState by remember(modelId) {
        dao.getModelById(modelId)
            .map<ModelEntity?, ModelDetailState> { entity ->
                entity?.let { ModelDetailState.Loaded(it) } ?: ModelDetailState.Missing
            }
            .onStart { emit(ModelDetailState.Loading) }
    }.collectAsState(initial = ModelDetailState.Loading)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (val state = modelState) {
                        is ModelDetailState.Loaded -> state.model.title
                        else -> "Model Details"
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = modelState) {
            ModelDetailState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            ModelDetailState.Missing -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Model not found", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is ModelDetailState.Loaded -> {
                val model = state.model
                val scrollState = rememberScrollState()
                val modelFile = remember(model.localFilePath) {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), model.localFilePath)
                }
                val modelExt = remember(modelFile.path) { modelFile.extension.lowercase(Locale.getDefault()) }
                val canPreview3d = remember(modelExt) { modelExt in setOf("stl", "obj") }
                var showViewer by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ModelThumbnail(
                        model = model,
                        canPreview3d = canPreview3d,
                        onPreviewRequested = {
                            if (canPreview3d && modelFile.exists()) {
                                showViewer = true
                            } else if (canPreview3d) {
                                Toast.makeText(context, "Model file not found for preview", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    Text(model.title, style = MaterialTheme.typography.headlineSmall)

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailRow(
                            icon = Icons.Default.Folder,
                            label = "Folder",
                            value = model.folderName.substringAfterLast("/")
                        )
                        DetailRow(
                            icon = Icons.Default.InsertDriveFile,
                            label = "File name",
                            value = model.localFilePath.substringAfterLast("/")
                        )
                        DetailRow(
                            icon = Icons.Default.Public,
                            label = "Source page",
                            value = model.pageUrl,
                            onClick = { if (model.pageUrl.isNotBlank()) onOpenInBrowser(model.pageUrl) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                editedTitle = model.title
                                editedUrl = model.pageUrl
                                showEditDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Edit details")
                        }

                        OutlinedButton(
                            onClick = {
                                pendingThumbnailModel = model
                                thumbnailPickerLauncher.launch("image/*")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Change thumbnail")
                        }
                    }

                    FilledTonalButton(
                        onClick = { onOpenInBrowser(model.pageUrl) },
                        enabled = model.pageUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open source page")
                    }

                    if (canPreview3d) {
                        Text(
                            text = "Tap the image to preview the 3D model (STL / OBJ).",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (showViewer && canPreview3d) {
                    Model3DViewerDialog(
                        file = modelFile,
                        extension = modelExt,
                        onDismiss = { showViewer = false }
                    )
                }
            }
        }
    }

    // Edit dialog (only when a model is loaded)
    if (showEditDialog) {
        val loadedModel = (modelState as? ModelDetailState.Loaded)?.model
        if (loadedModel != null) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text("Edit Model Details") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editedTitle,
                            onValueChange = { editedTitle = it },
                            label = { Text("Title") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = editedUrl,
                            onValueChange = { editedUrl = it },
                            label = { Text("Source URL") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val updated = loadedModel.copy(title = editedTitle, pageUrl = editedUrl)
                                dao.updateModel(updated)
                                withContext(Dispatchers.IO) { writeMetadataForModel(updated) }
                                showEditDialog = false
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel") } }
            )
        } else {
            showEditDialog = false
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Model3DViewerDialog(file: File, extension: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.allowFileAccessFromFileURLs = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                        }
                    },
                    update = { webView ->
                        val parentDir = file.parentFile?.let { "file://${it.absolutePath}/" } ?: "file:///"
                        val modelName = file.name
                        val html = buildString {
                            append(
                                """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                  <style>
                                    body, html { margin:0; padding:0; overflow:hidden; background:#111; }
                                    #container { width:100vw; height:100vh; }
                                  </style>
                                </head>
                                <body>
                                  <div id="container"></div>
                                  <script type="module">
                                    import * as THREE from 'https://unpkg.com/three@0.152.2/build/three.module.js';
                                    import { OrbitControls } from 'https://unpkg.com/three@0.152.2/examples/jsm/controls/OrbitControls.js';
                                    import { STLLoader } from 'https://unpkg.com/three@0.152.2/examples/jsm/loaders/STLLoader.js';
                                    import { OBJLoader } from 'https://unpkg.com/three@0.152.2/examples/jsm/loaders/OBJLoader.js';

                                    const scene = new THREE.Scene();
                                    scene.background = new THREE.Color(0x111111);
                                    const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 1000);
                                    camera.position.set(0, 0.5, 1.5);

                                    const renderer = new THREE.WebGLRenderer({ antialias: true });
                                    renderer.setSize(window.innerWidth, window.innerHeight);
                                    document.getElementById('container').appendChild(renderer.domElement);

                                    const hemi = new THREE.HemisphereLight(0xffffff, 0x444444, 1.2);
                                    scene.add(hemi);
                                    const dir = new THREE.DirectionalLight(0xffffff, 0.8);
                                    dir.position.set(5, 10, 7.5);
                                    scene.add(dir);

                                    const controls = new OrbitControls(camera, renderer.domElement);
                                    controls.enableDamping = true;

                                    const loaderType = "${extension.lowercase(Locale.getDefault())}";
                                    const modelUrl = new URL("${modelName}", window.location.href).toString();

                                    function fitCameraToObject(object) {
                                      const box = new THREE.Box3().setFromObject(object);
                                      const size = box.getSize(new THREE.Vector3());
                                      const center = box.getCenter(new THREE.Vector3());
                                      const maxDim = Math.max(size.x, size.y, size.z);
                                      const fitHeightDistance = maxDim / (2 * Math.atan(Math.PI * camera.fov / 360));
                                      const fitWidthDistance = fitHeightDistance / camera.aspect;
                                      const distance = Math.max(fitHeightDistance, fitWidthDistance);
                                      const direction = controls.target.clone()
                                        .sub(camera.position)
                                        .normalize()
                                        .multiplyScalar(distance);
                                      controls.target.copy(center);
                                      camera.position.copy(center).sub(direction);
                                      camera.near = distance / 100;
                                      camera.far = distance * 100;
                                      camera.updateProjectionMatrix();
                                      controls.update();
                                    }

                                    function onLoad(object) {
                                      scene.add(object);
                                      fitCameraToObject(object);
                                      animate();
                                    }

                                    if (loaderType === "stl") {
                                      const loader = new STLLoader();
                                      loader.load(modelUrl, geometry => {
                                        const material = new THREE.MeshStandardMaterial({ color: 0xcccccc, metalness: 0.1, roughness: 0.6 });
                                        const mesh = new THREE.Mesh(geometry, material);
                                        mesh.castShadow = true;
                                        mesh.receiveShadow = true;
                                        onLoad(mesh);
                                      });
                                    } else {
                                      const loader = new OBJLoader();
                                      loader.load(modelUrl, obj => {
                                        obj.traverse(child => {
                                          if (child.isMesh) {
                                            child.material = new THREE.MeshStandardMaterial({ color: 0xcccccc, metalness: 0.1, roughness: 0.6 });
                                          }
                                        });
                                        onLoad(obj);
                                      });
                                    }

                                    window.addEventListener('resize', () => {
                                      camera.aspect = window.innerWidth / window.innerHeight;
                                      camera.updateProjectionMatrix();
                                      renderer.setSize(window.innerWidth, window.innerHeight);
                                    });

                                    function animate() {
                                      requestAnimationFrame(animate);
                                      controls.update();
                                      renderer.render(scene, camera);
                                    }
                                  </script>
                                </body>
                                </html>
                                """.trimIndent()
                            )
                        }
                        webView.loadDataWithBaseURL(parentDir, html, "text/html", "utf-8", null)
                    }
                )
            }
        }
    )
}
@Composable
private fun ModelThumbnail(model: ModelEntity, canPreview3d: Boolean, onPreviewRequested: () -> Unit) {
    val imageBitmap = remember(model.thumbnailData) {
        model.thumbnailData?.let { data ->
            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
        }
    }

    val imagePath = remember(model.thumbnailUrl, imageBitmap) {
        if (imageBitmap == null) {
            when {
                model.thumbnailUrl?.startsWith("http") == true -> model.thumbnailUrl
                !model.thumbnailUrl.isNullOrEmpty() -> File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MeshVault/${model.thumbnailUrl}"
                )
                else -> null
            }
        } else null
    }

    val clickableModifier = if (canPreview3d) Modifier.clickable { onPreviewRequested() } else Modifier

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .then(clickableModifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        when {
            imageBitmap != null -> {
                androidx.compose.foundation.Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            imagePath != null -> {
                AsyncImage(
                    model = imagePath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No thumbnail", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    val textColor = if (onClick == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (onClick != null) base.clickable(onClick = onClick) else base
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = textColor)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = textColor.copy(alpha = 0.8f))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private sealed interface ModelDetailState {
    object Loading : ModelDetailState
    object Missing : ModelDetailState
    data class Loaded(val model: ModelEntity) : ModelDetailState
}
