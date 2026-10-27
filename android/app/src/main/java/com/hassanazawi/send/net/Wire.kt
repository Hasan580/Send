package com.hassanazawi.send.net

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SEND/1 wire format. Every connection is TLS. A frame is a 4-byte big-endian length followed by
 * UTF-8 JSON. The first frame of a connection says what it is for ("op"); data connections then
 * carry raw file bytes between frames.
 */
object Proto {
    const val PORT = 53420
    const val VERSION = 1
    const val MAX_FRAME = 8 shl 20
    const val BUF = 256 * 1024
}

fun obj(vararg p: Pair<String, Any?>): JSONObject = JSONObject().also { o -> for ((k, v) in p) o.put(k, v) }

/** org.json turns a JSON null into the string "null" in optString; treat it as absent instead. */
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

fun okFrame() = obj("ok" to true)
fun errFrame(why: String) = obj("ok" to false, "err" to why)

class Wire(val socket: Socket) {
    val input = socket.getInputStream()
    val output = socket.getOutputStream()
    private val lock = Any()

    /** Next frame, or null on a clean close between frames. */
    fun readFrame(): JSONObject? {
        val h = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = input.read(h, n, 4 - n)
            if (r < 0) {
                if (n == 0) return null
                throw EOFException("frame header")
            }
            n += r
        }
        val len = ((h[0].toInt() and 0xff) shl 24) or ((h[1].toInt() and 0xff) shl 16) or
            ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
        if (len < 0 || len > Proto.MAX_FRAME) throw IOException("bad frame length $len")
        val b = ByteArray(len)
        var o = 0
        while (o < len) {
            val r = input.read(b, o, len - o)
            if (r < 0) throw EOFException("frame body")
            o += r
        }
        return JSONObject(String(b, Charsets.UTF_8))
    }

    fun writeFrame(o: JSONObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        val all = ByteArray(4 + b.size)
        all[0] = (b.size ushr 24).toByte()
        all[1] = (b.size ushr 16).toByte()
        all[2] = (b.size ushr 8).toByte()
        all[3] = b.size.toByte()
        System.arraycopy(b, 0, all, 4, b.size)
        synchronized(lock) {
            output.write(all)
            output.flush()
        }
    }
}

private val rng = SecureRandom()

fun randomHex(bytes: Int): String {
    val b = ByteArray(bytes)
    rng.nextBytes(b)
    return b.toHex()
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (x in this) sb.append("0123456789abcdef"[(x.toInt() shr 4) and 15]).append("0123456789abcdef"[x.toInt() and 15])
    return sb.toString()
}

fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

/** Constant-time string compare, so a wrong token leaks nothing through timing. */
fun safeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

/**
 * Four digits both screens show while pairing. It is derived from the certificate the phone
 * presents, so a man in the middle (who must present a different certificate) makes the two differ.
 */
fun pairCode(certFingerprintHex: String, nonce: String): String {
    val d = sha256("$certFingerprintHex:$nonce".toByteArray())
    val v = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
        ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
    return (v % 10000).toString().padStart(4, '0')
}
