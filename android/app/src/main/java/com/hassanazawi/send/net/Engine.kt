package com.hassanazawi.send.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.hassanazawi.send.data.Files
import com.hassanazawi.send.data.Paired
import com.hassanazawi.send.data.Pending
import com.hassanazawi.send.data.Store
import com.hassanazawi.send.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Everything the phone does on the network. The phone is the only server: the PC connects to it,
 * so the PC never needs an inbound firewall rule. A paired PC keeps one "session" socket open for
 * messages (offers, accepts, cancel, text) and opens data sockets on demand for file bytes.
 */
object Engine {
    private const val TAG = "Send"

    private lateinit var app: Context
    lateinit var store: Store
        private set

    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "send-io").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val order = CopyOnWriteArrayList<String>() // newest first
    private val sessions = ConcurrentHashMap<String, Session>()
    private val texts = CopyOnWriteArrayList<TextMsg>()
    private val queuedTexts = CopyOnWriteArrayList<String>()

    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var pairReq: PairReq? = null
    @Volatile private var ip: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var tickerOn = false
    @Volatile var foreground = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private class Session(val peerId: String, val peerName: String, val wire: Wire) {
        val since = System.currentTimeMillis()
        fun send(o: JSONObject): Boolean = try { wire.writeFrame(o); true } catch (e: Exception) { close(); false }
        fun close() { runCatching { wire.socket.close() } }
    }

    private class PairReq(val id: String, val name: String, val code: String) {
        val answer = CompletableFuture<Boolean>()
    }

    /** Logcat output from apps is dropped on this phone, so debug builds also write a file (run-as ... cat files/log.txt). */
    fun dbg(msg: String, e: Throwable? = null) {
        Log.i(TAG, msg, e)
        if (!com.hassanazawi.send.BuildConfig.DEBUG || !::app.isInitialized) return
        runCatching {
            val f = java.io.File(app.filesDir, "log.txt")
            if (f.length() > 200_000) f.delete()
            f.appendText("${System.currentTimeMillis() % 1_000_000_000} $msg ${e ?: ""}\n" + (e?.stackTraceToString()?.take(1800)?.plus("\n") ?: ""))
        }
    }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        store = Store(app)
        Notifier.init(app)
    }

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (server != null) return
        try {
            val ss = Tls.context().serverSocketFactory.createServerSocket() as SSLServerSocket
            ss.reuseAddress = true
            ss.receiveBufferSize = 1 shl 20
            ss.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
            ss.bind(InetSocketAddress(Proto.PORT), 64)
            server = ss
            lastError = null
            dbg("listening on ${Proto.PORT}, fingerprint ${Tls.fingerprint()}")
            Thread({
                while (!ss.isClosed) {
                    try {
                        val c = ss.accept() as SSLSocket
                        pool.execute { serve(c) }
                    } catch (e: IOException) {
                        dbg("accept failed", e)
                        if (ss.isClosed) break
                    }
                }
            }, "send-accept").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            dbg("server start failed", e)
            lastError = "Can't listen on port ${Proto.PORT}: ${e.message}"
        }
        watchNetwork()
        startTicker()
        publish()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        transfers.values.filter { it.state == TState.ACTIVE || it.state == TState.OFFERED }.forEach { fail(it, "Send was stopped") }
        runCatching { netCb?.let { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCb = null
        tickerOn = false
        publish()
    }

    private fun startTicker() {
        if (tickerOn) return
        tickerOn = true
        scope.launch {
            var n = 0
            while (isActive && tickerOn) {
                delay(250)
                n++
                val now = System.currentTimeMillis()
                var active = 0
                for (t in transfers.values) {
                    if (t.state == TState.ACTIVE) {
                        active++
                        t.sampleSpeed(now)
                        if (now - t.lastActivity > 45_000) fail(t, "Connection lost")
                    }
                }
                if (n % 40 == 0) sessions.values.forEach { it.send(obj("t" to "ping")) }
                if (active > 0) publish()
            }
        }
    }

    private fun watchNetwork() {
        if (netCb != null) {
            refreshIp()
            return
        }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshIp()
            override fun onLost(network: Network) = refreshIp()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = refreshIp()
        }
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb) }
        netCb = cb
        refreshIp()
    }

    private fun refreshIp() {
        val v = findIp()
        if (v != ip) {
            ip = v
            publish()
        }
    }

    @Suppress("DEPRECATION")
    private fun findIp(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress?.let { return it }
        }
        // Phone is the hotspot: no Wi-Fi client network, but an AP interface with a site-local address.
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }

    // ---------------------------------------------------------------- connections

    private fun serve(s: SSLSocket) {
        dbg("connection from ${s.inetAddress}")
        try {
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            val w = Wire(s)
            val first = w.readFrame() ?: return
            when (first.str("op")) {
                "hello" -> w.writeFrame(
                    obj("ok" to true, "v" to Proto.VERSION, "id" to store.deviceId, "name" to store.deviceName, "model" to Build.MODEL)
                )
                "pair" -> doPair(w, first)
                "session" -> doSession(w, s, first)
                "put", "get" -> doData(w, s, first)
                else -> w.writeFrame(errFrame("op"))
            }
        } catch (e: Exception) {
            dbg("connection ended", e)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun authed(f: JSONObject): Paired? {
        val p = store.find(f.str("id")) ?: return null
        return if (safeEquals(p.token, f.str("token"))) p else null
    }

    // ---- pairing

    private fun doPair(w: Wire, f: JSONObject) {
        val id = f.str("id")
        val name = f.str("name").take(60).ifBlank { "PC" }
        val nonce = f.str("nonce")
        if (id.isEmpty() || nonce.length < 8) {
            w.writeFrame(errFrame("bad"))
            return
        }
        val req = PairReq(id, name, pairCode(Tls.fingerprint(), nonce))
        synchronized(this) {
            if (pairReq != null) {
                w.writeFrame(errFrame("busy"))
                return
            }
            pairReq = req
        }
        publish()
        if (!foreground) Notifier.pair(app, name, req.code)
        val allowed = try { req.answer.get(60, TimeUnit.SECONDS) } catch (e: Exception) { false }
        synchronized(this) { pairReq = null }
        Notifier.cancelPair(app)
        if (!allowed) {
            w.writeFrame(errFrame("denied"))
            publish()
            return
        }
        val token = randomHex(32)
        store.addPaired(Paired(id, name, token, System.currentTimeMillis()))
        w.writeFrame(obj("ok" to true, "token" to token, "id" to store.deviceId, "name" to store.deviceName))
        publish()
    }

    fun answerPair(allow: Boolean) {
        pairReq?.answer?.complete(allow)
    }

    // ---- session (control channel)

    private fun doSession(w: Wire, s: Socket, first: JSONObject) {
        val p = authed(first)
        if (p == null) {
            w.writeFrame(errFrame("auth"))
            return
        }
        val sess = Session(p.id, p.name, w)
        sessions.put(p.id, sess)?.close()
        w.writeFrame(okFrame())
        s.soTimeout = 35_000
        onSessionUp(sess)
        try {
            while (true) {
                val m = w.readFrame() ?: break
                onMessage(sess, m)
            }
        } finally {
            sessions.remove(p.id, sess)
            sess.close()
            onSessionDown(sess)
        }
    }

    private fun onSessionUp(sess: Session) {
        publish()
        for (id in order.reversed()) {
            val t = transfers[id] ?: continue
            if (t.dir == Dir.OUT && t.state == TState.QUEUED) offer(t, sess)
        }
        queuedTexts.toList().forEach { sess.send(obj("t" to "text", "text" to it)) }
        queuedTexts.clear()
    }

    private fun onSessionDown(sess: Session) {
        if (!sessions.containsKey(sess.peerId)) {
            transfers.values.filter { it.dir == Dir.OUT && it.state == TState.OFFERED && it.peerId == sess.peerId }
                .forEach { it.state = TState.QUEUED; it.peerId = null }
        }
        publish()
    }

    private fun onMessage(sess: Session, m: JSONObject) {
        when (m.str("t")) {
            "ping" -> sess.send(obj("t" to "pong"))
            "offer" -> onOffer(sess, m)
            "accept" -> transfers[m.str("tid")]?.let { t ->
                if (t.dir == Dir.OUT && t.state == TState.OFFERED) {
                    t.accepted = true
                    t.state = TState.ACTIVE
                    t.started = System.currentTimeMillis()
                    t.lastActivity = t.started
                    publish()
                }
            }
            "reject" -> transfers[m.str("tid")]?.let { fail(it, if (m.str("err") == "space") "Not enough space on the PC" else "Declined") }
            "cancel" -> transfers[m.str("tid")]?.let { cancelLocal(it) }
            "done" -> transfers[m.str("tid")]?.let { t ->
                if (t.dir == Dir.OUT && t.state == TState.ACTIVE) {
                    if (m.optBoolean("ok", true)) finish(t, TState.DONE, null) else fail(t, m.str("err").ifBlank { "The PC could not save the files" })
                }
            }
            "text" -> {
                val txt = m.str("text")
                if (txt.isNotEmpty()) {
                    addText(TextMsg(Dir.IN, txt.take(20_000), System.currentTimeMillis()))
                    if (!foreground) Notifier.text(app, sess.peerName, txt)
                    publish()
                }
            }
        }
    }

    // ---- incoming offers (PC to phone)

    private fun onOffer(sess: Session, m: JSONObject) {
        val tid = m.str("tid")
        val arr: JSONArray = m.optJSONArray("items") ?: return
        if (tid.isEmpty() || transfers.containsKey(tid)) return
        if (arr.length() == 0 || arr.length() > 20_000) {
            sess.send(obj("t" to "reject", "tid" to tid, "err" to "items"))
            return
        }
        val items = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(it, Files.cleanName(o.str("name")), o.optLong("size").coerceAtLeast(0), Files.cleanRel(o.str("rel")), null)
        }
        val total = items.sumOf { it.size }
        val free = runCatching { Environment.getExternalStorageDirectory().usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (total + 64L * 1024 * 1024 > free) {
            sess.send(obj("t" to "reject", "tid" to tid, "err" to "space"))
            return
        }
        val t = Transfer(tid, Dir.IN, sess.peerId, sess.peerName, items)
        t.accepted = true
        t.state = TState.ACTIVE
        t.started = System.currentTimeMillis()
        t.lastActivity = t.started
        add(t)
        sess.send(obj("t" to "accept", "tid" to tid))
        publish()
    }

    // ---- data connections

    private fun doData(w: Wire, s: Socket, first: JSONObject) {
        s.soTimeout = 60_000
        val buf = ByteArray(Proto.BUF)
        var req: JSONObject? = first
        while (req != null) {
            val p = authed(req)
            if (p == null) {
                w.writeFrame(errFrame("auth"))
                return
            }
            when (req.str("op")) {
                "put" -> doPut(w, s, req, p, buf)
                "get" -> doGet(w, s, req, p, buf)
                else -> return
            }
            req = w.readFrame()
        }
    }

    private fun lookup(req: JSONObject, p: Paired, dir: Dir): Pair<Transfer, Item>? {
        val t = transfers[req.str("tid")] ?: return null
        val item = t.items.getOrNull(req.optInt("i", -1)) ?: return null
        if (t.dir != dir || t.peerId != p.id || !t.accepted || t.state != TState.ACTIVE) return null
        return t to item
    }

    private fun doPut(w: Wire, s: Socket, req: JSONObject, p: Paired, buf: ByteArray) {
        val (t, item) = lookup(req, p, Dir.IN) ?: run { w.writeFrame(errFrame("tid")); return }
        if (item.done) {
            w.writeFrame(errFrame("done"))
            return
        }
        t.register(s)
        var pend: Pending? = null
        try {
            t.bytes.addAndGet(-item.got.getAndSet(0)) // a retry starts the file over
            pend = try { Files.create(app, item.name, item.rel) } catch (e: IOException) { w.writeFrame(errFrame("io")); return }
            w.writeFrame(okFrame())
            var left = item.size
            while (left > 0) {
                val n = w.input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("PC closed mid-file")
                pend.out.write(buf, 0, n)
                item.got.addAndGet(n.toLong())
                t.bytes.addAndGet(n.toLong())
                t.lastActivity = System.currentTimeMillis()
                left -= n
            }
            pend.commit()
            pend = null
            item.done = true
            w.writeFrame(okFrame())
            if (t.items.all { it.done }) finish(t, TState.DONE, null) else if (t.items.size < 50) publish()
        } catch (e: Exception) {
            pend?.abort()
            t.bytes.addAndGet(-item.got.getAndSet(0))
            throw e
        } finally {
            t.unregister(s)
        }
    }

    private fun doGet(w: Wire, s: Socket, req: JSONObject, p: Paired, buf: ByteArray) {
        val (t, item) = lookup(req, p, Dir.OUT) ?: run { w.writeFrame(errFrame("tid")); return }
        val uri = item.uri ?: run { w.writeFrame(errFrame("uri")); return }
        t.register(s)
        try {
            val pfd = try { app.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
            if (pfd == null) {
                w.writeFrame(errFrame("open"))
                return
            }
            val size = if (pfd.statSize >= 0) pfd.statSize else item.size
            w.writeFrame(obj("ok" to true, "size" to size))
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { src ->
                var left = size
                while (left > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("source shorter than reported")
                    w.output.write(buf, 0, n)
                    t.bytes.addAndGet(n.toLong())
                    t.lastActivity = System.currentTimeMillis()
                    left -= n
                }
                w.output.flush()
            }
        } finally {
            t.unregister(s)
        }
    }

    // ---------------------------------------------------------------- sending from the phone

    /** Sends picked or shared files to the connected PC (or waits until one connects). */
    fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val items = uris.mapIndexed { i, u ->
            val (name, size) = Files.describe(app, u)
            Item(i, name, size, null, u)
        }
        val sess = activeSession()
        val t = Transfer(randomHex(8), Dir.OUT, sess?.peerId, sess?.peerName ?: "your PC", items)
        add(t)
        if (sess != null) offer(t, sess)
        publish()
    }

    fun sendText(text: String) {
        val v = text.trim()
        if (v.isEmpty()) return
        addText(TextMsg(Dir.OUT, v.take(20_000), System.currentTimeMillis()))
        val sess = activeSession()
        if (sess == null || !sess.send(obj("t" to "text", "text" to v.take(20_000)))) queuedTexts.add(v.take(20_000))
        publish()
    }

    private fun offer(t: Transfer, sess: Session) {
        t.peerId = sess.peerId
        t.peerName = sess.peerName
        val arr = JSONArray()
        for (it in t.items) arr.put(obj("i" to it.i, "name" to it.name, "size" to it.size, "rel" to it.rel))
        if (sess.send(obj("t" to "offer", "tid" to t.tid, "items" to arr))) {
            t.state = TState.OFFERED
            t.lastActivity = System.currentTimeMillis()
        }
    }

    private fun activeSession(): Session? = sessions.values.maxByOrNull { it.since }

    // ---------------------------------------------------------------- transfer bookkeeping

    private fun add(t: Transfer) {
        transfers[t.tid] = t
        order.add(0, t.tid)
        while (order.size > 40) {
            val old = order.removeAt(order.size - 1)
            transfers.remove(old)?.closeAll()
        }
    }

    private fun addText(m: TextMsg) {
        texts.add(0, m)
        while (texts.size > 30) texts.removeAt(texts.size - 1)
    }

    private fun finish(t: Transfer, state: TState, err: String?) {
        if (t.state == TState.DONE || t.state == TState.FAILED || t.state == TState.CANCELLED) return
        t.state = state
        t.error = err
        t.ended = System.currentTimeMillis()
        t.speed = 0
        if (state == TState.DONE) t.bytes.set(t.total)
        publish()
        if (state == TState.DONE && t.dir == Dir.IN && !foreground) {
            Notifier.received(app, t.peerName, t.items.size, t.title)
        }
    }

    private fun fail(t: Transfer, why: String) {
        t.closeAll()
        finish(t, TState.FAILED, why)
    }

    private fun cancelLocal(t: Transfer) {
        t.closeAll()
        finish(t, TState.CANCELLED, null)
    }

    /** User pressed cancel. */
    fun cancel(tid: String) {
        val t = transfers[tid] ?: return
        sessions[t.peerId]?.send(obj("t" to "cancel", "tid" to tid))
        cancelLocal(t)
    }

    fun clearFinished() {
        for (id in order.toList()) {
            val t = transfers[id] ?: continue
            if (t.state == TState.DONE || t.state == TState.FAILED || t.state == TState.CANCELLED) {
                order.remove(id)
                transfers.remove(id)
            }
        }
        publish()
    }

    fun fingerprint(): String = Tls.fingerprint()

    fun forget(peerId: String) {
        store.removePaired(peerId)
        sessions.remove(peerId)?.close()
        publish()
    }

    fun rename(name: String) {
        store.deviceName = name
        publish()
    }

    // ---------------------------------------------------------------- state for the UI

    private fun setLocks(on: Boolean) {
        if (on) {
            if (wifiLock == null) {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "send:wifi").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            }
            if (wakeLock == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "send:transfer").also {
                    it.setReferenceCounted(false)
                    it.acquire(2 * 60 * 60 * 1000L)
                }
            }
        } else {
            runCatching { wifiLock?.release() }
            wifiLock = null
            runCatching { wakeLock?.release() }
            wakeLock = null
        }
    }

    @Synchronized
    private fun publish() {
        if (!::app.isInitialized) return
        val list = order.mapNotNull { transfers[it] }.map { t ->
            TransferUi(
                t.tid, t.dir, t.peerName, t.title, t.items.size, t.total,
                if (t.state == TState.DONE) t.total else t.bytes.get().coerceAtMost(t.total),
                t.speed, t.state, t.error, t.started, t.ended,
            )
        }
        setLocks(list.any { it.state == TState.ACTIVE })
        _ui.value = UiState(
            running = server != null,
            ip = ip,
            deviceName = store.deviceName,
            connected = sessions.values.map { PeerUi(it.peerId, it.peerName, it.wire.socket.inetAddress?.hostAddress ?: "") },
            paired = store.paired().map { PeerUi(it.id, it.name) },
            pair = pairReq?.let { PairUi(it.id, it.name, it.code) },
            transfers = list,
            texts = texts.toList(),
            error = lastError,
        )
    }
}
