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
 * 关键修复（v1.0.1）：
 *  - onAttach 时主动 selectDevice：解决"APP 前台运行时插设备不发 intent"导致无画面
 *  - onDetach 防抖 500ms 不清空 currentDevice：解决低端 SoC USB 瞬断导致状态闪烁
 *  - isFirstOpen 区分：重连时复用 currentDevice，避免 UI 闪烁
 */
class UvcCameraManager(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var helper: CameraHelper? = null
    private var currentDevice: UsbDevice? = null

    /** 外部传入的预览 Surface（来自 SurfaceView.getHolder 或 TextureView）。 */
    private var previewSurface: Surface? = null

    /** 当前是否处于"暂停"状态（UI 暂停按钮）。 */
    @Volatile
    private var isPaused = false

    /** 是否已成功 startPreview。 */
    @Volatile
    private var isPreviewing = false

    /** 主线程 Handler，用于防抖和 UI 回调。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 防抖标记：onDetach 后 500ms 内若 onAttach 回来则合并，不报"已断开"。 */
    @Volatile
    private var detachDebouncePending = false

    private val DETACH_DEBOUNCE_MS = 500L

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 创建 CameraHelper 并绑定 StateCallback。调用时机：Activity onCreate 之后。
     */
    fun init() {
        val h = CameraHelper()
        helper = h
        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice?) {
                // 修复 Bug 2：APP 前台时插设备不发 intent，必须在这里主动 select
                device ?: return
                // 取消正在挂起的 detach 防抖（瞬断后又重连）
                detachDebouncePending = false
                currentDevice = device
                h.selectDevice(device)
            }

            override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                device ?: return
                // 设备已授权并打开，继续 openCamera
                // 取消任何挂起的 detach 防抖（设备真的回来了）
                detachDebouncePending = false
                try {
                    h.openCamera()
                    // tryStartPreview 由 onCameraOpen 回调触发更稳，这里不重复
                    onConnected()
                } catch (e: Exception) {
                    onError(e.message ?: "openCamera failed")
                }
            }

            override fun onCameraOpen(device: UsbDevice?) {
                // camera 已打开，可开始预览
                detachDebouncePending = false
                tryStartPreview()
                onConnected()
            }

            override fun onCameraClose(device: UsbDevice?) {
                // 相机被关闭（设备瞬断常见）
                isPreviewing = false
                // 不立即报断开，等防抖窗口
                scheduleDebouncedDisconnect()
            }

            override fun onDeviceClose(device: UsbDevice?) {
                // 设备连接关闭
                isPreviewing = false
                scheduleDebouncedDisconnect()
            }

            override fun onDetach(device: UsbDevice?) {
                // 设备物理断开
                isPreviewing = false
                scheduleDebouncedDisconnect()
                // 注意：不清空 currentDevice，以便重插时复用；
                // 真正清空在 release() 或防抖窗口结束后的硬重置
            }

            override fun onCancel(device: UsbDevice?) {
                // 用户取消授权
                scheduleDebouncedDisconnect()
            }
        })
    }

    /**
     * 防抖地触发 onDisconnected 回调。
     * 若在 [DETACH_DEBOUNCE_MS] 内 onAttach/onDeviceOpen 回来，则取消本次回调，
     * 避免 UI 闪烁（解决 Bug 1：低端 SoC USB 瞬断）。
     */
    private fun scheduleDebouncedDisconnect() {
        if (detachDebouncePending) return
        detachDebouncePending = true
        mainHandler.postDelayed({
            if (detachDebouncePending) {
                detachDebouncePending = false
                onDisconnected()
            }
        }, DETACH_DEBOUNCE_MS)
    }

    /**
     * 选定 USB 设备。外部在 USB_DEVICE_ATTACHED intent 或 BroadcastReceiver 时调用。
     * 内部 onAttach 回调也会自动调，所以这里只是冗余入口。
     */
    fun selectDevice(device: UsbDevice) {
        currentDevice = device
        helper?.selectDevice(device)
    }

    /**
     * 绑定外部预览 Surface。surfaceCreated 时调用。
     */
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
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

    /**
     * 返回当前设备支持的预览分辨率。若相机未打开则返回空列表。
     */
    fun getSupportedSizes(): List<Size> {
        val h = helper ?: return emptyList()
        return try {
            h.supportedSizeList ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 切换预览分辨率。需要重新 startPreview。
     */
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
        // 取消可能挂起的防抖回调
        detachDebouncePending = false
        mainHandler.removeCallbacksAndMessages(null)
        stopPreviewQuietly()
        helper?.release()
        helper = null
        previewSurface = null
        currentDevice = null
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
            h.addSurface(surface, false)
            h.startPreview()
            isPreviewing = true
        } catch (e: Exception) {
            onError(e.message ?: "startPreview failed")
        }
    }

    private fun stopPreviewQuietly() {
        val h = helper ?: return
        val surface = previewSurface ?: return
        try {
            h.stopPreview()
            h.removeSurface(surface)
        } catch (_: Exception) {
            // 忽略
        }
        isPreviewing = false
    }
}
