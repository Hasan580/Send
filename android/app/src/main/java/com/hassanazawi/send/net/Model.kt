package com.hassanazawi.send.net

import android.net.Uri
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

enum class Dir { IN, OUT }

/** QUEUED: waiting for a PC to connect. OFFERED: PC asked, no answer yet. */
enum class TState { QUEUED, OFFERED, ACTIVE, DONE, FAILED, CANCELLED }

class Item(val i: Int, val name: String, val size: Long, val rel: String?, val uri: Uri?) {
    val got = AtomicLong()
    @Volatile var done = false
}

class Transfer(
    val tid: String,
    val dir: Dir,
    @Volatile var peerId: String?,
    @Volatile var peerName: String,
    val items: List<Item>,
) {
    @Volatile var state = TState.QUEUED
    @Volatile var error: String? = null
    @Volatile var accepted = false
    val bytes = AtomicLong()
    val total: Long = items.sumOf { it.size }
    val created = System.currentTimeMillis()
    @Volatile var started = 0L
    @Volatile var ended = 0L
    @Volatile var lastActivity = created
    @Volatile var speed = 0L

    private val socks: MutableSet<Closeable> = Collections.synchronizedSet(HashSet())
    private var sampleAt = 0L
    private var sampleBytes = 0L

    fun register(c: Closeable) { socks.add(c) }
    fun unregister(c: Closeable) { socks.remove(c) }

    /** Drops every data connection of this transfer, which aborts the read/write loops using them. */
    fun closeAll() {
        synchronized(socks) {
            socks.forEach { runCatching { it.close() } }
            socks.clear()
        }
    }

    /** Smoothed bytes/second, sampled by the engine ticker. */
    fun sampleSpeed(now: Long) {
        val b = bytes.get()
        if (sampleAt == 0L) { sampleAt = now; sampleBytes = b; return }
        val dt = now - sampleAt
        if (dt < 400) return
        val inst = (b - sampleBytes) * 1000 / dt
        speed = if (speed == 0L) inst else (speed * 6 + inst * 4) / 10
        sampleAt = now
        sampleBytes = b
    }

    val title: String get() = items.firstOrNull()?.name ?: ""
}

data class PeerUi(val id: String, val name: String, val ip: String = "")
data class PairUi(val id: String, val name: String, val code: String)
data class TextMsg(val dir: Dir, val text: String, val at: Long)

data class TransferUi(
    val tid: String,
    val dir: Dir,
    val peerName: String,
    val title: String,
    val count: Int,
    val total: Long,
    val done: Long,
    val speed: Long,
    val state: TState,
    val error: String?,
    val started: Long,
    val ended: Long,
)

data class UiState(
    val running: Boolean = false,
    val ip: String? = null,
    val deviceName: String = "",
    val connected: List<PeerUi> = emptyList(),
    val paired: List<PeerUi> = emptyList(),
    val pair: PairUi? = null,
    val transfers: List<TransferUi> = emptyList(),
    val texts: List<TextMsg> = emptyList(),
    val error: String? = null,
)
