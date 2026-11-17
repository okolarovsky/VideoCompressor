package com.example.videocompressor.media

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun GalleryScreen(viewModel: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // One launcher for every permission request (initial grant and "Upravit výběr").
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refresh(MediaPermissions.accessLevel(context))
    }

    fun requestOrOpenSettings() {
        val activity = context as? Activity
        if (activity != null && MediaPermissions.isPermanentlyDenied(activity)) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        } else {
            MediaPermissions.markRequested(context)
            permissionLauncher.launch(MediaPermissions.requiredPermissions())
        }
    }

    // Re-check after returning from Settings (or any other screen) and load the gallery if access is granted.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(MediaPermissions.accessLevel(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Initial load for a process that already has access.
    LaunchedEffect(Unit) {
        viewModel.refresh(MediaPermissions.accessLevel(context))
    }

    when (val s = state) {
        is GalleryUiState.Initial -> InitialContent(onGrant = { requestOrOpenSettings() })

        is GalleryUiState.LimitedAccess -> MediaGrid(
            items = s.items,
            header = "Vybrané fotky a videa",
            actionText = "Upravit výběr fotek",
            onAction = { permissionLauncher.launch(MediaPermissions.requiredPermissions()) }
        )

        is GalleryUiState.FullAccess -> MediaGrid(
            items = s.items,
            header = "Celá galerie",
            actionText = null,
            onAction = {}
        )
    }
}

@Composable
private fun InitialContent(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Pro zobrazení galerie potřebuje aplikace přístup k vašim fotkám a videím.",
            textAlign = TextAlign.Center
        )
        Button(onClick = onGrant, modifier = Modifier.padding(top = 16.dp)) {
            Text("Povolit přístup ke galerii")
        }
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    header: String,
    actionText: String?,
    onAction: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text(text = "$header (${items.size})", modifier = Modifier.padding(8.dp))
        if (actionText != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(actionText)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(items, key = { it.uri.toString() }) { item ->
                Column {
                    Text(text = item.displayName, maxLines = 2)
                    Text(text = if (item.type == MediaType.VIDEO) "Video" else "Fotka")
                }
            }
        }
    }
}
