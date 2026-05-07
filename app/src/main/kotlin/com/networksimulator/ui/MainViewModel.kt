package com.networksimulator.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.networksimulator.model.NetworkProfile
import com.networksimulator.model.ProfileType
import com.networksimulator.model.SimulationConfig
import com.networksimulator.stats.StatSnapshot
import com.networksimulator.vpn.NetworkSimulatorVpnService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ── Profile selection ─────────────────────────────────────────────────────

    private val _selectedProfile = MutableLiveData(NetworkProfile.allProfiles().first())
    val selectedProfile: LiveData<NetworkProfile> = _selectedProfile

    // ── Custom profile sliders ────────────────────────────────────────────────

    private val _customLatency   = MutableLiveData(100L)
    private val _customJitter    = MutableLiveData(20L)
    private val _customLoss      = MutableLiveData(5f)
    private val _customBandwidth = MutableLiveData(-1)

    val customLatency:   LiveData<Long>  = _customLatency
    val customJitter:    LiveData<Long>  = _customJitter
    val customLoss:      LiveData<Float> = _customLoss
    val customBandwidth: LiveData<Int>   = _customBandwidth

    // ── Target app ────────────────────────────────────────────────────────────

    private val _targetPackage = MutableLiveData("")
    val targetPackage: LiveData<String> = _targetPackage

    // ── VPN running state ─────────────────────────────────────────────────────

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    // ── Current Train phase ───────────────────────────────────────────────────

    private val _currentPhaseLabel = MutableLiveData("—")
    val currentPhaseLabel: LiveData<String> = _currentPhaseLabel

    // ── Stats log (newest first) ──────────────────────────────────────────────

    private val _statsLog = MutableLiveData<List<StatSnapshot>>(emptyList())
    val statsLog: LiveData<List<StatSnapshot>> = _statsLog

    private val _latestSnapshot = MutableLiveData<StatSnapshot?>()
    val latestSnapshot: LiveData<StatSnapshot?> = _latestSnapshot

    // ── Events ────────────────────────────────────────────────────────────────

    private val _event = MutableLiveData<UiEvent?>()
    val event: LiveData<UiEvent?> = _event

    sealed class UiEvent {
        data class RequestVpnPermission(val prepareIntent: Intent) : UiEvent()
        object VpnStarted : UiEvent()
        object VpnStopped : UiEvent()
    }

    // ── Stats collection ──────────────────────────────────────────────────────

    init {
        // Start collecting snapshots and phase labels from the companion singletons.
        attachStatsCollector()
        attachPhaseCollector()
    }

    private fun attachStatsCollector() {
        NetworkSimulatorVpnService.stats.snapshots
            .onEach { snap ->
                _latestSnapshot.postValue(snap)
                val current = _statsLog.value ?: emptyList()
                // Keep last 300 entries (~5 minutes at 1/sec)
                _statsLog.postValue(listOf(snap) + current.take(299))
            }
            .launchIn(viewModelScope)
    }

    private fun attachPhaseCollector() {
        NetworkSimulatorVpnService.currentPhaseLabel
            .onEach { label -> _currentPhaseLabel.postValue(label) }
            .launchIn(viewModelScope)
    }

    // ── Screen marker ─────────────────────────────────────────────────────────

    /**
     * Stamps the next stats snapshot with [label].
     * Call this from the UI whenever the tester moves to a new screen.
     */
    fun markScreen(label: String) {
        NetworkSimulatorVpnService.stats.pendingScreenLabel = label.trim()
    }

    fun clearLog() { _statsLog.value = emptyList() }

    // ── Profile API ───────────────────────────────────────────────────────────

    fun selectProfile(type: ProfileType) {
        _selectedProfile.value = when (type) {
            ProfileType.SLOW_3G      -> NetworkProfile.slow3G()
            ProfileType.HIGH_LATENCY -> NetworkProfile.highLatency()
            ProfileType.PACKET_LOSS  -> NetworkProfile.packetLoss()
            ProfileType.CUSTOM       -> buildCustomProfile()
            ProfileType.TRAIN        -> NetworkProfile.train()
        }
    }

    fun setCustomLatency(ms: Long)        { _customLatency.value   = ms;      refreshCustom() }
    fun setCustomJitter(ms: Long)         { _customJitter.value    = ms;      refreshCustom() }
    fun setCustomLoss(percent: Float)     { _customLoss.value      = percent; refreshCustom() }
    fun setCustomBandwidth(kbps: Int)     { _customBandwidth.value = kbps;    refreshCustom() }
    fun setTargetPackage(pkg: String)     { _targetPackage.value   = pkg.trim() }

    fun toggleVpn(prepareIntent: Intent?) {
        if (_isRunning.value == true) stopVpn()
        else if (prepareIntent != null) _event.value = UiEvent.RequestVpnPermission(prepareIntent)
        else startVpn()
    }

    fun onVpnPermissionGranted() = startVpn()
    fun consumeEvent()           { _event.value = null }

    fun refreshRunningState() {
        val running = NetworkSimulatorVpnService.isRunning
        _isRunning.value = running
        // Drop a stale VpnStopped event that accumulated while the activity was
        // in the background — prevents a phantom "Simulation stopped" toast on resume.
        if (_event.value is UiEvent.VpnStopped && running) _event.value = null
        // Also drop a stale VpnStopped if the VPN is genuinely not running but
        // _isRunning was never reset (service died without telling the ViewModel).
        if (!running && _isRunning.value == true) _event.value = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun refreshCustom() {
        if (_selectedProfile.value?.type == ProfileType.CUSTOM)
            _selectedProfile.value = buildCustomProfile()
    }

    private fun buildCustomProfile() = NetworkProfile.custom(
        latencyMs         = _customLatency.value   ?: 100L,
        jitterMs          = _customJitter.value    ?: 20L,
        packetLossPercent = _customLoss.value      ?: 5f,
        bandwidthKbps     = _customBandwidth.value ?: -1
    )

    private fun startVpn() {
        val config = SimulationConfig(
            isEnabled         = true,
            profile           = _selectedProfile.value ?: NetworkProfile.slow3G(),
            targetPackageName = _targetPackage.value   ?: ""
        )
        val ctx = getApplication<Application>()
        ctx.startForegroundService(
            Intent(ctx, NetworkSimulatorVpnService::class.java).apply {
                action = NetworkSimulatorVpnService.ACTION_START
                putExtra(NetworkSimulatorVpnService.EXTRA_CONFIG, config)
            }
        )
        _isRunning.value = true
        _event.value     = UiEvent.VpnStarted
        // No need to re-attach — stats is a stable singleton, subscription is live
    }

    private fun stopVpn() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, NetworkSimulatorVpnService::class.java).apply {
                action = NetworkSimulatorVpnService.ACTION_STOP
            }
        )
        _isRunning.value = false
        _event.value     = UiEvent.VpnStopped
    }
}
