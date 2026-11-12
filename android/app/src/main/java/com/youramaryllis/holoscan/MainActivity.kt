package com.youramaryllis.holoscan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    // Model loading, model swaps and frame analysis all run on this one thread,
    // so a model is never closed while a frame is using it.
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    // Network and file work for refresh / export / import, off the analysis thread.
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val embedder = AtomicReference<Embedder>()
    private val settings by lazy { Settings(this) }
    private var prices by mutableStateOf(PriceBook("", "", emptyMap()))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { App() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute { embedder.get()?.close() }
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
    }

    @Composable
    private fun App() {
        var granted by remember {
            mutableStateOf(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

        var loaded by remember { mutableStateOf<CardIndex?>(null) }
        var loadError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            // Model + 20k-card index take a few seconds; load off the main thread.
            analysisExecutor.execute {
                try {
                    embedder.set(Embedder(Assets.modelFile(this@MainActivity, settings.model).absolutePath))
                    val idx = Assets.loadIndex(this@MainActivity)
                    // Prices are optional: a missing or bad snapshot just shows no prices.
                    // Prices refreshed in the app are laid over the bundled snapshot.
                    val book = runCatching { PriceSources.default(this@MainActivity).load() }.getOrDefault(prices)
                    val refreshed = RefreshedPrices(this@MainActivity).load()
                    runOnUiThread {
                        prices = refreshed?.let { book.overlay(it) } ?: book
                        loaded = idx
                    }
                } catch (t: Throwable) {
                    runOnUiThread { loadError = t.toString() }
                }
            }
        }

        when {
            !granted -> Message("HoloScan needs the camera to identify cards.") {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
            loadError != null -> Message("Could not load the card model:\n$loadError")
            loaded == null -> Message("Loading card database…")
            else -> Screens(loaded!!)
        }
    }

    @Composable
    private fun Screens(index: CardIndex) {
        val store = remember { CollectionStore(this) }
        val byId = remember(index) { index.cards.associateBy { it.id } }
        var collection by remember { mutableStateOf(store.load()) }
        var showCollection by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var modelName by remember { mutableStateOf(settings.model) }
        var autoAddSeconds by remember { mutableStateOf(settings.autoAddSeconds) }
        var buttonsLeft by remember { mutableStateOf(settings.buttonsLeft) }
        BackHandler(enabled = showCollection || showSettings) { showCollection = false; showSettings = false }
        when {
            showSettings -> SettingsScreen(byId, collection, modelName, autoAddSeconds, buttonsLeft,
                onModel = { modelName = it },
                onAutoAdd = { settings.autoAddSeconds = it; autoAddSeconds = it },
                onButtonsLeft = { settings.buttonsLeft = it; buttonsLeft = it },
                onCollection = { collection = store.replace(it) },
                onImportedSettings = {
                    autoAddSeconds = settings.autoAddSeconds
                    buttonsLeft = settings.buttonsLeft
                    // The model setting is applied on the next app start.
                },
                onClose = { showSettings = false })
            showCollection -> CollectionScreen(byId, collection,
                onChange = { id, d -> collection = store.add(id, d) },
                onClose = { showCollection = false })
            else -> ScanScreen(index, collection.values.sum(), modelName, autoAddSeconds, buttonsLeft,
                onChange = { id, d -> collection = store.add(id, d) },
                onOpenCollection = { showCollection = true },
                onOpenSettings = { showSettings = true })
        }
    }

    @Composable
    private fun SettingsScreen(
        byId: Map<String, Card>,
        collection: Map<String, Int>,
        modelName: String,
        autoAddSeconds: Int,
        buttonsLeft: Boolean,
        onModel: (String) -> Unit,
        onAutoAdd: (Int) -> Unit,
        onButtonsLeft: (Boolean) -> Unit,
        onCollection: (Map<String, Int>) -> Unit,
        onImportedSettings: () -> Unit,
        onClose: () -> Unit,
    ) {
        var switching by remember { mutableStateOf(false) }
        var confirmReset by remember { mutableStateOf(false) }
        var pendingImport by remember { mutableStateOf<Backup.Contents?>(null) }

        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) exportTo(uri, collection, byId)
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ioExecutor.execute {
                val result = runCatching { contentResolver.openInputStream(uri)!!.use { Backup.import(it) } }
                runOnUiThread {
                    result.onSuccess { pendingImport = it }
                        .onFailure { toast("Import failed: ${it.message}") }
                }
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)
            .verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done") }
            }
            Spacer(Modifier.height(12.dp))
            Heading("Recognition model")
            listOf(
                Assets.MODEL_INT8 to ("Fast (int8)" to "Default. Smaller, quantized weights; same accuracy in testing, much faster."),
                Assets.MODEL_FULL to ("Full (fp32)" to "Original weights. Slower; use to compare if a card keeps being misread."),
            ).forEach { (name, text) ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !switching && name != modelName) {
                        switching = true
                        analysisExecutor.execute {
                            val old = embedder.getAndSet(Embedder(Assets.modelFile(this@MainActivity, name).absolutePath))
                            old?.close()
                            settings.model = name
                            runOnUiThread { onModel(name); switching = false }
                        }
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = name == modelName, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(text.first, color = Color.White, fontSize = 15.sp)
                        Note(text.second)
                    }
                }
            }
            if (switching) Text("Loading model…", color = AMBER, fontSize = 12.sp)

            Spacer(Modifier.height(20.dp))
            Heading("Auto-add")
            Note("Add the detected card after holding it this long, unless you tap ✓ or ✕ first. " +
                "Only matches of ${pct(CardLock.CONFIDENT)} or more are added automatically.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                AUTO_ADD_CHOICES.forEach { sec ->
                    Choice(if (sec == 0) "Off" else "$sec s", sec == autoAddSeconds) { onAutoAdd(sec) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Heading("✓ / ✕ buttons")
            Note("Which side of the scan screen, for the thumb holding the phone.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Choice("Left", buttonsLeft) { onButtonsLeft(true) }
                Choice("Right", !buttonsLeft) { onButtonsLeft(false) }
            }

            Spacer(Modifier.height(20.dp))
            Heading("Collection data")
            Note("Export saves a .zip of CSV files (collection.csv, settings.csv) that you can copy to " +
                "another phone and import there, or open in a spreadsheet. There's no account or cloud copy: " +
                "the collection is only on this phone (Refresh prices sends just the card ids to the price sites).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = {
                    exporter.launch("holoscan-backup-${LocalDate.now()}.zip")
                }) { Text("Export") }
                OutlinedButton(onClick = {
                    importer.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "*/*"))
                }) { Text("Import") }
                OutlinedButton(enabled = collection.isNotEmpty(), onClick = { confirmReset = true }) {
                    Text("Reset", color = if (collection.isNotEmpty()) RED else Color.Gray)
                }
            }
        }

        if (confirmReset) AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset collection?") },
            text = { Text("This deletes all ${collection.values.sum()} cards from the collection on this phone. " +
                "Export first if you might want them back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onCollection(emptyMap())
                    Log.i(TAG, "collection reset")
                    toast("Collection reset")
                }) { Text("Delete all", color = RED) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )

        pendingImport?.let { imp ->
            val n = imp.collection.values.sum()
            AlertDialog(
                onDismissRequest = { pendingImport = null },
                title = { Text("Import $n cards?") },
                text = {
                    Text("${imp.collection.size} different cards" +
                        (imp.collection.keys.count { it !in byId }.takeIf { it > 0 }
                            ?.let { " ($it not in this app's card list; kept, but not shown)" } ?: "") +
                        ". Replace the current collection (${collection.values.sum()} cards), or add to it?")
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = {
                            pendingImport = null
                            onCollection(imp.collection)
                            settings.import(imp.settings)
                            onImportedSettings()
                            Log.i(TAG, "import replace $n cards")
                            toast("Imported $n cards")
                        }) { Text("Replace") }
                        TextButton(onClick = {
                            pendingImport = null
                            val merged = collection.toMutableMap()
                            imp.collection.forEach { (id, c) -> merged[id] = (merged[id] ?: 0) + c }
                            onCollection(merged)
                            Log.i(TAG, "import add $n cards")
                            toast("Added $n cards")
                        }) { Text("Add to current") }
                        TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                    }
                },
            )
        }
    }

    private fun exportTo(uri: Uri, collection: Map<String, Int>, byId: Map<String, Card>) {
        val stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'"))
        ioExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri)!!.use { Backup.export(it, collection, byId, settings.export(), stamp) }
            }
            runOnUiThread {
                result.onSuccess { toast("Exported ${collection.values.sum()} cards") }
                    .onFailure { toast("Export failed: ${it.message}") }
            }
        }
    }

    @Composable
    private fun ScanScreen(
        index: CardIndex,
        collectionCount: Int,
        modelName: String,
        autoAddSeconds: Int,
        buttonsLeft: Boolean,
        onChange: (String, Int) -> Unit,
        onOpenCollection: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val haptics = LocalHapticFeedback.current
        val viewSize = remember { AtomicReference(0 to 0) }
        val paused = remember { AtomicBoolean(false) }
        val lock = remember { CardLock(autoAddSeconds * 1000L) }
        lock.autoAddMs = autoAddSeconds * 1000L
        val change by rememberUpdatedState(onChange)
        var result by remember { mutableStateOf<ScanResult?>(null) }
        var locked by remember { mutableStateOf<Locked?>(null) }
        var picking by remember { mutableStateOf(false) }
        var toast by remember { mutableStateOf<String?>(null) }
        val provider = remember { AtomicReference<ProcessCameraProvider?>() }
        val disposed = remember { AtomicBoolean(false) }

        // The camera is bound to the activity, so stop analysing (and auto-adding)
        // when the scan screen is left for the collection or settings.
        DisposableEffect(Unit) {
            onDispose { disposed.set(true); paused.set(true); provider.get()?.unbindAll() }
        }

        fun added(card: Card, how: String) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            toast = "Added ${card.name} (${card.set})$how"
        }

        fun closePicker() {
            picking = false
            if (locked?.added == false) lock.restartTimer(SystemClock.elapsedRealtime())
            locked = lock.state
            paused.set(false)
            Log.i(TAG, "picker cancelled")
        }

        fun pick(card: Card) {
            val l = locked ?: return
            if (!(l.added && l.card.id == card.id)) {
                // Picked after an auto-add of a different card: swap it, don't keep both.
                if (l.added) change(l.card.id, -1)
                change(card.id, 1)
                added(card, if (l.added) " instead of ${l.card.name}" else "")
            }
            Log.i(TAG, "picked ${card.id}" + if (l.added && l.card.id != card.id) " replacing ${l.card.id}" else "")
            lock.choose(card)
            locked = lock.state
            picking = false
            paused.set(false)
        }

        BackHandler(enabled = picking) { closePicker() }

        Box(Modifier.fillMaxSize().onSizeChanged { viewSize.set(it.width to it.height) }) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        if (disposed.get()) return@addListener   // screen left before the camera was ready
                        val p = providerFuture.get().also { provider.set(it) }
                        // Same 4:3 aspect for preview and analysis so the on-screen
                        // guide maps onto the analysed frame.
                        val selector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(1440, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                        val preview = Preview.Builder().setResolutionSelector(selector).build()
                            .also { it.surfaceProvider = view.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(selector)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor, CardAnalyzer(embedder, index, viewSize, paused) { r ->
                            runOnUiThread {
                                if (paused.get()) return@runOnUiThread   // picker opened while this frame ran
                                result = r
                                val before = lock.state
                                val autoAdd = lock.update(r.matches, SystemClock.elapsedRealtime())
                                locked = lock.state
                                logLock(before, lock.state, r, autoAdd)
                                if (autoAdd) locked?.card?.let { c -> change(c.id, 1); added(c, " automatically") }
                            }
                        })
                        p.unbindAll()
                        p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                },
            )
            GuideOverlay(added = locked?.added == true)

            val r = result
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HoloScan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenCollection) { Text("Collection ($collectionCount)") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
                if (r != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(r.input.asImageBitmap(), "model input",
                            Modifier.size(width = 40.dp, height = 55.dp))
                        Spacer(Modifier.width(8.dp))
                        val label = if (modelName == Assets.MODEL_INT8) "int8" else "full"
                        Text("model input · $label · ${r.millis} ms · top ${pct(r.matches.first().score)}",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }

            val l = locked
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Color(0xE6101418), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .navigationBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val buttons = @Composable {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        RoundButton("✓", GREEN, enabled = l != null && !l.added) {
                            val c = l!!.card
                            change(c.id, 1)
                            lock.markAdded()
                            locked = lock.state
                            added(c, "")
                            Log.i(TAG, "added by check ${c.id}")
                        }
                        RoundButton("✕", RED, enabled = l != null) {
                            picking = true
                            paused.set(true)
                            Log.i(TAG, "picker opened for ${l?.card?.id}")
                        }
                    }
                }
                if (buttonsLeft) { buttons(); Spacer(Modifier.width(14.dp)) }
                Column(Modifier.weight(1f)) {
                    if (l == null) {
                        Text("Hold a card inside the frame, right side up", color = Color.White)
                        if (r != null) Note("no card recognised (best ${pct(r.matches.first().score)})")
                        // The model only knows upright cards; an upside-down one scores like a random object.
                        Note("Cards are only recognised upright, not upside down or sideways")
                        Note("✓ adds the card · ✕ picks another match")
                    } else {
                        if (l.added) AddedBanner()
                        CardSummary(l.card, lockLabel(l, lock.remaining(SystemClock.elapsedRealtime())),
                            ok = l.best >= CardLock.CONFIDENT)
                        PriceLine(l.card.id)
                        // Fixed order (see CardLock): the list only grows, it doesn't reshuffle.
                        if (l.others.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Column(Modifier.clickable { picking = true; paused.set(true) }) {
                                Note("Also possible (✕ to pick)")
                                l.others.take(3).forEach { m ->
                                    Text("${m.card.name} · ${m.card.set} #${m.card.number} · ${pct(m.score)}",
                                        color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                                        maxLines = 1, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                    toast?.let { Text(it, color = GREEN, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
                if (!buttonsLeft) { Spacer(Modifier.width(14.dp)); buttons() }
            }

            if (picking && l != null) Picker(l, buttonsLeft, onPick = { pick(it) }, onCancel = { closePicker() })
        }
    }

    /** Full-screen list of the detected card and its candidates; the camera is paused while it's open. */
    @Composable
    private fun Picker(l: Locked, buttonsLeft: Boolean, onPick: (Card) -> Unit, onCancel: () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onCancel)) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Color(0xFF1A1F24), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clickable(enabled = false) {}
                    .navigationBarsPadding().padding(16.dp),
            ) {
                Text("Which card is it?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Note(if (l.added) "${l.card.name} was added; picking another card replaces it."
                    else "Tap the right card to add it. Cancel goes back to auto-detect.")
                Spacer(Modifier.height(8.dp))
                val options = listOf(Match(l.card, l.best)) + l.others
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options, key = { it.card.id }) { m ->
                        val first = m.card.id == l.card.id
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .border(1.dp, if (first) GREEN else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                                .clickable { onPick(m.card) }.padding(12.dp),
                        ) {
                            Text(m.card.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("${m.card.set} · #${m.card.number}${if (m.card.total.isNotEmpty()) "/${m.card.total}" else ""}" +
                                listOf(m.card.rarity, m.card.released.take(4)).filter { it.isNotEmpty() }
                                    .joinToString("") { " · $it" },
                                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                            Text(
                                "match ${pct(m.score)}" + (if (first) (if (l.added) " · added" else " · detected") else "") +
                                    (prices[m.card.id]?.usdEstimate?.let { " · ≈ ${usd(it)}" } ?: ""),
                                color = if (first) GREEN else Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    if (!buttonsLeft) Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.height(52.dp)) { Text("Cancel", fontSize = 16.sp) }
                }
            }
        }
    }

    @Composable
    private fun RoundButton(glyph: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(68.dp)
                .background(if (enabled) color else Color(0xFF3A3F44), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (enabled) Color.Black else Color(0xFF777C80), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun AddedBanner() {
        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp).background(GREEN, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("✓ Card added", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }

    /** One logcat line per lock change, so a phone test can be read back with `adb logcat -s HoloScan`. */
    private fun logLock(before: Locked?, after: Locked?, r: ScanResult, autoAdd: Boolean) {
        val top = r.matches.first()
        when {
            autoAdd -> Log.i(TAG, "auto-added ${after?.card?.id} best ${pct(after?.best ?: 0f)}")
            after?.card?.id != before?.card?.id && after != null ->
                Log.i(TAG, "lock ${after.card.id} (${after.card.name}) at ${pct(after.score)}" +
                    (before?.let { " replacing ${it.card.id} best ${pct(it.best)}" } ?: ""))
            after == null && before != null ->
                Log.i(TAG, "release ${before.card.id} best ${pct(before.best)}, top now ${pct(top.score)}")
        }
        Log.v(TAG, "frame ${r.millis} ms top ${top.card.id} ${pct(top.score)} lock ${after?.card?.id} " +
            "others ${after?.others?.joinToString(",") { it.card.id }}")
    }

    private fun lockLabel(l: Locked, remainingMs: Long?) = buildString {
        append("match ${pct(l.score)}")
        when {
            l.added -> {}
            l.best < CardLock.CONFIDENT -> append(" · low confidence, check the card")
            remainingMs != null -> append(" · adding in %.1f s".format(remainingMs / 1000f))
        }
    }

    @Composable
    private fun GuideOverlay(added: Boolean) {
        Canvas(Modifier.fillMaxSize()) {
            val g = guideRect(size.width, size.height)
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(g.left, g.top, g.right, g.bottom,
                    CornerRadius(24f, 24f)))
            }
            clipPath(path, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.45f)) }
            // The outline turns green once the card in it has been added.
            drawRoundRect(if (added) GREEN else Color.White, Offset(g.left, g.top), GSize(g.width(), g.height()),
                CornerRadius(24f, 24f), style = Stroke(width = if (added) 10f else 4f))
        }
    }

    @Composable
    private fun CardSummary(c: Card, label: String, ok: Boolean) {
        Text(c.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${c.set} · #${c.number}${if (c.total.isNotEmpty()) "/${c.total}" else ""}",
            color = Color.White, fontSize = 15.sp)
        Text(listOf(c.rarity, c.released.take(4), c.series).filter { it.isNotEmpty() }.joinToString(" · "),
            color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        Text(label, color = if (ok) GREEN else AMBER, fontSize = 12.sp)
    }

    @Composable
    private fun CollectionScreen(
        byId: Map<String, Card>,
        collection: Map<String, Int>,
        onChange: (String, Int) -> Unit,
        onClose: () -> Unit,
    ) {
        val rows = collection.entries.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
            .sortedWith(compareBy({ it.first.released }, { it.first.set }, { it.first.number.padStart(4, '0') }))
        var refreshing by remember { mutableStateOf(false) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
        val history = settings.priceRefreshes
        val wait = RefreshLimit.waitMs(history, now)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Collection · ${collection.values.sum()} cards", color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val total = collection.entries.sumOf { (id, n) -> (prices[id]?.usdEstimate ?: 0.0) * n }
                    Note("≈ ${usd(total)} estimated${priceDate()}")
                }
                TextButton(onClick = onClose) { Text("Scan") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !refreshing && wait == 0L && collection.isNotEmpty(),
                    onClick = {
                        refreshing = true
                        settings.priceRefreshes = RefreshLimit.record(history, System.currentTimeMillis())
                        refreshPrices(collection.keys) { refreshing = false }
                    },
                ) { Text(if (refreshing) "Refreshing…" else "Refresh prices") }
                Spacer(Modifier.width(10.dp))
                Note(when {
                    refreshing -> "${collection.size} cards"
                    wait > 0 -> "next in ${wait / 60_000}:%02d · ${RefreshLimit.left(history, now)} left this hour"
                        .format((wait / 1000) % 60)
                    else -> "${RefreshLimit.left(history, now)} of ${RefreshLimit.PER_HOUR} left this hour"
                })
            }
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) Text("Nothing yet — scan a card and tap ✓.", color = Color.White.copy(alpha = 0.7f))
            LazyColumn {
                items(rows, key = { it.first.id }) { (c, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = Color.White, fontSize = 16.sp)
                            Note("${c.set} #${c.number} · ${c.rarity}")
                        }
                        prices[c.id]?.usdEstimate?.let {
                            Text(usd(it * n), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                        }
                        TextButton(onClick = { onChange(c.id, -1) }) { Text("−") }
                        Text("$n", color = Color.White)
                        TextButton(onClick = { onChange(c.id, 1) }) { Text("+") }
                    }
                }
            }
        }
    }

    /** Fetches fresh prices for the given cards, lays them over the snapshot and keeps them for next start. */
    private fun refreshPrices(ids: Collection<String>, done: () -> Unit) {
        val known = prices
        val stamp = { ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'")) }
        ioExecutor.execute {
            val result = runCatching {
                val fresh = PriceSources.live(known, stamp).load(ids)
                val store = RefreshedPrices(this)
                store.save((store.load() ?: PriceBook(fresh.updated, fresh.source, emptyMap())).overlay(fresh)
                    .copy(updated = fresh.updated))
                fresh
            }
            runOnUiThread {
                result.onSuccess { fresh ->
                    prices = prices.overlay(fresh)
                    Log.i(TAG, "prices refreshed ${fresh.cards.size}/${ids.size}")
                    toast("Updated prices for ${fresh.cards.size} of ${ids.size} cards")
                }.onFailure {
                    Log.w(TAG, "price refresh failed", it)
                    toast("Price refresh failed: ${it.message}")
                }
                done()
            }
        }
    }

    @Composable
    private fun Message(text: String, action: @Composable () -> Unit = {}) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White)
            Spacer(Modifier.height(16.dp))
            action()
        }
    }

    @Composable
    private fun Heading(text: String) = Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

    @Composable
    private fun Note(text: String) = Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)

    @Composable
    private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Text(label, color = Color.White, fontSize = 15.sp)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun pct(score: Float) = "${(score * 100).toInt()}%"

    private fun usd(v: Double) = if (v < 100) "$%.2f".format(v) else "$%,.0f".format(v)

    private fun priceDate() = prices.updated.take(10).let { if (it.isEmpty()) "" else " · prices $it" }

    /** Estimated price per variant, e.g. "≈ $0.27 normal · $0.43 reverse-holofoil · €0.08 trend". */
    @Composable
    private fun PriceLine(cardId: String) {
        val p = prices[cardId]
        val text = if (p == null) "no price data" else buildList {
            p.usd.entries.sortedBy { it.value }.forEach { (variant, v) -> add("${usd(v)} $variant") }
            p.eur["trend"]?.let { add("€%.2f trend".format(it)) }
        }.joinToString(" · ", prefix = "≈ ")
        val date = p?.updated?.let { " · refreshed ${it.take(10)}" } ?: priceDate()
        Text(text + date, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
    }

    companion object {
        val AUTO_ADD_CHOICES = listOf(0, 1, 2, 3, 5)   // seconds; 0 = off
        val GREEN = Color(0xFF8BD17C)
        val AMBER = Color(0xFFE0B050)
        val RED = Color(0xFFE57373)
        const val TAG = "HoloScan"
    }
}
