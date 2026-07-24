package com.example.uvcviewer.camera

import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size

/**
 * UVC 相机管理器：封装 com.herohan:UVCAndroid 的 CameraHelper 生命周期。
 *
 * 关键修复：
 *  - v1.0.1: onAttach 主动 selectDevice / detach 防抖 500ms
 *  - v1.0.5: 防抖延长到 3s，断开后自动重连（低端 SoC 骁龙680 USB 频繁瞬断）
 */
class UvcCameraManager(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var helper: CameraHelper? = null
    private var currentDevice: UsbDevice? = null

    /** 上次已调用 selectDevice 的设备 serial，防止 onAttach 和 BroadcastReceiver 重复调用。 */
    private var lastSelectedSerial: String? = null

    /** 外部传入的预览 Surface（来自 SurfaceView.getHolder）。 */
    private var previewSurface: Surface? = null

    /** 当前是否处于"暂停"状态（UI 暂停按钮）。 */
    @Volatile
    private var isPaused = false

    /** 是否已成功 startPreview。 */
    @Volatile
    private var isPreviewing = false

    /** 兼容模式：强制用低分辨率（320×240）减少 USB 带宽需求，缓解低端 SoC UVC 瞬断。 */
    @Volatile
    var compatibilityMode: Boolean = false

    /** 主线程 Handler，用于防抖和重连定时器。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 防抖 ----

    /** onDetach 后等待设备恢复的时间窗口（骁龙680 USB 瞬断可达 1-2s）。 */
    private val DETACH_DEBOUNCE_MS = 3000L

    /** 防抖标记：窗口内若 onAttach 回来则合并，不报"已断开"。 */
    @Volatile
    private var detachDebouncePending = false

    // ---- 自动重连 ----

    /** 是否正在执行自动重连流程。 */
    @Volatile
    private var isReconnecting = false

    /** 最大自动重连尝试次数（防死循环）。 */
    private val MAX_RECONNECT_ATTEMPTS = 5

    /** 当前重连尝试次数。 */
    private var reconnectAttempt = 0

    /** 重连间隔（ms），逐次递增。 */
    private val RECONNECT_INTERVAL_MS = 1500L

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun init() {
        val h = CameraHelper()
        helper = h
        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice?) {
                device ?: return
                resetReconnectState()
                detachDebouncePending = false
                currentDevice = device
                // 防止 onAttach 和 BroadcastReceiver 同时重复调用 selectDevice
                val serial = device.serialNumber ?: device.deviceName
                if (serial != lastSelectedSerial) {
                    lastSelectedSerial = serial
                    h.selectDevice(device)
                }
            }

            override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                device ?: return
                resetReconnectState()
                detachDebouncePending = false
                try {
                    h.openCamera()
                    onConnected()
                } catch (e: Exception) {
                    onError(e.message ?: "openCamera failed")
                }
            }

            override fun onCameraOpen(device: UsbDevice?) {
                resetReconnectState()
                detachDebouncePending = false
                tryStartPreview()
                onConnected()
            }

            override fun onCameraClose(device: UsbDevice?) {
                isPreviewing = false
                // 防抖：如果短时间内恢复，不报断开，到期后触发自动重连
                scheduleDebouncedDisconnect()
            }

            override fun onDeviceClose(device: UsbDevice?) {
                isPreviewing = false
                scheduleDebouncedDisconnect()
            }

            override fun onDetach(device: UsbDevice?) {
                isPreviewing = false
                scheduleDebouncedDisconnect()
            }

            override fun onCancel(device: UsbDevice?) {
                scheduleDebouncedDisconnect()
            }
        })
    }

    /**
     * 防抖地触发断开回调。若防抖窗口内设备恢复，取消回调。
     * 防抖到期后触发自动重连（而非直接报断开），给设备第二次机会。
     *
     * @return true 表示防抖已挂起
     */
    private fun scheduleDebouncedDisconnect(): Boolean {
        if (detachDebouncePending) return false
        detachDebouncePending = true
        mainHandler.postDelayed({
            if (detachDebouncePending) {
                detachDebouncePending = false
                // 防抖到期仍未恢复，尝试自动重连
                attemptReconnect()
            }
        }, DETACH_DEBOUNCE_MS)
        return true
    }

    // ------------------------------------------------------------------
    // 自动重连
    // ------------------------------------------------------------------

    /**
     * 尝试自动重连：重新 selectDevice + openCamera + startPreview。
     * 最多尝试 [MAX_RECONNECT_ATTEMPTS] 次，逐次间隔递增。
     */
    private fun attemptReconnect() {
        val dev = currentDevice ?: return
        val h = helper ?: return
        if (isReconnecting || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                resetReconnectState()
                onDisconnected()
            }
            return
        }
        isReconnecting = true
        reconnectAttempt++

        mainHandler.postDelayed({
            isReconnecting = false
            if (h.isCameraOpened) {
                // 已经恢复，直接尝试预览
                resetReconnectState()
                tryStartPreview()
                onConnected()
            } else {
                // 设备还在，尝试重新打开
                try {
                    h.selectDevice(dev)
                    // selectDevice 会触发 onDeviceOpen → openCamera → onCameraOpen → tryStartPreview
                    // 如果 selectDevice 失败，catch 里自动重试下一次
                } catch (_: Exception) {
                    // 重试
                    attemptReconnect()
                }
            }
        }, RECONNECT_INTERVAL_MS)
    }

    private fun resetReconnectState() {
        isReconnecting = false
        reconnectAttempt = 0
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------
    // 外部接口
    // ------------------------------------------------------------------

    fun selectDevice(device: UsbDevice) {
        val serial = device.serialNumber ?: device.deviceName
        if (serial == lastSelectedSerial) return  // 已选过同一设备，防重复
        lastSelectedSerial = serial
        currentDevice = device
        resetReconnectState()
        helper?.selectDevice(device)
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        // 修复：当 Surface 就绪时，如果相机已打开但尚未开始预览，立即启动
        // 解决 Redmi Pad SE 上 "已连接但无画面" 的问题
        if (surface != null && !isPreviewing && !isPaused && !isReconnecting) {
            tryStartPreview()
        }
    }

    // ------------------------------------------------------------------
    // 暂停 / 继续
    // ------------------------------------------------------------------

    fun pausePreview() {
        if (isPaused) return
        isPaused = true
        stopPreviewQuietly()
    }

    fun resumePreview() {
        if (!isPaused) return
        isPaused = false
        tryStartPreview()
    }

    // ------------------------------------------------------------------
    // 分辨率
    // ------------------------------------------------------------------

    fun getSupportedSizes(): List<Size> {
        val h = helper ?: return emptyList()
        return try {
            h.supportedSizeList ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setPreviewSize(size: Size): Boolean {
        val h = helper ?: return false
        return try {
            stopPreviewQuietly()
            h.setPreviewSize(size)
            tryStartPreview()
            true
        } catch (e: Exception) {
            onError(e.message ?: "setPreviewSize failed")
            false
        }
    }

    // ------------------------------------------------------------------
    // 释放
    // ------------------------------------------------------------------

    fun release() {
        detachDebouncePending = false
        resetReconnectState()
        stopPreviewQuietly()
        helper?.release()
        helper = null
        previewSurface = null
        currentDevice = null
        lastSelectedSerial = null
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun tryStartPreview() {
        val h = helper ?: return
        val surface = previewSurface ?: return
        if (isPaused || isPreviewing) return
        if (!h.isCameraOpened) return
        try {
            // 兼容模式：先尝试设置低分辨率 320×240，降低 USB 带宽需求
            if (compatibilityMode) {
                applyCompatibilityResolution(h)
            }
            h.addSurface(surface, false)
            h.startPreview()
            isPreviewing = true
        } catch (e: Exception) {
            onError(e.message ?: "startPreview failed")
        }
    }

    /** 兼容模式：尝试 320×240，不可用则用支持列表中的最低分辨率。 */
    private fun applyCompatibilityResolution(h: CameraHelper) {
        try {
            val sizes = h.supportedSizeList
            if (!sizes.isNullOrEmpty()) {
                // 优先找 320×240
                var target = sizes.find { it.width == 320 && it.height == 240 }
                // 其次找 640×480
                if (target == null) target = sizes.find { it.width == 640 && it.height == 480 }
                // 最后取宽度最小的
                if (target == null) target = sizes.minByOrNull { it.width }
                if (target != null) h.setPreviewSize(target)
            }
        } catch (_: Exception) {
            // 静默忽略，用默认分辨率
        }
    }

    private fun stopPreviewQuietly() {
        val h = helper ?: return
        val surface = previewSurface ?: return
        try {
            h.stopPreview()
            h.removeSurface(surface)
        } catch (_: Exception) {
        }
        isPreviewing = false
    }
}
