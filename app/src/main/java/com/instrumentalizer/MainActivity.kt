@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.instrumentalizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.*
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) { super.onCreate(s); setContent { App() } }
}

@Composable fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("s", 0) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "system")!!) }
    var quality by remember { mutableStateOf(prefs.getString("q", "high")!!) }
    var keepOn by remember { mutableStateOf(prefs.getBoolean("keep", true)) }
    var page by remember { mutableStateOf("home") }
    var src by remember { mutableStateOf<Uri?>(null) }
    var wav by remember { mutableStateOf<File?>(null) }
    var video by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            src = uri; video = ctx.contentResolver.getType(uri)?.startsWith("video") == true
            progress = 0f; err = null
            scope.launch {
                try {
                    wav = withContext(Dispatchers.Default) {
                        Separator.run(ctx, uri, if (quality == "high") 0.5f else 0.25f) { progress = it } }
                    page = "player"
                } catch (e: Throwable) { err = e.message ?: "Could not process this file" }
                progress = null
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { u ->
        if (u != null) ctx.contentResolver.openOutputStream(u)?.use { o -> wav?.inputStream()?.use { it.copyTo(o) } }
    }
    fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()

    MaterialTheme(if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            when (page) {
                "settings" -> Column(Modifier.padding(24.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    Text("Theme")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system", "light", "dark").forEach { t ->
                            FilterChip(theme == t, { theme = t; put("theme", t) }, { Text(t.replaceFirstChar { it.uppercase() }) }) } }
                    Text("Separation quality")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(quality == "fast", { quality = "fast"; put("q", "fast") }, { Text("Fast") })
                        FilterChip(quality == "high", { quality = "high"; put("q", "high") }, { Text("Studio (slower)") }) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep screen on while playing", Modifier.weight(1f))
                        Switch(keepOn, { keepOn = it; prefs.edit().putBoolean("keep", it).apply() }) }
                    OutlinedButton({ ctx.cacheDir.listFiles()?.forEach { it.delete() }; wav = null }) { Text("Clear temporary files") }
                    Button({ page = if (wav != null) "player" else "home" }) { Text("Back") }
                }
                "player" -> PlayerScreen(src!!, wav!!, video, keepOn, { page = "home" }, { save.launch("instrumental.wav") })
                else -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Instrumentalizer", style = MaterialTheme.typography.headlineLarge)
                    Text("Remove vocals. Change key and tempo. 100% offline.")
                    Spacer(Modifier.height(32.dp))
                    val p = progress
                    if (p != null) {
                        LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                        Text("Removing vocals… ${(p * 100).toInt()}%")
                    } else {
                        Button({ pick.launch(arrayOf("audio/*", "video/*")) }) { Text("Choose audio or video") }
                        if (wav != null) TextButton({ page = "player" }) { Text("Back to last track") }
                    }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton({ page = "settings" }) { Text("Settings") }
                }
            }
        }
    }
}

@Composable fun PlayerScreen(src: Uri, wav: File, video: Boolean, keepOn: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var semi by remember { mutableIntStateOf(0) }
    var tempo by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0) }
    var dur by remember { mutableLongStateOf(1) }
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            val f = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx))
            val a = f.createMediaSource(MediaItem.fromUri(Uri.fromFile(wav)))
            setMediaSource(if (video) MergingMediaSource(f.createMediaSource(MediaItem.fromUri(src)), a) else a)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(p: Boolean) { playing = p }
                override fun onTracksChanged(t: Tracks) {   // always use the instrumental audio, never the original
                    val g = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (g.size > 1 && !g.last().isSelected) trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.last().mediaTrackGroup, 0)).build()
                }
            })
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(Unit) { while (true) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(1); delay(250) } }
    // pitch and speed are independent in the player's time-stretch engine: key changes without changing tempo
    LaunchedEffect(semi, tempo) { player.playbackParameters = PlaybackParameters(tempo, 2f.pow(semi / 12f)) }

    Column(Modifier.fillMaxSize().padding(20.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (video) AndroidView({ PlayerView(it).apply { this.player = player; useController = false; keepScreenOn = keepOn } },
            Modifier.fillMaxWidth().aspectRatio(16 / 9f))
        else Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f), Alignment.Center) { Text("♪ Instrumental", style = MaterialTheme.typography.headlineMedium) }
        Slider(pos.toFloat(), { player.seekTo(it.toLong()) }, valueRange = 0f..dur.toFloat())
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("${pos / 60000}:%02d".format(pos / 1000 % 60)); Text("${dur / 60000}:%02d".format(dur / 1000 % 60)) }
        Button({ if (playing) player.pause() else player.play() }, Modifier.align(Alignment.CenterHorizontally)) { Text(if (playing) "Pause" else "Play") }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Key  ${if (semi > 0) "+" else ""}$semi", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ if (semi > -12) semi-- }) { Text("♭ −") }
                FilledTonalButton({ if (semi < 12) semi++ }) { Text("♯ +") } } }
        Text("Tempo  ${(tempo * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(tempo, { tempo = (it * 20).toInt() / 20f }, valueRange = 0.5f..1.5f)
        OutlinedButton({ semi = 0; tempo = 1f }) { Text("Reset key & tempo") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onSave) { Text("Save instrumental") }
            TextButton(onBack) { Text("New file") } }
    }
}
