package com.hassanazawi.send.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hassanazawi.send.net.Engine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

private enum class Access { None, Partial, Full }

private const val VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

private fun access(ctx: Context): Access {
    fun ok(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 -> when {
            ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_VIDEO) -> Access.Full
            Build.VERSION.SDK_INT >= 34 && ok(VISUAL_USER_SELECTED) -> Access.Partial
            else -> Access.None
        }
        ok(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.Full
        else -> Access.None
    }
}

private fun wanted(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private data class Media(val id: Long, val video: Boolean, val size: Long, val durationMs: Long) {
    val key: Long get() = id * 2 + if (video) 1 else 0
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
        )
}

/** Every photo and video on the phone, newest first. Only ids and sizes: thumbnails load as cells scroll into view. */
private fun queryMedia(ctx: Context): List<Media> {
    val out = ArrayList<Media>()
    runCatching {
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.SIZE, MediaStore.MediaColumns.DURATION),
            "${FileColumns.MEDIA_TYPE} IN (?, ?)",
            arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString(), FileColumns.MEDIA_TYPE_VIDEO.toString()),
            "${FileColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 8000) {
                out += Media(c.getLong(0), c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO, c.getLong(2), if (c.isNull(3)) 0 else c.getLong(3))
            }
        }
    }
    return out
}

@OptIn(ExperimentalCoroutinesApi::class)
private object Thumbs {
    val cache = LruCache<Long, ImageBitmap>(320)
    val pool: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun GalleryScreen(onClose: () -> Unit, onSystemPicker: () -> Unit) {
    val ctx = LocalContext.current
    var acc by remember { mutableStateOf(access(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { acc = access(ctx) }
    var items by remember { mutableStateOf<List<Media>?>(null) }
    LaunchedEffect(acc) {
        if (acc != Access.None) items = withContext(Dispatchers.IO) { queryMedia(ctx) }
    }

    var filter by remember { mutableIntStateOf(0) } // 0 all, 1 photos, 2 videos
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val all = items.orEmpty()
    val shown = remember(items, filter) { all.filter { filter == 0 || (filter == 1 && !it.video) || (filter == 2 && it.video) } }
    val byKey = remember(items) { all.associateBy { it.key } }
    val total = picked.sumOf { byKey[it]?.size ?: 0L }

    Box(Modifier.fillMaxSize().background(C.canvas).blockTouches()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).tappable(onClick = onClose), contentAlignment = Alignment.Center) { Icon(Ic.Back, C.ink, 22.dp) }
                Txt(buildAnnotatedString { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Photos") } }, T.title, Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                if (shown.isNotEmpty()) {
                    val allPicked = shown.all { it.key in picked }
                    GhostButton(if (allPicked) "None" else "Select all", compact = true) {
                        picked = if (allPicked) picked - shown.map { it.key }.toSet() else picked + shown.map { it.key }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }

            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Photos", "Videos").forEachIndexed { i, label ->
                    val on = filter == i
                    Box(
                        Modifier.clip(CircleShape)
                            .background(if (on) C.ink else C.elevated)
                            .border(1.dp, if (on) C.ink else C.hairlineStrong, CircleShape)
                            .tappable { filter = i }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) { Txt(label, T.caption.copy(fontSize = 13.sp), color = if (on) C.canvas else C.body) }
                }
            }

            if (acc == Access.Partial) {
                SendCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("Showing only the photos you allowed.", T.caption, Modifier.weight(1f))
                        GhostButton("Allow all", compact = true) { ask.launch(wanted()) }
                    }
                }
            }

            when {
                acc == Access.None -> Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    SendCard(Modifier.fillMaxWidth(), padding = PaddingValues(22.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Txt(buildAnnotatedString { append("Show your "); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("photos") }; append(".") }, T.title)
                            Txt("Allow access so you can pick photos and videos here and send them to your PC. They never leave your network.", T.bodySm)
                            PrimaryButton("Allow access", Modifier.fillMaxWidth()) { ask.launch(wanted()) }
                            GhostButton("Use the system picker instead", Modifier.fillMaxWidth()) { onSystemPicker(); onClose() }
                        }
                    }
                }
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("Loading...", T.bodySm) }
                shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Txt(buildAnnotatedString { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Nothing here.") } }, T.title)
                }
                else -> LazyVerticalGrid(
                    GridCells.Fixed(3),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 130.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shown, key = { it.key }) { m ->
                        Cell(m, m.key in picked) { picked = if (m.key in picked) picked - m.key else picked + m.key }
                    }
                }
            }
        }

        AnimatedVisibility(
            picked.isNotEmpty(),
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                    .clip(RoundedCornerShape(R12.xl.dp)).background(C.elevated)
                    .border(1.dp, C.hairlineStrong, RoundedCornerShape(R12.xl.dp))
                    .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("${picked.size} selected", T.itemTitle)
                    Txt(fmtSize(total), T.mono)
                }
                GhostButton("Clear", compact = true) { picked = emptySet() }
                Spacer(Modifier.width(8.dp))
                PrimaryButton("Send", icon = Ic.Up) {
                    Engine.sendUris(picked.mapNotNull { byKey[it]?.uri })
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun Cell(m: Media, selected: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState(Thumbs.cache.get(m.key), m.key) {
        if (value == null) {
            value = withContext(Thumbs.pool) {
                runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(320, 320), null).asImageBitmap() }.getOrNull()
            }?.also { Thumbs.cache.put(m.key, it) }
        }
    }
    val shape = RoundedCornerShape(R12.sm.dp)
    Box(
        Modifier.aspectRatio(1f).clip(shape).background(C.elevated)
            .then(if (selected) Modifier.border(2.dp, C.ink, shape) else Modifier)
            .tappable(onClick = onToggle),
    ) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x55000000)))
        if (m.video) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Txt(duration(m.durationMs), T.mono.copy(fontSize = 11.sp, lineHeight = 14.sp), color = C.ink) }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .then(if (selected) Modifier.background(C.ink) else Modifier.border(1.5.dp, Color(0xB3FFFFFF), CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Ic.Check, C.canvas, 14.dp, width = 2.2f) }
    }
}
