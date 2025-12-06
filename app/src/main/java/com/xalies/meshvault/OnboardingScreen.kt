package com.xalies.meshvault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE) }
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.modelDao()

    var isResyncing by remember { mutableStateOf(false) }
    var isDriveConnected by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context) != null) }

    // --- 1. Folder Picker for "Old Library" ---
    val vaultPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }

            preferences.edit().putString("vault_tree_uri", uri.toString()).apply()

            scope.launch {
                isResyncing = true
                resyncExistingVaultContents(context, dao, forceRescan = true)
                Toast.makeText(context, "Old library scanned successfully", Toast.LENGTH_SHORT).show()
                isResyncing = false
            }
        }
    }

    // --- 2. Google Drive Sign-In Launcher ---
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
            isDriveConnected = true
            Toast.makeText(context, "Auto-Backup Enabled!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Sign-In Failed", Toast.LENGTH_SHORT).show()
        }
    }

    // --- UI CONTENT ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // App Logo
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "App Logo",
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp))
                .padding(16.dp),
            contentScale = ContentScale.Fit
        )

        Text(
            text = "Welcome to MeshVault",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Your personal organizer for 3D printing models. Browse repositories, download files, and keep everything in one secure vault.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        // --- SECTION: HOW IT WORKS ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How it works:", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                InstructionRow("1. Browse", "Use the built-in browser to find models on sites like Printables or MakerWorld.")
                InstructionRow("2. Download", "Hit download, and MeshVault will auto-organize the files and grab metadata.")
                InstructionRow("3. Manage", "View your collection in the Vault. Edit details or export to your PC via Wi-Fi.")
            }
        }

        // --- SECTION: OLD FOLDER ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Have an existing library?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "If you have a 'MeshVault' folder from a previous installation, click below to reconnect it.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Button(
                onClick = { vaultPickerLauncher.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                enabled = !isResyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isResyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning...")
                } else {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan for Old Folder")
                }
            }
        }

        // --- SECTION: CLOUD BACKUP ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Secure your files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Connect Google Drive to automatically backup your models when on Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            if (isDriveConnected) {
                OutlinedButton(
                    onClick = { /* Already connected */ },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                    colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Google Drive Connected")
                }
            } else {
                Button(
                    onClick = {
                        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                            .build()
                        val client = GoogleSignIn.getClient(context, signInOptions)
                        signInLauncher.launch(client.signInIntent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect Google Drive")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // --- GET STARTED ---
        Button(
            onClick = {
                preferences.edit().putBoolean("onboarding_completed", true).apply()
                onFinish()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Get Started", fontSize = 18.sp)
        }
    }
}

@Composable
fun InstructionRow(title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp), color = MaterialTheme.colorScheme.primary)
        Text(desc, style = MaterialTheme.typography.bodyMedium)
    }
}