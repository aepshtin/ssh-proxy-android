package com.sshvpn.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sshvpn.app.R
import com.sshvpn.app.data.SshProfile
import com.sshvpn.app.databinding.ActivityMainBinding
import com.sshvpn.app.databinding.ItemProfileBinding
import com.sshvpn.app.viewmodel.MainViewModel
import com.sshvpn.app.vpn.SshVpnService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = ProfileAdapter()
    private var pendingProfile: SshProfile? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingProfile?.let { connectToProfile(it) }
        }
        pendingProfile = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(SshVpnService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(SshVpnService.EXTRA_MESSAGE) ?: ""
            viewModel.updateStatus(status)
            updateUi(status, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        setupRecyclerView()
        setupFab()
        requestNotificationPermission()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SshVpnService.ACTION_STATUS_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun setupRecyclerView() {
        binding.recyclerProfiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerProfiles.adapter = adapter

        adapter.onConnectClick = { profile ->
            if (viewModel.connectionStatus.value == SshVpnService.STATUS_CONNECTED) {
                disconnect()
            } else {
                requestVpnPermission(profile)
            }
        }

        adapter.onMenuClick = { view, profile ->
            showProfileMenu(view, profile)
        }
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, ProfileEditActivity::class.java))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profiles.collect { profiles ->
                    adapter.submitList(profiles)
                    binding.emptyView.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerProfiles.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionStatus.collect { status ->
                    adapter.activeProfileId = viewModel.activeProfileId.value
                    adapter.isConnected = status == SshVpnService.STATUS_CONNECTED
                    adapter.notifyDataSetChanged()
                    updateStatusBar(status)
                }
            }
        }
    }

    private fun updateStatusBar(status: String) {
        when (status) {
            SshVpnService.STATUS_CONNECTED -> {
                binding.statusBar.visibility = View.VISIBLE
                binding.statusBar.setBackgroundColor(getColor(R.color.connected))
                binding.statusText.text = getString(R.string.status_connected)
            }
            SshVpnService.STATUS_DISCONNECTED -> {
                binding.statusBar.visibility = View.GONE
            }
            SshVpnService.STATUS_ERROR -> {
                binding.statusBar.visibility = View.VISIBLE
                binding.statusBar.setBackgroundColor(getColor(R.color.error))
                binding.statusText.text = getString(R.string.status_error)
            }
        }
    }

    private fun requestVpnPermission(profile: SshProfile) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingProfile = profile
            vpnPermissionLauncher.launch(intent)
        } else {
            connectToProfile(profile)
        }
    }

    private fun connectToProfile(profile: SshProfile) {
        viewModel.setActiveProfile(profile.id)

        val intent = Intent(this, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_CONNECT
            putExtra(SshVpnService.EXTRA_HOST, profile.host)
            putExtra(SshVpnService.EXTRA_PORT, profile.port)
            putExtra(SshVpnService.EXTRA_USERNAME, profile.username)
            putExtra(SshVpnService.EXTRA_PASSWORD, profile.password)
            putExtra(SshVpnService.EXTRA_PER_APP, profile.usePerAppVpn)
            putExtra(SshVpnService.EXTRA_ALLOWED_APPS, profile.allowedApps)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun disconnect() {
        val intent = Intent(this, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        viewModel.setActiveProfile(null)
    }

    private fun showProfileMenu(view: View, profile: SshProfile) {
        PopupMenu(this, view).apply {
            menuInflater.inflate(R.menu.profile_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> {
                        val intent = Intent(this@MainActivity, ProfileEditActivity::class.java)
                        intent.putExtra("profile_id", profile.id)
                        startActivity(intent)
                        true
                    }
                    R.id.action_delete -> {
                        confirmDelete(profile)
                        true
                    }
                    R.id.action_select_apps -> {
                        val intent = Intent(this@MainActivity, AppSelectorActivity::class.java)
                        intent.putExtra("profile_id", profile.id)
                        startActivity(intent)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun confirmDelete(profile: SshProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_profile)
            .setMessage(getString(R.string.delete_confirm, profile.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteProfile(profile)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateUi(status: String, message: String) {
        when (status) {
            SshVpnService.STATUS_ERROR -> {
                Toast.makeText(this, "Error: $message", Toast.LENGTH_LONG).show()
            }
            SshVpnService.STATUS_CONNECTED -> {
                Toast.makeText(this, R.string.status_connected, Toast.LENGTH_SHORT).show()
            }
            SshVpnService.STATUS_DISCONNECTED -> {
                Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

    private var profiles = listOf<SshProfile>()
    var activeProfileId: Long? = null
    var isConnected: Boolean = false
    var onConnectClick: ((SshProfile) -> Unit)? = null
    var onMenuClick: ((View, SshProfile) -> Unit)? = null

    fun submitList(list: List<SshProfile>) {
        profiles = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(profiles[position])
    }

    override fun getItemCount() = profiles.size

    inner class ViewHolder(private val binding: ItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: SshProfile) {
            binding.profileName.text = profile.name
            binding.profileHost.text = "${profile.host}:${profile.port}"
            binding.profileUser.text = profile.username

            val isActive = activeProfileId == profile.id && isConnected
            binding.btnConnect.text = if (isActive) {
                binding.root.context.getString(R.string.disconnect)
            } else {
                binding.root.context.getString(R.string.connect)
            }

            binding.statusIndicator.visibility = if (isActive) View.VISIBLE else View.GONE

            binding.btnConnect.setOnClickListener {
                onConnectClick?.invoke(profile)
            }

            binding.btnMenu.setOnClickListener {
                onMenuClick?.invoke(it, profile)
            }
        }
    }
}
