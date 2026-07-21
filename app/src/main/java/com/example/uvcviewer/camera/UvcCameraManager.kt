package com.example.uvcviewer.camera

import android.hardware.usb.UsbDevice
import android.view.SurfaceHolder
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.USBMonitor

/**
 * UVC 相机管理器：封装 USBMonitor + UVCCamera 的连接生命周期。
 *
 * 外部使用流程：
 *   1. register() / unregister()  → 注册/反注册 USB 监听
 *   2. requestPermission(device)  → 申请 USB 权限并自动打开
 *   3. pausePreview() / resumePreview()
 *   4. setPreviewSize() / getSupportedSizes()
 *   5. release()                  → 彻底释放
 *
 * 错误通过 [onError] 回调上抛。
 */
class UvcCameraManager(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var currentDevice: UsbDevice? = null

    /** Surface 已就绪时由外部传入的 holder。 */
    private var surfaceHolder: SurfaceHolder? = null

    /** 当前是否处于"暂停"状态（UI 暂停按钮）。 */
    @Volatile
    private var isPaused = false

    /** UVCCamera 内部状态：是否已成功 startPreview。 */
    @Volatile
    private var isPreviewing = false

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 初始化 USBMonitor 并绑定 device attach/detach 回调。
     * 调用时机：Activity onCreate 之后、surfaceCreated 之前均可。
     */
    fun init(monitor: USBMonitor) {
        usbMonitor = monitor
    }

    fun register() {
        usbMonitor?.register()
    }

    fun unregister() {
        usbMonitor?.unregister()
    }

    /**
     * 绑定外部 SurfaceHolder。surfaceCreated 时调用。
     */
    fun setSurfaceHolder(holder: SurfaceHolder?) {
        surfaceHolder = holder
    }

    // ------------------------------------------------------------------
    // 权限与打开
    // ------------------------------------------------------------------

    /**
     * 申请 USB 权限；权限通过后由 USBMonitor 的 onConnect 回调打开相机。
     */
    fun requestPermission(device: UsbDevice) {
        usbMonitor?.requestPermission(device)
    }

    // ------------------------------------------------------------------
    // 内部：被 USBMonitor.onConnect 回调
    // ------------------------------------------------------------------

    /**
     * 实际打开 UVC 设备。由外部在 USBMonitor.onConnect 回调中调用。
     */
    fun openCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        currentDevice = device
        releaseCameraQuietly()

        try {
            val camera = UVCCamera()
            camera.open(ctrlBlock)

            // 立即尝试 startPreview；若 surface 尚未就绪则等待 setSurfaceHolder
            uvcCamera = camera
            tryStartPreview()
            onConnected()
        } catch (e: Exception) {
            onError(e.message ?: "Open camera failed")
        }
    }

    /**
     * 设备物理断开或权限丢失时调用。
     */
    fun closeCamera() {
        releaseCameraQuietly()
        currentDevice = null
        isPreviewing = false
        onDisconnected()
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
     * 返回当前设备支持的预览分辨率（来自 UVCCamera.getSupportedSizeList）。
     * 若相机未打开则返回空列表。
     */
    fun getSupportedSizes(): List<UVCCamera.Size> {
        val camera = uvcCamera ?: return emptyList()
        return try {
            camera.supportedSizeList
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 切换预览分辨率。需要重新 startPreview。
     */
    fun setPreviewSize(width: Int, height: Int): Boolean {
        val camera = uvcCamera ?: return false
        return try {
            stopPreviewQuietly()
            camera.setPreviewSize(width, height)
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
        releaseCameraQuietly()
        usbMonitor?.let { monitor ->
            try {
                monitor.unregister()
                monitor.destroy()
            } catch (_: Exception) {
                // 忽略释放过程中的异常
            }
        }
        usbMonitor = null
        surfaceHolder = null
        currentDevice = null
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun tryStartPreview() {
        val camera = uvcCamera ?: return
        val holder = surfaceHolder ?: return
        if (isPaused || isPreviewing) return
        try {
            // 首次打开时 previewWidth/Height 可能为 0，统一用默认分辨率启动
            camera.setPreviewSize(
                UVCCamera.DEFAULT_PREVIEW_WIDTH,
                UVCCamera.DEFAULT_PREVIEW_HEIGHT
            )
            camera.setPreviewDisplay(holder)
            camera.startPreview()
            isPreviewing = true
        } catch (e: Exception) {
            onError(e.message ?: "startPreview failed")
        }
    }

    private fun stopPreviewQuietly() {
        val camera = uvcCamera ?: return
        try {
            camera.stopPreview()
        } catch (_: Exception) {
            // 忽略
        }
        isPreviewing = false
    }

    private fun releaseCameraQuietly() {
        val camera = uvcCamera ?: return
        try {
            stopPreviewQuietly()
            camera.close()
        } catch (_: Exception) {
            // 忽略
        }
        uvcCamera = null
    }
}
