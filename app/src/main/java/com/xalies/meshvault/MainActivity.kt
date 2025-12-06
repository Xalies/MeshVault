package com.xalies.meshvault

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xalies.meshvault.ui.theme.MeshVaultTheme
import com.google.android.gms.ads.MobileAds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        // Initialize AdMob
        MobileAds.initialize(this) {}
        // Disable all ad audio
        MobileAds.setAppMuted(true)
        MobileAds.setAppVolume(0f)

        setContent {
            MeshVaultTheme {
                MainApp()
            }
        }
    }
}

private fun WebView.muteAudioIfAvailable() {
    // Reflection keeps compilation safe on older API levels while still muting audio
    // when the method becomes available via updated WebView binaries.
    runCatching {
        val method = WebView::class.java.getMethod("setAudioMuted", Boolean::class.javaPrimitiveType)
        method.invoke(this, true)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainApp() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getDatabase(context).modelDao() }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var currentBrowserUrl by remember { mutableStateOf("https://www.printables.com") }

    LaunchedEffect(Unit) {
        resyncExistingVaultContents(context, dao)
    }

    // --- FIX 1 & 2: CREATE PERSISTENT WEBVIEW ---
    // We create the WebView once here. It survives tab switching.
    val sharedWebView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Prevent any click sounds from the WebView itself
            isSoundEffectsEnabled = false

            // Mute all media from the embedded browser when the API supports it
            muteAudioIfAvailable()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                // Block autoplaying media to keep audio silent on older API levels
                mediaPlaybackRequiresUserGesture = true

                // FIX FOR GOOGLE SIGN-IN:
                // We remove "; wv" from the user agent. This makes Google think
                // we are a real Chrome browser, not an embedded WebView.
                userAgentString = userAgentString.replace("; wv", "")
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, "Dashboard") },
                    label = { Text("Dashboard") },
                    selected = currentRoute == "dashboard",
                    onClick = {
                        navController.navigate("dashboard") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Public, "Browser") },
                    label = { Text("Browser") },
                    selected = currentRoute == "browser",
                    onClick = {
                        navController.navigate("browser") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, "Vault") },
                    label = { Text("Vault") },
                    selected = currentRoute == "library",
                    onClick = {
                        navController.navigate("library") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    onSiteSelected = { url ->
                        currentBrowserUrl = url
                        // We must explicitly load the URL here because the WebView is reused
                        sharedWebView.loadUrl(url)
                        navController.navigate("browser") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable("browser") {
                // We pass the EXISTING WebView instance
                BrowserScreen(webView = sharedWebView)
            }

            composable("library") {
                LibraryScreen(
                    onItemClick = { url ->
                        // 1. Update the shared URL state
                        currentBrowserUrl = url

                        // 2. Force the persistent WebView to load it immediately
                        sharedWebView.loadUrl(url)

                        // 3. Switch tabs to the Browser
                        navController.navigate("browser") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }
}