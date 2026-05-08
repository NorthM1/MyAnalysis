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
import androidx.media3.common.Player
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 子序列 DTW
 * 在 patientSeq 中找到与 doctorSeq 最匹配的一段
 * 返回：最优对齐的平均角度差 + 患者序列的起止帧索引
 */
data class SubDTWResult(
    val avgAngleDiff: Float,   // 平均角度差（°）
    val patientStart: Int,     // 患者最优起始帧
    val patientEnd: Int        // 患者最优结束帧
)


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
    private val FRAME_INTERVAL_MS = 100L   // 每100ms处理一帧 ≈ 10fps，可按需调整
    private var lastFrameTimeDoctor = 0L
    private var lastFrameTimePatient = 0L

    var isPaused = false
    private var isPickingFile = false

    // ── 文件选择器 ────────────────────────────────────────────────────────────
    private var pendingPicker: ((Uri) -> Unit)? = null

    val doctorPoseList = mutableListOf<List<NormalizedLandmark>>()
    val patientPoseList = mutableListOf<List<NormalizedLandmark>>()

    var endedCount = 0

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

        videoViewDoctor = findViewById(R.id.video_view_doctor)
        videoViewPatient = findViewById(R.id.video_view_patient)
        overlayDoctor = findViewById(R.id.overlay_doctor)
        overlayPatient = findViewById(R.id.overlay_patient)   // 新增
        btnLoadDoctor = findViewById(R.id.btn_load_doctor)
        btnLoadPatient = findViewById(R.id.btn_load_patient)
        btnStart = findViewById(R.id.btn_start)

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
                uri = doctorUri!!,
                videoView = videoViewDoctor,
                overlay = overlayDoctor,
                isDoctor = true
            )
            runDetectionOnVideo(
                uri = patientUri!!,
                videoView = videoViewPatient,
                overlay = overlayPatient,
                isDoctor = false
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
                minPoseTrackingConfidence = 0.2f,
                minPosePresenceConfidence = 0.2f,
                currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
            )
            if (isDoctor) poseLandmarkerHelperDoctor = helper
            else poseLandmarkerHelperPatient = helper
        }

        // 获取视频分辨率
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        val videoWidth =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                ?: 640
        val videoHeight =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                ?: 480
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
            imageReaderDoctor = imageReader
            analysisThreadDoctor?.quitSafely()
            analysisThreadDoctor = analysisThread
        } else {
            imageReaderPatient?.close()
            imageReaderPatient = imageReader
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

                val bitmap = image.toBitmapScaled(targetWidth = 256)
                val timestampUs = image.timestamp / 1000

                helper.detectVideoFrame(bitmap, System.currentTimeMillis())?.let { result ->
                    val landmarks = result.results[0].landmarks()
                    if (landmarks.isNotEmpty()) {
                        if (isDoctor) doctorPoseList.add(landmarks[0])
                        else patientPoseList.add(landmarks[0])
                    }
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
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
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
                                endedCount++
                                if (endedCount == 2) {  // 两路都结束
                                    val score = calculateScoreDTW(doctorPoseList, patientPoseList)
                                    Log.d("mmmmm", "" + score)
                                }
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
            if (isDoctor) {
                exoPlayerDoctor?.release(); exoPlayerDoctor = player
            } else {
                exoPlayerPatient?.release(); exoPlayerPatient = player
            }
        }
    }

    /**
     * 把一帧 landmarks 提取为关节角度向量
     */
    fun extractAngleVector(frame: List<NormalizedLandmark>): FloatArray {
        val joints = listOf(
            // ── 手臂 ──────────────────────────────────────────────────────────────────
            Triple(11, 13, 15), // 左肘角：左肩-左肘-左腕（手臂弯曲程度）
            Triple(12, 14, 16), // 右肘角：右肩-右肘-右腕（手臂弯曲程度）
//            Triple(13, 15, 17), // 左腕角：左肘-左腕-左小指（手腕弯曲）
//            Triple(14, 16, 18), // 右腕角：右肘-右腕-右小指（手腕弯曲）
//            Triple(13, 15, 19), // 左腕角：左肘-左腕-左食指（手腕弯曲）
//            Triple(14, 16, 20), // 右腕角：右肘-右腕-右食指（手腕弯曲）

            // ── 肩部 ──────────────────────────────────────────────────────────────────
            Triple(23, 11, 13), // 左肩纵向角：左髋-左肩-左肘（手臂前后抬起幅度）
            Triple(24, 12, 14), // 右肩纵向角：右髋-右肩-右肘（手臂前后抬起幅度）
            Triple(12, 11, 13), // 左肩横向角：右肩-左肩-左肘（手臂左右展开幅度）
            Triple(11, 12, 14), // 右肩横向角：左肩-右肩-右肘（手臂左右展开幅度）

            // ── 躯干 ──────────────────────────────────────────────────────────────────
            Triple(11, 23, 24), // 左躯干角：左肩-左髋-右髋（上身左侧倾斜）
            Triple(12, 24, 23), // 右躯干角：右肩-右髋-左髋（上身右侧倾斜）
            Triple(11, 12, 24), // 肩髋角右：左肩-右肩-右髋（躯干扭转）
            Triple(12, 11, 23), // 肩髋角左：右肩-左肩-左髋（躯干扭转）

//            // ── 颈部/头部 ─────────────────────────────────────────────────────────────
//            Triple(11, 12,  0), // 颈部角：左肩-右肩-鼻子（头部前后倾）
//            Triple(12, 11,  0), // 颈部角：右肩-左肩-鼻子（头部左右偏）

            // ── 下半身（如需要可开启）────────────────────────────────────────────────
            Triple(23, 25, 27), // 左膝角：左髋-左膝-左踝（膝盖弯曲程度）
            Triple(24, 26, 28), // 右膝角：右髋-右膝-右踝（膝盖弯曲程度）
            Triple(11, 23, 25), // 左髋角：左肩-左髋-左膝（髋部弯曲程度）
            Triple(12, 24, 26), // 右髋角：右肩-右髋-右膝（髋部弯曲程度）
//             Triple(25, 27, 31), // 左踝角：左膝-左踝-左脚尖（踝关节角度）
//             Triple(26, 28, 32), // 右踝角：右膝-右踝-右脚尖（踝关节角度）
        )
        return FloatArray(joints.size) { i ->
            val (a, b, c) = joints[i]
            angle(frame[a], frame[b], frame[c])
        }
    }

    /**
     * 两个角度向量之间的距离（单位：度）
     */
    fun vectorDistance(v1: FloatArray, v2: FloatArray): Float {
        var sum = 0f
        for (i in v1.indices) {
            val diff = abs(v1[i] - v2[i]).coerceAtMost(90f)
            sum += diff
        }
        return sum / v1.size  // 平均角度差（°）
    }


    /**
     * 带 Sakoe-Chiba Band 约束的 DTW（防止过度弯曲，提升性能）
     * windowRatio: 允许的最大时间偏移比例，推荐 0.1~0.2
     */
    fun dtwWithWindow(
        seq1: List<FloatArray>,
        seq2: List<FloatArray>,
        windowRatio: Float = 0.15f
    ): Float {
        val n = seq1.size
        val m = seq2.size
        val window = (maxOf(n, m) * windowRatio).toInt().coerceAtLeast(1)

        val dp = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        dp[0][0] = 0f

        for (i in 1..n) {
            // Band 约束：j 的范围限制在窗口内
            val jStart = maxOf(1, i - window)
            val jEnd = minOf(m, i + window)
            for (j in jStart..jEnd) {
                val cost = vectorDistance(seq1[i - 1], seq2[j - 1])
                val minPrev = minOf(
                    dp[i - 1][j],
                    dp[i][j - 1],
                    dp[i - 1][j - 1]
                )
                if (minPrev < Float.MAX_VALUE) {
                    dp[i][j] = cost + minPrev
                }
            }
        }

        // 归一化：除以对角路径长度
        val pathLen = (n + m).toFloat()
        return dp[n][m] / pathLen
    }


    //    /**
//     * 最终评分入口
//     */
//    fun calculateScoreDTW(
//        doctorList: List<List<NormalizedLandmark>>,
//        patientList: List<List<NormalizedLandmark>>
//    ): Int {
//        // 1. 提取角度序列
//        val seq1 = doctorList.map { extractAngleVector(it) }
//        val seq2 = patientList.map { extractAngleVector(it) }
//
//        // 2. 计算 DTW 归一化距离（单位：平均角度差°）
//        val avgAngleDiff = dtwWithWindow(seq1, seq2, windowRatio = 0.15f)
//
//        // 3. 映射到分数
//        //    avgAngleDiff = 0°  → 100分（完美）
//        //    avgAngleDiff = 30° → 0分（差异很大）
//        //    可根据实际数据调整 maxDiff
//        val maxDiff = 30f
//        val score = ((1f - avgAngleDiff / maxDiff) * 100f)
//            .coerceIn(0f, 100f)
//            .toInt()
//
//        return score
//    }
    fun calculateScoreDTW(
        doctorList: List<List<NormalizedLandmark>>,
        patientList: List<List<NormalizedLandmark>>
    ): Int {
        if (doctorList.isEmpty() || patientList.isEmpty()) return 0

        val doctorSeq = doctorList.map { extractAngleVector(it) }
        val patientSeq = patientList.map { extractAngleVector(it) }

        // 第一步：找到患者有效段
        val subResult = subsequenceDTW(doctorSeq, patientSeq, windowRatio = 0.15f)
        if (subResult.avgAngleDiff == Float.MAX_VALUE) return 0

        // 提取患者有效段
        val validPatientSeq = patientSeq.subList(subResult.patientStart, subResult.patientEnd)

        Log.d("mmmmm", "患者有效段：第${subResult.patientStart}~${subResult.patientEnd}帧，共${validPatientSeq.size}帧")

        // 第二步：按5秒切块评分
        // 采样率 20fps（FRAME_INTERVAL_MS=50ms），5秒=100帧
        val framesPerSegment = (5000 / FRAME_INTERVAL_MS).toInt()

        val segmentScores = mutableListOf<Int>()
        var segIndex = 0

        while (true) {
            // 医生序列的切块范围
            val doctorStart = segIndex * framesPerSegment
            val doctorEnd = minOf(doctorStart + framesPerSegment, doctorSeq.size)
            if (doctorStart >= doctorSeq.size) break

            // 患者有效段按比例映射到对应范围
            // 医生第 doctorStart~doctorEnd 帧 对应 患者有效段的哪个范围
            val ratio = validPatientSeq.size.toFloat() / doctorSeq.size
            val patStart = (doctorStart * ratio).toInt().coerceIn(0, validPatientSeq.size)
            val patEnd = (doctorEnd * ratio).toInt().coerceIn(0, validPatientSeq.size)

            if (patStart >= patEnd || doctorStart >= doctorEnd) {
                segIndex++
                continue
            }

            val doctorChunk = doctorSeq.subList(doctorStart, doctorEnd)
            val patientChunk = validPatientSeq.subList(patStart, patEnd)

            // 对这段单独跑DTW评分
            val avgDiff = dtwWithWindow(doctorChunk, patientChunk, windowRatio = 0.15f)
            val segScore = if (avgDiff == Float.MAX_VALUE) 0
            else ((1f - avgDiff / 30f) * 100f).coerceIn(0f, 100f).toInt()

            segmentScores.add(segScore)

            val segStartSec = segIndex * 5
            val segEndSec = segStartSec + 5
            Log.d("mmmmm", "第${segIndex + 1}段 (${segStartSec}s~${segEndSec}s): $segScore 分，角度差=%.1f°".format(avgDiff))

            segIndex++
        }

        if (segmentScores.isEmpty()) return 0

        // 第三步：汇总
        val totalScore = segmentScores.average().toInt()
        Log.d("mmmmm", "分段明细: $segmentScores")
        Log.d("mmmmm", "最终总分: $totalScore")

        return totalScore
    }

    // 计算三点夹角（角度）
    fun angle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Float {
        val v1x = a.x() - b.x();
        val v1y = a.y() - b.y()
        val v2x = c.x() - b.x();
        val v2y = c.y() - b.y()
        val dot = v1x * v2x + v1y * v2y
        val mag = sqrt((v1x * v1x + v1y * v1y) * (v2x * v2x + v2y * v2y))
        return Math.toDegrees(acos((dot / mag).coerceIn(-1f, 1f).toDouble())).toFloat()
    }


    // ── YUV→Bitmap（直接降采样转换，跳过JPEG）────────────────────────────────────
    private fun android.media.Image.toBitmapScaled(targetWidth: Int = 256): Bitmap {
        val srcW = width
        val srcH = height

        // 计算缩放步长：每隔 step 个像素采一次
        val step = (srcW.toFloat() / targetWidth).coerceAtLeast(1f)
        val dstW = (srcW / step).toInt()
        val dstH = (srcH / step).toInt()

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val pixels = IntArray(dstW * dstH)

        for (dstRow in 0 until dstH) {
            val srcRow = (dstRow * step).toInt().coerceIn(0, srcH - 1)
            for (dstCol in 0 until dstW) {
                val srcCol = (dstCol * step).toInt().coerceIn(0, srcW - 1)

                // 读 Y 分量
                val yIdx = srcRow * yRowStride + srcCol
                val y = yBuf.get(yIdx).toInt() and 0xFF

                // 读 UV 分量（UV 是 Y 的一半分辨率）
                val uvRow = (srcRow / 2) * uvRowStride
                val uvCol = (srcCol / 2) * uvPixelStride
                val u = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128

                // YUV → RGB
                val r = (y + 1.370705f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.698001f * v - 0.337633f * u).toInt().coerceIn(0, 255)
                val b = (y + 1.732446f * u).toInt().coerceIn(0, 255)

                pixels[dstRow * dstW + dstCol] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        return bitmap
    }

    fun subsequenceDTW(
        doctorSeq: List<FloatArray>,   // 完整标准动作序列
        patientSeq: List<FloatArray>,  // 包含多余部分的患者序列
        windowRatio: Float = 0.15f
    ): SubDTWResult {
        val n = doctorSeq.size   // 医生帧数
        val m = patientSeq.size  // 患者帧数

        if (n == 0 || m == 0) return SubDTWResult(Float.MAX_VALUE, 0, 0)

        val window = (maxOf(n, m) * windowRatio).toInt().coerceAtLeast(abs(n - m) + 1)

        val dp = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        val pathLen = Array(n + 1) { IntArray(m + 1) { 0 } }

        // ✅ 关键改动1：第0行全部初始化为0
        // 含义：医生从第0帧开始，可以从患者任意位置j开始匹配
        for (j in 0..m) dp[0][j] = 0f

        for (i in 1..n) {
            val jStart = maxOf(1, i - window)
            val jEnd = minOf(m, i + window)
            for (j in jStart..jEnd) {
                val cost = vectorDistance(doctorSeq[i - 1], patientSeq[j - 1])

                val d1 = dp[i - 1][j]
                val d2 = dp[i][j - 1]
                val d3 = dp[i - 1][j - 1]
                val minVal = minOf(d1, d2, d3)

                if (minVal == Float.MAX_VALUE) continue

                dp[i][j] = cost + minVal
                pathLen[i][j] = when (minVal) {
                    d3 -> pathLen[i - 1][j - 1] + 1
                    d1 -> pathLen[i - 1][j] + 1
                    else -> pathLen[i][j - 1] + 1
                }
            }
        }

        // ✅ 关键改动2：找医生全部匹配完（第n行）时，患者的最优结束位置
        var bestEnd = -1
        var bestCost = Float.MAX_VALUE
        for (j in 1..m) {
            if (dp[n][j] < bestCost) {
                bestCost = dp[n][j]
                bestEnd = j
            }
        }

        if (bestEnd == -1 || bestCost == Float.MAX_VALUE) {
            return SubDTWResult(Float.MAX_VALUE, 0, m)
        }

        // ✅ 回溯找患者起始帧
        val patientStart = backtrackStart(dp, pathLen, n, bestEnd)

        val avgDiff = bestCost / pathLen[n][bestEnd].coerceAtLeast(1)

        Log.d("mmmmm", "医生: $n 帧，患者总: $m 帧")
        Log.d("mmmmm", "最优匹配段：患者第 $patientStart ~ $bestEnd 帧")
        Log.d("mmmmm", "平均角度差: %.1f°".format(avgDiff))

        return SubDTWResult(avgDiff, patientStart, bestEnd)
    }

    /**
     * 回溯找患者起始帧
     * 从 dp[n][bestEnd] 往左上角回溯，直到 dp[0][j] 为止
     */
    private fun backtrackStart(
        dp: Array<FloatArray>,
        pathLen: Array<IntArray>,
        n: Int,
        bestEnd: Int
    ): Int {
        var i = n
        var j = bestEnd
        while (i > 0) {
            val d1 = dp[i - 1][j]
            val d2 = if (j > 0) dp[i][j - 1] else Float.MAX_VALUE
            val d3 = if (j > 0) dp[i - 1][j - 1] else Float.MAX_VALUE
            val minVal = minOf(d1, d2, d3)
            when (minVal) {
                d3 -> {
                    i--; j--
                }

                d1 -> i--
                else -> j--
            }
        }
        // 此时 i=0，j 就是患者的起始帧索引
        return j
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────
    override fun onPause() {
        if (!isPickingFile) {
            overlayDoctor.clear()
            overlayPatient.clear()
            if (videoViewDoctor.isPlaying) videoViewDoctor.stopPlayback()
            if (videoViewPatient.isPlaying) videoViewPatient.stopPlayback()
            videoViewDoctor.visibility = View.GONE
            videoViewPatient.visibility = View.GONE
        }
        isPickingFile = false
        super.onPause()
    }

    override fun onDestroy() {
        exoPlayerDoctor?.release(); exoPlayerDoctor = null
        exoPlayerPatient?.release(); exoPlayerPatient = null
        imageReaderDoctor?.close(); imageReaderDoctor = null
        imageReaderPatient?.close(); imageReaderPatient = null
        analysisThreadDoctor?.quitSafely(); analysisThreadDoctor = null
        analysisThreadPatient?.quitSafely(); analysisThreadPatient = null
        super.onDestroy()
    }

    // ── Listener ──────────────────────────────────────────────────────────────
    override fun onError(error: String, errorCode: Int) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) { /* IMAGE模式不走此回调 */
    }
}