package com.example.videocompressor

import android.app.Activity
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.videocompressor.media.AccessLevel
import com.example.videocompressor.media.MediaPermissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val ScreenBackground = Color(0xFFFBF8F2)
private val CardBackground = Color(0xFFEFE8DD)
private val TextDark = Color(0xFF191816)
private val ButtonLight = Color(0xFFF8F4EC)
private val SelectedGold = Color(0xFFC0A678)
private val OutlineColor = Color(0x33191816)
private val DisabledGray = Color(0xFFA09A92)
private val SwitchCheckedTrack = Color(0xFFC0A678)
private val SwitchUncheckedTrack = Color(0xFFD9D0C1)
private val SwitchDisabledUncheckedTrack = Color(0xFFE6DFD3)
private val SwitchDisabledUncheckedThumb = Color(0xFFF3EEE5)
private val PreviewGray = Color(0xFF5E5A54)
private val ScrollbarColor = Color(0x66191816)
private val DialogBackground = Color(0xFFFBF8F2)
private val DialogBodyText = Color(0xCC191816)
private val DialogCancelText = Color(0xFF605D5B)
private val CardShape = RoundedCornerShape(28.dp)
private val ButtonHeight = 56.dp
private val ContentBottomPadding = 24.dp

private data class QuickOption(val targetMb: Int, val label: String)

private val quickOptions = listOf(
    QuickOption(50, "Maximální kvalita (do 50 MB)"),
    QuickOption(25, "Běžná velikost (do 25 MB)"),
    QuickOption(10, "Úsporný režim (do 10 MB)")
)

private const val HDR_ERROR_MESSAGE =
    "Hardware zařízení nepodporuje přímé zpracování tohoto HDR formátu. Zkuste video s běžným barevným rozsahem (SDR)."

private fun friendlyErrorMessage(raw: String): String =
    if (raw.contains("GL_EXT_YUV_target", ignoreCase = true) || raw.contains("shader", ignoreCase = true)) {
        HDR_ERROR_MESSAGE
    } else {
        raw
    }

private fun czechVideoCount(count: Int): String = when {
    count == 1 -> "1 video"
    count in 2..4 -> "$count videa"
    else -> "$count videí"
}

private fun selectionSummary(count: Int): String = when (count) {
    in 2..4 -> "Vybrána $count videa"
    else -> "Vybráno $count videí"
}

@Composable
fun CompressorScreen(viewModel: VideoCompressorViewModel = viewModel()) {
    val context = LocalContext.current

    val uiState by viewModel.uiState.collectAsState()
    val selected by viewModel.selectedVideos.collectAsState()
    val keepAudio by viewModel.keepAudio.collectAsState()
    val targetMb by viewModel.targetSizeMb.collectAsState()

    val scrollState = rememberScrollState()

    var showRationale by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showSettingsRedirect by remember { mutableStateOf(false) }

    // Systémový dialog oprávnění (Allow all / Allow limited access). Po odpovědi se otevře náš BottomSheet,
    // který čte MediaStore přímo, takže zobrazí jen videa povolená uživatelem.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (MediaPermissions.accessLevel(context) != AccessLevel.NONE) {
            viewModel.loadAvailableGalleryVideos()
            showSheet = true
        }
    }

    fun requestPermissionOrSettings() {
        val activity = context as? Activity
        val permanentlyDenied = activity != null && MediaPermissions.isPermanentlyDenied(activity)
        if (permanentlyDenied) {
            showSettingsRedirect = true
        } else {
            MediaPermissions.markRequested(context)
            permissionLauncher.launch(MediaPermissions.requiredPermissions())
        }
    }

    /**
     * Opens the sheet. From the main tap the selection starts empty; from the pencil the current
     * videos are pre-checked, so a stale choice from a previous compression never leaks in.
     */
    fun launchPicker(fromPencil: Boolean = false) {
        when (MediaPermissions.accessLevel(context)) {
            AccessLevel.FULL, AccessLevel.LIMITED -> {
                if (fromPencil) viewModel.preselectCurrentVideos() else viewModel.clearPickerSelection()
                viewModel.loadAvailableGalleryVideos()
                showSheet = true
            }
            AccessLevel.NONE -> showRationale = true
        }
    }

    // Returning from Settings (or the system permission dialog) refreshes access and the list.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && showSheet) {
                viewModel.loadAvailableGalleryVideos()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showSheet) {
        VideoPickerSheet(
            viewModel = viewModel,
            accessLevel = MediaPermissions.accessLevel(context),
            onManage = { permissionLauncher.launch(MediaPermissions.requiredPermissions()) },
            onDismiss = { showSheet = false }
        )
    }

    if (showSettingsRedirect) {
        AlertDialog(
            onDismissRequest = { showSettingsRedirect = false },
            containerColor = DialogBackground,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Oprávnění zamítnuto", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
            text = {
                Text(
                    "Bez oprávnění nelze videa vybrat. Povolte je v nastavení aplikace.",
                    color = DialogBodyText,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSettingsRedirect = false
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = TextDark, contentColor = Color.White)
                ) { Text("Otevřít nastavení", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsRedirect = false }) { Text("Zrušit", color = DialogCancelText) }
            }
        )
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri: Uri? ->
        val state = viewModel.uiState.value
        if (uri != null && state is VideoCompressorViewModel.UiState.Success && state.files.isNotEmpty()) {
            viewModel.saveToUri(state.files.first(), uri)
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val state = viewModel.uiState.value
        if (uri != null && state is VideoCompressorViewModel.UiState.Success) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.saveAllToTree(uri, state.files)
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            containerColor = DialogBackground,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text("Přístup k videím", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            },
            text = {
                Text(
                    "Aplikace potřebuje přístup k videím v zařízení, aby je mohla vybrat ke kompresi.",
                    color = DialogBodyText,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRationale = false
                        requestPermissionOrSettings()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = TextDark, contentColor = Color.White)
                ) { Text("Povolit", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text("Zrušit", color = DialogCancelText)
                }
            }
        )
    }

    val isCompressing = uiState is VideoCompressorViewModel.UiState.Compressing
    val isFinished = uiState is VideoCompressorViewModel.UiState.Success ||
        uiState is VideoCompressorViewModel.UiState.Saved
    val isError = uiState is VideoCompressorViewModel.UiState.Error

    // Steps 1 and 2 are locked while compressing and after "Uloženo!", until "Další videa" or "Zrušit".
    val controlsLocked = isCompressing || isFinished

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .verticalScrollbar(scrollState)
            .verticalScroll(scrollState)
            .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = ContentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Video Compressor",
            style = MaterialTheme.typography.headlineSmall,
            color = TextDark,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        StepTitle("1. Krok: Výběr videa")
        StepCard {
            if (selected.isEmpty()) {
                AddVideoButton(onClick = { launchPicker() })
            } else {
                val isSingle = selected.size == 1
                SelectedVideoRow(
                    name = if (isSingle) selected.first().name else selectionSummary(selected.size),
                    details = if (isSingle) "${selected.first().resolution} • ${selected.first().duration}" else null,
                    enabled = !controlsLocked,
                    onEdit = { launchPicker(fromPencil = true) }
                )
            }
        }

        StepTitle("2. Krok: Nastavení")
        StepCard {
            SettingsRow(
                keepAudio = keepAudio,
                enabled = !controlsLocked,
                onChange = { viewModel.setKeepAudio(it) }
            )
            Text(
                text = "Rychlé volby",
                color = if (controlsLocked) DisabledGray else TextDark,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            quickOptions.forEach { option ->
                QuickChoice(
                    label = option.label,
                    selected = targetMb == option.targetMb,
                    enabled = !controlsLocked,
                    onClick = { viewModel.setTargetSize(option.targetMb) }
                )
            }
        }

        StepTitle("3. Krok: Spustit")
        StepCard {
            when (val state = uiState) {
                is VideoCompressorViewModel.UiState.Compressing -> {
                    Text("Komprese ${state.current} z ${state.total}", color = TextDark, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = TextDark,
                        trackColor = OutlineColor
                    )
                    Text("${(state.progress * 100).toInt()} %", color = TextDark)
                    OutlinedButton(
                        onClick = { viewModel.cancelCompression() },
                        modifier = Modifier.fillMaxWidth().height(ButtonHeight),
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDark)
                    ) { Text("Zrušit", color = TextDark) }
                }

                is VideoCompressorViewModel.UiState.Success -> {
                    Text(
                        text = "Hotovo! Zkomprimováno ${czechVideoCount(state.files.size)}",
                        color = TextDark,
                        fontWeight = FontWeight.SemiBold
                    )
                    ActionButton(
                        text = "Uložit do Galerie",
                        onClick = { viewModel.saveToGallery(state.files) }
                    )
                    SecondaryButton(
                        text = "Uložit do složky",
                        onClick = {
                            if (state.files.size == 1) createDocLauncher.launch(state.files.first().name)
                            else treeLauncher.launch(null)
                        }
                    )
                    SecondaryButton(text = "Zpět", onClick = { viewModel.reset() })
                }

                is VideoCompressorViewModel.UiState.Saved -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(TextDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                        }
                        Text("Uloženo!", color = TextDark, fontWeight = FontWeight.Bold)
                        ActionButton(text = "Další videa", onClick = { viewModel.reset() })
                    }
                }

                is VideoCompressorViewModel.UiState.Error -> {
                    Text(friendlyErrorMessage(state.message), color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = TextDark)
                    ) { Text("Zpět") }
                }

                else -> {
                    ActionButton(
                        text = "Spustit kompresi",
                        onClick = { viewModel.compressVideo() },
                        enabled = selected.isNotEmpty()
                    )
                }
            }
        }
    }
}

/**
 * Vertical scrollbar drawn over the content. The track starts 15 % from the top of the viewport
 * and ends at the bottom content padding, so its lower end lines up with the bottom edge of the last card.
 * Thumb size and position follow [state].
 */
private fun Modifier.verticalScrollbar(
    state: ScrollState,
    width: Dp = 4.dp,
    color: Color = ScrollbarColor,
    endInset: Dp = 6.dp
): Modifier = drawWithContent {
    drawContent()
    val totalMax = state.maxValue.toFloat()
    val currentScroll = state.value.toFloat()
    if (totalMax > 0f) {
        val trackTop = size.height * 0.15f
        val trackBottom = size.height - ContentBottomPadding.toPx()
        val trackHeight = trackBottom - trackTop
        val thumbHeight = (trackHeight * (size.height / (size.height + totalMax)))
            .coerceIn(36.dp.toPx(), trackHeight * 0.5f)
        val scrollProgress = currentScroll / totalMax
        val thumbTop = trackTop + scrollProgress * (trackHeight - thumbHeight)
        val thumbWidth = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - endInset.toPx() - thumbWidth, thumbTop),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2f)
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text = text, color = TextDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StepCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

@Composable
private fun AddVideoButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(ButtonHeight),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = ButtonLight, contentColor = TextDark)
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = TextDark)
        Spacer(Modifier.width(12.dp))
        Text("Klikněte pro výběr videa", color = TextDark)
    }
}

@Composable
private fun SelectedVideoRow(name: String, details: String?, enabled: Boolean, onEdit: () -> Unit) {
    val contentColor = if (enabled) TextDark else DisabledGray
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) PreviewGray else DisabledGray),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, color = contentColor, fontWeight = FontWeight.Bold)
            if (details != null) {
                Text(text = details, color = contentColor)
            }
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(Icons.Default.Edit, contentDescription = "Změnit výběr", tint = contentColor)
        }
    }
}

@Composable
private fun SettingsRow(keepAudio: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ponechat zvuk", color = if (enabled) TextDark else DisabledGray, fontWeight = FontWeight.Bold)
        Switch(
            checked = keepAudio,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 16.dp),
            colors = SwitchDefaults.colors(
                checkedTrackColor = SwitchCheckedTrack,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = SwitchUncheckedTrack,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedTrackColor = DisabledGray,
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.85f),
                disabledCheckedBorderColor = Color.Transparent,
                disabledUncheckedTrackColor = SwitchDisabledUncheckedTrack,
                disabledUncheckedThumbColor = SwitchDisabledUncheckedThumb,
                disabledUncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun QuickChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(ButtonHeight),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = SelectedGold,
                contentColor = TextDark,
                disabledContainerColor = DisabledGray,
                disabledContentColor = Color.White
            )
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(ButtonHeight),
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = TextDark,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = DisabledGray
            ),
            border = BorderStroke(1.dp, if (enabled) OutlineColor else DisabledGray)
        ) { Text(label) }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(ButtonHeight),
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = ButtonLight, contentColor = TextDark),
        border = BorderStroke(1.dp, OutlineColor)
    ) { Text(text, color = TextDark) }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(ButtonHeight),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = TextDark,
            contentColor = Color.White,
            disabledContainerColor = DisabledGray,
            disabledContentColor = Color.White
        )
    ) { Text(text) }
}
