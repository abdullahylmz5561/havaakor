package com.example.havaakor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
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
        PlayScreen()
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

@Composable
fun PlayScreen() {
    val ctx = LocalContext.current
    val synth = remember { ChordSynth() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var hands by remember { mutableStateOf<List<Hand>>(emptyList()) }
    var label by remember { mutableStateOf("—") }
    var keyIdx by remember { mutableIntStateOf(0) }
    var swap by remember { mutableStateOf(false) }
    val keyRef = rememberUpdatedState(keyIdx)
    val swapRef = rememberUpdatedState(swap)

    // Model dosyası yoksa uygulama çökmesin, ekranda açıklama göstersin
    val analyzerResult = remember {
        runCatching {
            HandAnalyzer(ctx) { detected ->
                val st = HandReader.read(detected, swapRef.value)
                synth.setVolume(st.volume)
                synth.setBrightness(st.brightness)
                synth.setChord(ChordLogic.frequencies(st, keyRef.value))
                label = ChordLogic.name(st, keyRef.value)
                hands = detected
            }
        }
    }
    val analyzer = analyzerResult.getOrNull()

    DisposableEffect(Unit) {
        synth.start()
        onDispose {
            synth.stop()
            analyzer?.close()
            executor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            if (analyzer != null) {
                AndroidView(
                    modifier = Modifier.matchParentSize(),
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
                                .setTargetResolution(Size(480, 640))
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
                // El iskeleti (ön kamera aynalı olduğu için x ters çevrilir)
                Canvas(Modifier.matchParentSize()) {
                    for (h in hands) {
                        val pts = h.pts.map { Offset((1f - it.x) * size.width, it.y * size.height) }
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
        }

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChordLogic.noteNames.forEachIndexed { i, n ->
                    FilterChip(
                        selected = keyIdx == i,
                        onClick = { keyIdx = i },
                        label = { Text(n) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Solak modu")
                Spacer(Modifier.padding(horizontal = 8.dp))
                Switch(checked = swap, onCheckedChange = { swap = it })
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Ekranın solundaki el: 1–5 parmak = akor (yumruk = sus), ele sola yatır = minör.\n" +
                    "Sağdaki el: yukarı–aşağı = ses, sağa–sola = tını parlaklığı.",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
