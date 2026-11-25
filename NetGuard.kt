package com.personal.aicomputer.tools

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/** SSRF protection: the agent may only reach public internet hosts, never localhost, the LAN or cloud metadata IPs. */
object NetGuard {
    fun isPublicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet6Address && b.isNotEmpty() && (b[0].toInt() and 0xFE) == 0xFC) return false // fc00::/7 unique local
        if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) return false // 100.64.0.0/10 CGNAT
        if (b.size == 4 && (b[0].toInt() and 0xFF) == 0) return false
        return true
    }

    /** Returns null when the URL is allowed, otherwise a human-readable reason. Performs DNS resolution. */
    fun checkUrl(url: String): String? {
        val uri = try { URI(url.trim()) } catch (e: Exception) { return "Invalid URL." }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Only http and https URLs are allowed (got '${uri.scheme}')."
        val host = uri.host?.lowercase() ?: return "URL has no host."
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) return "Local network hosts are blocked."
        val addrs = try { InetAddress.getAllByName(host) } catch (e: UnknownHostException) { return "Could not resolve host '$host'." }
        if (addrs.any { !isPublicAddress(it) }) return "The host resolves to a private or local address, which is blocked."
        return null
    }
}
