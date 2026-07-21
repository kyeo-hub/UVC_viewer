package com.example.uvcviewer.widget

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 简单的 SurfaceView 封装：
 * - surfaceCreated 时把 Surface 报给外部用于 CameraHelper.addSurface
 * - 不再需要 SurfaceHolder（新库 com.herohan:UVCAndroid 直接用 Surface）
 */
class CameraSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    var onSurfaceReady: ((android.view.Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        onSurfaceReady?.invoke(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 无需处理，CameraHelper 会按实际分辨率填充
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurfaceDestroyed?.invoke()
    }
}
