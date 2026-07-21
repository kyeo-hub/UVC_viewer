package com.example.uvcviewer.camera

import android.hardware.usb.UsbDevice
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size

/**
 * UVC 相机管理器：封装 com.herohan:UVCAndroid 的 CameraHelper 生命周期。
 *
 * 外部使用流程：
 *   1. init()           → 创建 CameraHelper 并绑定 StateCallback
 *   2. selectDevice()   → 选定 UVC 设备并自动打开
 *   3. pausePreview() / resumePreview()
 *   4. setPreviewSize() / getSupportedSizes()
 *   5. release()        → 彻底释放
 *
 * 错误通过 [onError] 回调上抛。
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
                // 由外部 MainActivity 的 USB intent 处理；此处不重复 select
            }

            override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                device ?: return
                // 设备已授权并打开，继续 openCamera
                try {
                    h.openCamera()
                    tryStartPreview()
                    onConnected()
                } catch (e: Exception) {
                    onError(e.message ?: "openCamera failed")
                }
            }

            override fun onCameraOpen(device: UsbDevice?) {
                // camera 已打开，可开始预览
                tryStartPreview()
                onConnected()
            }

            override fun onCameraClose(device: UsbDevice?) {
                isPreviewing = false
                onDisconnected()
            }

            override fun onDeviceClose(device: UsbDevice?) {
                isPreviewing = false
                currentDevice = null
                onDisconnected()
            }

            override fun onDetach(device: UsbDevice?) {
                isPreviewing = false
                currentDevice = null
                onDisconnected()
            }

            override fun onCancel(device: UsbDevice?) {
                // 用户取消授权
            }
        })
    }

    /**
     * 选定 USB 设备。外部在 USB_DEVICE_ATTACHED intent 或 StateCallback.onAttach 时调用。
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
