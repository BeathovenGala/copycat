package com.copycat.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

data class Laptop(val host: String, val port: Int, val name: String)

class NotPairedException : IOException("Not paired with a laptop")
class UnauthorizedException : IOException("Laptop rejected this phone — pair again")
class PinRequiredException : IOException("This laptop needs a PIN")
class MultipleLaptopsException(val laptops: List<Laptop>) : IOException("Several laptops found — open CopyCat to choose yours")

object Net {
    private const val DISCOVERY_PORT = 47821
    private const val DISCOVERY_WINDOW_MS = 1500
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 30000

    fun discover(context: Context): List<Laptop> {
        val found = linkedMapOf<String, Laptop>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300
            val msg = "COPYCAT_DISCOVER".toByteArray()
            val targets = (listOf(InetAddress.getByName("255.255.255.255")) + listOfNotNull(subnetBroadcast(context)) + interfaceBroadcasts()).distinct()
            repeat(2) {
                targets.forEach { runCatching { socket.send(DatagramPacket(msg, msg.size, it, DISCOVERY_PORT)) } }
            }
            val deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                runCatching {
                    val o = JSONObject(String(packet.data, 0, packet.length))
                    if (o.optString("app") == "copycat") {
                        val host = packet.address.hostAddress ?: return@runCatching
                        found[host] = Laptop(host, o.optInt("port", Prefs.DEFAULT_PORT), o.optString("name", host))
                    }
                }
            }
        }
        return found.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(context: Context): InetAddress? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo ?: return null
        if (dhcp.ipAddress == 0) return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val bytes = ByteArray(4) { i -> (broadcast shr (i * 8) and 0xFF).toByte() }
        return InetAddress.getByAddress(bytes)
    }

    // 255.255.255.255 follows the default route (mobile data when the phone is the hotspot) and
    // dhcpInfo is empty then, so also broadcast on every local interface, including the hotspot's.
    private fun interfaceBroadcasts(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())

    fun ping(host: String, port: Int): String? = runCatching {
        val conn = open("http://$host:$port/ping", "GET")
        conn.inputStream.use { JSONObject(it.reader().readText()).optString("name") }
    }.getOrNull()

    fun pair(laptop: Laptop, pin: String, prefs: Prefs) {
        val conn = open("http://${laptop.host}:${laptop.port}/pair", "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(JSONObject().put("pin", pin).put("device", deviceName()).toString().toByteArray()) }
        when (conn.responseCode) {
            200 -> {
                val body = JSONObject(conn.inputStream.use { it.reader().readText() })
                prefs.host = laptop.host
                prefs.port = laptop.port
                prefs.laptopName = body.optString("name", laptop.name)
                prefs.token = body.getString("token")
            }
            403 -> throw if (pin.isEmpty()) PinRequiredException() else IOException("Wrong PIN")
            429 -> throw IOException("Too many wrong PINs — wait a minute")
            else -> throw IOException("Pairing failed (${conn.responseCode})")
        }
    }

    fun sendText(context: Context, text: String) =
        withRediscovery(context) { base, token ->
            post("$base/text", token, "text/plain; charset=utf-8") { it.write(text.toByteArray()) }
        }

    fun sendFile(context: Context, file: File, name: String, mime: String) =
        withRediscovery(context) { base, token ->
            post("$base/file?name=${URLEncoder.encode(name, "UTF-8")}", token, mime) { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }

    /**
     * Finds the laptop on this Wi-Fi and connects without a PIN (the laptop auto-accepts by default).
     * Once paired, only that laptop is used; switching is left to the user so the phone never
     * starts sending to someone else's laptop on a shared network.
     */
    fun ensurePaired(context: Context): Laptop {
        val prefs = Prefs(context)
        val known = prefs.host?.takeIf { prefs.token != null }
        if (known != null && ping(known, prefs.port) != null) return Laptop(known, prefs.port, prefs.laptopName ?: known)
        val found = discover(context)
        val laptop = if (prefs.token != null) {
            found.firstOrNull { it.name == prefs.laptopName } ?: throw IOException("${prefs.laptopName} not found on this Wi-Fi")
        } else when (found.size) {
            0 -> throw IOException("Laptop not found on this Wi-Fi")
            1 -> found[0]
            else -> throw MultipleLaptopsException(found)
        }
        connectTo(laptop, prefs)
        return laptop
    }

    fun connectTo(laptop: Laptop, prefs: Prefs) {
        if (prefs.token != null && laptop.name == prefs.laptopName) {
            prefs.host = laptop.host
            prefs.port = laptop.port
        } else {
            pair(laptop, "", prefs)
        }
    }

    // The laptop's IP can change (DHCP) and it may forget us, so reconnect once on failure.
    private fun withRediscovery(context: Context, call: (String, String) -> Unit) {
        val prefs = Prefs(context)
        val host = prefs.host
        val token = prefs.token
        if (host != null && token != null) {
            try {
                return call("http://$host:${prefs.port}", token)
            } catch (e: UnauthorizedException) {
                prefs.token = null
            } catch (_: IOException) {
            }
        }
        ensurePaired(context)
        call("http://${prefs.host}:${prefs.port}", prefs.token ?: throw NotPairedException())
    }

    private fun post(url: String, token: String, contentType: String, write: (java.io.OutputStream) -> Unit) {
        val conn = open(url, "POST")
        conn.setRequestProperty("X-CopyCat-Token", token)
        conn.setRequestProperty("X-CopyCat-Device", deviceName())
        conn.setRequestProperty("Content-Type", contentType)
        conn.doOutput = true
        conn.setChunkedStreamingMode(64 * 1024)
        conn.outputStream.use(write)
        when (val code = conn.responseCode) {
            200 -> Unit
            401 -> throw UnauthorizedException()
            else -> throw IOException("Laptop returned $code")
        }
    }

    private fun open(url: String, method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
    }

    private fun deviceName() = Build.MODEL ?: "phone"
}
