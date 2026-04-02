package com.sshvpn.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_profiles")
data class SshProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    val usePerAppVpn: Boolean = false,
    val allowedApps: String = ""  // comma-separated package names
)
