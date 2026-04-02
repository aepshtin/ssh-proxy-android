package com.sshvpn.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sshvpn.app.R
import com.sshvpn.app.data.SshProfile
import com.sshvpn.app.databinding.ActivityProfileEditBinding
import com.sshvpn.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class ProfileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private val viewModel: MainViewModel by viewModels()
    private var editingProfileId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        editingProfileId = intent.getLongExtra("profile_id", 0)

        if (editingProfileId > 0) {
            supportActionBar?.title = getString(R.string.edit_profile)
            loadProfile()
        } else {
            supportActionBar?.title = getString(R.string.add_profile)
            binding.editPort.setText("22")
        }

        binding.btnSave.setOnClickListener { saveProfile() }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            val profile = viewModel.getProfile(editingProfileId)
            if (profile != null) {
                binding.editName.setText(profile.name)
                binding.editHost.setText(profile.host)
                binding.editPort.setText(profile.port.toString())
                binding.editUsername.setText(profile.username)
                binding.editPassword.setText(profile.password)
            }
        }
    }

    private fun saveProfile() {
        val name = binding.editName.text.toString().trim()
        val host = binding.editHost.text.toString().trim()
        val portStr = binding.editPort.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()
        val password = binding.editPassword.text.toString()

        if (name.isEmpty()) {
            binding.editName.error = getString(R.string.field_required)
            return
        }
        if (host.isEmpty()) {
            binding.editHost.error = getString(R.string.field_required)
            return
        }
        if (username.isEmpty()) {
            binding.editUsername.error = getString(R.string.field_required)
            return
        }
        if (password.isEmpty()) {
            binding.editPassword.error = getString(R.string.field_required)
            return
        }

        val port = portStr.toIntOrNull() ?: 22
        if (port < 1 || port > 65535) {
            binding.editPort.error = getString(R.string.invalid_port)
            return
        }

        val profile = SshProfile(
            id = editingProfileId,
            name = name,
            host = host,
            port = port,
            username = username,
            password = password
        )

        viewModel.saveProfile(profile)
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
