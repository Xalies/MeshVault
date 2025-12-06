package com.xalies.meshvault

import android.annotation.SuppressLint
import android.content.Context
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

    // Check Onboarding State
    val preferences = remember { context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE) }
    val onboardingCompleted = remember { preferences.getBoolean("onboarding_completed", false) }
    val startDestination = if (onboardingCompleted) "dashboard" else "onboarding"

    var currentBrowserUrl by remember { mutableStateOf("https://www.printables.com") }

    LaunchedEffect(Unit) {
        if (onboardingCompleted) {
            resyncExistingVaultContents(context, dao)
        }
    }

    // --- PERSISTENT WEBVIEW ---
    val sharedWebView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isSoundEffectsEnabled = false
            muteAudioIfAvailable()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                userAgentString = userAgentString.replace("; wv", "")
            }
        }
    }

    Scaffold(
        bottomBar = {
            // Hide BottomBar on Onboarding Screen
            if (currentRoute != "onboarding") {
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
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("onboarding") {
                OnboardingScreen(
                    onFinish = {
                        navController.navigate("dashboard") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                )
            }

            composable("dashboard") {
                DashboardScreen(
                    onSiteSelected = { url ->
                        currentBrowserUrl = url
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
                BrowserScreen(webView = sharedWebView)
            }

            composable("library") {
                LibraryScreen(
                    onItemClick = { url ->
                        currentBrowserUrl = url
                        sharedWebView.loadUrl(url)
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