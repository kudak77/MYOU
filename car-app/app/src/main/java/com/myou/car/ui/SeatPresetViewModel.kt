package com.myou.car.ui

import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.myou.car.cabin.CabinController
import com.myou.car.door.DoorController
import com.myou.car.location.LocationMonitor
import com.myou.car.rest.RestArea
import com.myou.car.rest.RestNotifier
import com.myou.car.rest.RestPhase
import com.myou.car.rest.RestSettings
import com.myou.car.rest.arrivedAt
import com.myou.car.rest.loadRestAreas
import com.myou.car.drive.GearMonitor
import com.myou.car.drive.NaviGuide
import com.myou.car.drive.PowerMonitor
import com.myou.car.key.DEMO_KEYS
import com.myou.car.key.DigitalKey
import com.myou.car.key.DigitalKeyEvent
import com.myou.car.key.MAX_KEY_NAME
import com.myou.car.key.SimulatedDigitalKeyGateway
import com.myou.car.seat.PresetRepository
import com.myou.car.sync.PhoneSyncServer
import com.myou.car.seat.SeatAxis
import com.myou.car.seat.ConsoleSeatController
import com.myou.car.seat.SeatControl
import com.myou.car.seat.SeatController
import com.myou.car.seat.SeatPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SeatUiState(
    val bounds: Map<SeatAxis, AreaBounds<Int>> = emptyMap(),
    val current: Map<SeatAxis, Int> = emptyMap(),
    /** 화면의 -/+ 로 고친 값. 해당 축의 시트가 실제로 움직이면 지워진다. */
    val edits: Map<SeatAxis, Int> = emptyMap(),
    /** 사용자가 시트 스위치로 지금 움직이고 있는 축 */
    val adjusting: Set<SeatAxis> = emptySet(),
    val presets: List<SeatPreset> = emptyList(),
    val activeId: String? = null,
    /** 프리셋 적용 중이면 진행 상황 문구 */
    val applying: String? = null,
    val message: String? = null,
    /** 디지털 키 접근/인증 진행 상황 */
    val keyStatus: String? = null,
    /** null = 아직 모름 */
    val gear: VehicleGear? = null,
    /** 주차 브레이크 체결 여부. null = 차량이 알려 주지 않음 */
    val parkingBrake: Boolean? = null,
    /** 시동 ON 여부. null = 아직 모름(또는 차량이 지원 안 함) */
    val ignitionOn: Boolean? = null,
    /** 디버그 에뮬레이터: 화면의 테스트용 시동 버튼을 보여줄지 */
    val testStartButton: Boolean = false,
    val rest: RestPhase = RestPhase.Idle,
    /** 이 차량의 디지털 키 (사용자가 바꾼 이름 반영) */
    val keys: List<DigitalKey> = DEMO_KEYS,
    val restSettings: RestSettings = RestSettings(),
    /** 현재 위치(위도, 경도). 아직 못 받았으면 null */
    val location: Pair<Double, Double>? = null,
    /** 지금 반경 안에 있는 휴게소·졸음쉼터 */
    val arrivedArea: RestArea? = null,
    /** 내비게이션(휴게소 안내) 진행 문구 */
    val naviStatus: String? = null,
) {
    /** 시트 자동 이동은 기어 P일 때만 허용한다. 기어를 모르면 막는다. */
    val parked: Boolean get() = gear == VehicleGear.GEAR_PARK

    /** 휴식 제안 조건(기획서): 기어 P + 주차 브레이크 체결. 주차 브레이크를 알 수 없는 차는 기어 P 만 본다. */
    val stoppedForRest: Boolean get() = parked && parkingBrake != false

    val supportedAxes: List<SeatAxis> get() = SeatAxis.entries.filter { it in bounds }
    val activePreset: SeatPreset? get() = presets.firstOrNull { it.id == activeId }

    /** 저장될 값: 실제 시트 값 위에 화면에서 고친 값을 덮는다. */
    val draft: Map<SeatAxis, Int> get() = current + edits

    /** 시트가 멈춰 있고 모든 축 값을 읽었으면 저장 가능 */
    val canSave: Boolean
        get() = applying == null && adjusting.isEmpty() &&
            supportedAxes.isNotEmpty() && supportedAxes.all { it in current }

    /** 저장될 값이 선택된 프리셋과 다른 축 */
    val changedFromActive: Set<SeatAxis>
        get() = activePreset?.let { p -> supportedAxes.filter { a -> p.positions[a] != null && p.positions[a] != draft[a] }.toSet() }
            ?: emptySet()
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class SeatPresetViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PresetRepository(app)
    /** 디버그 에뮬레이터에서 Virtual Console이 응답하면 콘솔 시트, 아니면 Pleos SDK. init 에서 정해진다. */
    private lateinit var seat: SeatControl

    private val key = SimulatedDigitalKeyGateway()
    private val door = DoorController(app)
    private val gearMonitor = GearMonitor(app)
    private val powerMonitor = PowerMonitor(app)
    private val navi = NaviGuide(app)
    /** 키로 인식했지만 아직 시동을 켜지 않은 운전자. 시동 ON 때 이 자세로 맞춘다. */
    private var pendingDriver: SeatPreset? = null

    private val restAreas = loadRestAreas(app)
    private val locationMonitor = LocationMonitor(app)
    private val cabin = CabinController(app)
    private val notifier = RestNotifier(app)
    private var restTimer: Job? = null
    /** 휴식 전 공조·조명 상태. 주행 복귀 때 되돌린다. */
    private var cabinBefore: CabinController.Snapshot? = null
    /** 휴식이 끝나면 돌아갈 운전 자세와 그 이름 */
    private var returnTo: Pair<String, Map<SeatAxis, Int>>? = null
    /** '전환 안 함'을 누른 곳과 이미 휴식을 마친 곳은 그 반경을 벗어날 때까지 다시 제안하지 않는다. */
    private var quietAreaId: String? = null

    private val _state = MutableStateFlow(SeatUiState())
    val state: StateFlow<SeatUiState> = _state.asStateFlow()

    private var applyJob: Job? = null
    private val settleJobs = mutableMapOf<SeatAxis, Job>()

    init {
        viewModelScope.launch { repo.presets.collect { list -> _state.update { it.copy(presets = list.sortedBy(SeatPreset::name)) } } }
        viewModelScope.launch { repo.activePresetId.collect { id -> _state.update { it.copy(activeId = id) } } }
        viewModelScope.launch { key.events.collect(::onKeyEvent) }
        viewModelScope.launch { gearMonitor.gear.collect(::onGear) }
        viewModelScope.launch {
            runCatching { powerMonitor.parkingBrakeOn.collect { on -> _state.update { it.copy(parkingBrake = on) }; updateRestSuggestion() } }
                .onFailure { android.util.Log.w("PowerMonitor", "parking brake unavailable: ${it.message}") }
        }
        viewModelScope.launch {
            runCatching { powerMonitor.ignitionOn.collect(::onIgnition) }
                .onFailure { android.util.Log.w("PowerMonitor", "ignition unavailable: ${it.message}") }
        }
        viewModelScope.launch {
            repo.keyNames.collect { names -> _state.update { it.copy(keys = DEMO_KEYS.map { k -> k.copy(label = names[k.id] ?: k.label) }) } }
        }
        // 디버그: 스마트폰 앱에서 보낸 프로필 받기
        viewModelScope.launch {
            runCatching {
                PhoneSyncServer(
                    onProfiles = { presets, names, rest ->
                        repo.importFromPhone(presets, names, rest).also { n ->
                            _state.update { it.copy(message = "스마트폰에서 프로필 ${n}개를 받았습니다.") }
                        }
                    },
                    status = ::statusJson,
                    export = repo::exportForPhone,
                ).run(app)
            }.onFailure { android.util.Log.w("PhoneSync", "server stopped: ${it.message}") }
        }
        viewModelScope.launch { navi.events.collect { msg -> _state.update { it.copy(naviStatus = msg) } } }
        viewModelScope.launch { repo.restSettings.collect { r -> _state.update { it.copy(restSettings = r) } } }
        viewModelScope.launch {
            // 서비스 연결 전에 한 등록은 버려질 수 있어, 10초간 위치가 없으면 다시 등록한다 (위치는 정차 중에도 매초 온다).
            locationMonitor.location.timeout(10.seconds)
                .retryWhen { e, _ -> android.util.Log.w("LocationMonitor", "retry: ${e.message}"); delay(3_000); true }
                .collect { onLocation(it.latitude, it.longitude) }
        }
        viewModelScope.launch {
            seat = if (ConsoleSeatController.isAvailable(app)) ConsoleSeatController() else SeatController(app)
            _state.update { it.copy(testStartButton = seat is ConsoleSeatController) }
            if (!loadCapabilities()) return@launch
            // 시트 스위치 조작을 실시간으로 반영 (차량 콜백)
            _state.value.supportedAxes.forEach { axis ->
                launch { seat.observe(axis).collect { onSeatValue(axis, it) } }
            }
            // 콜백을 주지 않는 환경을 위한 보조 확인
            while (true) {
                delay(1_000)
                _state.value.supportedAxes.forEach { axis -> seat.read(axis)?.let { onSeatValue(axis, it) } }
            }
        }
    }

    /** 차량 서비스 연결 직후엔 실패할 수 있어 몇 번 재시도한다. */
    private suspend fun loadCapabilities(): Boolean {
        repeat(5) { attempt ->
            val bounds = SeatAxis.entries.mapNotNull { axis -> seat.bounds(axis)?.let { axis to it } }.toMap()
            if (bounds.isNotEmpty()) {
                _state.update { it.copy(bounds = bounds) }
                return true
            }
            delay(1_000L * (attempt + 1))
        }
        _state.update { it.copy(message = "이 차량에서 시트 정보를 읽을 수 없습니다. (지원 안 함 또는 권한 없음)") }
        return false
    }

    private fun onSeatValue(axis: SeatAxis, value: Int) {
        val previous = _state.value.current[axis]
        if (previous == value) return
        _state.update { it.copy(current = it.current + (axis to value)) }
        if (previous == null) return // 첫 값은 '조절 중'으로 보지 않음

        // 시트 스위치로 움직였으면 그 축은 화면 수정값 대신 실제 값을 따른다.
        _state.update { it.copy(adjusting = it.adjusting + axis, edits = it.edits - axis) }
        settleJobs[axis]?.cancel()
        settleJobs[axis] = viewModelScope.launch {
            delay(SETTLE_MS)
            _state.update { it.copy(adjusting = it.adjusting - axis) }
        }
    }

    /** 화면의 -/+ : 시트는 움직이지 않고 저장될 값만 [delta] 단계만큼 바꾼다. */
    fun nudge(axis: SeatAxis, delta: Int) = _state.update { s ->
        val b = s.bounds[axis] ?: return@update s
        val base = s.draft[axis] ?: return@update s
        val step = ((b.maxValue - b.minValue) / 20).coerceAtLeast(1)
        val next = (base + delta * step).coerceIn(b.minValue, b.maxValue)
        s.copy(edits = if (next == s.current[axis]) s.edits - axis else s.edits + (axis to next))
    }

    fun saveCurrentAs(name: String) {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            val preset = SeatPreset(UUID.randomUUID().toString(), name.trim().ifBlank { "운전자 ${s.presets.size + 1}" }, s.draft)
            repo.upsert(preset)
            repo.setActive(preset.id)
            _state.update { it.copy(edits = emptyMap(), message = "'${preset.name}' 프리셋을 저장했습니다.") }
        }
    }

    fun overwrite(preset: SeatPreset) {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            repo.upsert(preset.copy(positions = s.draft))
            repo.setActive(preset.id)
            _state.update { it.copy(edits = emptyMap(), message = "'${preset.name}'을(를) 새 값으로 바꿨습니다.") }
        }
    }

    fun delete(preset: SeatPreset) = viewModelScope.launch { repo.delete(preset.id) }

    /** 프리셋에 디지털 키를 연결한다(null 이면 연결 해제). 그 키로 접근하면 이 프리셋으로 시트를 맞춘다. */
    fun linkKey(preset: SeatPreset, keyId: String?) = viewModelScope.launch { repo.linkKey(preset.id, keyId) }

    /** 키 이름을 바꾼다. 비우면 기본 이름(스마트폰 키 N)으로 돌아간다. */
    fun renameKey(keyId: String, name: String) = viewModelScope.launch { repo.renameKey(keyId, name.trim().take(MAX_KEY_NAME)) }

    /** 시연용: [keyId] 키를 가진 사람이 차에 다가오는 상황을 만든다. */
    fun simulateKeyApproach(keyId: String) = viewModelScope.launch { key.simulateApproach(keyId) }

    /** 접근 감지 → 인증 → 운전자 식별 → 문 잠금 해제 → 시트 프리셋 적용 */
    private suspend fun onKeyEvent(e: DigitalKeyEvent) {
        when (e) {
            is DigitalKeyEvent.Approaching ->
                _state.update { it.copy(keyStatus = "디지털 키 감지 · 약 ${e.distanceM.toInt()}m") }
            is DigitalKeyEvent.Authenticated -> {
                if (!_state.value.parked) {
                    _state.update { it.copy(keyStatus = "기어가 P가 아니어서 문 열기와 시트 조절을 하지 않습니다.") }
                    return
                }
                val driver = _state.value.presets.firstOrNull { it.keyId == e.keyId }
                if (driver == null) {
                    _state.update { it.copy(keyStatus = "프리셋에 연결되지 않은 키입니다. 문을 열지 않습니다.") }
                    return
                }
                val unlocked = door.unlockAll()
                val doorText = if (unlocked) "문 잠금 해제" else "문 잠금 해제 실패"
                repo.setActive(driver.id)
                if (_state.value.ignitionOn == true) {
                    // 이미 시동이 켜져 있으면(누군가 타 있음) 이지 액세스 없이 바로 맞춘다.
                    _state.update { it.copy(keyStatus = "${driver.name} 인식 · $doorText · 시동이 켜져 있어 맞춤 자세로 이동") }
                    moveToDriver(driver)
                    return
                }
                // 시나리오 1: 먼저 이지 액세스로 시트를 뒤로 물려 타기 쉽게 하고, 맞춤 자세는 탑승 후 시동을 켤 때 적용한다.
                pendingDriver = driver
                _state.update { it.copy(keyStatus = "${driver.name} 인식 · $doorText · 이지 액세스(시트 뒤로)") }
                val s = _state.value
                if (s.supportedAxes.isNotEmpty() && SeatAxis.FORE_AFT in s.supportedAxes) {
                    moveSeat("이지 액세스", s.current + (SeatAxis.FORE_AFT to EASY_ACCESS_FORE_AFT))?.join()
                }
                _state.update { it.copy(keyStatus = "${driver.name} 인식 · $doorText · 탑승 후 시동을 켜면 맞춤 자세로 바뀌어요") }
            }
        }
    }

    /** 시동 ON(시동 버튼): 탑승 대기 중인 운전자가 있으면 그 맞춤 자세로 시트를 옮긴다. */
    private fun onIgnition(on: Boolean) {
        val was = _state.value.ignitionOn
        _state.update { it.copy(ignitionOn = on) }
        if (!on || was == true) return
        val driver = pendingDriver ?: return
        pendingDriver = null
        _state.update { it.copy(keyStatus = "시동 ON · ${driver.name} 맞춤 자세로 이동") }
        viewModelScope.launch { moveToDriver(driver) }
    }

    /** 폰 앱이 가져가는 차량 상태. 휴식이 끝나면 폰이 알림을 띄운다. */
    private fun statusJson(): String {
        val s = _state.value
        val rest = org.json.JSONObject().apply {
            when (val p = s.rest) {
                RestPhase.Idle -> put("phase", "Idle")
                is RestPhase.Suggested -> { put("phase", "Suggested"); put("area", p.area.name) }
                is RestPhase.Resting -> { put("phase", "Resting"); put("area", p.area.name); put("endsAtMillis", p.endsAtMillis) }
                is RestPhase.Finished -> { put("phase", "Finished"); put("area", p.area.name) }
            }
        }
        return org.json.JSONObject().apply {
            put("rest", rest)
            s.ignitionOn?.let { put("ignitionOn", it) }
            s.activePreset?.let { put("driver", it.name) }
            put("nowMillis", System.currentTimeMillis())
        }.toString()
    }

    /** 디버그 전용: 테스트용 시동 버튼. 켜져 있으면 끄고, 꺼져 있으면 켠다. */
    fun pressTestStartButton() = viewModelScope.launch {
        if (!powerMonitor.pressTestStartButton(on = _state.value.ignitionOn != true)) {
            _state.update { it.copy(message = "시동 버튼을 누르지 못했습니다. (Virtual Console 연결 확인)") }
        }
    }

    private suspend fun moveToDriver(driver: SeatPreset) {
        val job = apply(driver) ?: return // 기어 P 아님 등: apply 가 이유를 메시지로 남김
        job.join()
        _state.update { it.copy(keyStatus = "${driver.name} 맞춤 자세 적용 끝") }
    }

    private fun onLocation(lat: Double, lng: Double) {
        val area = restAreas.arrivedAt(lat, lng)
        if (area == null) quietAreaId = null
        _state.update { it.copy(location = lat to lng, arrivedArea = area) }
        updateRestSuggestion()
    }

    /** 시나리오 2: 휴게소·졸음쉼터 반경 안 + 기어 P(정차)이면 휴식모드를 제안한다. 조건이 깨지면 제안을 거둔다. */
    private fun updateRestSuggestion() {
        val s = _state.value
        val area = s.arrivedArea
        when (s.rest) {
            RestPhase.Idle -> if (area != null && s.stoppedForRest && area.id != quietAreaId) {
                _state.update { it.copy(rest = RestPhase.Suggested(area)) }
                notifier.speak("${area.name}에 도착했습니다. 휴식모드로 전환할까요?")
            }
            is RestPhase.Suggested -> if (area == null || !s.stoppedForRest) _state.update { it.copy(rest = RestPhase.Idle) }
            else -> Unit
        }
    }

    /** NaviHelper 로 지금 위치에서 가장 가까운 휴게소·졸음쉼터 경로 안내를 요청한다. */
    fun guideToNearestRestArea() {
        val (lat, lng) = _state.value.location ?: run {
            _state.update { it.copy(naviStatus = "위치를 아직 모릅니다.") }
            return
        }
        val nearest = restAreas.minByOrNull { com.myou.car.rest.distanceMeters(lat, lng, it.lat, it.lng) } ?: return
        val km = com.myou.car.rest.distanceMeters(lat, lng, nearest.lat, nearest.lng) / 1000
        val ok = navi.guideTo(nearest)
        _state.update { it.copy(naviStatus = if (ok) "${nearest.name}(%.1fkm)로 안내를 요청했어요.".format(km) else "내비게이션에 안내를 요청하지 못했어요.") }
    }

    fun declineRest() {
        val p = _state.value.rest as? RestPhase.Suggested ?: return
        quietAreaId = p.area.id
        _state.update { it.copy(rest = RestPhase.Idle) }
    }

    /** 무중력 시트 + 공조·조명 설정 후 휴식 타이머를 시작한다. 지금 자세(또는 현재 운전자 프리셋)는 복귀용으로 기억한다. */
    fun startRest() {
        val s = _state.value
        val p = s.rest as? RestPhase.Suggested ?: return
        val r = s.restSettings
        returnTo = s.activePreset?.let { it.name to it.positions } ?: ("휴식 전 자세" to s.current)
        // 운전자별 휴식 자세가 있으면 그것을, 없으면 차량 기본 휴식 자세를 쓴다.
        if (moveSeat("휴식 자세", s.activePreset?.restPositions ?: r.seat) == null) return // 기어 P 아님 등: moveSeat 가 이유를 메시지로 남김
        viewModelScope.launch {
            cabinBefore = cabin.snapshot() // 주행 복귀 때 되돌리려고 휴식 전 실내 환경을 기억한다.
            r.temperatureC?.let { cabin.setTemperature(it) }
            r.fanSpeed?.let { cabin.setFanSpeed(it) }
            r.ambientPercent?.let { cabin.setRestLighting(it) }
        }
        val endsAt = System.currentTimeMillis() + r.minutes * 60_000L
        _state.update { it.copy(rest = RestPhase.Resting(p.area, endsAt)) }
        restTimer = viewModelScope.launch {
            delay(r.minutes * 60_000L)
            finishRest(timeUp = true)
        }
    }

    /** 휴식 끝: 시간이 다 됐으면 알림과 음성으로 알린다. 시트는 '주행 복귀'를 눌러야 돌아간다. */
    fun finishRest(timeUp: Boolean = false) {
        val area = when (val p = _state.value.rest) {
            is RestPhase.Resting -> p.area
            else -> return
        }
        restTimer?.cancel()
        _state.update { it.copy(rest = RestPhase.Finished(area)) }
        if (timeUp) notifier.notify("휴식 시간이 끝났어요", "주행 복귀를 누르면 시트가 운전 자세로 돌아갑니다.")
    }

    /** 시나리오 3: 시트를 휴식 전 운전 자세로 되돌린다. */
    fun returnToDrive() {
        val area = when (val p = _state.value.rest) {
            is RestPhase.Resting -> p.area
            is RestPhase.Finished -> p.area
            else -> return
        }
        val (name, positions) = returnTo ?: return
        if (moveSeat(name, positions) == null) return
        restTimer?.cancel()
        returnTo = null
        cabinBefore?.let { before -> viewModelScope.launch { cabin.restore(before) } }
        cabinBefore = null
        quietAreaId = area.id
        _state.update { it.copy(rest = RestPhase.Idle) }
        notifier.speak("운전 자세로 돌아갑니다. 안전 운전하세요.")
    }

    fun changeRestMinutes(delta: Int) = updateRestSettings { r ->
        val choices = RestSettings.MINUTE_CHOICES
        val i = choices.indexOfFirst { it >= r.minutes }.coerceAtLeast(0)
        r.copy(minutes = choices[(i + delta).coerceIn(0, choices.lastIndex)])
    }

    fun changeRestTemperature(delta: Float) = updateRestSettings { r ->
        r.copy(temperatureC = ((r.temperatureC ?: 22f) + delta).coerceIn(16f, 30f))
    }

    /** 왼쪽에 보이는 자세(−/+ 수정 포함)를 휴식 자세로 저장한다. */
    fun saveCurrentAsRestSeat() {
        val s = _state.value
        if (!s.canSave) return
        val driver = s.activePreset
        if (driver != null) {
            // 현재 운전자가 있으면 그 운전자의 휴식 자세로 저장한다.
            viewModelScope.launch { repo.upsert(driver.copy(restPositions = s.draft)) }
        } else {
            updateRestSettings { it.copy(seat = s.draft) }
        }
        _state.update { it.copy(edits = emptyMap(), message = "지금 자세를 ${driver?.let { "'${it.name}'의 " } ?: ""}휴식 자세로 저장했습니다.") }
    }

    private fun updateRestSettings(change: (RestSettings) -> RestSettings) =
        viewModelScope.launch { repo.saveRestSettings(change(_state.value.restSettings)) }

    private fun onGear(gear: VehicleGear) {
        _state.update { it.copy(gear = gear) }
        updateRestSuggestion()
        // 휴식 중에 출발하려 하면 휴식을 끝내고, 시트를 먼저 되돌리라고 알린다 (P가 아니면 시트를 움직이지 않으므로).
        if (gear != VehicleGear.GEAR_PARK && _state.value.rest is RestPhase.Resting) {
            finishRest()
            _state.update { it.copy(message = "출발 전에 기어를 P에 두고 '주행 복귀'로 시트를 운전 자세로 되돌리세요.") }
            notifier.speak("시트가 휴식 자세입니다. 주행 복귀 후 출발하세요.")
        }
        // 시트가 움직이는 중에 주차 상태를 벗어나면 즉시 멈춘다 (취소 시 moveTo 가 STOP 을 보냄).
        if (gear != VehicleGear.GEAR_PARK && applyJob?.isActive == true) {
            applyJob?.cancel()
            _state.update { it.copy(applying = null, message = "기어가 P에서 바뀌어 시트 움직임을 멈췄습니다.") }
        }
    }

    fun apply(preset: SeatPreset): Job? = moveSeat(preset.name, preset.positions) { repo.setActive(preset.id) }

    /** 시트를 [positions]로 옮긴다. 기어가 P가 아니거나 이미 움직이는 중이면 null. */
    private fun moveSeat(name: String, positions: Map<SeatAxis, Int>, before: suspend () -> Unit = {}): Job? {
        if (!_state.value.parked) {
            _state.update { it.copy(message = "기어를 P(주차)에 두어야 시트를 움직일 수 있습니다.") }
            return null
        }
        if (applyJob?.isActive == true) return null
        return viewModelScope.launch {
            _state.update { it.copy(edits = emptyMap()) }
            before()
            val failed = mutableListOf<String>()
            for (axis in _state.value.supportedAxes) {
                val target = positions[axis] ?: continue
                val b = _state.value.bounds.getValue(axis)
                _state.update { it.copy(applying = "${axis.label} 맞추는 중…") }
                if (!seat.moveTo(axis, target.coerceIn(b.minValue, b.maxValue))) failed += axis.label
            }
            _state.update {
                it.copy(
                    applying = null,
                    message = if (failed.isEmpty()) "'$name'(으)로 맞췄습니다."
                    else "일부 항목을 맞추지 못했습니다: ${failed.joinToString()}",
                )
            }
        }.also { applyJob = it }
    }

    fun cancelApply() {
        applyJob?.cancel()
        _state.update { it.copy(applying = null, message = "적용을 멈췄습니다.") }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        if (::seat.isInitialized) seat.release()
        door.release()
        gearMonitor.release()
        powerMonitor.release()
        navi.release()
        locationMonitor.release()
        cabin.release()
        notifier.release()
    }

    private companion object {
        /** 이 시간 동안 값 변화가 없으면 조절이 끝난 것으로 본다. */
        const val SETTLE_MS = 1_500L
        /** shortcut: 앞뒤 위치는 값이 클수록 뒤쪽이라고 가정(휴식 자세와 같은 가정), 실차에서 방향을 확인한다. */
        const val EASY_ACCESS_FORE_AFT = 100
    }
}
