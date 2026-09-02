package com.qiuminal.juicedict.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.qiuminal.juicedict.AppUpdater
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.ReleaseInfo

/**
 * About page (1:1 with Tiger Helper layout):
 * icon / app name / version (+update badge) / tagline,
 * then contact card and justify-aligned changelog card.
 * Update check runs silently on open; a red badge appears only when
 * a newer GitHub release is found.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        // Android 15 (targetSdk 35) forces edge-to-edge: keep the top bar
        // below the status bar and the page above the navigation bar.
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        val currentVersion = version ?: "0.0.0"
        findViewById<TextView>(R.id.tv_version).text =
            getString(R.string.about_version, currentVersion)

        // New version available: query GitHub releases in background,
        // reveal the badge only when a newer release exists.
        val updateBadge = findViewById<TextView>(R.id.tv_update_badge)
        AppUpdater.checkLatest { info ->
            runOnUiThread {
                if (info != null && AppUpdater.isNewer(info.versionName, currentVersion)) {
                    updateBadge.visibility = View.VISIBLE
                    updateBadge.setOnClickListener { confirmUpdate(info) }
                }
            }
        }

        // Changelog: bold version+date heading lines, justified body.
        val changelogText = getString(R.string.changelog_content)
        val changelogSpannable = SpannableString(changelogText)
        val headingRegex = Regex("^\\d+\\.\\d+\\.\\d+\\uFF08\\d{4}-\\d{2}-\\d{2}\\uFF09", RegexOption.MULTILINE)
        for (match in headingRegex.findAll(changelogText)) {
            changelogSpannable.setSpan(
                StyleSpan(Typeface.BOLD),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val changelogView = findViewById<JustifyTextView>(R.id.tv_changelog)
        changelogView.setTextSizeSp(15f)
        changelogView.setLineSpacingExtraDp(6f)
        changelogView.setTextColor(resolveOnSurfaceColor())
        changelogView.setText(changelogSpannable)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // GitHub link opens the profile; QQ number copies to clipboard.
        findViewById<TextView>(R.id.btn_github).setOnClickListener {
            openUrl("https://github.com/qiuminal")
        }
        findViewById<TextView>(R.id.btn_qq).setOnClickListener {
            val qq = getString(R.string.contact_qq_value)
            try {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("QQ", qq))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Open source licenses: GPL-3.0 / CC-CEDICT (CC BY-SA 4.0) / third-party notices.
        findViewById<View>(R.id.row_license_gpl).setOnClickListener {
            showLicense(getString(R.string.license_app_title), "licenses/gpl-3.0.txt")
        }
        findViewById<View>(R.id.row_license_cc).setOnClickListener {
            showLicense(getString(R.string.license_dict_title), "licenses/cc-by-sa-4.0.txt")
        }
        findViewById<View>(R.id.row_license_notices).setOnClickListener {
            showLicense(getString(R.string.license_notices_title), "licenses/third-party-notices.txt")
        }
    }

    /** Shows a full license text from assets/licenses in a scrollable dialog. */
    private fun showLicense(title: String, assetPath: String) {
        val text = try {
            assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            getString(R.string.license_read_failed, assetPath)
        }
        val scrollView = ScrollView(this)
        val textView = TextView(this)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val padding = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(padding, padding, padding, padding)
        textView.setTextIsSelectable(true)
        textView.text = text
        scrollView.addView(textView)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(R.string.license_close, null)
            .show()
    }

    private fun resolveOnSurfaceColor(): Int {
        val value = TypedValue()
        if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, value, true)) {
            return value.data
        }
        val fallback = TypedValue()
        if (theme.resolveAttribute(android.R.attr.textColorPrimary, fallback, true)) {
            return fallback.data
        }
        return 0xFF1C1B1F.toInt()
    }

    private fun confirmUpdate(info: ReleaseInfo) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_update_confirm)
        dialog.findViewById<TextView>(R.id.tvUpdateTitle)?.text =
            "${getString(R.string.update_confirm_title)} v${info.versionName}"
        dialog.findViewById<View>(R.id.btnUpdateCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnUpdateOk)?.setOnClickListener {
            dialog.dismiss()
            startDownload(info.apkUrl)
        }
        dialog.show()
    }

    private fun startDownload(url: String) {
        val progressDialog = Dialog(this)
        progressDialog.setContentView(R.layout.dialog_update_progress)
        progressDialog.setCancelable(false)
        progressDialog.show()
        val progressBar = progressDialog.findViewById<ProgressBar>(R.id.pbUpdate)
        val percentText = progressDialog.findViewById<TextView>(R.id.tvUpdatePercent)
        AppUpdater.downloadAndInstall(
            this,
            url,
            onProgress = { p ->
                progressBar?.progress = p
                percentText?.text = "$p%"
            },
            onDone = { ok, err ->
                progressDialog.dismiss()
                if (!ok) {
                    Toast.makeText(
                        this,
                        getString(R.string.update_failed, err ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
