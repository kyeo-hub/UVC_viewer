package com.example.uvcviewer.camera

import android.hardware.usb.UsbDevice
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size

/**
 * UVC 相机管理器：封装 com.herohan:UVCAndroid 的 CameraHelper 生命周期。
 *
 * 职责单一：设备连接 → 打开 → 预览 → 断开。无自动重连，无兼容模式。
 */
class UvcCameraManager(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var helper: CameraHelper? = null
    private var currentDevice: UsbDevice? = null

    /** 上次已调用 selectDevice 的设备 serial，防止重复调用。 */
    private var lastSelectedSerial: String? = null

    /** 外部传入的预览 Surface。 */
    private var previewSurface: Surface? = null

    /** 当前是否处于"暂停"状态。 */
    @Volatile
    private var isPaused = false

    /** 是否已成功 startPreview。 */
    @Volatile
    private var isPreviewing = false

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    fun init() {
        val h = CameraHelper()
        helper = h
        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice?) {
                device ?: return
                currentDevice = device
                val serial = device.serialNumber ?: device.deviceName
                if (serial != lastSelectedSerial) {
                    lastSelectedSerial = serial
                    h.selectDevice(device)
                }
            }

            override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                device ?: return
                try {
                    h.openCamera()
                    onConnected()
                } catch (e: Exception) {
                    onError(e.message ?: "openCamera failed")
                }
            }

            override fun onCameraOpen(device: UsbDevice?) {
                tryStartPreview()
                onConnected()
            }

            override fun onCameraClose(device: UsbDevice?) {
                isPreviewing = false
                onDisconnected()
            }

            override fun onDeviceClose(device: UsbDevice?) {
                isPreviewing = false
                onDisconnected()
            }

            override fun onDetach(device: UsbDevice?) {
                isPreviewing = false
                onDisconnected()
            }

            override fun onCancel(device: UsbDevice?) {
                onDisconnected()
            }
        })
    }

    // ------------------------------------------------------------------
    // 外部接口
    // ------------------------------------------------------------------

    fun selectDevice(device: UsbDevice) {
        val serial = device.serialNumber ?: device.deviceName
        if (serial == lastSelectedSerial) return
        lastSelectedSerial = serial
        currentDevice = device
        helper?.selectDevice(device)
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        // Surface 就绪时若相机已打开则立即启动预览
        if (surface != null && !isPreviewing && !isPaused) {
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
        }
        isPreviewing = false
    }
}
