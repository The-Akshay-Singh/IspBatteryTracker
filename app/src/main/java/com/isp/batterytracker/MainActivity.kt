package com.isp.batterytracker

import android.app.Applicationpackage com.isp.batterytracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

val Navy = Color(0xFF0B1F3A); val Teal = Color(0xFF0F766E); val Bg = Color(0xFFF1F5F9)
val Ink = Color(0xFF0F172A); val Muted = Color(0xFF64748B)
val Green = Color(0xFF16A34A); val Amber = Color(0xFFF59E0B); val Red = Color(0xFFDC2626)

class VM(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    val batteries = dao.batteries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val checks = dao.checks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun add(b: Battery) = viewModelScope.launch { dao.add(b) }
    fun addCheck(c: HealthCheck) = viewModelScope.launch { dao.addCheck(c) }
    fun delete(b: Battery) = viewModelScope.launch { dao.delete(b) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Navy.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Bg)) { Surface(color = Bg) { App() } } }
    }
}

fun healthColor(h: Int) = if (h >= 80) Green else if (h >= 60) Amber else Red
fun status(s: Stat): Pair<String, Color> =
    if (s.dueDay < LocalDate.now().toEpochDay()) "Overdue" to Red
    else if (s.health >= 80) "Healthy" to Green else if (s.health >= 60) "Watch" to Amber else "Critical" to Red

@SuppressLint("MissingPermission")
fun getLoc(ctx: Context, cb: (Location?) -> Unit) {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provs = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (provs.isEmpty()) { cb(null); return }
    LocationManagerCompat.getCurrentLocation(lm, provs.first(), null, ContextCompat.getMainExecutor(ctx)) { loc ->
        cb(loc ?: provs.firstNotNullOfOrNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() })
    }
}

@Suppress("DEPRECATION")
fun reverse(ctx: Context, lat: Double, lng: Double): Address? =
    runCatching { Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()

@Composable
fun App(vm: VM = viewModel()) {
    val batteries by vm.batteries.collectAsState()
    val checks by vm.checks.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    val stats = batteries.map { stat(it, checks) }
    val sel = stats.firstOrNull { it.b.id == selected }
    val today = LocalDate.now().toEpochDay()
    BackHandler(adding || sel != null) { adding = false; selected = null }

    Scaffold(containerColor = Bg, floatingActionButton = {
        if (!adding && sel == null) ExtendedFloatingActionButton(onClick = { adding = true }, containerColor = Teal, contentColor = Color.White) {
            Text("＋  Add battery", fontWeight = FontWeight.SemiBold)
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                adding -> {
                    Header("Add battery", "Register a backup battery", { adding = false })
                    AddForm(Modifier.weight(1f)) { vm.add(it); adding = false }
                }
                sel != null -> {
                    Header(sel.b.serial, "${sel.b.model} • ${sel.b.site}", { selected = null })
                    Detail(sel, checks.filter { it.batteryId == sel.b.id }, Modifier.weight(1f),
                        { vm.addCheck(it) }, { vm.delete(sel.b); selected = null })
                }
                else -> {
                    Header("Battery Tracker", "Network power backup monitor", null) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("${stats.size}", "Batteries", Modifier.weight(1f))
                            StatTile("${stats.count { it.dueDay <= today + 30 }}", "Due ≤ 30 days", Modifier.weight(1f))
                            StatTile("${stats.count { it.health < 60 }}", "Critical", Modifier.weight(1f))
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("Search serial, site or area") },
                            singleLine = true, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
                        val shown = stats.filter { s -> listOf(s.b.serial, s.b.site, s.b.area).any { it.contains(query, true) } }.sortedBy { it.dueDay }
                        if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (stats.isEmpty()) "No batteries yet.\nTap “Add battery” to start tracking." else "No matches.", color = Muted, textAlign = TextAlign.Center)
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                            items(shown, key = { it.b.id }) { s -> BatteryCard(s) { selected = s.b.id } }
                            item { Footer() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Header(title: String, sub: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
        .background(Brush.verticalGradient(listOf(Navy, Color(0xFF115E67)))).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back", color = Color.White, fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                Surface(color = Color.White.copy(alpha = .14f), shape = RoundedCornerShape(50)) {
                    Text("✦ Designed for Akhilesh", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Color.White.copy(alpha = .7f), fontSize = 13.sp)
            content()
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier) = Surface(color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(14.dp), modifier = modifier) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = .75f), fontSize = 11.sp)
    }
}

@Composable
fun Chip(text: String, c: Color) = Surface(color = c.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
    Text(text, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
fun Footer() = Text("Designed for Akhilesh  ✦  ISP Battery Tracker", color = Muted, fontSize = 11.sp,
    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) = Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp); content()
    }
}

@Composable
fun Tile(label: String, value: String, modifier: Modifier) = Card(modifier, shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
    Column(Modifier.padding(14.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BatteryCard(s: Stat, onClick: () -> Unit) {
    val c = healthColor(s.health); val (label, sc) = status(s)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(c.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Text("${s.health}%", color = c, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.b.serial, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                    Text("${s.b.model} • ${s.b.site}", color = Muted, fontSize = 13.sp)
                    Text("📍 ${s.b.area.ifBlank { "No area" }}", color = Muted, fontSize = 13.sp)
                }
                Chip(label, sc)
            }
            LinearProgressIndicator(progress = { s.health / 100f }, color = c, trackColor = c.copy(alpha = .15f),
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
            Row {
                Text("⏱ %.1f h backup".format(s.backupHrs), color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("🗓 ${LocalDate.ofEpochDay(s.dueDay)}", color = Ink, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun Field(v: String, l: String, num: Boolean = false, onChange: (String) -> Unit) = OutlinedTextField(
    v, onChange, label = { Text(l) }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
    keyboardOptions = KeyboardOptions(keyboardType = if (num) KeyboardType.Decimal else KeyboardType.Text)
)

@Composable
fun AddForm(modifier: Modifier, onSave: (Battery) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var serial by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }; var area by remember { mutableStateOf("") }
    var cap by remember { mutableStateOf("100") }; var load by remember { mutableStateOf("5") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }; var life by remember { mutableStateOf("48") }
    var lat by remember { mutableStateOf<Double?>(null) }; var lng by remember { mutableStateOf<Double?>(null) }
    var addr by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }; var err by remember { mutableStateOf("") }

    fun locate() {
        busy = true; msg = ""
        getLoc(ctx) { loc ->
            if (loc == null) { busy = false; msg = "Couldn't get location. Turn on GPS and try again." }
            else {
                lat = loc.latitude; lng = loc.longitude
                scope.launch {
                    val a = withContext(Dispatchers.IO) { reverse(ctx, loc.latitude, loc.longitude) }
                    addr = a?.getAddressLine(0) ?: ""
                    if (area.isBlank()) area = a?.subLocality ?: a?.locality ?: ""
                    busy = false
                }
            }
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) locate() else msg = "Location permission denied."
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Battery") {
            Field(serial, "Serial / asset ID") { serial = it }
            Field(model, "Model / brand") { model = it }
        }
        Section("Location") {
            Field(site, "Site (OLT / node / tower)") { site = it }
            Field(area, "Area") { area = it }
            Button(onClick = {
                val ok = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
                if (ok) locate() else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                Text(if (busy) "Locating…" else "📍 Use current location")
            }
            if (lat != null && lng != null) Surface(color = Teal.copy(alpha = .08f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("%.6f, %.6f".format(lat, lng), fontWeight = FontWeight.SemiBold, color = Ink)
                    if (addr.isNotBlank()) Text(addr, color = Muted, fontSize = 13.sp)
                }
            }
            if (msg.isNotEmpty()) Text(msg, color = Red, fontSize = 13.sp)
        }
        Section("Specifications") {
            Field(cap, "Capacity (Ah)", true) { cap = it }
            Field(load, "Average load (A)", true) { load = it }
            Field(date, "Install date (YYYY-MM-DD)") { date = it }
            Field(life, "Rated life (months)", true) { life = it }
        }
        if (err.isNotEmpty()) Text(err, color = Red)
        Button(onClick = {
            val d = runCatching { LocalDate.parse(date) }.getOrNull()
            val c = cap.toDoubleOrNull(); val l = load.toDoubleOrNull(); val lm = life.toIntOrNull()
            err = when {
                serial.isBlank() -> "Enter a serial / asset ID."
                d == null -> "Install date must be YYYY-MM-DD."
                c == null || c <= 0 -> "Enter a valid capacity."
                l == null || l <= 0 -> "Enter a valid load."
                lm == null || lm <= 0 -> "Enter a valid rated life."
                else -> ""
            }
            if (err.isEmpty()) onSave(Battery(serial = serial.trim(), model = model, site = site, area = area,
                capacityAh = c!!, loadAmps = l!!, installEpochDay = d!!.toEpochDay(), lifeMonths = lm!!,
                lat = lat, lng = lng, address = addr.ifBlank { null }))
        }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal),
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save battery", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        Footer()
    }
}

@Composable
fun Detail(s: Stat, checks: List<HealthCheck>, modifier: Modifier, onCheck: (HealthCheck) -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var h by remember { mutableStateOf("") }; var v by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val c = healthColor(s.health); val (label, sc) = status(s)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Battery health") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.health}%", color = c, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip(label, sc)
            }
            LinearProgressIndicator(progress = { s.health / 100f }, color = c, trackColor = c.copy(alpha = .15f),
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)))
            Text(if (s.measured) "Based on latest measured check" else "Estimated from age — log a check for accuracy", color = Muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Probable drain time", "%.1f h".format(s.backupHrs), Modifier.weight(1f))
            Tile("Replace by", "${LocalDate.ofEpochDay(s.dueDay)}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Age", "${s.ageMonths} months", Modifier.weight(1f))
            Tile("Capacity / load", "${s.b.capacityAh} Ah / ${s.b.loadAmps} A", Modifier.weight(1f))
        }
        Section("Location") {
            Text("${s.b.site}${if (s.b.area.isNotBlank()) ", " + s.b.area else ""}", color = Ink, fontWeight = FontWeight.SemiBold)
            Text("Installed ${LocalDate.ofEpochDay(s.b.installEpochDay)}", color = Muted, fontSize = 13.sp)
            if (s.b.lat != null && s.b.lng != null) {
                Text("📍 %.6f, %.6f".format(s.b.lat, s.b.lng), color = Ink, fontSize = 13.sp)
                if (!s.b.address.isNullOrBlank()) Text(s.b.address, color = Muted, fontSize = 13.sp)
                OutlinedButton(onClick = {
                    val uri = Uri.parse("geo:${s.b.lat},${s.b.lng}?q=${s.b.lat},${s.b.lng}(${Uri.encode(s.b.serial)})")
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text("Open in Maps") }
            } else Text("No GPS location saved", color = Muted, fontSize = 13.sp)
        }
        Section("Health log") {
            Field(h, "Health (%)", true) { h = it }
            Field(v, "Voltage (V)", true) { v = it }
            Button(onClick = {
                val hp = h.toIntOrNull()
                if (hp != null && hp in 0..100) {
                    onCheck(HealthCheck(batteryId = s.b.id, epochDay = LocalDate.now().toEpochDay(), healthPct = hp, voltage = v.toDoubleOrNull() ?: 0.0))
                    h = ""; v = ""
                }
            }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal),
                modifier = Modifier.fillMaxWidth()) { Text("Add check") }
            if (checks.isNotEmpty()) HorizontalDivider()
            checks.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${LocalDate.ofEpochDay(it.epochDay)}", color = Muted, modifier = Modifier.weight(1f))
                    Text("${it.voltage} V  ", color = Muted)
                    Chip("${it.healthPct}%", healthColor(it.healthPct))
                }
            }
        }
        OutlinedButton(onClick = { confirm = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Delete battery", color = Red)
        }
        Footer()
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete battery?") },
        text = { Text("This removes ${s.b.serial} and its health log from this device.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class VM(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    val batteries = dao.batteries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val checks = dao.checks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun add(b: Battery) = viewModelScope.launch { dao.add(b) }
    fun addCheck(c: HealthCheck) = viewModelScope.launch { dao.addCheck(c) }
    fun delete(b: Battery) = viewModelScope.launch { dao.delete(b) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { App() } } }
    }
}

fun healthColor(h: Int) = if (h >= 80) Color(0xFF2E7D32) else if (h >= 60) Color(0xFFF9A825) else Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: VM = viewModel()) {
    val batteries by vm.batteries.collectAsState()
    val checks by vm.checks.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    val stats = batteries.map { stat(it, checks) }
    val sel = stats.firstOrNull { it.b.id == selected }
    BackHandler(adding || sel != null) { adding = false; selected = null }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (adding) "Add battery" else sel?.b?.serial ?: "Battery Tracker") }) },
        floatingActionButton = { if (!adding && sel == null) FloatingActionButton({ adding = true }) { Text("+") } }
    ) { pad ->
        Box(Modifier.padding(pad).padding(12.dp)) {
            when {
                adding -> AddForm { vm.add(it); adding = false }
                sel != null -> Detail(sel, checks.filter { it.batteryId == sel.b.id },
                    { vm.addCheck(it) }, { vm.delete(sel.b); selected = null })
                else -> Column {
                    val due = stats.count { it.dueDay <= LocalDate.now().toEpochDay() + 30 }
                    val weak = stats.count { it.health < 60 }
                    Text("${stats.size} batteries • $due due within 30 days • $weak below 60% health")
                    OutlinedTextField(query, { query = it }, label = { Text("Search serial, site or area") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(stats.filter { s ->
                            listOf(s.b.serial, s.b.site, s.b.area).any { it.contains(query, true) }
                        }.sortedBy { it.dueDay }) { s ->
                            Card(Modifier.fillMaxWidth().clickable { selected = s.b.id }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${s.b.serial} • ${s.b.model}", style = MaterialTheme.typography.titleMedium)
                                    Text("${s.b.site}, ${s.b.area}")
                                    Text("Health ${s.health}%" + if (s.measured) " (measured)" else " (estimated)", color = healthColor(s.health))
                                    Text("Backup ≈ %.1f h • Replace by %s".format(s.backupHrs, LocalDate.ofEpochDay(s.dueDay)))
                                    if (s.dueDay < LocalDate.now().toEpochDay()) Text("OVERDUE for replacement", color = Color(0xFFC62828))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Num(v: String, l: String, onChange: (String) -> Unit) = OutlinedTextField(
    v, onChange, label = { Text(l) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
)

@Composable
fun AddForm(onSave: (Battery) -> Unit) {
    var serial by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }; var area by remember { mutableStateOf("") }
    var cap by remember { mutableStateOf("100") }; var load by remember { mutableStateOf("5") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }; var life by remember { mutableStateOf("48") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(serial, { serial = it }, label = { Text("Serial / asset ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model / brand") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(site, { site = it }, label = { Text("Site (OLT / node / tower)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(area, { area = it }, label = { Text("Area") }, modifier = Modifier.fillMaxWidth())
        Num(cap, "Capacity (Ah)") { cap = it }
        Num(load, "Average load (A)") { load = it }
        OutlinedTextField(date, { date = it }, label = { Text("Install date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
        Num(life, "Rated life (months)") { life = it }
        Button(onClick = {
            val d = runCatching { LocalDate.parse(date) }.getOrNull()
            val c = cap.toDoubleOrNull(); val l = load.toDoubleOrNull(); val lm = life.toIntOrNull()
            if (serial.isNotBlank() && d != null && c != null && l != null && l > 0 && lm != null && lm > 0)
                onSave(Battery(serial = serial.trim(), model = model, site = site, area = area,
                    capacityAh = c, loadAmps = l, installEpochDay = d.toEpochDay(), lifeMonths = lm))
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

@Composable
fun Detail(s: Stat, checks: List<HealthCheck>, onCheck: (HealthCheck) -> Unit, onDelete: () -> Unit) {
    var h by remember { mutableStateOf("") }; var v by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${s.b.model} • ${s.b.capacityAh} Ah • load ${s.b.loadAmps} A")
        Text("${s.b.site}, ${s.b.area}")
        Text("Installed ${LocalDate.ofEpochDay(s.b.installEpochDay)} (${s.ageMonths} months ago)")
        Text("Health ${s.health}%", color = healthColor(s.health), style = MaterialTheme.typography.titleLarge)
        Text("Probable drain time: %.1f h on mains failure".format(s.backupHrs))
        Text("Replace by ${LocalDate.ofEpochDay(s.dueDay)}")
        HorizontalDivider()
        Text("Log health check", style = MaterialTheme.typography.titleMedium)
        Num(h, "Health (%)") { h = it }
        Num(v, "Voltage (V)") { v = it }
        Button(onClick = {
            val hp = h.toIntOrNull()
            if (hp != null && hp in 0..100) {
                onCheck(HealthCheck(batteryId = s.b.id, epochDay = LocalDate.now().toEpochDay(), healthPct = hp, voltage = v.toDoubleOrNull() ?: 0.0))
                h = ""; v = ""
            }
        }) { Text("Add check") }
        checks.forEach { Text("${LocalDate.ofEpochDay(it.epochDay)} — ${it.healthPct}% @ ${it.voltage} V") }
        HorizontalDivider()
        OutlinedButton(onClick = onDelete) { Text("Delete battery") }
    }
}
