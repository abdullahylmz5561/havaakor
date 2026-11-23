package com.example.havaakor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size as AndroidSize
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent {
            MaterialTheme {
                AppRoot(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        if (i != null && i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            incoming.value = i.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}

@Composable
fun AppRoot(incoming: MutableState<String?>) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    if (granted) {
        PlayScreen(incoming)
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("HavaAkor", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Elinle akor çalmak için kameraya ihtiyaç var. Görüntü cihazdan çıkmaz.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Kamerayı aç")
            }
        }
    }
}

private val HAND_LINES = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16,
    13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17,
)

private val SLOT_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFFD81B60),
)

@Composable
fun PlayScreen(incoming: MutableState<String?>) {
    val ctx = LocalContext.current
    val synth = remember { ChordSynth() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // ---- durum ----
    var hands by remember { mutableStateOf<List<Hand>>(emptyList()) }
    var label by remember { mutableStateOf("—") }
    var gesture by remember { mutableIntStateOf(0) }
    var bank by remember { mutableIntStateOf(0) }
    var swap by remember { mutableStateOf(false) }
    var transpose by remember { mutableIntStateOf(0) }
    var showLegend by remember { mutableStateOf(true) }

    var song by remember { mutableStateOf<Song?>(null) }
    var chordText by remember { mutableStateOf(DEFAULT_CHORDS.joinToString(", ")) }
    val chordNames = remember(chordText) { parseChordList(chordText) }
    val specs = remember(chordNames) { chordNames.map { parseChord(it) } }
    var currentEvent by remember { mutableIntStateOf(-1) }

    var bpm by remember { mutableIntStateOf(90) }
    var strumIdx by remember { mutableIntStateOf(0) }
    var drumIdx by remember { mutableIntStateOf(2) }
    var drumVol by remember { mutableFloatStateOf(0.7f) }
    var rhythmOn by remember { mutableStateOf(false) }
    var instrument by remember { mutableIntStateOf(0) }

    var showLink by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val taps = remember { mutableListOf<Long>() }
    val lastChord = remember { arrayOf("") }
    val listState = rememberLazyListState()

    // Analiz thread'inin okuyacağı güncel değerler
    val swapRef = rememberUpdatedState(swap)
    val specsRef = rememberUpdatedState(specs)
    val transposeRef = rememberUpdatedState(transpose)
    val songRef = rememberUpdatedState(song)
    val eventRef = rememberUpdatedState(currentEvent)

    // Model dosyası yoksa uygulama çökmesin, ekranda açıklama göstersin
    val analyzerResult = remember {
        runCatching {
            HandAnalyzer(ctx) { detected ->
                val st = HandReader.read(detected, swapRef.value)
                synth.setVolume(st.volume)
                synth.setBrightness(st.brightness)
                val idx = if (st.gesture in 1..SLOTS) {
                    (if (st.tilted) SLOTS else 0) + st.gesture - 1
                } else {
                    -1
                }
                val spec = specsRef.value.getOrNull(idx)
                val t = transposeRef.value
                synth.setChord(spec?.frequencies(t) ?: emptyList())
                label = spec?.display(t) ?: "—"
                gesture = st.gesture
                bank = if (st.tilted) 1 else 0
                if (spec != null && spec.original != lastChord[0]) {
                    lastChord[0] = spec.original
                    val ev = songRef.value?.events
                    val nxt = eventRef.value + 1
                    if (ev != null && nxt < ev.size && ev[nxt] == spec.original) {
                        currentEvent = nxt
                    }
                }
                hands = detected
            }
        }
    }
    val analyzer = analyzerResult.getOrNull()

    // ---- işlemler ----
    fun applySong(s: Song) {
        song = s
        chordText = s.chords.take(MAX_CHORDS).joinToString(", ")
        currentEvent = -1
        lastChord[0] = ""
        if (s.bpm > 0) bpm = s.bpm.coerceIn(50, 160)
    }

    fun loadFromUrl(url: String) {
        loading = true
        status = null
        Thread {
            val res = runCatching { SongParser.parseHtml(SongParser.fetch(url)) }
            mainHandler.post {
                loading = false
                val s = res.getOrNull()
                if (s != null) {
                    applySong(s)
                    showLink = false
                    status = null
                } else if (res.isFailure) {
                    status = "Sayfa alınamadı: " + (res.exceptionOrNull()?.message ?: "bağlantı hatası")
                } else {
                    status = "Bu sayfada akor bulunamadı. Sayfadaki akorlu metni kopyalayıp aşağıya yapıştırmayı deneyin."
                }
            }
        }.start()
    }

    fun parsePasted() {
        val s = SongParser.parseText(pasteText, "Yapıştırılan şarkı")
        if (s != null) {
            applySong(s)
            showLink = false
            status = null
        } else {
            status = "Metinde akor bulunamadı."
        }
    }

    fun jumpTo(lineIdx: Int) {
        val s = song ?: return
        for (j in lineIdx until s.lines.size) {
            val m = s.lines[j].marks.firstOrNull()
            if (m != null) {
                currentEvent = m.event - 1
                lastChord[0] = ""
                return
            }
        }
    }

    fun tap() {
        val now = SystemClock.uptimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000L) taps.clear()
        taps.add(now)
        if (taps.size > 6) taps.removeAt(0)
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
            bpm = (60000f / avg).toInt().coerceIn(50, 160)
        }
    }

    // Tarayıcıdan "Paylaş" ile gelen link
    val inc = incoming.value
    LaunchedEffect(inc) {
        if (inc != null) {
            incoming.value = null
            val url = Regex("https?://\\S+").find(inc)?.value
            showLink = true
            if (url != null) {
                urlText = url
                loadFromUrl(url)
            } else {
                pasteText = inc
            }
        }
    }

    LaunchedEffect(bpm) { synth.setBpm(bpm) }
    LaunchedEffect(strumIdx) { synth.setStrum(strumIdx) }
    LaunchedEffect(drumIdx) { synth.setDrums(drumIdx) }
    LaunchedEffect(drumVol) { synth.setDrumVolume(drumVol) }
    LaunchedEffect(rhythmOn) { synth.setRhythm(rhythmOn) }
    LaunchedEffect(instrument) { synth.setInstrument(instrument) }

    // Sıradaki akorun satırına kaydır
    LaunchedEffect(currentEvent, song) {
        val s = song
        if (s != null && s.lines.isNotEmpty()) {
            val n = currentEvent + 1
            val li = if (n >= 0 && n < s.eventLine.size) s.eventLine[n] else s.lines.size - 1
            listState.animateScrollToItem(maxOf(0, li - 1))
        }
    }

    DisposableEffect(Unit) {
        // Uygulama arka plana gidince ses ve ritim tamamen dursun, dönünce temiz başlasın
        val activity = ctx as ComponentActivity
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_PAUSE) {
                    synth.stop()
                    rhythmOn = false
                } else if (event == Lifecycle.Event.ON_RESUME) {
                    synth.start()
                }
            }
        }
        activity.lifecycle.addObserver(observer)
        synth.start()
        onDispose {
            activity.lifecycle.removeObserver(observer)
            synth.stop()
            analyzer?.close()
            executor.shutdown()
        }
    }

    // ---- türetilmiş değerler ----
    val nextChord: String? = song?.events?.getOrNull(currentEvent + 1)
    val nextIdx = if (nextChord != null) chordNames.indexOf(nextChord) else -1
    val nextSlot = if (nextIdx >= 0 && nextIdx / SLOTS == bank) nextIdx % SLOTS else -1
    val currentSlot = if (gesture in 1..SLOTS) gesture - 1 else -1
    val legendNames = chordNames.map { n -> parseChord(n)?.display(transpose) ?: n }
    val nextText: String = when {
        song == null -> "Serbest mod: 7 hazır akor"
        nextChord == null -> "Şarkı bitti"
        else -> {
            val shown = parseChord(nextChord)?.display(transpose) ?: nextChord
            val where = when {
                nextIdx < 0 -> " (listede yok)"
                nextIdx / SLOTS != bank -> " (el " + (if (nextIdx / SLOTS == 1) "yatık" else "dik") + ")"
                else -> ""
            }
            "Sıradaki: $shown$where"
        }
    }

    // ---- ekran ----
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (analyzer != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        val previewView = PreviewView(c).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val providerFuture = ProcessCameraProvider.getInstance(c)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(AndroidSize(480, 640))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build()
                                .also { it.setAnalyzer(executor, analyzer) }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                c as ComponentActivity,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                analysis,
                            )
                        }, ContextCompat.getMainExecutor(c))
                        previewView
                    },
                )
                // El iskeleti. Görüntü 3:4, kare alana FILL_CENTER ile sığdırıldığı için kırpmayı hesaba katar.
                Canvas(Modifier.fillMaxSize()) {
                    val vw = size.width
                    val vh = size.height
                    val imgAspect = 0.75f
                    val dispW: Float
                    val dispH: Float
                    if (vw / vh > imgAspect) {
                        dispW = vw
                        dispH = vw / imgAspect
                    } else {
                        dispH = vh
                        dispW = vh * imgAspect
                    }
                    val offX = (dispW - vw) / 2f
                    val offY = (dispH - vh) / 2f
                    for (h in hands) {
                        if (h.pts.size < 21) continue
                        val pts = h.pts.map { Offset((1f - it.x) * dispW - offX, it.y * dispH - offY) }
                        for ((a, b) in HAND_LINES) {
                            drawLine(Color.White.copy(alpha = 0.7f), pts[a], pts[b], strokeWidth = 3f)
                        }
                        pts.forEach { drawCircle(Color(0xFFFFB300), 6f, it) }
                    }
                }
            } else {
                Text(
                    "El takip modeli bulunamadı.\napp/src/main/assets/hand_landmarker.task dosyasını ekleyin (README'ye bakın).",
                    modifier = Modifier.padding(24.dp),
                    color = Color.Red,
                )
            }
            if (showLegend) {
                GestureLegend(
                    names = legendNames,
                    bank = bank,
                    current = currentSlot,
                    next = nextSlot,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song?.title ?: "Şarkı yüklenmedi",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(nextText, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { instrument = 1 - instrument },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text(Instruments.names[instrument], maxLines = 1) }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { showLink = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Şarkı linki", maxLines = 1) }
            Button(
                onClick = { rhythmOn = !rhythmOn },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (rhythmOn) "Ritim ■" else "Ritim ▶", maxLines = 1) }
            OutlinedButton(
                onClick = { showSettings = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Ayarlar", maxLines = 1) }
        }
        Spacer(Modifier.height(6.dp))

        val s = song
        if (s == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Bir akor sayfasının linkini girin; sayfadaki akorlar parmaklara dağıtılır.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Şimdilik 7 hazır akor çalıyor: 1–5 parmak, rock işareti ve shaka. El yatıkken ikinci set.",
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                itemsIndexed(s.lines) { idx, line ->
                    SongLineView(
                        line = line,
                        cur = currentEvent,
                        nxt = currentEvent + 1,
                        chordNames = chordNames,
                        transpose = transpose,
                        onClick = { jumpTo(idx) },
                    )
                }
            }
        }
    }

    // ---- link dialogu ----
    if (showLink) {
        AlertDialog(
            onDismissRequest = { showLink = false },
            title = { Text("Şarkı linki") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("Akor sayfasının linki") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        label = { Text("veya akorlu metni yapıştırın") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (loading) {
                        Spacer(Modifier.height(8.dp))
                        Text("Yükleniyor…")
                    }
                    val st = status
                    if (st != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(st, color = Color.Red, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { loadFromUrl(urlText) },
                        enabled = !loading && urlText.isNotBlank(),
                    ) { Text("Linkten getir") }
                    TextButton(
                        onClick = { parsePasted() },
                        enabled = pasteText.isNotBlank(),
                    ) { Text("Metinden") }
                }
            },
            dismissButton = { TextButton(onClick = { showLink = false }) { Text("Kapat") } },
        )
    }

    // ---- ayarlar dialogu ----
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Ayarlar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Tempo: $bpm BPM")
                    Slider(
                        value = bpm.toFloat(),
                        onValueChange = { bpm = it.toInt() },
                        valueRange = 50f..160f,
                    )
                    OutlinedButton(onClick = { tap() }) { Text("TAP: şarkıyla birlikte vur") }

                    Spacer(Modifier.height(12.dp))
                    Text("Ses")
                    ChipRow(Instruments.names, instrument) { instrument = it }

                    Spacer(Modifier.height(12.dp))
                    Text("Vuruş kalıbı")
                    ChipRow(Rhythms.strumNames, strumIdx) { strumIdx = it }

                    Spacer(Modifier.height(12.dp))
                    Text("Davul")
                    ChipRow(Rhythms.drumNames, drumIdx) { drumIdx = it }
                    Text("Davul sesi")
                    Slider(value = drumVol, onValueChange = { drumVol = it }, valueRange = 0f..1f)

                    Spacer(Modifier.height(12.dp))
                    val sign = if (transpose > 0) "+" else ""
                    Text("Ton: $sign$transpose yarım ses")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { transpose = (transpose - 1).coerceAtLeast(-6) }) { Text("−") }
                        OutlinedButton(onClick = { transpose = (transpose + 1).coerceAtMost(6) }) { Text("+") }
                        OutlinedButton(onClick = { transpose = 0 }) { Text("Sıfırla") }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Solak modu", modifier = Modifier.weight(1f))
                        Switch(checked = swap, onCheckedChange = { swap = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Jest rehberi", modifier = Modifier.weight(1f))
                        Switch(checked = showLegend, onCheckedChange = { showLegend = it })
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = chordText,
                        onValueChange = { chordText = it },
                        label = { Text("Akor listesi (parmak sırasıyla)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "En çok $MAX_CHORDS akor. 1–5 parmak = ilk beş akor, rock = 6., shaka = 7.; el yatıkken 8.–14. akorlar.",
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Tamam") } },
        )
    }
}

@Composable
fun ChipRow(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { i, n ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(n) })
        }
    }
}

@Composable
fun GestureLegend(names: List<String>, bank: Int, current: Int, next: Int, modifier: Modifier) {
    Row(
        modifier.background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (s in 0 until SLOTS) {
            GestureCard(
                slot = s,
                name = names.getOrNull(bank * SLOTS + s),
                isCurrent = s == current,
                isNext = s == next,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun GestureCard(slot: Int, name: String?, isCurrent: Boolean, isNext: Boolean, modifier: Modifier) {
    val color = SLOT_COLORS[slot]
    val shape = RoundedCornerShape(6.dp)
    var m = modifier.clip(shape).background(if (isCurrent) color else Color(0x33FFFFFF))
    if (isNext) m = m.border(2.dp, Color(0xFFFFEB3B), shape)
    Column(m.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 30.dp, height = 26.dp)) {
            val shape5 = GESTURE_SHAPES[slot]
            // Soldan sağa: serçe, yüzük, orta, işaret, başparmak (aynalı görüntüdeki sol el gibi)
            val order = intArrayOf(4, 3, 2, 1, 0)
            val gap = size.width * 0.06f
            val bw = (size.width - gap * 4f) / 5f
            for (j in 0 until 5) {
                val ext = shape5[order[j]]
                val h = size.height * (if (ext) 1f else 0.35f)
                drawRoundRect(
                    color = if (ext) Color.White else Color(0x66FFFFFF),
                    topLeft = Offset(j * (bw + gap), size.height - h),
                    size = Size(bw, h),
                    cornerRadius = CornerRadius(bw / 2f, bw / 2f),
                )
            }
        }
        Text(
            name ?: "—",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun SongLineView(
    line: SongLine,
    cur: Int,
    nxt: Int,
    chordNames: List<String>,
    transpose: Int,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)) {
        if (line.marks.isNotEmpty()) {
            val annotated = buildAnnotatedString {
                var col = 0
                for (m in line.marks) {
                    val shown = parseChord(m.chord)?.display(transpose) ?: m.chord
                    val pad = maxOf(m.pos - col, if (col > 0) 1 else 0)
                    append(" ".repeat(pad))
                    val idx = chordNames.indexOf(m.chord)
                    val color = if (idx >= 0) SLOT_COLORS[idx % SLOTS] else Color.Gray
                    val style = if (m.event == cur) {
                        SpanStyle(color = Color.White, background = color, fontWeight = FontWeight.Bold)
                    } else if (m.event == nxt) {
                        SpanStyle(color = color, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
                    } else {
                        SpanStyle(color = color, fontWeight = FontWeight.Bold)
                    }
                    pushStyle(style)
                    append(shown)
                    pop()
                    col += pad + shown.length
                }
            }
            Text(annotated, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        if (line.text.isNotBlank()) {
            Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        } else if (line.marks.isEmpty()) {
            Spacer(Modifier.height(8.dp))
        }
    }
}
