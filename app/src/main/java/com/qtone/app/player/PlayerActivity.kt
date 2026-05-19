package com.qtone.app.player

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.max
import java.util.Locale

class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(AndroidColor.BLACK)

        val url = intent.getStringExtra("url").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        val itemId = intent.getStringExtra("item_id").orEmpty()
        val streamType = intent.getStringExtra("stream_type").orEmpty()
        val seriesId = intent.getStringExtra("series_id").orEmpty()
        val rating = intent.getStringExtra("rating").orEmpty()
        val genre = intent.getStringExtra("genre").orEmpty()
        val year = intent.getStringExtra("year").orEmpty()
        val plot = intent.getStringExtra("plot").orEmpty()
        val prefs = getSharedPreferences("qtone_session", Context.MODE_PRIVATE)

        val progressPrefix = when (streamType) {
            "movie" -> "movie"
            "series_episode" -> "series_episode"
            else -> ""
        }

        val resumePosition = if (progressPrefix.isNotBlank() && itemId.isNotBlank()) {
            prefs.getLong("${progressPrefix}_position_$itemId", 0L)
        } else {
            0L
        }

        val resumeDuration = if (progressPrefix.isNotBlank() && itemId.isNotBlank()) {
            prefs.getLong("${progressPrefix}_duration_$itemId", 0L)
        } else {
            0L
        }

        val showResumePrompt =
            progressPrefix.isNotBlank() &&
                itemId.isNotBlank() &&
                resumeDuration > 0L &&
                resumePosition >= 60_000L &&
                resumePosition < resumeDuration * 0.90f

        val movieFallbackUrls = if (streamType == "movie") {
            buildMovieFallbackUrls(url)
        } else {
            emptyList()
        }
        var movieFallbackIndex = 0

        player = ExoPlayer.Builder(this).build().also { exo ->
            fun loadUrl(playUrl: String, seekMs: Long = 0L) {
                exo.setMediaItem(
                    MediaItem.Builder()
                        .setUri(playUrl)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(title)
                                .build()
                        )
                        .build()
                )
                exo.prepare()
                if (seekMs > 0L) {
                    exo.seekTo(seekMs)
                }
                exo.playWhenReady = true
                exo.play()
            }

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (movieFallbackIndex < movieFallbackUrls.size) {
                        val nextUrl = movieFallbackUrls[movieFallbackIndex++]
                        loadUrl(nextUrl, resumePosition)
                    }
                }
            })

            loadUrl(url, if (showResumePrompt) 0L else resumePosition)
            if (showResumePrompt) {
                exo.pause()
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val playerView = PlayerView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            this.player = this@PlayerActivity.player
            useController = false
            keepScreenOn = true
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val overlay = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setContent {
                QtonePlayerOverlay(
                    player = player,
                    playerView = playerView,
                    title = title,
                    rating = rating,
                    genre = genre,
                    year = year,
                    plot = plot,
                    showResumePrompt = showResumePrompt,
                    resumePosition = resumePosition
                )
            }
        }

        root.addView(playerView)
        root.addView(overlay)
        setContentView(root)

        overlay.post {
            overlay.isFocusable = true
            overlay.isFocusableInTouchMode = true
            overlay.requestFocus()
        }
    }

    override fun onStop() {
        saveMovieProgress()
        super.onStop()
        player?.release()
        player = null
    }

    private fun saveMovieProgress() {
        val itemId = intent.getStringExtra("item_id").orEmpty()
        val streamType = intent.getStringExtra("stream_type").orEmpty()
        val seriesId = intent.getStringExtra("series_id").orEmpty()
        val progressPrefix = when (streamType) {
            "movie" -> "movie"
            "series_episode" -> "series_episode"
            else -> ""
        }

        player?.let { exo ->
            if (progressPrefix.isNotBlank() && itemId.isNotBlank()) {
                val position = exo.currentPosition.coerceAtLeast(0L)
                val duration = exo.duration.coerceAtLeast(0L)
                if (duration > 0L) {
                    val nearEnd = position >= duration * 0.90f
                    val tooShort = position < 60_000L
                    val editor = getSharedPreferences("qtone_session", Context.MODE_PRIVATE).edit()
                    if (nearEnd || tooShort) {
                        editor
                            .remove("${progressPrefix}_position_$itemId")
                            .remove("${progressPrefix}_duration_$itemId")

                        if (progressPrefix == "series_episode" && seriesId.isNotBlank()) {
                            val mappedEpisode = getSharedPreferences("qtone_session", Context.MODE_PRIVATE)
                                .getString("series_continue_episode_$seriesId", "")
                            if (mappedEpisode == itemId) {
                                editor.remove("series_continue_episode_$seriesId")
                            }
                        }
                    } else {
                        editor
                            .putLong("${progressPrefix}_position_$itemId", position)
                            .putLong("${progressPrefix}_duration_$itemId", duration)

                        if (progressPrefix == "series_episode" && seriesId.isNotBlank()) {
                            editor.putString("series_continue_episode_$seriesId", itemId)
                        }
                    }
                    editor.apply()
                }
            }
        }
    }
    private fun buildMovieFallbackUrls(originalUrl: String): List<String> {
        if (originalUrl.isBlank()) return emptyList()

        val base = originalUrl.substringBeforeLast(".", missingDelimiterValue = originalUrl)
        val currentExt = originalUrl.substringAfterLast(".", missingDelimiterValue = "").lowercase()
        val extensions = listOf("mp4", "mkv", "ts", "avi", "mov")

        return extensions
            .filter { it != currentExt }
            .map { "$base.$it" }
    }

}

@OptIn(UnstableApi::class)
@Composable
private fun QtonePlayerOverlay(
    player: ExoPlayer?,
    playerView: PlayerView,
    title: String,
    rating: String,
    genre: String,
    year: String,
    plot: String,
    showResumePrompt: Boolean,
    resumePosition: Long
) {
    var isPlaying by remember { mutableStateOf(player?.isPlaying == true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var tracks by remember { mutableStateOf(player?.currentTracks ?: Tracks.EMPTY) }
    val osdFocusRequester = remember { FocusRequester() }
    var selectedRow by remember { mutableStateOf(1) } // 0 = seek bar, 1 = controls
    var selectedControl by remember { mutableStateOf(1) } // default to Play/Pause
    var osdVisible by remember { mutableStateOf(false) }
    var osdActivityTick by remember { mutableStateOf(0) }
    var showResumeChoice by remember { mutableStateOf(showResumePrompt) }
    var resumeChoiceIndex by remember { mutableStateOf(0) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var audioMenuIndex by remember { mutableStateOf(0) }
    var subtitleMenuIndex by remember { mutableStateOf(0) }

    val aspectIcon = when (resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "⛶"
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "⤢"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "⬚"
        else -> "⛶"
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = player.duration.coerceAtLeast(0L)
                position = player.currentPosition.coerceAtLeast(0L)
            }
        }

        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        delay(200)
        try { osdFocusRequester.requestFocus() } catch (_: Throwable) {}

        while (player != null) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            tracks = player.currentTracks
            delay(500)
        }
    }

    LaunchedEffect(osdVisible, osdActivityTick, showAudioMenu, showSubtitleMenu, showResumeChoice) {
        if (osdVisible && !showAudioMenu && !showSubtitleMenu && !showResumeChoice) {
            delay(5_000)
            osdVisible = false
        }
    }

    val audioTracks = remember(tracks) { playerTrackOptions(tracks, C.TRACK_TYPE_AUDIO) }
    val subtitleTracks = remember(tracks) { playerTrackOptions(tracks, C.TRACK_TYPE_TEXT) }

    fun rewind() {
        player?.seekTo(max(0L, (player.currentPosition - 10_000L)))
    }

    fun playPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.playWhenReady = true
                it.play()
            }
        }
    }

    fun fastForward() {
        player?.let {
            val target = it.currentPosition + 10_000L
            val dur = it.duration.takeIf { d -> d > 0L } ?: target
            it.seekTo(target.coerceAtMost(dur))
        }
    }

    fun seekRelative(deltaMs: Long) {
        player?.let {
            val target = it.currentPosition + deltaMs
            val dur = it.duration.takeIf { d -> d > 0L } ?: target
            it.seekTo(target.coerceIn(0L, dur))
        }
    }

    fun cycleAspect() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = resizeMode
    }

    fun selectCurrentControl() {
        when (selectedControl) {
            0 -> rewind()
            1 -> playPause()
            2 -> fastForward()
            3 -> cycleAspect()
            4 -> if (subtitleTracks.isNotEmpty()) {
                subtitleMenuIndex = 0
                showSubtitleMenu = true
            }
            5 -> if (audioTracks.isNotEmpty()) {
                audioMenuIndex = 0
                showAudioMenu = true
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(osdFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                fun wakeOsd() {
                    osdVisible = true
                    osdActivityTick += 1
                }

                if (showResumeChoice) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            resumeChoiceIndex = 0
                            true
                        }
                        Key.DirectionRight -> {
                            resumeChoiceIndex = 1
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            showResumeChoice = false
                            osdVisible = false
                            osdActivityTick += 1
                            if (resumeChoiceIndex == 0) {
                                player?.seekTo(resumePosition)
                            } else {
                                player?.seekTo(0L)
                            }
                            player?.playWhenReady = true
                            player?.play()
                            true
                        }
                        Key.Back -> {
                            showResumeChoice = false
                            osdVisible = false
                            player?.seekTo(0L)
                            player?.playWhenReady = true
                            player?.play()
                            true
                        }
                        else -> true
                    }
                } else if (showAudioMenu) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            audioMenuIndex = (audioMenuIndex - 1).coerceAtLeast(0)
                            wakeOsd()
                            true
                        }
                        Key.DirectionDown -> {
                            audioMenuIndex = (audioMenuIndex + 1).coerceAtMost((audioTracks.size - 1).coerceAtLeast(0))
                            wakeOsd()
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            audioTracks.getOrNull(audioMenuIndex)?.let { player?.selectTrack(it) }
                            showAudioMenu = false
                            wakeOsd()
                            true
                        }
                        Key.Back -> {
                            showAudioMenu = false
                            wakeOsd()
                            true
                        }
                        else -> false
                    }
                } else if (showSubtitleMenu) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            subtitleMenuIndex = (subtitleMenuIndex - 1).coerceAtLeast(0)
                            wakeOsd()
                            true
                        }
                        Key.DirectionDown -> {
                            subtitleMenuIndex = (subtitleMenuIndex + 1).coerceAtMost(subtitleTracks.size)
                            wakeOsd()
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (subtitleMenuIndex == 0) {
                                player?.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                            } else {
                                subtitleTracks.getOrNull(subtitleMenuIndex - 1)?.let { option ->
                                    player?.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .build()
                                    player?.selectTrack(option)
                                }
                            }
                            showSubtitleMenu = false
                            wakeOsd()
                            true
                        }
                        Key.Back -> {
                            showSubtitleMenu = false
                            wakeOsd()
                            true
                        }
                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.Back -> {
                            if (osdVisible) {
                                osdVisible = false
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (!osdVisible) {
                                osdVisible = true
                                osdActivityTick += 1
                                true
                            } else {
                                osdActivityTick += 1
                                if (selectedRow == 0) {
                                    playPause()
                                } else {
                                    selectCurrentControl()
                                }
                                true
                            }
                        }
                        Key.MediaRewind -> {
                            rewind()
                            true
                        }
                        Key.MediaFastForward -> {
                            fastForward()
                            true
                        }
                        Key.MediaPlayPause -> {
                            playPause()
                            true
                        }
                        Key.MediaPlay -> {
                            player?.playWhenReady = true
                            player?.play()
                            true
                        }
                        Key.MediaPause -> {
                            player?.pause()
                            true
                        }
                        Key.DirectionUp -> {
                            if (!osdVisible) false else {
                                osdActivityTick += 1
                                selectedRow = 0
                                true
                            }
                        }
                        Key.DirectionDown -> {
                            if (!osdVisible) false else {
                                osdActivityTick += 1
                                selectedRow = 1
                                true
                            }
                        }
                        Key.DirectionLeft -> {
                            if (!osdVisible) false else {
                                osdActivityTick += 1
                                if (selectedRow == 0) {
                                    seekRelative(-300_000L)
                                } else {
                                    selectedControl = (selectedControl - 1).coerceAtLeast(0)
                                }
                                true
                            }
                        }
                        Key.DirectionRight -> {
                            if (!osdVisible) false else {
                                osdActivityTick += 1
                                if (selectedRow == 0) {
                                    seekRelative(300_000L)
                                } else {
                                    selectedControl = (selectedControl + 1).coerceAtMost(5)
                                }
                                true
                            }
                        }
                        else -> false
                    }
                }
            }
    ) {
        if (osdVisible) {
        // Live-TV-style bottom OSD: one translucent rounded panel holding seek bar, metadata, and controls.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xD8000000))
                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(12.dp))
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatTime(position), color = Color(0xFFE4E2EA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                FocusableSeekBar(
                    position = position,
                    duration = duration,
                    selected = selectedRow == 0,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(formatTime(duration), color = Color(0xFFE4E2EA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(end = 24.dp)
                ) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 22.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val metadataText = listOfNotNull(
                        rating.takeIf { it.isNotBlank() }?.let { "RATING: $it" },
                        genre.takeIf { it.isNotBlank() }?.let { "GENRE: $it" },
                        year.takeIf { it.isNotBlank() }?.let { "YEAR: ${it.take(4)}" }
                    ).joinToString("  ")

                    if (metadataText.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            metadataText,
                            color = Color(0xFFE4E2EA),
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (plot.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            plot,
                            color = Color(0xFFC9C5D4),
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            maxLines = 15,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OsdIconButton("◀◀", "Rewind", selected = selectedRow == 1 && selectedControl == 0) {
                        rewind()
                    }

                    OsdIconButton(if (isPlaying) "Ⅱ" else "▶", "Play / Pause", selected = selectedRow == 1 && selectedControl == 1) {
                        playPause()
                    }

                    OsdIconButton("▶▶", "Fast Forward", selected = selectedRow == 1 && selectedControl == 2) {
                        fastForward()
                    }

                    OsdIconButton(aspectIcon, "Aspect Ratio", selected = selectedRow == 1 && selectedControl == 3) {
                        cycleAspect()
                    }

                    OsdIconButton("CC", "Subtitles", enabled = subtitleTracks.isNotEmpty(), selected = selectedRow == 1 && selectedControl == 4) {
                        subtitleMenuIndex = 0
                        showSubtitleMenu = true
                        osdActivityTick += 1
                    }

                    OsdIconButton("♪", "Audio", enabled = audioTracks.isNotEmpty(), selected = selectedRow == 1 && selectedControl == 5) {
                        audioMenuIndex = 0
                        showAudioMenu = true
                        osdActivityTick += 1
                    }
                }
            }
        }

        }

        if (osdVisible && showAudioMenu) {
            TrackPickerPopup(
                title = "Audio",
                options = audioTracks.map { it.label },
                selectedIndex = audioMenuIndex,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 34.dp, bottom = 96.dp)
            )
        }

        if (osdVisible && showSubtitleMenu) {
            TrackPickerPopup(
                title = "Subtitles",
                options = listOf("Off") + subtitleTracks.map { it.label },
                selectedIndex = subtitleMenuIndex,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 86.dp, bottom = 96.dp)
            )
        }

        if (showResumeChoice) {
            ResumeChoicePopup(
                resumePosition = resumePosition,
                selectedIndex = resumeChoiceIndex,
                onResume = {
                    showResumeChoice = false
                    osdVisible = false
                    osdActivityTick += 1
                    player?.seekTo(resumePosition)
                    player?.playWhenReady = true
                    player?.play()
                },
                onRestart = {
                    showResumeChoice = false
                    osdVisible = false
                    osdActivityTick += 1
                    player?.seekTo(0L)
                    player?.playWhenReady = true
                    player?.play()
                }
            )
        }

    }
}

@Composable
private fun OsdIconButton(
    icon: String,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(if (icon == "CC") 50.dp else 48.dp)
            .height(42.dp),
        shape = RoundedCornerShape(9.dp),
        color = when {
            !enabled -> Color.Transparent
            selected -> Color(0x22FFFFFF)
            else -> Color.Transparent
        },
        border = if (selected) BorderStroke(2.dp, Color(0xFFEAFBFF)) else BorderStroke(1.dp, Color.Transparent),
        contentColor = Color.White
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                icon,
                fontSize = when (icon) {
                    "CC" -> if (selected) 18.sp else 15.sp
                    "♪" -> if (selected) 30.sp else 27.sp
                    "Ⅱ", "▶" -> if (selected) 29.sp else 25.sp
                    else -> if (selected) 25.sp else 22.sp
                },
                fontWeight = FontWeight.Black,
                color = when {
                    !enabled -> Color(0x66EAF8FF)
                    selected -> Color.White
                    else -> Color(0xFFEAF8FF)
                }
            )
        }
    }
}

@Composable
private fun FocusableSeekBar(
    position: Long,
    duration: Long,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val fraction = if (duration > 0L) {
        (position.coerceIn(0L, duration).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Color(0x1AFFFFFF) else Color.Transparent)
            .border(
                if (selected) 1.dp else 0.dp,
                if (selected) Color(0x55FFFFFF) else Color.Transparent,
                RoundedCornerShape(17.dp)
            )
            .padding(horizontal = if (selected) 8.dp else 0.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (selected) 8.dp else 7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF8B8B8B))
        )

        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(if (selected) 8.dp else 7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF9B59FF))
        )
    }
}


@Composable
private fun TrackPickerPopup(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .width(230.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEE050508))
            .border(1.dp, Color(0xAAEAFBFF), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)

        options.take(10).forEachIndexed { index, option ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (index == selectedIndex) Color(0x33FFFFFF) else Color.Transparent)
                    .border(
                        if (index == selectedIndex) 1.dp else 0.dp,
                        if (index == selectedIndex) Color(0xFFEAFBFF) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    option,
                    color = if (index == selectedIndex) Color.White else Color(0xFFD8D4E8),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ResumeChoicePopup(
    resumePosition: Long,
    selectedIndex: Int,
    onResume: () -> Unit,
    onRestart: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF0050508))
                .border(1.dp, Color(0xAAEAFBFF), RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Continue watching?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("Resume from ${formatTime(resumePosition)} or restart from the beginning.", color = Color(0xFFD8D4E8), fontSize = 14.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    onClick = onResume,
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedIndex == 0) Color(0x33FFFFFF) else Color.Transparent,
                    border = BorderStroke(if (selectedIndex == 0) 2.dp else 1.dp, if (selectedIndex == 0) Color(0xFFEAFBFF) else Color(0x66EAFBFF))
                ) {
                    Text("Resume", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }

                Surface(
                    onClick = onRestart,
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedIndex == 1) Color(0x33FFFFFF) else Color.Transparent,
                    border = BorderStroke(if (selectedIndex == 1) 2.dp else 1.dp, if (selectedIndex == 1) Color(0xFFEAFBFF) else Color(0x66EAFBFF))
                ) {
                    Text("Restart", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
    }
}


private data class TrackOption(
    val label: String,
    val group: androidx.media3.common.TrackGroup,
    val trackIndex: Int
)

private fun playerTrackOptions(tracks: Tracks, type: Int): List<TrackOption> {
    return tracks.groups
        .filter { group -> group.type == type }
        .flatMap { group ->
            (0 until group.length).map { index ->
                val format = group.getTrackFormat(index)
                val language = languageDisplayName(format.language)
                val label = format.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }.orEmpty()
                val fallback = when (type) {
                    C.TRACK_TYPE_AUDIO -> "Audio ${index + 1}"
                    C.TRACK_TYPE_TEXT -> "Subtitle ${index + 1}"
                    else -> "Track ${index + 1}"
                }

                TrackOption(
                    label = listOf(language, label).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { fallback },
                    group = group.mediaTrackGroup,
                    trackIndex = index
                )
            }
        }
}


private fun languageDisplayName(code: String?): String {
    val normalized = code?.trim()?.lowercase(Locale.US).orEmpty()
    if (normalized.isBlank() || normalized == "und") return ""

    return when (normalized) {
        "en", "eng", "en-us", "en-gb" -> "English"
        "es", "spa", "esp", "es-es", "es-mx", "es-us" -> "Spanish"
        "pt", "por", "pt-br" -> "Portuguese"
        "ar", "ara" -> "Arabic"
        "fr", "fre", "fra" -> "French"
        "de", "ger", "deu" -> "German"
        "it", "ita" -> "Italian"
        "ja", "jpn" -> "Japanese"
        "ko", "kor" -> "Korean"
        "zh", "zho", "chi" -> "Chinese"
        else -> Locale(normalized.take(2)).displayLanguage.replaceFirstChar { it.titlecase(Locale.US) }
    }
}

private fun ExoPlayer.selectTrack(option: TrackOption) {
    trackSelectionParameters = trackSelectionParameters
        .buildUpon()
        .setOverrideForType(TrackSelectionOverride(option.group, listOf(option.trackIndex)))
        .build()
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L

    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
