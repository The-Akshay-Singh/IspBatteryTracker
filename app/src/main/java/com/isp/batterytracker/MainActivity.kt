package com.isp.batterytracker

import android.app.Application
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
