package com.sshvpn.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sshvpn.app.SshVpnApp
import com.sshvpn.app.data.SshProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as SshVpnApp).database.sshProfileDao()

    val profiles = dao.getAllProfiles()

    private val _connectionStatus = MutableStateFlow("disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _activeProfileId = MutableStateFlow<Long?>(null)
    val activeProfileId: StateFlow<Long?> = _activeProfileId.asStateFlow()

    fun updateStatus(status: String) {
        _connectionStatus.value = status
    }

    fun setActiveProfile(id: Long?) {
        _activeProfileId.value = id
    }

    fun deleteProfile(profile: SshProfile) {
        viewModelScope.launch {
            dao.delete(profile)
        }
    }

    suspend fun getProfile(id: Long): SshProfile? {
        return dao.getProfileById(id)
    }

    fun saveProfile(profile: SshProfile) {
        viewModelScope.launch {
            dao.insert(profile)
        }
    }
}
