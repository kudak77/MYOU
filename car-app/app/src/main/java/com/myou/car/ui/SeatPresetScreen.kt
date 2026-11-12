package com.myou.car.ui

import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myou.car.key.DigitalKey
import com.myou.car.key.MAX_KEY_NAME
import com.myou.car.rest.RestPhase
import com.myou.car.seat.SeatAxis
import com.myou.car.seat.SeatPreset
import kotlinx.coroutines.delay

@Composable
fun SeatPresetScreen(vm: SeatPresetViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        if (state.message != null) { delay(4_000); vm.clearMessage() }
    }

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        MySeatPanel(state, vm, Modifier.weight(1.1f))
        PresetListPanel(state, vm, Modifier.weight(1f))
    }
}

/** 왼쪽: 시트 스위치로 맞춘 자세를 실시간으로 보여주고 저장한다. */
@Composable
private fun MySeatPanel(state: SeatUiState, vm: SeatPresetViewModel, modifier: Modifier) {
    var name by remember { mutableStateOf("") }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("내 시트 자세", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

        Guide()
        StatusBanner(state)

        state.supportedAxes.forEach { axis ->
            AxisRow(
                axis = axis,
                value = state.draft[axis],
                seatValue = state.current[axis]?.takeIf { axis in state.edits },
                bounds = state.bounds.getValue(axis),
                moving = axis in state.adjusting,
                savedValue = state.activePreset?.positions?.get(axis)?.takeIf { axis in state.changedFromActive },
                enabled = state.applying == null && axis in state.current,
                onNudge = { vm.nudge(axis, it) },
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(20) },
            label = { Text("프리셋 이름 (예: 출퇴근)") },
            textStyle = MaterialTheme.typography.titleLarge,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.saveCurrentAs(name); name = "" },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text("지금 자세를 새 프리셋으로 저장", style = MaterialTheme.typography.titleLarge) }

        val active = state.activePreset
        if (active != null && state.changedFromActive.isNotEmpty()) {
            OutlinedButton(
                onClick = { vm.overwrite(active) },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("'${active.name}'에 지금 자세 덮어쓰기", style = MaterialTheme.typography.titleMedium) }
        }

    }
}

@Composable
private fun Guide() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GuideStep(1, "차량의 전동 시트 스위치로 편한 자세를 맞추세요.")
        GuideStep(2, "화면의 − / + 로 저장할 값을 직접 고칠 수도 있어요.")
        GuideStep(3, "이름을 정하고 저장하세요.")
    }
}

@Composable
private fun GuideStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium) }
        Text(text, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StatusBanner(state: SeatUiState) {
    val (text, color) = when {
        state.applying != null -> state.applying to MaterialTheme.colorScheme.secondaryContainer
        state.supportedAxes.isEmpty() -> "시트 정보를 불러오는 중…" to MaterialTheme.colorScheme.surfaceVariant
        state.adjusting.isNotEmpty() -> "시트 조절 중… 멈추면 저장할 수 있어요" to MaterialTheme.colorScheme.tertiaryContainer
        // 위 조건을 지나 canSave 가 false 면 남은 이유는 아직 못 읽은 축뿐이다.
        !state.canSave -> "현재 시트 값을 읽는 중…" to MaterialTheme.colorScheme.surfaceVariant
        else ->"자세가 고정됐어요. 저장할 수 있어요" to MaterialTheme.colorScheme.primaryContainer
    }
    val bg by animateColorAsState(color, label = "status")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        if (state.applying != null || state.adjusting.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun AxisRow(
    axis: SeatAxis,
    value: Int?,
    seatValue: Int?,
    bounds: AreaBounds<Int>,
    moving: Boolean,
    savedValue: Int?,
    enabled: Boolean,
    onNudge: (Int) -> Unit,
) {
    val range = (bounds.maxValue - bounds.minValue).coerceAtLeast(1)
    val fraction = value?.let { ((it - bounds.minValue).toFloat() / range).coerceIn(0f, 1f) } ?: 0f
    val bg by animateColorAsState(
        if (moving) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        label = "axis",
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(axis.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (moving) Text("움직이는 중", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
            NudgeButton("−", "${axis.label} 줄이기", enabled && value != null && value > bounds.minValue) { onNudge(-1) }
            Text(
                value?.toString() ?: "-",
                Modifier.widthIn(min = 56.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            NudgeButton("+", "${axis.label} 늘리기", enabled && value != null && value < bounds.maxValue) { onNudge(1) }
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
        val notes = listOfNotNull(seatValue?.let { "실제 시트 $it" }, savedValue?.let { "저장된 값 $it" })
        if (notes.isNotEmpty()) {
            Text(notes.joinToString("  ·  "), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun NudgeButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(64.dp).semantics { contentDescription = description },
    ) { Text(symbol, style = MaterialTheme.typography.headlineMedium) }
}

/** 오른쪽: 저장된 운전자 프리셋. */
@Composable
private fun PresetListPanel(state: SeatUiState, vm: SeatPresetViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RestPanel(state, vm)
        DigitalKeyPanel(state, vm)
        Text("운전자 프리셋", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        if (!state.parked) {
            Text(
                if (state.gear == null) "기어 상태를 확인하는 중… 확인되기 전에는 시트를 움직이지 않아요."
                else "기어가 P(주차)일 때만 시트를 자동으로 맞출 수 있어요.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // 저장·적용·정지 결과. 왼쪽 열은 스크롤되므로 항상 보이는 이쪽에 둔다.
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        }
        if (state.presets.isEmpty()) {
            Text("아직 저장된 자세가 없습니다.\n시트를 맞춘 뒤 왼쪽에서 저장하세요.", style = MaterialTheme.typography.titleMedium)
        }
        // 오른쪽 열 전체가 스크롤되므로 목록은 일반 Column 으로 둔다 (프리셋은 몇 개뿐).
        state.presets.forEach { preset ->
            key(preset.id) {
                PresetCard(
                    preset = preset,
                    active = preset.id == state.activeId,
                    busy = state.applying != null || !state.parked,
                    onApply = { vm.apply(preset) },
                    onDelete = { vm.delete(preset) },
                    keys = state.keys,
                    onLinkKey = { vm.linkKey(preset, it) },
                )
            }
        }
        if (state.applying != null) {
            OutlinedButton(onClick = vm::cancelApply, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("시트 움직임 멈추기", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** 시나리오 2·3: 휴게소 도착 → 휴식 제안 → 휴식(무중력 시트·공조·조명·타이머) → 주행 복귀 */
@Composable
private fun RestPanel(state: SeatUiState, vm: SeatPresetViewModel) {
    val r = state.restSettings
    val highlight = state.rest !is RestPhase.Idle
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("휴식모드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        when (val p = state.rest) {
            RestPhase.Idle -> {
                Text(
                    state.arrivedArea?.let { "${it.name}(${it.type.label}) 안에 있어요." + if (!state.stoppedForRest) " $STOP_HINT" else "" }
                        ?: if (state.location == null) "위치를 확인하는 중…" else "휴게소·졸음쉼터에 도착해 $STOP_HINT",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.arrivedArea == null && state.location != null) {
                    OutlinedButton(onClick = vm::guideToNearestRestArea, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text("가까운 휴게소로 안내", style = MaterialTheme.typography.titleMedium)
                    }
                }
                state.naviStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                SettingRow("휴식 시간", "${r.minutes}분", { vm.changeRestMinutes(-1) }, { vm.changeRestMinutes(1) })
                r.temperatureC?.let { t ->
                    SettingRow("실내 온도", "%.1f°C".format(t), { vm.changeRestTemperature(-0.5f) }, { vm.changeRestTemperature(0.5f) })
                }
                Text(
                    state.activePreset.let { d ->
                        (if (d?.restPositions != null) "휴식 자세(${d.name}): " else "휴식 자세(기본): ") +
                            SeatAxis.entries.mapNotNull { a -> (d?.restPositions ?: r.seat)[a]?.let { "${a.label} $it" } }.joinToString("  ")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = vm::saveCurrentAsRestSeat, enabled = state.canSave, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("지금 자세를 휴식 자세로 저장", style = MaterialTheme.typography.titleMedium)
                }
            }
            is RestPhase.Suggested -> {
                Text("${p.area.name}(${p.area.type.label})에 도착했어요. 휴식모드로 전환할까요?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "시트를 휴식 자세로 바꾸고" + (r.temperatureC?.let { " 실내 온도 %.1f°C,".format(it) } ?: "") +
                        " 조명을 어둡게 합니다. ${r.minutes}분 뒤 알려 드려요.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::startRest, enabled = state.applying == null, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text("휴식모드 시작", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedButton(onClick = vm::declineRest, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text("전환 안 함", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            is RestPhase.Resting -> {
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(p.endsAtMillis) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                val left = ((p.endsAtMillis - now) / 1000).coerceAtLeast(0)
                Text("휴식 중 · ${p.area.name}", style = MaterialTheme.typography.headlineSmall)
                Text("남은 시간 %d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.finishRest() }, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text("휴식 끝내기", style = MaterialTheme.typography.titleLarge)
                    }
                    Button(onClick = vm::returnToDrive, enabled = state.parked && state.applying == null, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text("바로 주행 복귀", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            is RestPhase.Finished -> {
                Text("휴식 시간이 끝났어요.", style = MaterialTheme.typography.headlineSmall)
                Text("주행 복귀를 누르면 시트가 운전 자세로 돌아갑니다.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = vm::returnToDrive, enabled = state.parked && state.applying == null, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                    Text("주행 복귀", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

private const val STOP_HINT = "기어를 P에 두고 주차 브레이크를 걸면 휴식을 제안해요."

@Composable
private fun SettingRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        NudgeButton("−", "$label 줄이기", true, onMinus)
        Text(value, Modifier.widthIn(min = 88.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        NudgeButton("+", "$label 늘리기", true, onPlus)
    }
}

/** 키 이름 바꾸기. 비워서 저장하면 기본 이름으로 돌아간다. */
@Composable
private fun RenameKeyDialog(key: DigitalKey, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(key.id) { mutableStateOf(key.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("스마트폰 키 이름") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_KEY_NAME) },
                    label = { Text("이름 (예: 아빠 폰)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("비워 두고 저장하면 기본 이름으로 돌아가요. (최대 ${MAX_KEY_NAME}자)", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("저장", style = MaterialTheme.typography.titleMedium) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("취소", style = MaterialTheme.typography.titleMedium) }
        },
    )
}

@Composable
private fun KeyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (selected) "✓ $label" else label, style = MaterialTheme.typography.titleMedium) },
        // 강조된(현재 운전자) 카드 배경에서도 선택이 보이도록 진한 색을 쓴다.
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/** 시연용 디지털 키 접근. 실제 차량에서는 BLE/UWB 감지로 같은 흐름이 시작된다. */
@Composable
private fun DigitalKeyPanel(state: SeatUiState, vm: SeatPresetViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("디지털 키 접근 (시연)", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                when (state.ignitionOn) { true -> "시동 ON"; false -> "시동 OFF"; null -> "" },
                color = if (state.ignitionOn == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.titleMedium,
            )
            // 에뮬레이터에는 누를 시동 버튼이 없어 디버그에서만 대신 보여준다.
            if (state.testStartButton) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = vm::pressTestStartButton, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(if (state.ignitionOn == true) "시동 끄기 (테스트)" else "시동 버튼 (테스트)", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Text(
            state.keyStatus ?: "스마트폰 키를 가지고 다가오는 상황을 만들어 보세요. 키는 아래 프리셋 카드에서 연결합니다.",
            style = MaterialTheme.typography.titleMedium,
        )
        var renaming by remember { mutableStateOf<DigitalKey?>(null) }
        state.keys.forEach { k ->
            val owner = state.presets.firstOrNull { it.keyId == k.id }?.name
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(k.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        owner?.let { "→ $it" } ?: "연결된 프리셋 없음",
                        color = if (owner == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = { renaming = k }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("이름 변경", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = { vm.simulateKeyApproach(k.id) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("접근", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        renaming?.let { k -> RenameKeyDialog(k, onDismiss = { renaming = null }, onSave = { vm.renameKey(k.id, it); renaming = null }) }
    }
}

@Composable
private fun PresetCard(
    preset: SeatPreset,
    active: Boolean,
    busy: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    keys: List<DigitalKey>,
    onLinkKey: (String?) -> Unit,
) {
    Card(
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (active) Text("현재 운전자", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                SeatAxis.entries.mapNotNull { a -> preset.positions[a]?.let { "${a.label} $it" } }.joinToString("   "),
                style = MaterialTheme.typography.titleMedium,
            )
            // 키 하나는 프리셋 하나에만 연결된다. 다른 프리셋에 연결된 키를 고르면 그쪽 연결은 풀린다.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("디지털 키", style = MaterialTheme.typography.titleMedium)
                KeyChip("없음", preset.keyId == null) { onLinkKey(null) }
                keys.forEach { k -> KeyChip(k.label, preset.keyId == k.id) { onLinkKey(k.id) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onApply, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("이 자세로 맞추기", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("삭제", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
