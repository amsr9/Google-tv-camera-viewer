package com.home.nvrviewer

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Talks to the Hikvision NVR's ISAPI (HTTP) interface to find out which cameras exist.
 * Video itself is streamed over RTSP by the player, not through this class.
 * All calls are blocking; run them off the main thread.
 */
object NvrClient {

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    /** Returns the camera list: manual count if set, otherwise auto-detected. */
    fun cameras(config: NvrConfig): List<Camera> {
        if (config.manualChannels > 0) {
            return (1..config.manualChannels).map { Camera(it, "Camera $it") }
        }
        return discover(config)
    }

    private fun discover(config: NvrConfig): List<Camera> {
        // NVRs list IP cameras under InputProxy; DVRs and single cameras use Video/inputs.
        val paths = listOf(
            "/ISAPI/ContentMgmt/InputProxy/channels",
            "/ISAPI/System/Video/inputs/channels",
        )
        var lastError: Exception? = null
        for (path in paths) {
            try {
                val cameras = parseChannels(get(config, path))
                if (cameras.isNotEmpty()) return cameras
            } catch (e: AuthException) {
                throw e // wrong password: don't keep trying (NVR may lock the account)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(
            "No cameras found" + (lastError?.message?.let { " ($it)" } ?: "") +
                ". Check ISAPI is enabled, or set the camera count manually in Settings."
        )
    }

    class AuthException(message: String) : IOException(message)

    private fun get(config: NvrConfig, path: String): String =
        String(getBytes(config, path), Charsets.UTF_8)

    /** Fetches one JPEG snapshot of a stream (e.g. 102 = camera 1 sub-stream). */
    fun snapshot(config: NvrConfig, streamId: Int): ByteArray =
        getBytes(config, "/ISAPI/Streaming/channels/$streamId/picture")

    fun getBytes(config: NvrConfig, path: String): ByteArray {
        val url = URL("http://${config.host}:${config.httpPort}$path")

        var conn = open(url)
        var code = conn.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val challenges = authHeaders(conn)
            conn.errorStream?.close()
            conn.disconnect()

            // Newer firmware may offer several Digest challenges (SHA-256 and MD5).
            // Pick one we can answer correctly: a wrong guess counts as a failed login.
            val digests = challenges.filter { it.startsWith("Digest", ignoreCase = true) }
            val digest = digests.firstOrNull { digestAlgorithm(it) == "MD5" }
                ?: digests.firstOrNull { digestAlgorithm(it) == "SHA-256" }
            val auth = if (digest != null) {
                digestHeader(digest, "GET", path, config.username, config.password)
            } else {
                val raw = "${config.username}:${config.password}".toByteArray()
                "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
            }
            conn = open(url)
            conn.setRequestProperty("Authorization", auth)
            code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                conn.disconnect()
                throw AuthException(
                    "Wrong username or password. Note: Hikvision locks the account " +
                        "for ~30 minutes after several failed attempts."
                )
            }
        }
        try {
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $path")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = false
        }

    private fun authHeaders(conn: HttpURLConnection): List<String> =
        conn.headerFields.entries
            .filter { it.key != null && it.key.equals("WWW-Authenticate", ignoreCase = true) }
            .flatMap { it.value }

    private fun digestParams(challenge: String): Map<String, String> =
        Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""")
            .findAll(challenge.substringAfter(' '))
            .associate { m -> m.groupValues[1].lowercase() to m.groupValues[2].ifEmpty { m.groupValues[3] } }

    /** "MD5", "SHA-256", or null for algorithms we don't support. No algorithm means MD5. */
    private fun digestAlgorithm(challenge: String): String? =
        when (digestParams(challenge)["algorithm"]?.uppercase()) {
            null, "MD5" -> "MD5"
            "SHA-256" -> "SHA-256"
            else -> null
        }

    /** RFC 7616 digest auth, which Hikvision uses by default. */
    private fun digestHeader(
        challenge: String,
        method: String,
        uri: String,
        user: String,
        pass: String,
    ): String {
        val params = digestParams(challenge)
        val algorithm = digestAlgorithm(challenge) ?: "MD5"
        fun h(s: String) = hash(algorithm, s)

        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val opaque = params["opaque"]
        val useQop = params["qop"]?.split(',')?.map { it.trim() }?.contains("auth") == true

        val ha1 = h("$user:$realm:$pass")
        val ha2 = h("$method:$uri")
        val nc = "00000001"
        val cnonce = randomHex(16)
        val response = if (useQop) h("$ha1:$nonce:$nc:$cnonce:auth:$ha2") else h("$ha1:$nonce:$ha2")

        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", ")
            append("algorithm=$algorithm, response=\"$response\"")
            if (useQop) append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
        }
    }

    private fun hash(algorithm: String, s: String): String =
        MessageDigest.getInstance(algorithm).digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Reads <InputProxyChannel> / <VideoInputChannel> entries: their <id> and <name>. */
    internal fun parseChannels(xml: String): List<Camera> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val result = mutableListOf<Camera>()
        var channelDepth = -1
        var id: Int? = null
        var name: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (channelDepth < 0 && (tag == "InputProxyChannel" || tag == "VideoInputChannel")) {
                        channelDepth = parser.depth
                        id = null
                        name = null
                    } else if (channelDepth >= 0 && parser.depth == channelDepth + 1) {
                        when (tag) {
                            "id" -> id = parser.nextText().trim().toIntOrNull()
                            "name" -> name = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (channelDepth >= 0 && parser.depth == channelDepth) {
                        val ch = id
                        if (ch != null) {
                            val label = name?.takeIf { it.isNotBlank() } ?: "Camera $ch"
                            result += Camera(ch, label)
                        }
                        channelDepth = -1
                    }
                }
            }
            event = parser.next()
        }
        return result.distinctBy { it.channel }.sortedBy { it.channel }
    }
}
