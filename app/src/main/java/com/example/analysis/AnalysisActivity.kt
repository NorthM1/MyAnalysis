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
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import io.github.sceneview.math.Color
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.pow
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

data class DTWResult(
    val avgAngleDiff: Float,
    val maxAngleDiff: Float,
    val errorFrameRatio: Float,  // 超过阈值的帧占比（0~1）
    val errorThreshold: Float    // 使用的阈值
)

// ✅ 新增数据类（放在文件顶部 SubDTWResult 旁边）
data class FrameResult(
    val result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
    val width: Int,
    val height: Int
)

data class SegmentItem(
    val index: Int,
    val startSec: Int,
    val endSec: Int,
    val score: Int,
    val doctorStartMs: Long,
    val patientStartMs: Long
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
    private lateinit var tvScore: TextView
    private lateinit var sb: SeekBar
    private lateinit var tvNums: TextView
    private lateinit var tvResult: TextView
    private lateinit var clScore: ConstraintLayout
    private lateinit var llSeekbar: LinearLayout
    private lateinit var rl: RelativeLayout
    private lateinit var rv: RecyclerView

    private val segmentItems = mutableListOf<SegmentItem>()
    private lateinit var segmentAdapter: SegmentAdapter

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

    var isPaused = false
    private var isPickingFile = false

    // ── 文件选择器 ────────────────────────────────────────────────────────────
    private var pendingPicker: ((Uri) -> Unit)? = null

    val doctorPoseList = mutableListOf<List<NormalizedLandmark>>()
    val patientPoseList = mutableListOf<List<NormalizedLandmark>>()

    // 帧时间戳 → 骨骼结果缓存（Key单位：毫秒）
    // ✅ 成员变量区替换原来的 doctorFrameCache/patientFrameCache
    val doctorFrameCache = java.util.TreeMap<Long, FrameResult>()
    val patientFrameCache = java.util.TreeMap<Long, FrameResult>()
    private var doctorFrameIndex = 0L
    private var patientFrameIndex = 0L

    var endedCount = 0

    private var lastFrameTsDoctor = 0L
    private var lastFrameTsPatient = 0L

    private var playbackStartMs = 0L
    private var playbackSeekPosMs = 0L
    // 患者有效段起始时间（毫秒）
    private var patientValidStartMs = 0L
    private var patientValidEndMs = 0L

    private var doctorAnalysisStartMs = 0L
    private var patientAnalysisStartMs = 0L

    private val seekBarHandler = Handler(android.os.Looper.getMainLooper())
    private var seekBarRunnable: Runnable? = null

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
        tvScore=findViewById(R.id.tv_score)
        tvNums=findViewById(R.id.tv_nums)
        tvResult=findViewById(R.id.tv_result)
        sb=findViewById(R.id.seek_bar)
        clScore=findViewById(R.id.cl_score)
        llSeekbar=findViewById(R.id.ll_seekbar)
        rl=findViewById(R.id.rl)
        rv=findViewById(R.id.rv)

        btnStart.isEnabled = false

        // ✅ 新增 SeekBar 监听
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // 手指按下：停止自动更新，暂停视频
                stopSeekBarUpdate()
                videoViewDoctor.pause()
                videoViewPatient.pause()
                exoPlayerDoctor?.pause()
                exoPlayerPatient?.pause()
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // 拖动过程中不触发跳转，避免频繁 seekTo 卡顿
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val posMs = seekBar.progress.toLong()
                videoViewDoctor.seekTo(posMs.toInt())
                videoViewPatient.seekTo(posMs.toInt())
                videoViewDoctor.start()
                videoViewPatient.start()

                playbackSeekPosMs = posMs
                playbackStartMs = System.currentTimeMillis()

                // ✅ 直接用视频时间查缓存
                updateOverlayFromCache(posMs, overlayDoctor, doctorFrameCache)
                updateOverlayFromCache(posMs, overlayPatient, patientFrameCache)

                startSeekBarUpdate()
            }
        })

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
            btnStart.visibility=View.GONE
            btnLoadDoctor.visibility=View.GONE
            btnLoadPatient.visibility=View.GONE

            // 两路同时启动分析
            backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
            //backgroundExecutor = Executors.newScheduledThreadPool(2)
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

    private fun startSeekBarUpdate() {
        stopSeekBarUpdate()
        seekBarRunnable = object : Runnable {
            override fun run() {
                // ✅ 直接用 VideoView 的播放位置查缓存（key 和 currentPosition 单位一致，都是视频相对毫秒）
                val currentMs = videoViewDoctor.currentPosition.toLong()
                sb.progress = currentMs.toInt()

                // ✅ 骨骼提前2帧显示，补偿渲染延迟（1帧=100ms，2帧=200ms）
                val skeletonMs = (currentMs + FRAME_INTERVAL_MS * 10).coerceAtMost(sb.max.toLong())
                updateOverlayFromCache(skeletonMs, overlayDoctor, doctorFrameCache)
                updateOverlayFromCache(skeletonMs, overlayPatient, patientFrameCache)

                seekBarHandler.postDelayed(this, 100)
            }
        }
        seekBarHandler.post(seekBarRunnable!!)
    }

    private fun stopSeekBarUpdate() {
        seekBarRunnable?.let { seekBarHandler.removeCallbacks(it) }
        seekBarRunnable = null
    }

    /**
     * 根据当前播放时间从缓存中找最近帧，更新骨骼覆盖层
     */
    private fun updateOverlayFromCache(
        videoMs: Long,  // ← 改名，含义更清晰
        overlay: OverlayView,
        cache: java.util.TreeMap<Long, FrameResult>
    ) {
        if (cache.isEmpty()) return
        val entry = cache.floorEntry(videoMs) ?: cache.firstEntry()
        entry?.value?.let { frameResult ->
            overlay.setResults(
                frameResult.result,
                frameResult.height,
                frameResult.width,
                RunningMode.IMAGE
            )
            overlay.invalidate()
        }
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

        // ✅ 限制最大480p，ExoPlayer会自动缩放输出
        val maxReaderWidth = 480
        val readerScale = minOf(1f, maxReaderWidth.toFloat() / videoWidth)
        val readerWidth = (videoWidth * readerScale).toInt()
        val readerHeight = (videoHeight * readerScale).toInt()

        val imageReader = android.media.ImageReader.newInstance(
            readerWidth, readerHeight, android.graphics.ImageFormat.YUV_420_888, 2
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
                val timestampUs = image.timestamp / 1000
                val lastTs = if (isDoctor) lastFrameTsDoctor else lastFrameTsPatient

                // ✅ 用视频时间戳控制帧率
                if (isPaused || timestampUs - lastTs < FRAME_INTERVAL_MS * 1000) return@setOnImageAvailableListener

                val helper = if (isDoctor) {
                    if (this::poseLandmarkerHelperDoctor.isInitialized) poseLandmarkerHelperDoctor else null
                } else {
                    if (this::poseLandmarkerHelperPatient.isInitialized) poseLandmarkerHelperPatient else null
                }
                // ✅ helper为null不占用时间窗口
                helper ?: return@setOnImageAvailableListener

                // ✅ 确认处理才更新时间戳
                if (isDoctor) lastFrameTsDoctor = timestampUs
                else lastFrameTsPatient = timestampUs

                val bitmap = image.toBitmapScaled(targetWidth = 256)

                helper.detectVideoFrame(bitmap, timestampUs)?.let { result ->
                    val landmarks = result.results[0].landmarks()
                    if (landmarks.isNotEmpty()) {
                        if (isDoctor) doctorPoseList.add(landmarks[0])
                        else patientPoseList.add(landmarks[0])
                    }

                    // ✅ 用视频相对时间作为key（系统时间 - 分析开始时间）
                    val videoRelativeMs = if (isDoctor) {
                        System.currentTimeMillis() - doctorAnalysisStartMs
                    } else {
                        System.currentTimeMillis() - patientAnalysisStartMs
                    }

                    if (isDoctor) {
                        doctorFrameCache[videoRelativeMs] = FrameResult(result.results[0], bitmap.width, bitmap.height)
                    } else {
                        patientFrameCache[videoRelativeMs] = FrameResult(result.results[0], bitmap.width, bitmap.height)
                    }


                    runOnUiThread {
                        overlay.setResults(
                            result.results[0],
                            bitmap.height,
                            bitmap.width,
                            RunningMode.IMAGE
                        )
                        overlay.invalidate()
                        bitmap.recycle()
                    }
                } ?: bitmap.recycle()

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

                    if (isDoctor) doctorAnalysisStartMs = System.currentTimeMillis()
                    else patientAnalysisStartMs = System.currentTimeMillis()

                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == androidx.media3.common.Player.STATE_ENDED) {
                                endedCount++
                                if (endedCount == 2) {  // 两路都结束
                                    val score = calculateScoreDTW(doctorPoseList, patientPoseList)
                                    tvScore.text="$score"

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
//            Triple(11, 13, 15), // 左肘角：左肩-左肘-左腕（手臂弯曲程度）
//            Triple(12, 14, 16), // 右肘角：右肩-右肘-右腕（手臂弯曲程度）
//            Triple(13, 15, 17), // 左腕角：左肘-左腕-左小指（手腕弯曲）
//            Triple(14, 16, 18), // 右腕角：右肘-右腕-右小指（手腕弯曲）
//            Triple(13, 15, 19), // 左腕角：左肘-左腕-左食指（手腕弯曲）
//            Triple(14, 16, 20), // 右腕角：右肘-右腕-右食指（手腕弯曲）

            // ── 肩部 ──────────────────────────────────────────────────────────────────
//            Triple(23, 11, 13), // 左肩纵向角：左髋-左肩-左肘（手臂前后抬起幅度）
//            Triple(24, 12, 14), // 右肩纵向角：右髋-右肩-右肘（手臂前后抬起幅度）
//            Triple(12, 11, 13), // 左肩横向角：右肩-左肩-左肘（手臂左右展开幅度）
//            Triple(11, 12, 14), // 右肩横向角：左肩-右肩-右肘（手臂左右展开幅度）

            // ── 躯干 ──────────────────────────────────────────────────────────────────
//            Triple(11, 23, 24), // 左躯干角：左肩-左髋-右髋（上身左侧倾斜）
//            Triple(12, 24, 23), // 右躯干角：右肩-右髋-左髋（上身右侧倾斜）
//            Triple(11, 12, 24), // 肩髋角右：左肩-右肩-右髋（躯干扭转）
//            Triple(12, 11, 23), // 肩髋角左：右肩-左肩-左髋（躯干扭转）

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
        windowRatio: Float = 0.15f,
        errorThreshold: Float = 15f  // ✅ 可修改的偏差阈值
    ): DTWResult {
        val n = seq1.size
        val m = seq2.size

        if (n == 0 || m == 0) return DTWResult(Float.MAX_VALUE, Float.MAX_VALUE, 1f, errorThreshold)

        val minWindow = abs(n - m) + 1
        val window = maxOf(minWindow, (maxOf(n, m) * windowRatio).toInt())

        val dp = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        val pathLen = Array(n + 1) { IntArray(m + 1) { 0 } }
        val costMap = Array(n + 1) { FloatArray(m + 1) { 0f } }
        dp[0][0] = 0f

        for (i in 1..n) {
            val jStart = maxOf(1, i - window)
            val jEnd = minOf(m, i + window)
            for (j in jStart..jEnd) {
                val cost = vectorDistance(seq1[i - 1], seq2[j - 1])
                costMap[i][j] = cost

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

        if (dp[n][m] == Float.MAX_VALUE) return DTWResult(Float.MAX_VALUE, Float.MAX_VALUE, 1f, errorThreshold)

        // 回溯最优路径，收集每帧的cost
        val pathCosts = mutableListOf<Float>()
        var i = n; var j = m
        while (i > 0 && j > 0) {
            pathCosts.add(costMap[i][j])
            val d1 = dp[i - 1][j]
            val d2 = if (j > 0) dp[i][j - 1] else Float.MAX_VALUE
            val d3 = if (i > 0 && j > 0) dp[i - 1][j - 1] else Float.MAX_VALUE
            val minVal = minOf(d1, d2, d3)
            when (minVal) {
                d3 -> { i--; j-- }
                d1 -> i--
                else -> j--
            }
        }

//        val actualPathLen = pathLen[n][m].coerceAtLeast(1)
//        val avgDiff = dp[n][m] / actualPathLen
        val avgDiff = dp[n][m] / (n + m).toFloat()
        val maxDiff = pathCosts.maxOrNull() ?: avgDiff
        val errorFrameRatio = pathCosts.count { it > errorThreshold }.toFloat() / pathCosts.size.coerceAtLeast(1)

        return DTWResult(avgDiff, maxDiff, errorFrameRatio, errorThreshold)
    }

    /**
     * 对角度序列做滑动窗口平滑
     * 在 extractAngleVector 之后、DTW 之前调用
     */
    fun smoothAngleSeq(
        seq: List<FloatArray>,
        windowSize: Int = 3
    ): List<FloatArray> {
        if (seq.size < windowSize) return seq
        val half = windowSize / 2
        return seq.mapIndexed { idx, _ ->
            val start = (idx - half).coerceAtLeast(0)
            val end = (idx + half + 1).coerceAtMost(seq.size)
            val window = seq.subList(start, end)
            val jointCount = seq[0].size
            FloatArray(jointCount) { j ->
                // ✅ 取中值而不是平均值
                val values = window.map { it[j] }.sorted()
                values[values.size / 2]
            }
        }
    }

    // ── 运动复杂度计算 ─────────────────────────────────────
    /**
     * 计算某段医生序列的运动复杂度（各关节角度标准差的均方根）
     * 用于加权汇总：动作幅度越大的分段权重越高
     * @param chunk  该段的角度向量列表
     * @return 复杂度值（>= 1.0）
     */
    fun computeComplexity(chunk: List<FloatArray>): Float {
        if (chunk.size < 2) return 1f
        val jointCount = chunk[0].size
        var totalVariance = 0f
        for (j in 0 until jointCount) {
            val mean = chunk.map { it[j] }.average().toFloat()
            val variance = chunk.map { v -> (v[j] - mean) * (v[j] - mean) }
                .average().toFloat()
            totalVariance += variance
        }
        return kotlin.math.sqrt(totalVariance / jointCount) + 1f  // +1 平滑项
    }

    // ── 多指标加权分段得分 ───────────────────────────────────
    /**
     * 多指标加权评分模型
     * S_avg  = max(0, 1 - (avgAngleDiff/30)^1.5) * 100   权重 0.5
     * S_err  = (1 - errorFrameRatio) * 100                权重 0.3
     * S_max  = max(0, 1 - maxAngleDiff/90) * 100          权重 0.2
     */
    fun computeSegmentScore(dtwResult: DTWResult): Int {
        if (dtwResult.avgAngleDiff == Float.MAX_VALUE) return 0

        // ① 平均偏差子分：非线性幂函数，alpha=1.5
        val ratio1 = (dtwResult.avgAngleDiff / 30f).coerceAtMost(1f)
        val sAvg = (1f - ratio1.toDouble().pow(1.5)).coerceAtLeast(0.0).toFloat() * 100f

        // ② 超阈值帧占比子分：线性
        val sErr = (1f - dtwResult.errorFrameRatio) * 100f

        // ③ 最大偏差子分：线性，上限90度
        val sMax = (1f - dtwResult.maxAngleDiff / 90f).coerceIn(0f, 1f) * 100f

        // ④ 加权综合
        return (0.5f * sAvg + 0.3f * sErr + 0.2f * sMax)
            .coerceIn(0f, 100f).toInt()
    }


    val complexityWeights = mutableListOf<Float>()      //  各段复杂度权重

    fun calculateScoreDTW(
        doctorList: List<List<NormalizedLandmark>>,
        patientList: List<List<NormalizedLandmark>>
    ): Int {
        if (doctorList.isEmpty() || patientList.isEmpty()) return 0

        val doctorSeqRaw = doctorList.map { extractAngleVector(it) }
        val patientSeqRaw = patientList.map { extractAngleVector(it) }

        // ✅ 平滑处理
        val doctorSeq = smoothAngleSeq(doctorSeqRaw, windowSize = 3)
        val patientSeq = smoothAngleSeq(patientSeqRaw, windowSize = 3)

        // ── 第一步：全局subsequenceDTW，找患者有效段 ──────────────────────────
        val subResult = subsequenceDTW(doctorSeq, patientSeq, windowRatio = 0.15f)
        if (subResult.avgAngleDiff == Float.MAX_VALUE) return 0

        val validPatientSeq = patientSeq.subList(subResult.patientStart, subResult.patientEnd)
        // ✅ 保存患者有效段的时间偏移
        patientValidStartMs = subResult.patientStart * FRAME_INTERVAL_MS
        patientValidEndMs = subResult.patientEnd * FRAME_INTERVAL_MS

        Log.d("mmmmm", "═══════════════════════════════════")
        Log.d("mmmmm", "医生: ${doctorSeq.size}帧，患者总: ${patientSeq.size}帧")
        Log.d("mmmmm", "最优匹配段：患者第 ${subResult.patientStart} ~ ${subResult.patientEnd} 帧")
        Log.d("mmmmm", "平均角度差: ${"%.1f".format(subResult.avgAngleDiff)}°")
        Log.d("mmmmm", "═══════════════════════════════════")

        // ── 第二步：全局对齐路径，用于动态切块 ───────────────────────────────
        // 在医生序列和患者有效段之间建立帧级对应关系
        val alignment = getAlignmentPath(doctorSeq, validPatientSeq, windowRatio = 0.15f)

        Log.d("mmmmm", "全局路径回溯完成，医生${doctorSeq.size}帧 → 患者有效段${validPatientSeq.size}帧")

        // ── 第三步：按医生帧切块，患者边界从alignment取 ──────────────────────
        val framesPerSegment = (5000 / FRAME_INTERVAL_MS).toInt()
        val segmentScores = mutableListOf<Int>()
        var segIndex = 0

        while (true) {
            val doctorStart = segIndex * framesPerSegment
            val doctorEnd = minOf(doctorStart + framesPerSegment, doctorSeq.size)
            if (doctorStart >= doctorSeq.size) break

            // ✅ 从全局路径取患者边界，完全消除速度影响
            val patStart = alignment[doctorStart]
            val patEnd = (alignment[doctorEnd - 1] + 1).coerceAtMost(validPatientSeq.size)

            if (patStart >= patEnd || doctorStart >= doctorEnd) {
                segIndex++; continue
            }

            val doctorChunk = doctorSeq.subList(doctorStart, doctorEnd)
            val patientChunk = validPatientSeq.subList(patStart, patEnd)

            // ✅ 分段内窗口放大到0.3，吸收局部速度抖动
            val dtwResult = dtwWithWindow(doctorChunk, patientChunk, windowRatio = 0.3f, errorThreshold = 15f)

            // 使用多指标加权评分替换原简单线性评分
            val segScore = computeSegmentScore(dtwResult)

            // 计算该段医生序列的运动复杂度作为权重
            val complexity = computeComplexity(doctorChunk)

            segmentScores.add(segScore)
            complexityWeights.add(complexity)


            // 保留原有详细Log
            val segStartSec = segIndex * 5
            val segEndSec = segStartSec + 5
            Log.d("mmmmm", "───────────────────────────────────")
            Log.d("mmmmm", "第${segIndex + 1}段 (${segStartSec}s ~ ${segEndSec}s)")
            Log.d("mmmmm", "  得分:       $segScore 分")
            Log.d("mmmmm", "  平均偏差:   ${"%.1f".format(dtwResult.avgAngleDiff)}°")
            Log.d("mmmmm", "  最大偏差:   ${"%.1f".format(dtwResult.maxAngleDiff)}°")
            Log.d("mmmmm", "  超${dtwResult.errorThreshold.toInt()}°帧占比: ${"%.1f".format(dtwResult.errorFrameRatio * 100)}%")
            Log.d("mmmmm", "  医生帧范围: $doctorStart ~ $doctorEnd（${doctorEnd - doctorStart}帧）")
            Log.d("mmmmm", "  患者帧范围: $patStart ~ $patEnd（${patEnd - patStart}帧）")

            segIndex++
        }

        if (segmentScores.isEmpty()) return 0

        // ── 第四步：汇总 ──────────────────────────────────────────────────────
        val weightSum = complexityWeights.sum().coerceAtLeast(1e-6f)
        val totalScore = segmentScores.zip(complexityWeights)
            .sumOf { (score, w) -> score * w.toDouble() }
            .div(weightSum)
            .toInt()
            .coerceIn(0, 100)

        Log.d("mmmmm", "═══════════════════════════════════")
        Log.d("mmmmm", "分段明细: $segmentScores")
        Log.d("mmmmm", "最终总分: $totalScore")
        Log.d("mmmmm", "═══════════════════════════════════")


        clScore.visibility=View.VISIBLE
        llSeekbar.visibility=View.VISIBLE
        btnStart.visibility=View.GONE
        btnLoadDoctor.visibility=View.GONE
        btnLoadPatient.visibility=View.GONE
        rl.visibility=View.VISIBLE
        rv.visibility=View.VISIBLE

        // ✅ 用 ExoPlayer 获取时长，VideoView 结束后 duration 可能不准
        val durationMs = exoPlayerDoctor?.duration ?: videoViewDoctor.duration.toLong()
        if (durationMs > 0) {
            sb.max = durationMs.toInt()
            sb.progress = 0
        }

        videoViewDoctor.seekTo(0)
        videoViewPatient.seekTo(0)
        videoViewDoctor.start()   // ✅ 确保 VideoView 从头播放
        videoViewPatient.start()

        // ✅ 初始化起点，必须在 startSeekBarUpdate 之前
        playbackSeekPosMs = 0L
        playbackStartMs = System.currentTimeMillis()

        startSeekBarUpdate()

        tvScore.text=totalScore.toString()
        tvNums.text="共 ${segIndex} 个分段"
        tvResult.apply{
            when{
                totalScore>85 ->{
                    text="优秀"
                    background= android.graphics.Color.parseColor("#FFE8F5E9").toDrawable()
                    setTextColor(android.graphics.Color.parseColor("#FF2E7D32"))
                }
                totalScore>75 ->{
                    text="良好"
                    background= android.graphics.Color.parseColor("#FFFFF3E0").toDrawable()
                    setTextColor(android.graphics.Color.parseColor("#FFE65100"))
                }
                else -> {
                    text="需改进"
                    background= android.graphics.Color.parseColor("#FFFFEBEE").toDrawable()
                    setTextColor(android.graphics.Color.parseColor("#FFC62828"))
                }
            }
        }


        return totalScore
    }

    // 计算三点夹角（角度）
    fun angle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Float {
        val v1x = a.x() - b.x()
        val v1y = a.y() - b.y()
        val v2x = c.x() - b.x()
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

    /**
     * 全局DTW路径回溯
     * 返回 alignment[i] = 医生第i帧对应的患者帧索引
     */
    fun getAlignmentPath(
        seq1: List<FloatArray>,
        seq2: List<FloatArray>,
        windowRatio: Float = 0.15f
    ): IntArray {
        val n = seq1.size
        val m = seq2.size
        val minWindow = abs(n - m) + 1
        val window = maxOf(minWindow, (maxOf(n, m) * windowRatio).toInt())

        val dp = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        dp[0][0] = 0f

        for (i in 1..n) {
            val jStart = maxOf(1, i - window)
            val jEnd = minOf(m, i + window)
            for (j in jStart..jEnd) {
                val cost = vectorDistance(seq1[i - 1], seq2[j - 1])
                val minVal = minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                if (minVal < Float.MAX_VALUE) dp[i][j] = cost + minVal
            }
        }

        // 回溯路径，记录每个医生帧(i)对应的患者帧(j)
        val alignment = IntArray(n)
        var i = n; var j = m

        while (i > 0 && j > 0) {
            alignment[i - 1] = j - 1
            val d1 = dp[i - 1][j]
            val d2 = dp[i][j - 1]
            val d3 = dp[i - 1][j - 1]
            val minVal = minOf(d1, d2, d3)
            when (minVal) {
                d3 -> { i--; j-- }
                d1 -> i--
                else -> j--
            }
        }
        // 填充路径起始未被回溯到的帧
        while (i > 0) { alignment[i - 1] = 0; i-- }

        return alignment
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

        stopSeekBarUpdate()

        super.onDestroy()
    }

    // ── Listener ──────────────────────────────────────────────────────────────
    override fun onError(error: String, errorCode: Int) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) { /* IMAGE模式不走此回调 */
    }
}