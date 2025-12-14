package com.xalies.meshvault

import android.annotation.SuppressLint
import android.os.Bundle
import android.content.Context // Added Context import
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xalies.meshvault.ui.theme.MeshVaultTheme
import com.google.android.gms.ads.MobileAds

class MainActivity : ComponentActivity() {
    private lateinit var billingHelper: BillingHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        // Initialize AdMob
        MobileAds.initialize(this) {}
        MobileAds.setAppMuted(true)
        MobileAds.setAppVolume(0f)

        // Initialize Billing
        billingHelper = BillingHelper(this)
        billingHelper.startConnection()

        setContent {
            MeshVaultTheme {
                MainApp(billingHelper)
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
fun MainApp(billingHelper: BillingHelper) {
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

    // Real ad-free status from the billing helper
    val isAdFreeFromPurchase by billingHelper.isAdFree.collectAsState()

    // Correct Logic: Show ads only if the version is 1.0+ AND the user has NOT paid.
    val showAds = AdManager.isVersionReviewMode() && !isAdFreeFromPurchase


    LaunchedEffect(Unit) {
        if (onboardingCompleted) {
            resyncExistingVaultContents(context, dao)
        }
    }

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
                    billingHelper = billingHelper,
                    onFinish = {
                        navController.navigate("dashboard") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                )
            }

            composable("dashboard") {
                DashboardScreen(
                    billingHelper = billingHelper,
                    showAds = showAds,
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
                    showAds = showAds,
                    onModelClick = { modelId ->
                        navController.navigate("modelDetails/$modelId")
                    }
                )
            }

            composable(
                route = "modelDetails/{modelId}",
                arguments = listOf(navArgument("modelId") { type = NavType.IntType })
            ) { backStackEntry ->
                val modelId = backStackEntry.arguments?.getInt("modelId") ?: return@composable

                ModelDetailScreen(
                    modelId = modelId,
                    onBack = { navController.popBackStack() },
                    onOpenInBrowser = { url ->
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
        }
    }
}
