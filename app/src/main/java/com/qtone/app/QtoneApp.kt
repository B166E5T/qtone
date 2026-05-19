package com.qtone.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

/**
 * Custom Application class that provides a tuned Coil ImageLoader for the
 * entire app. By implementing ImageLoaderFactory, this becomes the default
 * loader for all AsyncImage calls — no manual wiring needed in composables.
 *
 * Why this matters for scroll smoothness:
 *
 * Coil's defaults are aimed at phones with single image lists. For a TV
 * grid that may have hundreds of posters in view across a scroll session,
 * the defaults evict images aggressively. When the user holds D-pad UP
 * after scrolling far DOWN, the top-of-list images have been evicted, and
 * Coil has to decode them from disk (or re-fetch from network) on the main
 * thread, causing visible freezes.
 *
 * The tuning below:
 *   - Memory cache: 30% of available app memory. Larger than default (~15%)
 *     but well within Fire TV's budget. Holds far more poster bitmaps in
 *     RAM so scroll-back doesn't trigger disk decodes.
 *   - Disk cache: 250MB. Generous — IPTV catalogs can be very large.
 *   - All policies enabled. Ensures fetched images go to both caches.
 *   - RGB_565 bitmap config: half the memory cost of ARGB_8888 with no
 *     visible quality loss for poster art on TV. Doubles the effective
 *     memory cache headroom.
 */
class QtoneApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024L * 1024L) // 250 MB
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            // RGB_565 — half the memory footprint vs ARGB_8888. For poster art
            // on a TV display the visual difference is imperceptible.
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            // Allow Coil to use hardware bitmaps where supported. Cheaper
            // GPU upload, less main-thread CPU work during scrolling.
            .allowHardware(true)
            .respectCacheHeaders(false) // We control caching, not the server.
            .build()
    }
}
