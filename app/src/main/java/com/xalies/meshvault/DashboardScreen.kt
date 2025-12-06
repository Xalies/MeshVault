package com.xalies.meshvault

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(
    billingHelper: BillingHelper,
    showAds: Boolean,
    onSiteSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()

    Column(modifier = Modifier.fillMaxSize()) {

        // Header Row with "Go Ad-Free"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Dashboard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Select a repository", style = MaterialTheme.typography.bodyMedium)
            }

            if (showAds) {
                Text(
                    text = "Go Ad-Free",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier
                        .clickable {
                            if (activity != null) {
                                billingHelper.launchPurchaseFlow(activity)
                            }
                        }
                        .padding(8.dp)
                )
            }
        }

        // Main Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(SUPPORTED_SITES) { site ->
                    Card(
                        modifier = Modifier
                            .height(100.dp)
                            .clickable { onSiteSelected(site.url) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = site.iconRes),
                                contentDescription = site.name,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(site.name, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (showAds) {
            BannerAd(adUnitId = "ca-app-pub-9083635854272688/1452548007")
        }
    }
}