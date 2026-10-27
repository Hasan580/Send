package com.hassanazawi.send.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hassanazawi.send.net.Dir
import com.hassanazawi.send.net.Engine
import com.hassanazawi.send.net.PairUi
import com.hassanazawi.send.net.PeerUi
import com.hassanazawi.send.net.TState
import com.hassanazawi.send.net.TextMsg
import com.hassanazawi.send.net.TransferUi
import com.hassanazawi.send.net.UiState
import com.hassanazawi.send.service.SendService
import kotlinx.coroutines.delay

private enum class Sheet { None, Settings, Text, Gallery }

fun fmtSize(b: Long): String {
    if (b < 1000) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1000.0
    var i = 0
    while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }
    val s = if (v >= 100) "%.0f".format(v) else "%.1f".format(v).removeSuffix(".0")
    return "$s ${u[i]}"
}

@Composable
fun SendRoot(onPickFiles: () -> Unit, onPickMedia: () -> Unit) {
    val s by Engine.ui.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(Sheet.None) }
    BackHandler(sheet != Sheet.None) { sheet = Sheet.None }

    val peer = s.connected.firstOrNull()
    val busy = s.transfers.any { it.state == TState.ACTIVE }
    val glow = when {
        s.error != null -> C.redGlow
        busy -> C.orangeGlow
        peer != null -> C.greenGlow
        else -> C.blueGlow
    }

    Box(Modifier.fillMaxSize().background(C.canvas)) {
        Glow(glow)
        Home(s, peer, onPickFiles, { sheet = Sheet.Gallery }, { sheet = Sheet.Settings }, { sheet = Sheet.Text })

        AnimatedVisibility(sheet == Sheet.Gallery, enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 14 }, exit = fadeOut(tween(120))) {
            GalleryScreen(onClose = { sheet = Sheet.None }, onSystemPicker = onPickMedia)
        }

        AnimatedVisibility(sheet == Sheet.Settings, enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 14 }, exit = fadeOut(tween(120))) {
            SettingsScreen(s) { sheet = Sheet.None }
        }
        AnimatedVisibility(sheet == Sheet.Text, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            TextSheet(connected = peer != null) { sheet = Sheet.None }
        }

        var last by remember { mutableStateOf<PairUi?>(null) }
        if (s.pair != null) last = s.pair
        AnimatedVisibility(s.pair != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            last?.let { PairSheet(it) }
        }
    }
}

@Composable
private fun Home(
    s: UiState,
    peer: PeerUi?,
    onPickFiles: () -> Unit,
    openGallery: () -> Unit,
    openSettings: () -> Unit,
    openText: () -> Unit,
) {
    val ctx = LocalContext.current
    val finished = s.transfers.any { it.state == TState.DONE || it.state == TState.FAILED || it.state == TState.CANCELLED }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("top") {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("send", T.wordmark)
                Spacer(Modifier.weight(1f))
                val (label, dot, pulse) = when {
                    !s.running -> Triple("Off", C.stone, false)
                    peer != null -> Triple("Connected", C.green, false)
                    s.ip == null -> Triple("No Wi-Fi", C.red, false)
                    else -> Triple("Searching", C.blue, true)
                }
                Pill { StatusDot(dot, pulse); Txt(label, T.caption, color = C.body) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).tappable(onClick = openSettings),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.Gear, C.mute, 20.dp) }
            }
        }

        item("hero") {
            Column(Modifier.padding(top = 22.dp, bottom = 10.dp)) {
                val title = buildAnnotatedString {
                    val it = SpanStyle(fontStyle = FontStyle.Italic)
                    when {
                        !s.running -> { append("Send is "); withStyle(it) { append("off") }; append(".") }
                        peer != null -> { append("Send to "); withStyle(it) { append(peer.name) }; append(".") }
                        s.ip == null -> { append("No "); withStyle(it) { append("Wi-Fi") }; append(".") }
                        s.paired.isNotEmpty() -> { append("Looking for "); withStyle(it) { append(s.paired.first().name) }; append(".") }
                        else -> { append("Waiting for your "); withStyle(it) { append("PC") }; append(".") }
                    }
                }
                Txt(title, T.display)
                Spacer(Modifier.height(14.dp))
                val sub = when {
                    !s.running -> "Turn it on so your PC can find this phone."
                    peer != null -> "Connected" + if (peer.ip.isNotEmpty()) " · ${peer.ip}" else ""
                    s.ip == null -> "Join the same network as your PC."
                    else -> "Open Send on Windows. It finds this phone and connects by itself."
                }
                Txt(sub, T.body)
            }
        }

        item("actions") {
            if (!s.running) {
                PrimaryButton("Turn on", Modifier.fillMaxWidth()) { SendService.start(ctx) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Send files", Modifier.weight(1f), icon = Ic.Up, onClick = onPickFiles)
                    GhostButton("Photos", icon = Ic.Image, onClick = openGallery)
                    GhostButton("Text", icon = Ic.Text, onClick = openText)
                }
            }
        }

        item("net") {
            Spacer(Modifier.height(6.dp))
            SendCard(Modifier.fillMaxWidth(), bg = C.deep, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(C.red))
                        Box(Modifier.size(9.dp).clip(CircleShape).background(C.yellow))
                        Box(Modifier.size(9.dp).clip(CircleShape).background(C.green))
                    }
                    KV("phone", s.deviceName.ifBlank { "-" })
                    KV("address", if (s.ip != null) "${s.ip}:53420" else "not on Wi-Fi")
                    KV("pc", peer?.let { it.name + if (it.ip.isNotEmpty()) " · ${it.ip}" else "" } ?: "not connected")
                    KV("security", "TLS 1.3 · pinned key")
                }
            }
        }

        item("activity-head") {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Activity", T.heading)
                Spacer(Modifier.weight(1f))
                if (finished) GhostButton("Clear", compact = true) { Engine.clearFinished() }
            }
        }

        if (s.transfers.isEmpty()) {
            item("empty") {
                SendCard(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt(buildAnnotatedString { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Nothing yet.") } }, T.title)
                        Txt("Files you send or receive show up here. You can also share from any app: pick Send.", T.bodySm)
                    }
                }
            }
        } else {
            items(s.transfers, key = { it.tid }) { t ->
                Box(Modifier.animateItem()) { TransferCard(t) { Engine.cancel(t.tid) } }
            }
        }

        if (s.texts.isNotEmpty()) {
            item("msg-head") { Txt("Messages", T.heading, Modifier.padding(top = 18.dp)) }
            items(s.texts.take(6), key = { "m${it.at}${it.text.hashCode()}" }) { m ->
                Box(Modifier.animateItem()) { TextCard(m) }
            }
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row {
        Txt(k, T.mono, Modifier.width(78.dp), color = C.ash)
        Txt(v, T.mono, color = C.ink, maxLines = 1)
    }
}

@Composable
private fun TransferCard(t: TransferUi, onCancel: () -> Unit) {
    val active = t.state == TState.ACTIVE || t.state == TState.OFFERED || t.state == TState.QUEUED
    val out = t.dir == Dir.OUT
    val title = if (t.count > 1) "${t.title} + ${t.count - 1} more" else t.title
    val frac = if (t.total > 0) t.done.toFloat() / t.total else if (t.state == TState.DONE) 1f else 0f
    val meta = when (t.state) {
        TState.QUEUED -> "Waiting for your PC · ${fmtSize(t.total)}"
        TState.OFFERED -> "Waiting for ${t.peerName} to accept"
        TState.ACTIVE -> "${(frac * 100).toInt()}% of ${fmtSize(t.total)}" + if (t.speed > 0) " · ${fmtSize(t.speed)}/s" else ""
        TState.DONE -> (if (out) "Sent" else "Received") + " · " + fmtSize(t.total) +
            if (t.ended > t.started && t.started > 0) " · %.1fs".format((t.ended - t.started) / 1000.0) else ""
        TState.FAILED -> t.error ?: "Failed"
        TState.CANCELLED -> "Cancelled"
    }
    SendCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(R12.md.dp)).background(C.elevated)
                        .border(1.dp, C.hairline, RoundedCornerShape(R12.md.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (out) Ic.Up else Ic.Down, C.ink, 18.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, T.itemTitle, maxLines = 1)
                    Txt(meta, T.mono, maxLines = 1, color = if (t.state == TState.FAILED) C.red else C.mute)
                }
                Spacer(Modifier.width(8.dp))
                when (t.state) {
                    TState.DONE -> Pill { StatusDot(C.green); Txt("Done", T.caption, color = C.body) }
                    TState.FAILED -> Pill { StatusDot(C.red); Txt("Failed", T.caption, color = C.body) }
                    TState.CANCELLED -> Pill { StatusDot(C.stone); Txt("Stopped", T.caption, color = C.body) }
                    else -> Box(
                        Modifier.size(36.dp).clip(CircleShape).tappable(onClick = onCancel),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.X, C.mute, 16.dp) }
                }
            }
            if (active) {
                Spacer(Modifier.height(12.dp))
                HairBar(if (t.state == TState.ACTIVE) frac else 0f)
            }
        }
    }
}

@Composable
private fun TextCard(m: TextMsg) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    SendCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(R12.md.dp)).background(C.elevated).border(1.dp, C.hairline, RoundedCornerShape(R12.md.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(if (m.dir == Dir.OUT) Ic.Up else Ic.Down, C.ink, 18.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(if (m.dir == Dir.OUT) "You sent" else "From your PC", T.caption)
                Txt(m.text, T.bodySm.copy(color = C.body), maxLines = 5)
            }
            Spacer(Modifier.width(8.dp))
            GhostButton(if (copied) "Copied" else "Copy", compact = true, icon = if (copied) Ic.Check else Ic.Copy) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Send", m.text))
                copied = true
            }
        }
    }
}

// ---------------------------------------------------------------- overlays

@Composable
private fun Scrim(onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss?.invoke() },
        contentAlignment = Alignment.BottomCenter,
    ) { content() }
}

@Composable
private fun PairSheet(p: PairUi) {
    Scrim(null) {
        Box(
            Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
                .clip(RoundedCornerShape(R12.xl.dp)).background(C.elevated)
                .border(1.dp, C.hairlineStrong, RoundedCornerShape(R12.xl.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(22.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(
                    buildAnnotatedString { append("Pair with "); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(p.name) }; append("?") },
                    T.title,
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(R12.lg.dp)).background(C.deep)
                        .border(1.dp, C.hairlineStrong, RoundedCornerShape(R12.lg.dp)).padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt(p.code, T.code) }
                Spacer(Modifier.height(12.dp))
                Txt("This code must be the same on your PC. If it is not, decline.", T.bodySm)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Decline", Modifier.weight(1f)) { Engine.answerPair(false) }
                    PrimaryButton("Allow", Modifier.weight(1f)) { Engine.answerPair(true) }
                }
            }
        }
    }
}

@Composable
private fun SendField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, single: Boolean = true) {
    BasicTextField(
        value, onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = single,
        textStyle = T.bodySm.copy(color = C.ink, fontSize = 15.sp),
        cursorBrush = SolidColor(C.ink),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(R12.md.dp)).background(C.card)
                    .border(1.dp, C.hairlineStrong, RoundedCornerShape(R12.md.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Txt(hint, T.bodySm.copy(fontSize = 15.sp), color = C.stone)
                inner()
            }
        },
    )
}


@Composable
private fun TextSheet(connected: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    Scrim(onClose) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(12.dp).navigationBarsPadding()
                .clip(RoundedCornerShape(R12.xl.dp)).background(C.elevated)
                .border(1.dp, C.hairlineStrong, RoundedCornerShape(R12.xl.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt(buildAnnotatedString { append("Send "); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("text") } }, T.title)
            SendField(text, { text = it }, "Type or paste something for your PC", Modifier.heightIn(min = 110.dp, max = 220.dp), single = false)
            if (!connected) Txt("Your PC is not connected. It will be sent when it connects.", T.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Paste", Modifier.weight(1f)) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { text = it }
                }
                PrimaryButton("Send", Modifier.weight(1f), enabled = text.isNotBlank()) {
                    Engine.sendText(text)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(s: UiState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember(s.deviceName) { mutableStateOf(s.deviceName) }
    val fp = remember { runCatching { Engine.fingerprint() }.getOrDefault("") }

    Column(Modifier.fillMaxSize().background(C.canvas).blockTouches().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).tappable(onClick = onClose), contentAlignment = Alignment.Center) { Icon(Ic.Back, C.ink, 22.dp) }
            Txt(buildAnnotatedString { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Settings") } }, T.title, Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SendCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("This phone", T.itemTitle)
                    Txt("The name your PC shows.", T.caption)
                    SendField(name, { name = it.take(40) }, "Phone name")
                    GhostButton("Save name", enabled = name.isNotBlank() && name != s.deviceName, compact = true) { Engine.rename(name) }
                }
            }
            SendCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("Paired PCs", T.itemTitle)
                    if (s.paired.isEmpty()) {
                        Txt("None yet. Open Send on your PC and it asks here.", T.bodySm)
                    } else {
                        s.paired.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(if (s.connected.any { it.id == p.id }) C.green else C.stone)
                                Spacer(Modifier.width(10.dp))
                                Txt(p.name, T.bodySm.copy(color = C.ink), Modifier.weight(1f), maxLines = 1)
                                GhostButton("Forget", compact = true, tint = C.red) { Engine.forget(p.id) }
                            }
                        }
                    }
                }
            }
            SendCard(Modifier.fillMaxWidth(), bg = C.deep) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Phone key (SHA-256)", T.itemTitle)
                    Txt(fp.chunked(8).chunked(4).joinToString("\n") { it.joinToString(" ") }, T.mono.copy(color = C.body))
                    Txt("Your PC pinned this key when you paired. If it ever changes, Send asks you to pair again.", T.caption)
                }
            }
            SendCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("Background", T.itemTitle)
                    Txt("Send stays ready so your PC can find this phone while the screen is off. Stop it here or from the notification.", T.bodySm)
                    GhostButton("Stop Send", tint = C.red, compact = true, enabled = s.running) {
                        ctx.startService(Intent(ctx, SendService::class.java).setAction(SendService.ACT_STOP))
                        onClose()
                    }
                }
            }
            Txt("Send ${com.hassanazawi.send.BuildConfig.VERSION_NAME} · files go straight between your devices over your own network.", T.caption, Modifier.padding(vertical = 8.dp))
        }
    }
}
