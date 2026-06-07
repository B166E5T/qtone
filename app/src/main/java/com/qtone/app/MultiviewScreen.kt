package com.qtone.app

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.qtone.app.model.Category
import com.qtone.app.model.MediaItem
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.qtone.app.ui.rememberIsTV
import com.qtone.app.ui.CategoryRow
import com.qtone.app.ui.QtoneColors
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════
// Multiview — watch two Live TV channels side by side.
//
// Entry: Settings → Multiview button → MultiviewScreen
// Exit:  Back → "Exit Multiview?" → Yes → returns to main screen
//
// Each box goes through these states:
//   Empty       → "+" icon, press OK to open channel picker
//   Playing     → video plays, press OK to show change-overlay
//   ReadyToChange → dimmed "+" over video, press OK to open picker
//
// Audio follows D-pad focus: whichever box is focused gets audio.
// The active-audio box shows a blue border + audio icon.
// ═══════════════════════════════════════════════════════════════════════

@OptIn(UnstableApi::class)
@Composable
fun MultiviewScreen(
    liveCategories: List<Category>,
    liveStreams: List<MediaItem>,
    onExit: () -> Unit
) {
    val context = LocalContext.current

    // FFmpeg-enabled renderer — same setup as LiveLayout and PlayerActivity.
    val renderersFactory = remember {
        androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
    }

    // Two independent ExoPlayer instances — one per box.
    val leftPlayer = remember { ExoPlayer.Builder(context, renderersFactory).build() }
    val rightPlayer = remember { ExoPlayer.Builder(context, renderersFactory).build() }

    DisposableEffect(Unit) {
        onDispose {
            leftPlayer.release()
            rightPlayer.release()
        }
    }

    // ── State ────────────────────────────────────────────────────────
    var leftChannel by remember { mutableStateOf<MediaItem?>(null) }
    var rightChannel by remember { mutableStateOf<MediaItem?>(null) }
    var focusedBox by remember { mutableStateOf(0) }  // 0 = left, 1 = right
    var leftShowOverlay by remember { mutableStateOf(false) }
    var rightShowOverlay by remember { mutableStateOf(false) }
    var leftError by remember { mutableStateOf(false) }
    var rightError by remember { mutableStateOf(false) }

    // Channel picker
    var showPicker by remember { mutableStateOf(false) }
    var pickerTargetBox by remember { mutableStateOf(0) }

    // Exit dialog
    var showExitDialog by remember { mutableStateOf(false) }

    // Focus management — the main layout needs to reclaim focus after
    // the picker or dialog closes, otherwise key events go nowhere.
    val parentFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        try { parentFocusRequester.requestFocus() } catch (_: Throwable) {}
    }

    LaunchedEffect(showPicker, showExitDialog) {
        if (!showPicker && !showExitDialog) {
            delay(100)
            try { parentFocusRequester.requestFocus() } catch (_: Throwable) {}
        }
    }

    // ── Audio follows focus ──────────────────────────────────────────
    LaunchedEffect(focusedBox, leftChannel, rightChannel) {
        leftPlayer.volume = if (focusedBox == 0 && leftChannel != null) 1f else 0f
        rightPlayer.volume = if (focusedBox == 1 && rightChannel != null) 1f else 0f
    }

    // ── Load / change channels ───────────────────────────────────────
    LaunchedEffect(leftChannel?.id) {
        leftError = false
        val url = leftChannel?.streamUrl
        if (url.isNullOrBlank()) {
            leftPlayer.stop()
            leftPlayer.clearMediaItems()
        } else {
            leftPlayer.setMediaItem(ExoMediaItem.fromUri(url))
            leftPlayer.prepare()
            leftPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(rightChannel?.id) {
        rightError = false
        val url = rightChannel?.streamUrl
        if (url.isNullOrBlank()) {
            rightPlayer.stop()
            rightPlayer.clearMediaItems()
        } else {
            rightPlayer.setMediaItem(ExoMediaItem.fromUri(url))
            rightPlayer.prepare()
            rightPlayer.playWhenReady = true
        }
    }

    // ── Error listeners ──────────────────────────────────────────────
    DisposableEffect(leftPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                leftError = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) leftError = false
            }
        }
        leftPlayer.addListener(listener)
        onDispose { leftPlayer.removeListener(listener) }
    }

    DisposableEffect(rightPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                rightError = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) rightError = false
            }
        }
        rightPlayer.addListener(listener)
        onDispose { rightPlayer.removeListener(listener) }
    }

    // ── Back handler ─────────────────────────────────────────────────
    BackHandler {
        when {
            showPicker -> showPicker = false
            leftShowOverlay || rightShowOverlay -> {
                leftShowOverlay = false
                rightShowOverlay = false
            }
            showExitDialog -> showExitDialog = false
            else -> showExitDialog = true
        }
    }

    // ── Main layout ──────────────────────────────────────────────────
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!showPicker && !showExitDialog)
                    Modifier
                        .focusRequester(parentFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (focusedBox == 1) {
                                        focusedBox = 0
                                        rightShowOverlay = false
                                        true
                                    } else false
                                }
                                Key.DirectionRight -> {
                                    if (focusedBox == 0) {
                                        focusedBox = 1
                                        leftShowOverlay = false
                                        true
                                    } else false
                                }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    val channel = if (focusedBox == 0) leftChannel else rightChannel
                                    val showOverlay = if (focusedBox == 0) leftShowOverlay else rightShowOverlay

                                    if (channel == null) {
                                        // Empty box → open picker
                                        pickerTargetBox = focusedBox
                                        showPicker = true
                                    } else if (!showOverlay) {
                                        // Playing → show change overlay
                                        if (focusedBox == 0) leftShowOverlay = true
                                        else rightShowOverlay = true
                                    } else {
                                        // Overlay showing → open picker to change
                                        if (focusedBox == 0) leftShowOverlay = false
                                        else rightShowOverlay = false
                                        pickerTargetBox = focusedBox
                                        showPicker = true
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                else Modifier
            )
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left box
            MultiviewBox(
                player = leftPlayer,
                channel = leftChannel,
                isFocused = focusedBox == 0 && !showPicker && !showExitDialog,
                showOverlay = leftShowOverlay,
                hasError = leftError,
                onTap = {
                    if (focusedBox != 0) {
                        // First tap on unfocused box: switch audio
                        focusedBox = 0
                        rightShowOverlay = false
                    } else if (leftChannel == null) {
                        // Tap on empty box: open picker
                        pickerTargetBox = 0
                        showPicker = true
                    } else if (!leftShowOverlay) {
                        // Second tap on focused playing box: show overlay
                        leftShowOverlay = true
                    } else {
                        // Tap on overlay: open picker to change channel
                        leftShowOverlay = false
                        pickerTargetBox = 0
                        showPicker = true
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Right box
            MultiviewBox(
                player = rightPlayer,
                channel = rightChannel,
                isFocused = focusedBox == 1 && !showPicker && !showExitDialog,
                showOverlay = rightShowOverlay,
                hasError = rightError,
                onTap = {
                    if (focusedBox != 1) {
                        // First tap on unfocused box: switch audio
                        focusedBox = 1
                        leftShowOverlay = false
                    } else if (rightChannel == null) {
                        // Tap on empty box: open picker
                        pickerTargetBox = 1
                        showPicker = true
                    } else if (!rightShowOverlay) {
                        // Second tap on focused playing box: show overlay
                        rightShowOverlay = true
                    } else {
                        // Tap on overlay: open picker to change channel
                        rightShowOverlay = false
                        pickerTargetBox = 1
                        showPicker = true
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // ── Channel picker overlay ───────────────────────────────────
        if (showPicker) {
            MultiviewChannelPicker(
                categories = liveCategories,
                channels = liveStreams,
                targetLabel = if (pickerTargetBox == 0) "Left" else "Right",
                onChannelSelected = { channel ->
                    if (pickerTargetBox == 0) {
                        leftChannel = channel
                        leftShowOverlay = false
                    } else {
                        rightChannel = channel
                        rightShowOverlay = false
                    }
                    showPicker = false
                },
                onDismiss = { showPicker = false }
            )
        }

        // ── Exit dialog ──────────────────────────────────────────────
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("Exit Multiview?", color = QtoneColors.Text) },
                text = { Text("Return to the main screen?", color = QtoneColors.Muted) },
                confirmButton = {
                    TextButton(onClick = {
                        showExitDialog = false
                        onExit()
                    }) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) { Text("No") }
                },
                containerColor = Color(0xEE101015)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Single video box — shows empty state, playing video, change overlay,
// or error state. Visual border indicates focus + active audio.
// ═══════════════════════════════════════════════════════════════════════

@OptIn(UnstableApi::class)
@Composable
private fun MultiviewBox(
    player: ExoPlayer,
    channel: MediaItem?,
    isFocused: Boolean,
    showOverlay: Boolean,
    hasError: Boolean,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) Color(0xFF4A90D9) else Color(0x33FFFFFF),
        animationSpec = tween(150),
        label = "mvBorder"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 3.dp else 1.dp,
        animationSpec = tween(150),
        label = "mvBorderWidth"
    )

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0A0E))
    ) {
        if (channel != null) {
            // ── Video player ─────────────────────────────────────────
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(AndroidColor.BLACK)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )

            // ── Channel name bar (always visible, subtle) ────────────
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x77000000))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    channel.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Active audio indicator ───────────────────────────────
            if (isFocused) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x88000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("♪", color = Color(0xFF4A90D9), fontSize = 16.sp)
                }
            }

            // ── Change channel overlay ───────────────────────────────
            if (showOverlay) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("+", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Press OK to change channel",
                            color = Color(0xCCFFFFFF),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ── Error overlay ────────────────────────────────────────
            if (hasError && !showOverlay) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xDD000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Channel unavailable",
                            color = QtoneColors.Muted,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Press OK to try another",
                            color = Color(0x88FFFFFF),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        } else {
            // ── Empty box ────────────────────────────────────────────
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "+",
                        color = if (isFocused) Color(0xCCFFFFFF) else Color(0x55FFFFFF),
                        fontSize = 60.sp,
                        fontWeight = FontWeight.Light
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Press OK to add channel",
                        color = if (isFocused) Color(0xAAFFFFFF) else QtoneColors.Muted,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Touch overlay for phones/tablets. On TV this is never composed.
        // Tap behavior:
        //   - Tap unfocused box → switch audio to this box
        //   - Tap focused playing box → show change-channel overlay
        //   - Tap overlay → open channel picker
        //   - Tap empty box → open channel picker
        val isTV = rememberIsTV()
        if (!isTV) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() })
                    }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Channel picker — full-screen overlay with categories, channel list,
// and search. Uses the same CategoryRow from Components.kt for visual
// consistency with the main Live TV section.
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun MultiviewChannelPicker(
    categories: List<Category>,
    channels: List<MediaItem>,
    targetLabel: String,
    onChannelSelected: (MediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }

    var selectedCategoryId by remember {
        mutableStateOf(categories.firstOrNull()?.id ?: "")
    }
    var searchQuery by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }

    // Delay search field appearance by 250ms so the categories column
    // gets initial focus when the picker opens. Without this, the search
    // Surface is the first focusable element and attracts focus + keyboard.
    var showSearch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        showSearch = true
    }

    val filteredChannels = remember(channels, selectedCategoryId, searchQuery) {
        if (searchQuery.isNotBlank()) {
            channels.filter { it.name.contains(searchQuery, ignoreCase = true) }
        } else {
            channels.filter { it.categoryId == selectedCategoryId }
        }
    }

    // When entering edit mode, request focus on the text field.
    LaunchedEffect(editing) {
        if (editing) {
            delay(80)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
            keyboard?.show()
        }
    }

    BackHandler {
        when {
            editing -> {
                editing = false
                keyboard?.hide()
                focusManager.clearFocus(force = true)
            }
            else -> onDismiss()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0050508))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // ── Header: title + search ───────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Select Channel — $targetLabel",
                    color = QtoneColors.Text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(16.dp))

                if (showSearch) {
                    if (editing) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            label = { Text("Search channels") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                editing = false
                                keyboard?.hide()
                                focusManager.clearFocus(force = true)
                            }),
                            modifier = Modifier
                                .width(280.dp)
                                .focusRequester(searchFocusRequester),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = QtoneColors.Text,
                                fontSize = 14.sp
                            )
                        )
                    } else {
                        Surface(
                            onClick = { editing = true },
                            modifier = Modifier
                                .width(280.dp)
                                .height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF15151B),
                            contentColor = QtoneColors.Text,
                            border = BorderStroke(1.dp, Color(0x44FFFFFF))
                        ) {
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (searchQuery.isBlank()) "Search channels…" else searchQuery,
                                    color = if (searchQuery.isBlank()) QtoneColors.Muted else QtoneColors.Text,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Body: categories + channels ──────────────────────────
            Row(Modifier.fillMaxSize()) {
                // Categories sidebar
                LazyColumn(
                    Modifier
                        .width(200.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(categories, key = { it.id }) { cat ->
                        CategoryRow(cat, selectedCategoryId == cat.id) {
                            selectedCategoryId = cat.id
                            searchQuery = ""
                            editing = false
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Channel list
                if (filteredChannels.isEmpty()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "No channels match \"$searchQuery\""
                            else "No channels in this category",
                            color = QtoneColors.Muted,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredChannels, key = { it.id }) { channel ->
                            ChannelPickerRow(
                                channel = channel,
                                onClick = { onChannelSelected(channel) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Single row in the channel picker list. Shows channel icon + name.
// Focus-aware border and background for D-pad navigation.
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ChannelPickerRow(
    channel: MediaItem,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(8.dp),
        color = if (focused) Color(0xFF1F1A28) else Color.Transparent,
        contentColor = QtoneColors.Text,
        border = if (focused) BorderStroke(1.5.dp, Color.White)
                 else BorderStroke(0.dp, Color.Transparent)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = channel.poster,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF131318))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                channel.name,
                color = if (focused) Color.White else Color(0xCCFFFFFF),
                fontSize = 14.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
