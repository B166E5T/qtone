package com.qtone.app.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttp client used everywhere in the app that needs HTTP.
 *
 * Why a singleton:
 * - One connection pool, one DNS cache. Channel switches reuse warm
 *   connections instead of opening fresh ones every time.
 * - Consistent behavior between API calls (XtreamClient), TMDB metadata,
 *   and stream playback (ExoPlayer).
 *
 * Why DoH (DNS over HTTPS):
 * - Bypasses the system DNS resolver, which on Fire OS gets corrupted
 *   over long uptime, causing the "login works but streams won't play"
 *   issue. With DoH, stream lookups go through Cloudflare's encrypted
 *   resolver instead of Android's system resolver.
 * - Also bypasses ISP DNS-level blocking (smaller ISPs that only filter
 *   at the DNS layer). IP-level and DPI blocking still affect playback.
 *
 * Fallback: if Cloudflare DoH is unreachable, we fall back to the system
 * resolver so users on unusual network setups don't lose all DNS.
 */
object SharedHttp {

    val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val doh = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .build()

        val resilientDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    doh.lookup(hostname)
                } catch (_: Exception) {
                    // Cloudflare unreachable — fall back to system DNS so
                    // the user isn't fully cut off on unusual networks.
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        return OkHttpClient.Builder()
            .dns(resilientDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Q/1.0")
                        .build()
                )
            }
            .build()
    }
}
