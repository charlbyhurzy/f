package com.futura.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    sealed interface BootstrapPhase {
        data object Loading : BootstrapPhase
        data object Ready : BootstrapPhase
        data class Failed(val reason: String) : BootstrapPhase
    }

    private val _phase = MutableStateFlow<BootstrapPhase>(BootstrapPhase.Loading)
    val phase: StateFlow<BootstrapPhase> = _phase.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        startBootstrap()
    }

    fun retryBootstrap() {
        if (_phase.value is BootstrapPhase.Failed) {
            startBootstrap()
        }
    }

    private fun startBootstrap() {
        _phase.value = BootstrapPhase.Loading
        _isReady.value = false

        viewModelScope.launch {
            try {
                runBootstrap()
                _phase.value = BootstrapPhase.Ready
                _isReady.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
                _phase.value = BootstrapPhase.Failed(
                    e.message ?: "Unknown bootstrap error"
                )
                _isReady.value = false
            }
        }
    }

    private suspend fun runBootstrap() {
        // Subsystem initialization will be added as its dependencies arrive.
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
