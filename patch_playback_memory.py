import sys
import os

# ════════════════════════════════════════════════════════════════════
# Frame drops during VOD playback (measured: ~8 dropped frames/sec vs
# 0.08/sec for VLC on the same file, same device, same hardware decoder).
#
# Evidence from logcat:
#   - Qtone process: 4+ GCs during ~20s of playback (338/385/591/576 ms),
#     heap at 78MB/84MB (7% free), one GC freed 26MB of large objects
#     (bitmaps) right as the first frame rendered.
#   - VLC process: ZERO GCs across 52s of playback (native decoder, no
#     Java heap churn), and it responds to onTrimMemory.
#
# Coil's memory cache is configured at maxSizePercent(0.30) — ~30% of the
# heap held as poster bitmaps. That's good for grid scrolling but stays
# fully allocated during playback, when no posters are visible. The GC
# pressure makes ExoPlayer's renderer miss frame deadlines, producing the
# steady single-frame drops seen in the log.
#
# Changes:
#   1. PlayerActivity.onCreate: clear Coil's memory cache before playback.
#      Posters reload from the 250MB disk cache when the user returns, so
#      there is no visible cost.
#   2. QtoneApp: implement onTrimMemory so the app releases bitmap memory
#      when the system signals pressure (VLC does this; we didn't).
# ════════════════════════════════════════════════════════════════════

player_path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "player", "PlayerActivity.kt")
app_path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "QtoneApp.kt")

for p in [player_path, app_path]:
    if not os.path.exists(p):
        print("ERROR: Could not find " + p)
        sys.exit(1)

# ────────────────────────────────────────────────────────────────
# 1. PlayerActivity — clear the bitmap memory cache on entry
# ────────────────────────────────────────────────────────────────
with open(player_path, "r", encoding="utf-8") as f:
    pc = f.read()

print("=== PlayerActivity.kt ===")

anchor = '        val playerView = PlayerView(this).apply {'
inject = '''        // Release Coil's poster bitmap cache before playback starts.
        // Coil is configured to hold up to 30% of the heap in bitmaps, which
        // is right for scrolling the grid but pure overhead here — no posters
        // are visible during playback. Holding them kept the heap near its
        // limit and triggered repeated 300-600ms GCs that made the video
        // renderer miss frame deadlines (measured ~8 dropped frames/sec).
        // Posters reload from the 250MB disk cache on return, so nothing is
        // lost visually.
        try {
            coil.Coil.imageLoader(this).memoryCache?.clear()
        } catch (_: Throwable) {
            // Never let a cache cleanup failure block playback.
        }

        val playerView = PlayerView(this).apply {'''

if "Release Coil's poster bitmap cache before playback" in pc:
    print("SKIP: PlayerActivity already clears the memory cache")
elif anchor not in pc:
    print("ERROR: could not find the PlayerView creation anchor")
    sys.exit(1)
else:
    pc = pc.replace(anchor, inject, 1)
    with open(player_path, "w", encoding="utf-8") as f:
        f.write(pc)
    print("OK: memory cache cleared at playback start")

# ────────────────────────────────────────────────────────────────
# 2. QtoneApp — honour onTrimMemory
# ────────────────────────────────────────────────────────────────
with open(app_path, "r", encoding="utf-8") as f:
    ac = f.read()

print("")
print("=== QtoneApp.kt ===")

class_anchor = "class QtoneApp : Application(), ImageLoaderFactory {"
trim_method = '''class QtoneApp : Application(), ImageLoaderFactory {

    /**
     * Release bitmap memory when the system reports pressure. Coil holds up
     * to 30% of the heap in poster bitmaps; without this the app never gives
     * any of it back, which kept the heap near its limit during playback and
     * caused GC-induced dropped frames. VLC does the same thing (it logs
     * onTrimMemory level 20 when its UI is hidden).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            val loader = coil.Coil.imageLoader(this)
            when {
                // UI no longer visible, or the system is under real pressure:
                // drop the bitmap cache entirely. It rebuilds from disk.
                level >= TRIM_MEMORY_UI_HIDDEN -> loader.memoryCache?.clear()
                else -> { /* mild pressure — keep the cache */ }
            }
        } catch (_: Throwable) {
            // Trimming is best-effort; never crash on it.
        }
    }
'''

if "override fun onTrimMemory" in ac:
    print("SKIP: onTrimMemory already implemented")
elif class_anchor not in ac:
    print("ERROR: could not find the QtoneApp class declaration")
    sys.exit(1)
else:
    ac = ac.replace(class_anchor, trim_method, 1)
    with open(app_path, "w", encoding="utf-8") as f:
        f.write(ac)
    print("OK: onTrimMemory implemented")

print("")
print("=" * 60)
print("Memory-pressure fixes applied.")
print("")
print("Test on Dark Waters - 2019, then re-run:")
print("  adb logcat -c")
print("  (play ~30s)")
print("  adb logcat -d | grep -E 'TotalFramesDropped|GC freed'")
print("")
print("Expect TotalFramesDropped to fall sharply and the 300-600ms")
print("GCs during playback to mostly disappear.")
print("=" * 60)
