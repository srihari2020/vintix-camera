package com.srihari.vintix.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    val settings: StateFlow<VintixSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = VintixSettings()
    )

    fun setTimestampEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setTimestampEnabled(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setHapticsEnabled(enabled) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setSoundEnabled(enabled) }
    }

    fun setExportResolution(preset: ExportResolutionPreset) {
        viewModelScope.launch { repository.setExportResolution(preset) }
    }

    fun setExportQuality(preset: ExportQualityPreset) {
        viewModelScope.launch { repository.setExportQuality(preset) }
    }
}
