package com.example.analysis

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class AnalysisActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    // ── UI ───────────────────────────────────────────────────────────────────
    private lateinit var videoViewDoctor: VideoView
    private lateinit var videoViewPatient: VideoView
    private lateinit var overlayDoctor: OverlayView
    private lateinit var overlayPatient: OverlayView          // 新增
    private lateinit var btnLoadDoctor: Button
    private lateinit var btnLoadPatient: Button
    private lateinit var btnStart: Button

    // ── URI ──────────────────────────────────────────────────────────────────
    private var doctorUri: Uri? = null
    private var patientUri: Uri? = null

    // ── 分析资源（各自独立）──────────────────────────────────────────────────
    private lateinit var backgroundExecutor: ScheduledExecutorService

    // Doctor
    private lateinit var poseLandmarkerHelperDoctor: PoseLandmarkerHelper
    private var exoPlayerDoctor: androidx.media3.exoplayer.ExoPlayer? = null
    private var imageReaderDoctor: android.media.ImageReader? = null
    private var analysisThreadDoctor: HandlerThread? = null

    // Patient
    private lateinit var poseLandmarkerHelperPatient: PoseLandmarkerHelper
    private var exoPlayerPatient: androidx.media3.exoplayer.ExoPlayer? = null
    private var imageReaderPatient: android.media.ImageReader? = null
    private var analysisThreadPatient: HandlerThread? = null

    // ── 降帧控制（两路各自记录上次处理时间）──────────────────────────────────
    private val FRAME_INTERVAL_MS = 50L   // 每200ms处理一帧 ≈ 5fps，可按需调整
    private var lastFrameTimeDoctor = 0L
    private var lastFrameTimePatient = 0L

    var isPaused = false
    private var isPickingFile = false

    // ── 文件选择器 ────────────────────────────────────────────────────────────
    private var pendingPicker: ((Uri) -> Unit)? = null
    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                // ✅ 真机必须持久化，否则后台线程访问时权限已失效
                contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                pendingPicker?.invoke(it)
            }
            pendingPicker = null
        }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.analysis)

        videoViewDoctor  = findViewById(R.id.video_view_doctor)
        videoViewPatient = findViewById(R.id.video_view_patient)
        overlayDoctor    = findViewById(R.id.overlay_doctor)
        overlayPatient   = findViewById(R.id.overlay_patient)   // 新增
        btnLoadDoctor    = findViewById(R.id.btn_load_doctor)
        btnLoadPatient   = findViewById(R.id.btn_load_patient)
        btnStart         = findViewById(R.id.btn_start)

        btnStart.isEnabled = false

        btnLoadDoctor.setOnClickListener {
            isPickingFile = true
            pendingPicker = { uri ->
                doctorUri = uri
                videoViewDoctor.setVideoURI(uri)
                videoViewDoctor.setOnPreparedListener { mp ->
                    mp.setVolume(0f, 0f); mp.start(); mp.pause()
                }
                checkBothLoaded()
            }
            pickVideo.launch(arrayOf("video/*"))
        }

        btnLoadPatient.setOnClickListener {
            isPickingFile = true
            pendingPicker = { uri ->
                patientUri = uri
                videoViewPatient.setVideoURI(uri)
                videoViewPatient.setOnPreparedListener { mp ->
                    mp.setVolume(0f, 0f); mp.start(); mp.pause()
                }
                checkBothLoaded()
            }
            pickVideo.launch(arrayOf("video/*"))
        }

        btnStart.setOnClickListener {
            doctorUri ?: return@setOnClickListener
            patientUri ?: return@setOnClickListener
            btnStart.isEnabled = false

            // 两路同时启动分析
            backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
            runDetectionOnVideo(
                uri            = doctorUri!!,
                videoView      = videoViewDoctor,
                overlay        = overlayDoctor,
                isDoctor       = true
            )
            runDetectionOnVideo(
                uri            = patientUri!!,
                videoView      = videoViewPatient,
                overlay        = overlayPatient,
                isDoctor       = false
            )
        }
    }

    private fun checkBothLoaded() {
        btnStart.isEnabled = doctorUri != null && patientUri != null
    }

    // ── 通用分析方法，doctor/patient 共用 ─────────────────────────────────────
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun runDetectionOnVideo(
        uri: Uri,
        videoView: VideoView,
        overlay: OverlayView,
        isDoctor: Boolean
    ) {
        // VideoView 负责可见画面播放
        with(videoView) {
            setVideoURI(uri)
            setOnPreparedListener { it.setVolume(0f, 0f); it.start() }
            requestFocus()
        }

        videoView.setOnClickListener {
            isPaused = if (videoView.isPlaying) {
                videoView.pause()
                exoPlayerDoctor?.pause()
                exoPlayerPatient?.pause()
                true
            } else {
                videoView.start()
                exoPlayerDoctor?.play()
                exoPlayerPatient?.play()
                false
            }
        }

        // 在后台线程初始化对应的 PoseLandmarkerHelper
        backgroundExecutor.execute {
            val helper = PoseLandmarkerHelper(
                context = applicationContext,
                runningMode = RunningMode.VIDEO,
                minPoseDetectionConfidence = 0.2f,
                minPoseTrackingConfidence  = 0.2f,
                minPosePresenceConfidence  = 0.2f,
                currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
            )
            if (isDoctor) poseLandmarkerHelperDoctor = helper
            else          poseLandmarkerHelperPatient = helper
        }

        // 获取视频分辨率
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        val videoWidth  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()  ?: 640
        val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 480
        retriever.release()

        // ImageReader
        val imageReader = android.media.ImageReader.newInstance(
            videoWidth, videoHeight, android.graphics.ImageFormat.YUV_420_888, 2
        )
        val analysisThread = HandlerThread(
            if (isDoctor) "VideoAnalysis-Doctor" else "VideoAnalysis-Patient"
        ).also { it.start() }
        val analysisHandler = Handler(analysisThread.looper)

        if (isDoctor) {
            imageReaderDoctor?.close()
            imageReaderDoctor  = imageReader
            analysisThreadDoctor?.quitSafely()
            analysisThreadDoctor = analysisThread
        } else {
            imageReaderPatient?.close()
            imageReaderPatient  = imageReader
            analysisThreadPatient?.quitSafely()
            analysisThreadPatient = analysisThread
        }

        // 帧回调：加入时间戳降帧判断
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                // 根据是否是 doctor 读取/更新各自的时间戳
                val lastTime = if (isDoctor) lastFrameTimeDoctor else lastFrameTimePatient
                if (isPaused || now - lastTime < FRAME_INTERVAL_MS) return@setOnImageAvailableListener

                // 更新时间戳
                val helper = if (isDoctor) {
                    lastFrameTimeDoctor = now
                    if (this::poseLandmarkerHelperDoctor.isInitialized) poseLandmarkerHelperDoctor else null
                } else {
                    lastFrameTimePatient = now
                    if (this::poseLandmarkerHelperPatient.isInitialized) poseLandmarkerHelperPatient else null
                }
                helper ?: return@setOnImageAvailableListener

                val bitmap = image.toBitmap()
                helper.detectVideoFrame(bitmap, System.currentTimeMillis())?.let { result ->
                    runOnUiThread {
                        overlay.setResults(
                            result.results[0],
                            bitmap.height,
                            bitmap.width,
                            RunningMode.IMAGE
                        )
                        overlay.invalidate()
                    }
                }
            } finally {
                image.close()
            }
        }, analysisHandler)

        // ExoPlayer 解码帧 → ImageReader（仅分析用，不显示）
        runOnUiThread {
            // 强制软解的 MediaCodecSelector：只返回非硬件加速的解码器
            val softwareOnlySelector =
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector {
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                        .filter { !it.hardwareAccelerated }
                }

            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this).also {
                it.setMediaCodecSelector(softwareOnlySelector)
            }

            val player = androidx.media3.exoplayer.ExoPlayer.Builder(this, renderersFactory)
                .build().apply {
                    setVideoSurface(imageReader.surface)
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
                    volume = 0f
                    prepare()
                    play()
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == androidx.media3.common.Player.STATE_ENDED) {
                                imageReader.close()
                                analysisThread.quitSafely()
                                backgroundExecutor.execute {
                                    if (isDoctor) {
                                        if (this@AnalysisActivity::poseLandmarkerHelperDoctor.isInitialized)
                                            poseLandmarkerHelperDoctor.clearPoseLandmarker()
                                    } else {
                                        if (this@AnalysisActivity::poseLandmarkerHelperPatient.isInitialized)
                                            poseLandmarkerHelperPatient.clearPoseLandmarker()
                                    }
                                }
                            }
                        }
                    })
                }
            if (isDoctor) { exoPlayerDoctor?.release(); exoPlayerDoctor = player }
            else          { exoPlayerPatient?.release(); exoPlayerPatient = player }
        }
    }

    // ── YUV→Bitmap ───────────────────────────────────────────────────────────
    private fun android.media.Image.toBitmap(): Bitmap {
        val w = width
        val h = height
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yRowStride    = yPlane.rowStride
        val uvRowStride   = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride  // 关键：这个值可能是1或2

        val nv21 = ByteArray(w * h + 2 * (w / 2) * (h / 2))

        // 逐行拷贝Y，跳过行尾padding
        for (row in 0 until h) {
            yPlane.buffer.position(row * yRowStride)
            yPlane.buffer.get(nv21, row * w, w)
        }

        // 按实际pixelStride步进读取UV
        val vuBase = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val srcIdx = row * uvRowStride + col * uvPixelStride
                val dstIdx = vuBase + (row * (w / 2) + col) * 2
                vPlane.buffer.position(srcIdx); nv21[dstIdx]     = vPlane.buffer.get()
                uPlane.buffer.position(srcIdx); nv21[dstIdx + 1] = uPlane.buffer.get()
            }
        }

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), 90, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            .copy(Bitmap.Config.ARGB_8888, true)
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────
    override fun onPause() {
        if (!isPickingFile) {
            overlayDoctor.clear()
            overlayPatient.clear()
            if (videoViewDoctor.isPlaying) videoViewDoctor.stopPlayback()
            if (videoViewPatient.isPlaying) videoViewPatient.stopPlayback()
            videoViewDoctor.visibility  = View.GONE
            videoViewPatient.visibility = View.GONE
        }
        isPickingFile = false
        super.onPause()
    }

    override fun onDestroy() {
        exoPlayerDoctor?.release();  exoPlayerDoctor  = null
        exoPlayerPatient?.release(); exoPlayerPatient = null
        imageReaderDoctor?.close();  imageReaderDoctor  = null
        imageReaderPatient?.close(); imageReaderPatient = null
        analysisThreadDoctor?.quitSafely();  analysisThreadDoctor  = null
        analysisThreadPatient?.quitSafely(); analysisThreadPatient = null
        super.onDestroy()
    }

    // ── Listener ──────────────────────────────────────────────────────────────
    override fun onError(error: String, errorCode: Int) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) { /* IMAGE模式不走此回调 */ }
}