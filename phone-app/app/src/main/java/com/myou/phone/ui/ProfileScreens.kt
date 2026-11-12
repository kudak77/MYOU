package com.myou.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myou.phone.data.DigitalKey
import com.myou.phone.data.MAX_NAME
import com.myou.phone.data.SeatAxis
import com.myou.phone.data.SeatPreset
import com.myou.phone.data.ZERO_GRAVITY
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun MyouApp(vm: ProfileViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editing = state.editing
    if (editing == null) ProfileListScreen(state, vm) else ProfileEditScreen(editing, state, vm)
}

/** 운전자 프로필 목록 + 디지털 키 이름 관리 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileListScreen(state: PhoneUiState, vm: ProfileViewModel) {
    var renaming by remember { mutableStateOf<DigitalKey?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("M:YOU", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = vm::newProfile) { Text("+ 프로필 추가") }
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CarCard(state, vm) }
            item { SectionTitle("운전자 프로필") }
            if (state.profiles.isEmpty()) {
                item { Text("아직 프로필이 없어요. 아래 + 버튼으로 내 시트 자세를 만들어 보세요.", style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.profiles, key = { it.id }) { p ->
                ProfileCard(p, state.keys.firstOrNull { it.id == p.keyId }?.label) { vm.edit(p) }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("휴식 설정") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("휴게소에서 휴식모드를 켤 때 차량이 쓰는 값이에요. '차량으로 보내기'로 함께 보내져요.", style = MaterialTheme.typography.bodyMedium)
                    val r = state.rest
                    StepRow("휴식 시간", "${r.minutes}분", { vm.changeRestMinutes(-1) }, { vm.changeRestMinutes(1) })
                    StepRow("실내 온도", "%.1f°C".format(r.temperatureC), { vm.changeRestTemperature(-0.5f) }, { vm.changeRestTemperature(0.5f) })
                    StepRow("풍량", "${r.fanSpeed}단", { vm.changeRestFan(-1) }, { vm.changeRestFan(1) })
                    StepRow("무드등 밝기", "${r.ambientPercent}%", { vm.changeRestAmbient(-10) }, { vm.changeRestAmbient(10) })
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("디지털 키") }
            items(state.keys, key = { it.id }) { k ->
                val owner = state.profiles.firstOrNull { it.keyId == k.id }?.name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = MaterialTheme.typography.titleMedium)
                        Text(owner?.let { "→ $it" } ?: "연결된 프로필 없음", color = MaterialTheme.colorScheme.outline)
                    }
                    TextButton(onClick = { renaming = k }, modifier = Modifier.heightIn(min = 48.dp)) { Text("이름 변경") }
                }
            }
        }
    }
    renaming?.let { k ->
        NameDialog(
            title = "스마트폰 키 이름",
            initial = k.label,
            hint = "비워 두고 저장하면 기본 이름으로 돌아가요.",
            onDismiss = { renaming = null },
            onSave = { vm.renameKey(k.id, it); renaming = null },
        )
    }
}

/** 차량 연결: 상태(휴식 남은 시간 등)와 보내기·가져오기 */
@Composable
private fun CarCard(state: PhoneUiState, vm: ProfileViewModel) {
    val car = state.car
    val resting = car?.restPhase == "Resting"
    Card(
        Modifier.fillMaxWidth(),
        colors = if (resting || car?.restPhase == "Finished") CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("내 차량", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when {
                car == null -> Text("차량에 연결되지 않았어요.", color = MaterialTheme.colorScheme.outline)
                resting -> {
                    var now by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(car.fetchedAt) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                    val left = (((car.restRemainingMs ?: 0) - (now - car.fetchedAt)) / 1000).coerceAtLeast(0)
                    Text("휴식 중 · ${car.restArea ?: ""}", style = MaterialTheme.typography.titleMedium)
                    Text("남은 시간 %d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                car.restPhase == "Finished" -> Text("휴식 시간이 끝났어요. 차량에서 '주행 복귀'를 누르세요.", style = MaterialTheme.typography.titleMedium)
                else -> Text(
                    listOfNotNull(
                        when (car.ignitionOn) { true -> "시동 ON"; false -> "시동 OFF"; null -> null },
                        car.driver?.let { "운전자 $it" },
                        if (car.restPhase == "Suggested") "휴게소 도착 · 휴식 제안 중" else null,
                    ).joinToString(" · ").ifBlank { "연결됨" },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::sendToCar, enabled = !state.syncing && state.profiles.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("차량으로 보내기")
                }
                OutlinedButton(onClick = vm::pullFromCar, enabled = !state.syncing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("차량에서 가져오기")
                }
            }
            state.syncStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun ProfileCard(p: SeatPreset, keyLabel: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(SeatAxis.entries.mapNotNull { a -> p.positions[a]?.let { "${a.label} $it" } }.joinToString(" · "))
            Text(
                "디지털 키: ${keyLabel ?: "연결 안 됨"}",
                color = if (keyLabel == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 프로필 편집: 이름, 시트 값, 디지털 키. 저장을 눌러야 반영된다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(p: SeatPreset, state: PhoneUiState, vm: ProfileViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = vm::closeEditor)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "새 프로필" else "프로필 편집") },
                navigationIcon = { TextButton(onClick = vm::closeEditor) { Text("취소") } },
                actions = { TextButton(onClick = vm::saveDraft) { Text("저장", fontWeight = FontWeight.Bold) } },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = p.name,
                onValueChange = { v -> vm.updateDraft { it.copy(name = v.take(MAX_NAME)) } },
                label = { Text("프로필 이름 (예: 출퇴근)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("시트 자세")
            SeatAxis.entries.forEach { axis ->
                val v = p.positions[axis] ?: 50
                AxisEditor(axis, v) { nv -> vm.updateDraft { it.copy(positions = it.positions + (axis to nv.coerceIn(0, 100))) } }
            }

            SectionTitle("휴식 자세 (무중력)")
            val useDefault = p.restPositions == null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("차량 기본 휴식 자세 사용", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = useDefault,
                    onCheckedChange = { on -> vm.updateDraft { it.copy(restPositions = if (on) null else ZERO_GRAVITY) } },
                )
            }
            if (useDefault) {
                Text("휴게소에서 휴식모드를 켜면 차량에 저장된 기본 무중력 자세를 써요. 끄면 이 운전자만의 휴식 자세를 정할 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                SeatAxis.entries.forEach { axis ->
                    val rv = p.restPositions?.get(axis) ?: ZERO_GRAVITY.getValue(axis)
                    AxisEditor(axis, rv, "휴식 ") { nv -> vm.updateDraft { it.copy(restPositions = (it.restPositions ?: ZERO_GRAVITY) + (axis to nv.coerceIn(0, 100))) } }
                }
            }

            SectionTitle("디지털 키")
            Text("이 키를 가지고 차에 다가가면 이 자세로 시트가 맞춰져요. 다른 프로필에 연결된 키를 고르면 그쪽 연결은 풀려요.",
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyChip("없음", p.keyId == null) { vm.updateDraft { it.copy(keyId = null) } }
                state.keys.forEach { k -> KeyChip(k.label, p.keyId == k.id) { vm.updateDraft { it.copy(keyId = k.id) } } }
            }

            Button(onClick = vm::saveDraft, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("저장") }
            if (!state.isNew) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("프로필 삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("'${p.name}' 프로필을 삭제할까요?") },
            text = { Text("삭제하면 되돌릴 수 없어요. 연결된 디지털 키도 풀려요.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteDraft() }) { Text("삭제", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun AxisEditor(axis: SeatAxis, value: Int, prefix: String = "", onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(axis.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            StepButton("−", "$prefix${axis.label} 줄이기", value > 0) { onChange(value - 1) }
            Text("$value", Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            StepButton("+", "$prefix${axis.label} 늘리기", value < 100) { onChange(value + 1) }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.semantics { contentDescription = prefix + axis.label },
        )
    }
}

@Composable
private fun StepRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        StepButton("−", "$label 줄이기", true, onMinus)
        Text(value, Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        StepButton("+", "$label 늘리기", true, onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun KeyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (selected) "✓ $label" else label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun NameDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("$hint (최대 ${MAX_NAME}자)", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
