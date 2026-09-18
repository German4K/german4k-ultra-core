package tv.own.owntv.core.german4k

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Prefer IPv4 over IPv6 for every request.
 *
 * The ticket behind this: "Android shows a black picture, the iPhone plays fine, same WiFi."
 * One of our stream hosts publishes an AAAA record, and several German ISPs hand out IPv6
 * prefixes that cannot reach it — the phone's happy-eyeballs implementation falls back, Android's
 * OkHttp path does not, and the customer sees nothing at all. Sorting A records first costs
 * nothing when IPv6 works (the address is simply tried later) and removes the whole class of
 * report when it doesn't.
 *
 * [inner] stays in charge of the actual lookup, so the customer's own DNS setting (plain or DoH)
 * keeps working exactly as before — this only reorders the answer.
 */
class German4kDns(private val inner: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = inner.lookup(hostname)
        if (addresses.size < 2) return addresses
        val v4 = addresses.filterIsInstance<Inet4Address>()
        if (v4.isEmpty() || v4.size == addresses.size) return addresses
        return v4 + addresses.filter { it !is Inet4Address }
    }
}
