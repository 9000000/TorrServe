package ru.yourok.torrserve.ui.fragments.main.update.server

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.server.local.ServerFile
import ru.yourok.torrserve.server.local.TorrService
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.dialogs.DialogList
import ru.yourok.torrserve.ui.fragments.TSFragment
import ru.yourok.torrserve.utils.Format
import java.io.File
import java.util.Timer
import kotlin.concurrent.timerTask

class ServerUpdateFragment : TSFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vi = inflater.inflate(R.layout.server_update_fragment, container, false)
        vi.findViewById<Button>(R.id.btnUpdate)?.also { btn ->
            btn.setOnClickListener {
                btn.isEnabled = false
                lifecycleScope.launch(Dispatchers.IO) {
                    showProgress()
                    try {
                        Settings.setHost("") // revert to local server
                        UpdaterServer.updateFromNet {
                            lifecycleScope.launch(Dispatchers.Main) {
                                showProgress(it)
                            }
                        }
                        TorrService.wait(5)
                        updateUI()
                        hideProgress()
                        withContext(Dispatchers.Main) {
                            btn.isEnabled = true
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            App.toast(App.context.getString(R.string.warn_error_download_server) + ": " + e.message, true)
                            btn.isEnabled = true
                        }
                        hideProgress()
                    }
                }
            }
        }

        vi.findViewById<View>(R.id.layoutServerRepo)?.setOnClickListener {
            showChangeRepoDialog()
        }

        vi.findViewById<Button>(R.id.btnChangeRepo)?.setOnClickListener {
            showChangeRepoDialog()
        }

        vi.findViewById<Button>(R.id.btnRefresh)?.setOnClickListener {
            view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text = getString(R.string.loading_torrent)
            updateUI(force = true)
        }

        vi.findViewById<Button>(R.id.btnSelectVersion)?.setOnClickListener {
            showSelectVersionDialog()
        }

        vi.findViewById<Button>(R.id.btnUpdateDownload).setOnClickListener {
            installFromDownload()
        }

        vi.findViewById<Button>(R.id.btnDeleteServer).setOnClickListener {
            lifecycleScope.launch {
                showProgress()
                TorrService.stop()
                ServerFile().stop()
                ServerFile().delete()
                updateUI()
                hideProgress()
            }
            clickOnMenu()
        }

        vi.findViewById<Button>(R.id.btnDownloadFFProbe)?.also { btn ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                btn.visibility = View.VISIBLE
                val tvInfo = vi.findViewById<TextView>(R.id.tvInfo)
                val file = File(App.context.filesDir, "ffprobe")
                tvInfo.alpha = 0.7f

                if (file.exists()) {
                    btn.setText(R.string.delete_ffprobe)
                    tvInfo.visibility = View.INVISIBLE
                } else {
                    btn.setText(R.string.install_ffprobe)
                    tvInfo.visibility = View.VISIBLE
                }

                btn.setOnClickListener {
                    btn.isEnabled = false
                    if (file.exists()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            showProgress()
                            try {
                                file.delete()
                                hideProgress()
                                withContext(Dispatchers.Main) {
                                    btn.setText(R.string.install_ffprobe)
                                    btn.isEnabled = true
                                }
                            } catch (_: Exception) {
                                hideProgress()
                            }
                        }
                        tvInfo.visibility = View.VISIBLE
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            showProgress()
                            try {
                                UpdaterServer.downloadFFProbe {
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        showProgress(it)
                                    }
                                }
                                delay(1000)
                                hideProgress()
                                withContext(Dispatchers.Main) {
                                    btn.setText(R.string.delete_ffprobe)
                                    btn.isEnabled = true
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    App.toast(App.context.getString(R.string.error_download_ffprobe) + ": " + e.message, true)
                                    btn.isEnabled = true
                                }
                                hideProgress()
                            }
                        }
                        tvInfo.visibility = View.INVISIBLE
                    }
                }
            } else {
                btn.visibility = View.GONE
            }
        }

        return vi
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateUI(force = true)
    }

    override fun onStop() {
        lifecycleScope.launch(Dispatchers.Main) {
            hideProgress()
        }
        super.onStop()
    }

    private fun updateUI(force: Boolean = true) {
        lifecycleScope.launch(Dispatchers.IO) {
            val arch = UpdaterServer.getArch()
            val localVer = UpdaterServer.getLocalVersion()
            val currentRepo = Settings.getTorrServerRepo()
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.tvArch)?.text = arch
                view?.findViewById<TextView>(R.id.tvLocalVersion)?.text = localVer
                view?.findViewById<TextView>(R.id.tvServerRepo)?.text = currentRepo
                if (force && (view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text.isNullOrBlank() ||
                        view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text == getString(R.string.no_updates))) {
                    view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text = getString(R.string.loading_torrent)
                }
            }
            UpdaterServer.check(force = force)
            val remoteVer = UpdaterServer.getRemoteVersion()
            val avail = UpdaterServer.getAvailableVersions()
            val curVer = UpdaterServer.getVersion()?.version ?: ""
            withContext(Dispatchers.Main) {
                val displayVer = if (remoteVer == getString(R.string.no_updates) && curVer.isNotEmpty()) {
                    "$curVer (${getString(R.string.no_updates)})"
                } else {
                    remoteVer
                }
                view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text = displayVer
                view?.findViewById<Button>(R.id.btnSelectVersion)?.visibility =
                    if (avail.size > 1) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showChangeRepoDialog() {
        val ctx = context ?: return
        val currentRepo = Settings.getTorrServerRepo()
        val gitSource = UpdaterServer.parseGitSource(currentRepo)
        val baseRepo = "${gitSource.owner}/${gitSource.repo}"

        val options = mutableListOf<String>()
        val values = mutableListOf<String>()

        val currentBranch = gitSource.branch.ifEmpty { "tet" }

        val bTet = "$baseRepo@tet"
        val bTetLabel = if (currentBranch.equals("tet", true)) "$bTet ✓ (Nhánh tet - Beta / Mới nhất)" else "$bTet (Nhánh tet - Beta / Mới nhất)"
        options.add(bTetLabel)
        values.add(bTet)

        val bMaster = "$baseRepo@master"
        val bMasterLabel = if (currentBranch.equals("master", true)) "$bMaster ✓ (Nhánh master - Stable / Chính thức)" else "$bMaster (Nhánh master - Stable / Chính thức)"
        options.add(bMasterLabel)
        values.add(bMaster)

        options.add(getString(R.string.custom_repo_entry))
        values.add("CUSTOM")

        AlertDialog.Builder(ctx)
            .setTitle(R.string.server_repo_dialog_title)
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = values[which]
                if (chosen == "CUSTOM") {
                    showCustomRepoInputDialog()
                } else {
                    applyNewRepo(chosen)
                }
            }
            .setNeutralButton(R.string.default_repo) { _, _ ->
                applyNewRepo(Settings.DEFAULT_TORRSERVER_REPO)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomRepoInputDialog() {
        val ctx = context ?: return
        val currentRepo = Settings.getTorrServerRepo()
        val input = AppCompatEditText(ctx).apply {
            setText(currentRepo)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.server_repo_dialog_hint)
            isSingleLine = true
        }

        val container = FrameLayout(ctx).apply {
            val pad = Format.dp2px(20f)
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(input)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.server_repo_dialog_title)
            .setMessage(R.string.server_repo_dialog_hint)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newRepo = input.text?.toString()?.trim() ?: ""
                applyNewRepo(if (newRepo.isEmpty()) Settings.DEFAULT_TORRSERVER_REPO else newRepo)
            }
            .setNeutralButton(R.string.default_repo) { _, _ ->
                applyNewRepo(Settings.DEFAULT_TORRSERVER_REPO)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyNewRepo(repo: String) {
        Settings.setTorrServerRepo(repo)
        UpdaterServer.resetCache()
        view?.findViewById<TextView>(R.id.tvServerRepo)?.text = Settings.getTorrServerRepo()
        view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text = getString(R.string.loading_torrent)
        lifecycleScope.launch(Dispatchers.IO) {
            showProgress()
            val success = UpdaterServer.check(force = true)
            val remoteVer = UpdaterServer.getRemoteVersion()
            val avail = UpdaterServer.getAvailableVersions()
            val curVer = UpdaterServer.getVersion()?.version ?: ""
            withContext(Dispatchers.Main) {
                val displayVer = if (remoteVer == getString(R.string.no_updates) && curVer.isNotEmpty()) {
                    "$curVer (${getString(R.string.no_updates)})"
                } else {
                    remoteVer
                }
                view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text = displayVer
                view?.findViewById<Button>(R.id.btnSelectVersion)?.visibility =
                    if (avail.size > 1) View.VISIBLE else View.GONE
                hideProgress()
                if (success) {
                    App.toast("${getString(R.string.update_server)}: $displayVer")
                } else {
                    App.toast(R.string.warn_error_check_ver, true)
                }
            }
        }
    }

    private fun showSelectVersionDialog() {
        val list = UpdaterServer.getAvailableVersions()
        if (list.isEmpty()) {
            App.toast(R.string.no_updates)
            return
        }
        val names = list.map { it.version }
        DialogList.show(requireContext(), getString(R.string.select_version_title), names) { chosenName, pos ->
            val chosenVer = list[pos]
            UpdaterServer.setVersion(chosenVer)
            view?.findViewById<TextView>(R.id.tvRemoteVersion)?.text = chosenName
            App.toast(chosenName)
        }
    }

    private var countClick = 0
    private var timer: Timer? = null

    private fun clickOnMenu() {
        if (timer != null)
            timer?.cancel()
        timer = Timer()
        timer?.schedule(timerTask {
            countClick = 0
        }, 3000)

        countClick++
        if (countClick > 4)
            view?.findViewById<Button>(R.id.btnUpdateDownload)?.visibility = View.VISIBLE
    }

    private fun installFromDownload() {
        val dw = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = dw.listFiles { file ->
            if (file.isFile)
                file.name.contains("TorrServer", true)
            else
                false
        }
        if (files.isNullOrEmpty()) {
            App.toast(R.string.warn_no_local_updates, true)
            return
        }

        DialogList.show(context ?: return, "", files.map { it.name }) { _: String, pos: Int ->
            lifecycleScope.launch(Dispatchers.IO) {
                val file = files[pos]
                showProgress()
                try {
                    Settings.setHost("") // revert to local server
                    UpdaterServer.updateFromFile(file.path)
                    delay(1000)
                    updateUI()
                    hideProgress()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val msg = "Error copy server: " + (e.message ?: "unknown error")
                        val tvInfo = view?.findViewById<TextView>(R.id.tvInfo)
                        tvInfo?.text = msg
                        tvInfo?.visibility = View.VISIBLE
                        App.toast(msg)
                    }
                    hideProgress()
                }
            }
        }
    }
}