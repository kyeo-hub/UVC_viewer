package com.example.uvcviewer

import android.app.AlertDialog
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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

    /** 当前已选定的 UVC 设备。 */
    private var currentDevice: UsbDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 初始化 UvcCameraManager（内部会创建 CameraHelper 并绑定 StateCallback）
        cameraManager = UvcCameraManager(
            onConnected = { setStatus(R.string.status_connected, false) },
            onDisconnected = { setStatus(R.string.status_disconnected, true) },
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

        // 5. 处理插入设备时由 manifest intent-filter 自动触发的 ATTACHED intent
        handleAttachIntent(intent)

        // 6. 初始状态：显示"等待接入"提示
        setStatus(R.string.status_waiting, true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAttachIntent(intent)
    }

    /**
     * 处理 ACTION_USB_DEVICE_ATTACHED：拿到设备后调 selectDevice。
     * CameraHelper 的 StateCallback.onDeviceOpen 会继续走 openCamera + startPreview。
     */
    private fun handleAttachIntent(intent: Intent?) {
        intent ?: return
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        device ?: return
        Log.i(TAG, "USB attached: ${device.deviceName}")
        currentDevice = device
        cameraManager.selectDevice(device)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.release()
    }

    // ------------------------------------------------------------------
    // UI 辅助
    // ------------------------------------------------------------------

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
