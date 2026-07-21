package com.example.uvcviewer

import android.app.AlertDialog
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.uvcviewer.camera.UvcCameraManager
import com.example.uvcviewer.databinding.ActivityMainBinding
import com.serenegiant.usb.USBMonitor

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UVCViewer"
    }

    private lateinit var binding: ActivityMainBinding

    /** USB 设备监听器（来自 UVCCamera 库）。 */
    private var usbMonitor: USBMonitor? = null

    /** UVC 相机管理器。 */
    private lateinit var cameraManager: UvcCameraManager

    /** 当前连接的 UVC 设备。 */
    private var currentDevice: UsbDevice? = null

    // ------------------------------------------------------------------
    // USBMonitor 回调
    // ------------------------------------------------------------------

    /**
     * USBMonitor 的 attach 回调：
     *  - 若是 UVC 设备（class 14 = VIDEO）则自动申请权限
     *  - 权限通过后由 onConnect 打开相机
     */
    private val deviceAttachListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            device ?: return
            Log.i(TAG, "USB attached: ${device.deviceName} cls=${device.deviceClass}")

            // 仅处理 UVC 类设备；放宽判断：vendor=0 或 class=0 时也尝试
            val isUvc = (device.deviceClass == 14) || device.interfaceCount > 0
            if (!isUvc) return

            currentDevice = device
            usbMonitor?.requestPermission(device)
        }

        override fun onDettach(device: UsbDevice?) {
            Log.i(TAG, "USB detached")
            cameraManager.closeCamera()
            currentDevice = null
        }

        override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            device ?: return
            ctrlBlock ?: return
            Log.i(TAG, "USB connect: ${device.deviceName}")
            cameraManager.openCamera(device, ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            Log.i(TAG, "USB disconnect")
            cameraManager.closeCamera()
        }

        override fun onCancel(device: UsbDevice?) {
            // 用户取消授权
        }
    }

    // ------------------------------------------------------------------
    // Activity 生命周期
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 初始化 UvcCameraManager
        cameraManager = UvcCameraManager(
            onConnected = { setStatus(R.string.status_connected, false) },
            onDisconnected = { setStatus(R.string.status_disconnected, true) },
            onError = { msg -> setStatus(getString(R.string.status_error, msg), true) }
        )

        // 2. 创建 USBMonitor 并绑定回调
        usbMonitor = USBMonitor(this, deviceAttachListener).also {
            cameraManager.init(it)
        }

        // 3. 绑定 SurfaceView 回调
        binding.cameraView.onSurfaceReady = { holder ->
            cameraManager.setSurfaceHolder(holder)
        }
        binding.cameraView.onSurfaceDestroyed = {
            cameraManager.setSurfaceHolder(null)
        }

        // 4. 按钮：暂停 / 继续
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

        // 5. 按钮：分辨率切换
        binding.btnResolution.setOnClickListener {
            showResolutionPicker()
        }

        // 6. 初始状态：显示"等待接入"提示
        setStatus(R.string.status_waiting, true)
    }

    override fun onResume() {
        super.onResume()
        // 注册 USB 监听，开启 attach 广播接收
        usbMonitor?.register()
    }

    override fun onPause() {
        super.onPause()
        // 反注册监听；相机仍保持连接（不释放，便于快速恢复）
        usbMonitor?.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.release()
        usbMonitor = null
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
                val s = sizes[which]
                cameraManager.setPreviewSize(s.width, s.height)
            }
            .show()
    }
}
