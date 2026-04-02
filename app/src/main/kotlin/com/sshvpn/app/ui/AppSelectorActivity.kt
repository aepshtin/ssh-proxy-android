package com.sshvpn.app.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sshvpn.app.R
import com.sshvpn.app.data.SshProfile
import com.sshvpn.app.databinding.ActivityAppSelectorBinding
import com.sshvpn.app.databinding.ItemAppBinding
import com.sshvpn.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectorBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = AppListAdapter()
    private var profileId: Long = 0
    private var profile: SshProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.select_apps)

        profileId = intent.getLongExtra("profile_id", 0)
        if (profileId == 0L) {
            finish()
            return
        }

        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter

        binding.switchPerApp.setOnCheckedChangeListener { _, isChecked ->
            binding.recyclerApps.isEnabled = isChecked
            adapter.isEnabled = isChecked
            adapter.notifyDataSetChanged()
        }

        binding.btnSave.setOnClickListener { save() }

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            profile = viewModel.getProfile(profileId)
            val selectedApps = profile?.allowedApps?.split(",")
                ?.filter { it.isNotBlank() }
                ?.map { it.trim() }
                ?.toSet() ?: emptySet()

            binding.switchPerApp.isChecked = profile?.usePerAppVpn == true

            val apps = withContext(Dispatchers.IO) {
                loadInstalledApps()
            }

            adapter.apps = apps
            adapter.selectedPackages = selectedApps.toMutableSet()
            adapter.isEnabled = binding.switchPerApp.isChecked
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || isImportantSystemApp(it) }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = appInfo
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun isImportantSystemApp(info: ApplicationInfo): Boolean {
        val important = setOf(
            "com.android.chrome", "com.android.browser",
            "com.google.android.youtube", "com.google.android.gm"
        )
        return info.packageName in important
    }

    private fun save() {
        val usePerApp = binding.switchPerApp.isChecked
        val selectedApps = if (usePerApp) {
            adapter.selectedPackages.joinToString(",")
        } else {
            ""
        }

        val updated = profile?.copy(
            usePerAppVpn = usePerApp,
            allowedApps = selectedApps
        ) ?: return

        viewModel.saveProfile(updated)
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: ApplicationInfo
)

class AppListAdapter : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    var apps = listOf<AppInfo>()
    var selectedPackages = mutableSetOf<String>()
    var isEnabled = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size

    inner class ViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            val pm = binding.root.context.packageManager
            binding.appName.text = app.label
            binding.appPackage.text = app.packageName
            binding.appIcon.setImageDrawable(pm.getApplicationIcon(app.icon))
            binding.appCheckbox.isChecked = app.packageName in selectedPackages
            binding.appCheckbox.isEnabled = isEnabled

            val clickListener = {
                if (isEnabled) {
                    val checked = !binding.appCheckbox.isChecked
                    binding.appCheckbox.isChecked = checked
                    if (checked) {
                        selectedPackages.add(app.packageName)
                    } else {
                        selectedPackages.remove(app.packageName)
                    }
                }
            }

            binding.root.setOnClickListener { clickListener() }
            binding.appCheckbox.setOnClickListener {
                if (binding.appCheckbox.isChecked) {
                    selectedPackages.add(app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                }
            }
        }
    }
}
