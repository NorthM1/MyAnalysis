package com.example.analysis

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

class AspectRatioVideoView(context: Context, attrs: AttributeSet? = null)
    : VideoView(context, attrs) {

    private var videoWidth  = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        videoWidth  = width
        videoHeight = height
        requestLayout()  // 触发重新测量
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (videoWidth == 0 || videoHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val availableWidth  = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        val videoRatio  = videoWidth.toFloat()  / videoHeight
        val screenRatio = availableWidth.toFloat() / availableHeight

        val finalWidth: Int
        val finalHeight: Int

        if (videoRatio > screenRatio) {
            // 视频更宽，以宽度为基准
            finalWidth  = availableWidth
            finalHeight = (availableWidth / videoRatio).toInt()
        } else {
            // 视频更高，以高度为基准
            finalHeight = availableHeight
            finalWidth  = (availableHeight * videoRatio).toInt()
        }

        setMeasuredDimension(finalWidth, finalHeight)
    }
}