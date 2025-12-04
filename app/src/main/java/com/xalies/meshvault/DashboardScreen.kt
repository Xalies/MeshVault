package com.xalies.meshvault

import androidx.compose.foundation.Image // <--- NEW IMPORT
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource // <--- NEW IMPORT
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(onSiteSelected: (String) -> Unit) {
    // 1. Root Column to hold Content + Ad
    Column(modifier = Modifier.fillMaxSize()) {

        // 2. Main Content (Weighted)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp)
        ) {
            Text("Dashboard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Select a repository to start browsing", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(24.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(SUPPORTED_SITES) { site ->
                    Card(
                        modifier = Modifier.height(100.dp).clickable { onSiteSelected(site.url) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // REPLACED Icon WITH Image
                            Image(
                                painter = painterResource(id = site.iconRes),
                                contentDescription = site.name,
                                modifier = Modifier.size(48.dp) // Adjusted size for PNG logos
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(site.name, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. The Ad at the bottom
        BannerAd(adUnitId = "ca-app-pub-9083635854272688/1452548007")
    }
}