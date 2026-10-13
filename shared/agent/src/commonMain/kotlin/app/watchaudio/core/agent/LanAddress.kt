package app.watchaudio.core.agent

/**
 * True if [host] is an IP address literal on a local network: IPv4 private (10/8, 172.16/12,
 * 192.168/16), link-local (169.254/16), shared address space (100.64/10, which VPNs such as
 * Tailscale use) or loopback; IPv6 unique local (fc00::/7), link-local (fe80::/10, with or
 * without a zone) or loopback. Host names and public addresses are refused: the phone only
 * connects to the paired PC on the user's own network or VPN (docs/research/agent-access.md §4).
 */
fun isLanHost(host: String): Boolean {
    val address = host.removePrefix("[").removeSuffix("]")
    ipv4(address)?.let { (a, b) ->
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    val groups = ipv6(address.substringBefore('%')) ?: return false
    val first = groups[0]
    return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80 ||
        (groups.subList(0, 7).all { it == 0 } && groups[7] == 1)
}

/** The first two octets of a dotted-quad IPv4 literal, or null if [text] is not one. */
private fun ipv4(text: String): Pair<Int, Int>? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part ->
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        part.toInt().takeIf { it <= 255 } ?: return null
    }
    return octets[0] to octets[1]
}

/** The eight 16-bit groups of an IPv6 literal (`::` allowed, no embedded IPv4), or null. */
private fun ipv6(text: String): List<Int>? {
    if (text.count { it == ':' } < 2) return null
    val halves = text.split("::")
    if (halves.size > 2) return null
    fun groups(part: String): List<Int>? = if (part.isEmpty()) emptyList() else part.split(':').map { group ->
        if (group.isEmpty() || group.length > 4 || !group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        group.toInt(16)
    }
    val head = groups(halves[0]) ?: return null
    if (halves.size == 1) return head.takeIf { it.size == 8 }
    val tail = groups(halves[1]) ?: return null
    val missing = 8 - head.size - tail.size
    if (missing < 1) return null
    return head + List(missing) { 0 } + tail
}
