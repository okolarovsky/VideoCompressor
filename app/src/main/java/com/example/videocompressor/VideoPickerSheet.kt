package com.example.videocompressor

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.videocompressor.media.AccessLevel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SheetBackground = Color(0xFFFBF8F2)
private val SheetCard = Color(0xFFEFE8DD)
private val SheetText = Color(0xFF191816)
private val SheetGold = Color(0xFFC0A678)
private val SheetThumbPlaceholder = Color(0xFF8C877E)
private val SheetDisabled = Color(0xFFA09A92)

/** Space reserved at the bottom of the grid so the last row stays clear of the sticky button. */
private val BottomBarHeight = 88.dp

/**
 * In-app video picker. Shows only what MediaStore returns for the current access level.
 * The confirm button is shown only when the sheet is fully expanded and stays pinned to the bottom edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPickerSheet(
    viewModel: VideoCompressorViewModel,
    accessLevel: AccessLevel,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val videos by viewModel.galleryVideos.collectAsState()
    val selection by viewModel.pickerSelection.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isExpanded = sheetState.currentValue == SheetValue.Expanded &&
        sheetState.targetValue == SheetValue.Expanded

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBackground
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Vyberte videa",
                    color = SheetText,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )

                if (accessLevel == AccessLevel.LIMITED) {
                    LimitedAccessCard(onManage = onManage)
                }

                if (videos.isEmpty()) {
                    Text("Žádná dostupná videa.", color = SheetText)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                        contentPadding = PaddingValues(bottom = BottomBarHeight),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(videos, key = { it.uri.toString() }) { video ->
                            VideoTile(
                                uri = video.uri,
                                name = video.displayName,
                                duration = video.durationLabel,
                                checked = video.uri in selection,
                                onToggle = { viewModel.togglePickerSelection(video.uri) }
                            )
                        }
                    }
                }
            }

            StickyConfirmButton(
                visible = isExpanded,
                count = selection.size,
                onConfirm = {
                    viewModel.confirmGallerySelection()
                    onDismiss()
                }
            )
        }
    }
}

/**
 * Confirm button pinned to the bottom edge, above the navigation bar. Appears only when [visible]
 * (sheet fully expanded) with a fade and slide-in; disabled while nothing is selected.
 */
@Composable
private fun BoxScope.StickyConfirmButton(visible: Boolean, count: Int, onConfirm: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Button(
                onClick = onConfirm,
                enabled = count > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheetText,
                    contentColor = Color.White,
                    disabledContainerColor = SheetDisabled,
                    disabledContentColor = Color.White
                )
            ) {
                Text("Potvrdit výběr ($count)", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LimitedAccessCard(onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SheetCard)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Zobrazena pouze povolená videa", color = SheetText, modifier = Modifier.weight(1f))
        TextButton(onClick = onManage) {
            Text("Spravovat", color = SheetText, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VideoTile(
    uri: Uri,
    name: String,
    duration: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, uri) }
    }

    Column(modifier = Modifier.clickable(onClick = onToggle)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SheetThumbPlaceholder)
        ) {
            thumb?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = duration,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (checked) SheetGold else Color.White.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = SheetText, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(text = name, color = SheetText, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Preview frame for a MediaStore video. Uses the content resolver on API 29+, the file path below that. */
private fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(uri, Size(320, 240), null)
    } else {
        @Suppress("DEPRECATION")
        ThumbnailUtils.createVideoThumbnail(File(uri.path ?: ""), Size(320, 240), null)
    }
} catch (e: Exception) {
    null
}
