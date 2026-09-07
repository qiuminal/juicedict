package com.qiuminal.juicedict.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.databinding.ActivityWifiTransferBinding
import com.qiuminal.juicedict.wifi.TransferEvent
import com.qiuminal.juicedict.wifi.WifiTransferController
import com.qiuminal.juicedict.wifi.WifiTransferService
import kotlinx.coroutines.launch

/**
 * 「从电脑中导入」页面（视觉稿：E:\Min\安卓开发\code_20260907.html）。
 *
 * 进入页面即启动 Wi-Fi 传输前台服务；退出页面服务继续保活，
 * 可从通知栏或本页底部「停止服务」结束会话。
 */
class WiFiTransferActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiTransferBinding
    private var currentUrl: String? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响传输，仅通知不可见 */ }

    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToast = Runnable {
        binding.toast.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { binding.toast.visibility = View.GONE }
            .start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.back.setOnClickListener { finish() }
        binding.urlPill.setOnClickListener { onPillClicked() }
        binding.stopButton.setOnClickListener {
            WifiTransferService.stop(this)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WifiTransferController.state.collect { renderState(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WifiTransferController.events.collect { renderEvents(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        WifiTransferService.start(this)
    }

    override fun onDestroy() {
        toastHandler.removeCallbacks(hideToast)
        super.onDestroy()
    }

    private fun onPillClicked() {
        val state = WifiTransferController.state.value
        when (state) {
            is WifiTransferController.UiState.Running -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("url", state.url))
                showCopyToast()
            }
            else -> WifiTransferService.start(this) // 已停止/失败时点击重试启动
        }
    }

    private fun renderState(state: WifiTransferController.UiState) {
        when (state) {
            is WifiTransferController.UiState.Stopped -> {
                binding.tipText.setText(R.string.wifi_transfer_tip)
                binding.urlText.text = getString(R.string.wifi_state_stopped)
                currentUrl = null
            }
            is WifiTransferController.UiState.Starting -> {
                binding.tipText.setText(R.string.wifi_transfer_tip)
                binding.urlText.setText(R.string.wifi_state_starting)
                currentUrl = null
            }
            is WifiTransferController.UiState.Running -> {
                binding.tipText.setText(R.string.wifi_transfer_tip)
                binding.urlText.text = state.display
                currentUrl = state.url
            }
            is WifiTransferController.UiState.Failed -> {
                binding.tipText.text = state.reason
                binding.urlText.setText(R.string.wifi_state_retry)
                currentUrl = null
            }
        }
    }

    private fun renderEvents(events: List<TransferEvent>) {
        val container = binding.recordsList
        container.removeAllViews()
        if (events.isEmpty()) {
            binding.recordsEmpty.visibility = View.VISIBLE
            return
        }
        binding.recordsEmpty.visibility = View.GONE
        for (event in events.asReversed()) {
            container.addView(eventRow(event))
        }
    }

    private fun eventRow(event: TransferEvent): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val time = DateFormat.getTimeFormat(this).format(event.timeMillis)
        row.addView(
            TextView(this).apply {
                text = time
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.transfer_text_dim))
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        )
        row.addView(
            TextView(this).apply {
                text = event.text
                textSize = 14f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (event.ok) R.color.transfer_success else R.color.transfer_fail
                    )
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    private fun showCopyToast() {
        toastHandler.removeCallbacks(hideToast)
        binding.toast.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(110).start()
        }
        toastHandler.postDelayed(hideToast, 1500)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
