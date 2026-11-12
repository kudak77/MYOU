package com.myou.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myou.phone.data.DEFAULT_POSITIONS
import com.myou.phone.data.DEMO_KEYS
import com.myou.phone.data.DigitalKey
import com.myou.phone.data.MAX_NAME
import com.myou.phone.data.ProfileRepository
import com.myou.phone.data.RestPrefs
import com.myou.phone.data.SeatPreset
import com.myou.phone.sync.CarLink
import com.myou.phone.sync.CarStatus
import com.myou.phone.sync.CarSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PhoneUiState(
    val profiles: List<SeatPreset> = emptyList(),
    /** 사용자가 바꾼 이름이 반영된 키 */
    val keys: List<DigitalKey> = DEMO_KEYS,
    /** 편집 중인 프로필(저장 전 사본). null 이면 목록 화면 */
    val editing: SeatPreset? = null,
    val isNew: Boolean = false,
    /** 차량으로 보내기 결과 문구 */
    val syncStatus: String? = null,
    val syncing: Boolean = false,
    val rest: RestPrefs = RestPrefs(),
    /** 마지막으로 받은 차량 상태. null = 차량에 연결 안 됨 */
    val car: CarStatus? = null,
)

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProfileRepository(app)
    private val editor = MutableStateFlow<Pair<SeatPreset, Boolean>?>(null)
    private val sync = MutableStateFlow<Pair<String?, Boolean>>(null to false)

    private val base = combine(repo.profiles, repo.keyNames, editor, sync, repo.restPrefs) { profiles, names, edit, (status, syncing), rest ->
        PhoneUiState(
            rest = rest,
            profiles = profiles,
            keys = DEMO_KEYS.map { k -> k.copy(label = names[k.id] ?: k.label) },
            editing = edit?.first,
            isNew = edit?.second ?: false,
            syncStatus = status,
            syncing = syncing,
        )
    }
    val state: StateFlow<PhoneUiState> = combine(base, CarLink.status) { s, car -> s.copy(car = car) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhoneUiState())

    fun newProfile() = editor.update { SeatPreset(UUID.randomUUID().toString(), "", DEFAULT_POSITIONS) to true }

    fun edit(p: SeatPreset) = editor.update { p to false }

    fun updateDraft(change: (SeatPreset) -> SeatPreset) = editor.update { it?.let { (p, isNew) -> change(p) to isNew } }

    fun closeEditor() = editor.update { null }

    /** 이름을 비우면 "운전자 N" 으로 저장한다. */
    fun saveDraft() {
        val p = editor.value?.first ?: return
        val name = p.name.trim().take(MAX_NAME).ifBlank { "운전자 ${state.value.profiles.size + 1}" }
        viewModelScope.launch {
            repo.save(p.copy(name = name))
            closeEditor()
        }
    }

    fun deleteDraft() {
        val p = editor.value?.first ?: return
        viewModelScope.launch {
            repo.delete(p.id)
            closeEditor()
        }
    }

    /** 프로필 전체와 키 이름을 차량 앱으로 보낸다. 차량 쪽 프로필은 폰 내용으로 바뀐다. */
    fun sendToCar() {
        if (sync.value.second) return
        val s = state.value
        sync.value = "차량으로 보내는 중…" to true
        viewModelScope.launch {
            sync.value = runCatching { CarSync.send(s.profiles, s.keys, s.rest) }.fold(
                onSuccess = { n -> "차량에 프로필 ${n}개를 보냈어요." to false },
                onFailure = { "차량에 연결하지 못했어요. 차량 앱이 켜져 있는지 확인하세요." to false },
            )
        }
    }

    fun changeRestMinutes(delta: Int) = updateRest { r ->
        val c = RestPrefs.MINUTE_CHOICES
        val i = c.indexOfFirst { it >= r.minutes }.coerceAtLeast(0)
        r.copy(minutes = c[(i + delta).coerceIn(0, c.lastIndex)])
    }
    fun changeRestTemperature(delta: Float) = updateRest { it.copy(temperatureC = (it.temperatureC + delta).coerceIn(16f, 30f)) }
    fun changeRestFan(delta: Int) = updateRest { it.copy(fanSpeed = (it.fanSpeed + delta).coerceIn(0, 8)) }
    fun changeRestAmbient(delta: Int) = updateRest { it.copy(ambientPercent = (it.ambientPercent + delta).coerceIn(0, 100)) }
    private fun updateRest(change: (RestPrefs) -> RestPrefs) = viewModelScope.launch { repo.saveRestPrefs(change(state.value.rest)) }

    /** 차량에 저장된 프로필·키 이름·휴식 설정을 폰으로 가져온다 (폰 쪽 내용은 바뀐다). */
    fun pullFromCar() {
        if (sync.value.second) return
        sync.value = "차량에서 가져오는 중…" to true
        viewModelScope.launch {
            sync.value = runCatching { repo.importFromCar(CarSync.fetchProfiles()) }.fold(
                onSuccess = { n -> "차량에서 프로필 ${n}개를 가져왔어요." to false },
                onFailure = { "차량에 연결하지 못했어요. 차량 앱이 켜져 있는지 확인하세요." to false },
            )
        }
    }

    /** 비우면 기본 이름(스마트폰 키 N)으로 돌아간다. */
    fun renameKey(keyId: String, name: String) = viewModelScope.launch { repo.renameKey(keyId, name.trim().take(MAX_NAME)) }
}
