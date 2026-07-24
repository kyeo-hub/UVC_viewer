package com.example.uvcviewer

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.uvcviewer.camera.UvcCameraManager
import com.example.uvcviewer.databinding.ActivityMainBinding
import com.serenegiant.usb.Size

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UVCViewer"
    }

    private lateinit var binding: ActivityMainBinding

    /** UVC 相机管理器（封装 com.herohan:UVCAndroid 的 CameraHelper）。 */
    private lateinit var cameraManager: UvcCameraManager

    /** 全屏切换状态。 */
    private var isFullscreen = false

    /** 记录进入全屏前 statusText 是否可见，退出全屏时恢复。 */
    private var statusTextWasVisible = true

    /** 前台 USB 设备插拔监听器。APP 前台运行时系统不发 ATTACHED intent，必须主动注册。 */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device ?: return
                    Log.i(TAG, "USB attached (foreground): ${device.deviceName}")
                    cameraManager.selectDevice(device)
                }
                // DETACHED 时由 UvcCameraManager 的 onDetach 回调处理（含防抖），这里不重复
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 初始化 UvcCameraManager（内部会创建 CameraHelper 并绑定 StateCallback）
        cameraManager = UvcCameraManager(
            onConnected = {
                setStatus(R.string.status_connected, false)
                // 有信号时保持屏幕常亮，防止息屏
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            },
            onDisconnected = {
                setStatus(R.string.status_disconnected, true)
                // 断开后恢复系统熄屏策略
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            },
            onError = { msg -> setStatus(getString(R.string.status_error, msg), true) }
        )
        cameraManager.init()

        // 2. 绑定 SurfaceView 回调：把 Surface 交给 CameraHelper.addSurface
        binding.cameraView.onSurfaceReady = { surface ->
            cameraManager.setPreviewSurface(surface)
        }
        binding.cameraView.onSurfaceDestroyed = {
            cameraManager.setPreviewSurface(null)
        }

        // 3. 按钮：暂停 / 继续
        var paused = false
        binding.btnPause.setOnClickListener {
            paused = !paused
            if (paused) {
                cameraManager.pausePreview()
                binding.btnPause.text = getString(R.string.btn_resume)
            } else {
                cameraManager.resumePreview()
                binding.btnPause.text = getString(R.string.btn_pause)
            }
        }

        // 4. 按钮：分辨率切换
        binding.btnResolution.setOnClickListener {
            showResolutionPicker()
        }

        // 5. 兼容模式开关
        var compatOn = false
        binding.btnCompatibility.setOnClickListener {
            compatOn = !compatOn
            cameraManager.compatibilityMode = compatOn
            binding.btnCompatibility.text = if (compatOn) getString(R.string.btn_compat_on) else getString(R.string.btn_compat_off)
        }

        // 6. 点击画面切换全屏 / 普通模式
        binding.cameraView.setOnClickListener {
            toggleUiVisibility()
        }

        // 6. 处理冷启动时由 manifest intent-filter 自动触发的 ATTACHED intent
        handleAttachIntent(intent)

        // 7. 初始状态：显示"等待接入"提示
        setStatus(R.string.status_waiting, true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAttachIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 修复 Bug 2：APP 前台运行时系统不再发 ACTION_USB_DEVICE_ATTACHED，
        // 必须主动注册 BroadcastReceiver 才能收到插设备事件
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        // 反注册避免泄漏
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    /**
     * 处理 ACTION_USB_DEVICE_ATTACHED：拿到设备后调 selectDevice。
     * CameraHelper 的 StateCallback.onDeviceOpen 会继续走 openCamera + startPreview。
     * 仅在冷启动 / manifest intent-filter 触发时生效；前台插设备走 usbReceiver。
     */
    private fun handleAttachIntent(intent: Intent?) {
        intent ?: return
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        device ?: return
        Log.i(TAG, "USB attached (cold start): ${device.deviceName}")
        cameraManager.selectDevice(device)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.release()
    }

    // ------------------------------------------------------------------
    // UI 辅助
    // ------------------------------------------------------------------

    /**
     * 切换全屏 / 普通模式。
     * 全屏时隐藏系统状态栏/导航栏 + 控制按钮 + 状态文本，画面沉浸。
     * 再次点击恢复。
     */
    private fun toggleUiVisibility() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            // 记录进入全屏前 statusText 的状态，退出时恢复
            statusTextWasVisible = binding.statusText.visibility == View.VISIBLE
            binding.statusText.visibility = View.GONE
            binding.controlBar.visibility = View.GONE
            setSystemUiFullscreen(true)
        } else {
            binding.statusText.visibility = if (statusTextWasVisible) View.VISIBLE else View.GONE
            binding.controlBar.visibility = View.VISIBLE
            setSystemUiFullscreen(false)
        }
    }

    /**
     * 使用 WindowInsetsControllerCompat 切换沉浸式全屏（API 30+ 兼容方案）。
     * 启用时隐藏系统栏（状态栏 + 导航栏），点击时短暂显示后自动隐回（IMMERSIVE_STICKY）。
     */
    private fun setSystemUiFullscreen(enable: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enable)
        val controller = WindowInsetsControllerCompat(window, binding.cameraView)
        if (enable) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setStatus(textResId: Int, visible: Boolean) {
        setStatus(getString(textResId), visible)
    }

    private fun setStatus(text: String, visible: Boolean) {
        binding.statusText.text = text
        // 状态文本仅在等待/错误等需要提示时可见，连接后隐藏避免遮挡画面
        binding.statusText.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 弹出对话框，列出设备支持的预览分辨率供用户选择。
     */
    private fun showResolutionPicker() {
        val sizes = cameraManager.getSupportedSizes()
        if (sizes.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("当前没有可用的分辨率列表。")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = sizes.map { "${it.width}x${it.height}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_resolution)
            .setItems(labels) { _, which ->
                val s: Size = sizes[which]
                cameraManager.setPreviewSize(s)
            }
            .show()
    }
}
