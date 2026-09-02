import sys
import os

# ════════════════════════════════════════════════════════════════════
# Late-frame tolerance for VOD playback.
#
# Diagnosis: on HEVC Main 10 files with B-frames (e.g. "Dark Waters -
# 2019"), the hardware decoder on Fire TV Stick 4K Max runs marginally
# behind realtime. ExoPlayer's MediaCodecVideoRenderer discards any frame
# more than 30ms late to protect A/V sync, producing a steady trickle of
# isolated single-frame drops (measured: 450 drops / 97s, never two
# consecutive) that reads as stop-motion.
#
# VLC plays the same file smoothly on the same device with the same
# hardware decoder — because it displays late frames instead of dropping
# them. The observable trade: Qtone has perfect lip sync but stutters;
# VLC is smooth with a slight, stable lip-sync offset.
#
# This patch widens the drop threshold from 30ms to 100ms for VOD only:
#   - Marginally late frames are displayed  -> smooth video
#   - Frames beyond 100ms late are still dropped -> sync drift is BOUNDED,
#     unlike VLC's unbounded policy. 100ms sits near the edge of human
#     detectability (broadcast tolerance is roughly -125ms to +45ms).
#
# Live TV is untouched — it keeps the default 30ms behavior.
#
# NOTE: Media3 1.4.x moved frame-release logic into VideoFrameReleaseControl.
# If shouldDropOutputBuffer is not overridable in your version, this will
# fail to compile — paste the error and we'll switch to the
# FrameTimingEvaluator approach instead.
# ════════════════════════════════════════════════════════════════════

factory_path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "player", "TolerantRenderersFactory.kt")
player_path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "player", "PlayerActivity.kt")

if not os.path.exists(player_path):
    print("ERROR: Could not find " + player_path)
    sys.exit(1)

# ── 1. Create the custom renderers factory ──
factory_src = '''package com.qtone.app.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * A RenderersFactory that tolerates late video frames instead of dropping
 * them at ExoPlayer's default 30ms threshold.
 *
 * Why this exists:
 * Some titles (HEVC Main 10 with B-frames) decode marginally slower than
 * realtime on Fire TV hardware. ExoPlayer's default policy discards any
 * frame more than 30ms behind the audio clock, which keeps lip sync exact
 * but produces continuous single-frame drops — visible as stop-motion.
 * VLC plays the same files smoothly because it displays late frames and
 * lets sync drift slightly instead.
 *
 * We take a bounded middle ground: display frames up to LATE_THRESHOLD_US
 * late (so marginal cases play smoothly), but still drop anything beyond
 * that, which caps how far A/V sync can slip. Unlike VLC's policy, the
 * drift can never exceed the threshold.
 *
 * Audio renderers (including the FFmpeg extension) are built by the parent
 * class and are unaffected.
 */
@UnstableApi
class TolerantRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(
            object : MediaCodecVideoRenderer(
                context,
                codecAdapterFactory,
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            ) {
                override fun shouldDropOutputBuffer(
                    earlyUs: Long,
                    elapsedRealtimeUs: Long,
                    isLastBuffer: Boolean
                ): Boolean {
                    // Default is -30_000 (30ms). We allow frames to be up to
                    // LATE_THRESHOLD_US late before discarding them.
                    return earlyUs < -LATE_THRESHOLD_US && !isLastBuffer
                }
            }
        )
    }

    companion object {
        /**
         * How late (in microseconds) a frame may be and still be displayed.
         * 100_000us = 100ms. Raise for smoother playback at the cost of more
         * potential sync drift; lower for tighter sync at the cost of more
         * dropped frames. The ExoPlayer default is 30_000us.
         */
        const val LATE_THRESHOLD_US = 100_000L
    }
}
'''

if os.path.exists(factory_path):
    print("SKIP: TolerantRenderersFactory.kt already exists")
else:
    with open(factory_path, "w", encoding="utf-8") as f:
        f.write(factory_src)
    print("OK: created TolerantRenderersFactory.kt")

# ── 2. Point PlayerActivity at it (VOD only) ──
with open(player_path, "r", encoding="utf-8") as f:
    pc = f.read()

print("")
print("=== PlayerActivity.kt ===")

old = "        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)"
new = "        val renderersFactory = com.qtone.app.player.TolerantRenderersFactory(this)"

if "TolerantRenderersFactory(this)" in pc:
    print("SKIP: already using TolerantRenderersFactory")
elif old not in pc:
    print("ERROR: could not find the DefaultRenderersFactory line in PlayerActivity")
    sys.exit(1)
else:
    pc = pc.replace(old, new, 1)
    with open(player_path, "w", encoding="utf-8") as f:
        f.write(pc)
    print("OK: VOD player now uses the tolerant factory")

print("")
print("=" * 60)
print("Late-frame tolerance applied to VOD only (100ms).")
print("")
print("Test:")
print("  1. Dark Waters - should now play smoothly")
print("  2. A normal H.264 movie - should be unchanged (its frames")
print("     are never late, so the threshold never engages)")
print("  3. Live TV - untouched, still uses the default renderer")
print("")
print("Measure with:")
print("  adb logcat -d | grep -E 'TotalFramesDropped|TotalVideoPlaybackTimeMs'")
print("")
print("To tune: edit LATE_THRESHOLD_US in TolerantRenderersFactory.kt")
print("=" * 60)
