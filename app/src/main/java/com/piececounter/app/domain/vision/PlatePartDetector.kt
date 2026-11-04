package com.piececounter.app.domain.vision

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenCV-Powered On-Device Vision Engine for 3D Printer Build Plates.
 *
 * Uses official OpenCV C++ routines (`Imgproc.matchTemplate` with `TM_CCOEFF_NORMED`,
 * `Imgproc.createCLAHE` in CIE LAB color space, `Imgproc.Sobel`, `Imgproc.GaussianBlur`,
 * `Imgproc.morphologyEx`, `Imgproc.findContours`, and `Imgproc.dilate`) combined with exact
 * rotated-polygon Non-Maximum Suppression ([BoxGeometry.MAX_OVERLAP_RATIO] = 10%).
 */
object PlatePartDetector {

    private const val MAX_ANALYSIS_DIMENSION = 640
    private const val MIN_KEEP_SCORE = 0.14f
    private const val PRIMARY_MATCH_SCORE = 0.46f
    private const val MAX_AUTO_CONFIDENCE = 0.96f

    /** Multi-scale template pyramid factors to handle camera tilt / perspective foreshortening. */
    private val TEMPLATE_SCALES = floatArrayOf(0.86f, 0.93f, 1.00f, 1.08f, 1.16f)

    /** Small perspective skew angles (relative to the user's reference angle) checked when rotation is enabled. */
    private val SKEW_ANGLES = floatArrayOf(0f, -5f, 5f)

    /** Ensures the native OpenCV C++ library is loaded on both Android devices and desktop JVM unit tests. */
    private val openCvReady: Boolean by lazy {
        try {
            val openPnpClass = Class.forName("nu.pattern.OpenCV")
            openPnpClass.getMethod("loadLocally").invoke(null)
            true
        } catch (_: Throwable) {
            try {
                OpenCVLoader.initLocal()
            } catch (_: Throwable) {
                try {
                    System.loadLibrary("opencv_java4")
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    data class PixelBuffer(
        val pixels: IntArray,
        val width: Int,
        val height: Int
    )

    /**
     * Converts an Android [Bitmap] into a downscaled [PixelBuffer] suitable for fast analysis.
     */
    fun bitmapToPixelBuffer(bitmap: Bitmap, maxDim: Int = MAX_ANALYSIS_DIMENSION): PixelBuffer {
        val srcW = bitmap.width.coerceAtLeast(1)
        val srcH = bitmap.height.coerceAtLeast(1)
        val scale = min(1f, maxDim.toFloat() / max(srcW, srcH).toFloat())
        val dstW = (srcW * scale).roundToInt().coerceAtLeast(16)
        val dstH = (srcH * scale).roundToInt().coerceAtLeast(16)
        val scaled = if (dstW == srcW && dstH == srcH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        }
        val pixels = IntArray(dstW * dstH)
        scaled.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        return PixelBuffer(pixels = pixels, width = dstW, height = dstH)
    }

    /** Image width / height of an analysis buffer (used for rotated-box geometry). */
    fun imageAspect(buffer: PixelBuffer): Float =
        buffer.width.toFloat() / buffer.height.coerceAtLeast(1).toFloat()

    /**
     * Step 1: Detects candidate foreground parts on the 3D printer plate using OpenCV CIE LAB
     * background subtraction, Otsu thresholding, morphological closing, and contour extraction.
     */
    fun detectCandidateParts(buffer: PixelBuffer): List<NormalizedRect> {
        check(openCvReady) { "OpenCV native library failed to load." }
        val w = buffer.width
        val h = buffer.height
        if (w < 16 || h < 16) return emptyList()

        val bgr = pixelBufferToBgrMat(buffer)
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)

        val labChannels = ArrayList<Mat>(3)
        Core.split(lab, labChannels)
        val lChan = labChannels[0]
        val aChan = labChannels[1]
        val bChan = labChannels[2]

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val lNorm = Mat()
        clahe.apply(lChan, lNorm)

        val (bgL, bgA, bgB) = estimateGlobalLabBackground(lNorm, aChan, bChan, w, h)

        val dL = Mat()
        val dA = Mat()
        val dB = Mat()
        Core.absdiff(lNorm, Scalar(bgL), dL)
        Core.absdiff(aChan, Scalar(bgA), dA)
        Core.absdiff(bChan, Scalar(bgB), dB)

        val diffCombined = Mat()
        Core.addWeighted(dL, 0.55, dA, 0.85, 0.0, diffCombined)
        Core.addWeighted(diffCombined, 1.0, dB, 0.85, 0.0, diffCombined)

        val blurred = Mat()
        Imgproc.GaussianBlur(diffCombined, blurred, Size(5.0, 5.0), 0.0)

        val binary = Mat()
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val minArea = (w * h) * 0.0008
        val maxArea = (w * h) * 0.35
        val candidates = mutableListOf<NormalizedRect>()

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val area = rect.width.toDouble() * rect.height.toDouble()
            val aspect = rect.width.toFloat() / rect.height.coerceAtLeast(1).toFloat()
            if (area in minArea..maxArea && aspect in 0.18f..5.5f) {
                val padX = 2f / w
                val padY = 2f / h
                val normRect = NormalizedRect(
                    left = (rect.x.toFloat() / w - padX).coerceIn(0f, 1f),
                    top = (rect.y.toFloat() / h - padY).coerceIn(0f, 1f),
                    right = ((rect.x + rect.width).toFloat() / w + padX).coerceIn(0f, 1f),
                    bottom = ((rect.y + rect.height).toFloat() / h + padY).coerceIn(0f, 1f)
                ).clamp(minSize = 0.025f)

                if (normRect.width in 0.025f..0.55f && normRect.height in 0.025f..0.55f) {
                    candidates.add(normRect)
                }
            }
            contour.release()
        }

        bgr.release()
        lab.release()
        lChan.release()
        aChan.release()
        bChan.release()
        lNorm.release()
        dL.release()
        dA.release()
        dB.release()
        diffCombined.release()
        blurred.release()
        binary.release()
        kernel.release()
        closed.release()
        hierarchy.release()

        return mergeOverlappingRects(candidates, iouThreshold = 0.25f)
    }

    /**
     * Chooses the best initial reference box from [candidates], or returns a centered default box
     * if no candidates were segmented.
     */
    fun suggestInitialReferenceBox(candidates: List<NormalizedRect>): NormalizedRect {
        if (candidates.isEmpty()) {
            return NormalizedRect(0.38f, 0.38f, 0.56f, 0.56f)
        }
        val sortedByArea = candidates.sortedBy { it.area }
        val medianArea = sortedByArea[sortedByArea.size / 2].area
        return candidates.minByOrNull { rect ->
            val areaDiff = abs(rect.area - medianArea) / medianArea.coerceAtLeast(0.0001f)
            val centerDist = hypot((rect.centerX - 0.5f).toDouble(), (rect.centerY - 0.5f).toDouble()).toFloat()
            areaDiff * 0.7f + centerDist * 0.3f
        } ?: candidates.first()
    }

    private data class CandidatePeak(
        val cx: Float,
        val cy: Float,
        val angle: Float,
        val scale: Float,
        val score: Float
    )

    /**
     * Step 2: Given a user-selected [referenceRect] defining how a single part looks,
     * uses OpenCV multi-scale `Imgproc.matchTemplate(TM_CCOEFF_NORMED)` on the learned foreground
     * probability & edge channels, combined with regional fill-density and 4-quadrant verification,
     * to find all matching elements on the plate.
     *
     * Returned boxes never overlap each other by more than [BoxGeometry.MAX_OVERLAP_RATIO] (10%).
     * By default, all returned [PartDetectionBox] items have `isSelected = true`.
     */
    fun matchPartsFromReference(
        buffer: PixelBuffer,
        referenceRect: NormalizedRect,
        sensitivity: Float = 0.60f,
        allowRotation: Boolean = true
    ): PlateAnalysisResult {
        check(openCvReady) { "OpenCV native library failed to load." }
        val w = buffer.width
        val h = buffer.height
        val aspect = imageAspect(buffer)
        val ref = referenceRect.clamp(minSize = 0.015f)
        val refAngle = normalizeAngle(ref.angleDegrees)

        val bgrOrig = pixelBufferToBgrMat(buffer)
        val refCx = ref.centerX * w
        val refCy = ref.centerY * h

        val tplW = (ref.width * w).roundToInt().coerceIn(6, w - 2)
        val tplH = (ref.height * h).roundToInt().coerceIn(6, h - 2)
        val tplX = (refCx - tplW * 0.5f).roundToInt().coerceIn(0, w - tplW)
        val tplY = (refCy - tplH * 0.5f).roundToInt().coerceIn(0, h - tplH)
        val tplRect = Rect(tplX, tplY, tplW, tplH)

        // Derotate by refAngle if needed so the reference template is extracted axis-aligned
        val baseWorkBgr = if (abs(refAngle) > 0.5f) {
            val rotMat = Imgproc.getRotationMatrix2D(Point(refCx.toDouble(), refCy.toDouble()), refAngle.toDouble(), 1.0)
            val warped = Mat()
            Imgproc.warpAffine(
                bgrOrig,
                warped,
                rotMat,
                bgrOrig.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE
            )
            rotMat.release()
            warped
        } else {
            bgrOrig.clone()
        }

        // Learn the reference model & build the OpenCV feature stack on the reference-aligned image
        val refFeatures = buildOpenCvFeatureStack(baseWorkBgr, tplRect, existingModel = null)
        val templMat = Mat(refFeatures.mergedFeatures, tplRect).clone()

        val allCandidatePeaks = ArrayList<CandidatePeak>()
        val skews = if (allowRotation) SKEW_ANGLES else floatArrayOf(0f)

        for (skew in skews) {
            val totalAngle = normalizeAngle(refAngle + skew)
            val skewBgr: Mat
            val skewFeatures: FeatureStack
            if (abs(skew) < 0.1f) {
                skewBgr = baseWorkBgr
                skewFeatures = refFeatures
            } else {
                val rotMat = Imgproc.getRotationMatrix2D(Point(refCx.toDouble(), refCy.toDouble()), totalAngle.toDouble(), 1.0)
                skewBgr = Mat()
                Imgproc.warpAffine(
                    bgrOrig,
                    skewBgr,
                    rotMat,
                    bgrOrig.size(),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE
                )
                rotMat.release()
                skewFeatures = buildOpenCvFeatureStack(skewBgr, tplRect, existingModel = refFeatures.colorModel)
            }

            val skewPenalty = abs(skew) * 0.002f

            for (scale in TEMPLATE_SCALES) {
                val sw = (tplW * scale).roundToInt().coerceIn(6, w - 2)
                val sh = (tplH * scale).roundToInt().coerceIn(6, h - 2)
                val resW = w - sw + 1
                val resH = h - sh + 1
                if (resW <= 2 || resH <= 2) continue

                val scaledTempl = Mat()
                if (sw == tplW && sh == tplH) {
                    templMat.copyTo(scaledTempl)
                } else {
                    Imgproc.resize(templMat, scaledTempl, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                }

                // OpenCV C++ multi-channel normalized cross-correlation (TM_CCOEFF_NORMED)
                val corrMat = Mat()
                Imgproc.matchTemplate(skewFeatures.mergedFeatures, scaledTempl, corrMat, Imgproc.TM_CCOEFF_NORMED)

                val corrData = FloatArray(resW * resH)
                corrMat.get(0, 0, corrData)

                val combinedMap = Mat(resH, resW, CvType.CV_32FC1)
                val combinedData = FloatArray(resW * resH)
                val scalePenalty = abs(scale - 1.0f) * 0.025f

                for (ry in 0 until resH) {
                    val cy = ry + sh / 2
                    val rowOff = ry * resW
                    for (rx in 0 until resW) {
                        val zncc = corrData[rowOff + rx]
                        if (zncc <= 0.05f) continue
                        val cx = rx + sw / 2
                        val gate = evaluateRegionalGate(
                            stack = skewFeatures,
                            model = refFeatures.colorModel,
                            cx = cx,
                            cy = cy,
                            sw = sw,
                            sh = sh
                        )
                        if (gate <= 0.08f) continue
                        val raw = zncc * 0.54f + gate * 0.46f
                        val score = raw * (0.64f + 0.36f * gate) - skewPenalty - scalePenalty
                        if (score > 0f) {
                            combinedData[rowOff + rx] = score
                        }
                    }
                }
                combinedMap.put(0, 0, combinedData)

                // OpenCV morphological dilate to find 2D local maxima in C++
                val dilateW = max(5, (sw * 0.52f).roundToInt() or 1)
                val dilateH = max(5, (sh * 0.52f).roundToInt() or 1)
                val dilateKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(dilateW.toDouble(), dilateH.toDouble())
                )
                val dilatedMap = Mat()
                Imgproc.dilate(combinedMap, dilatedMap, dilateKernel)
                val dilatedData = FloatArray(resW * resH)
                dilatedMap.get(0, 0, dilatedData)

                // Map local maxima centers from the derotated frame back to original image coordinates.
                // Since OpenCV getRotationMatrix2D(..., +totalAngle, 1.0) rotates counter-clockwise by totalAngle,
                // the inverse mapping back to original image coordinates rotates clockwise by +totalAngle.
                val rad = Math.toRadians(totalAngle.toDouble())
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()

                for (ry in 0 until resH) {
                    val rowOff = ry * resW
                    for (rx in 0 until resW) {
                        val s = combinedData[rowOff + rx]
                        if (s >= MIN_KEEP_SCORE && s >= dilatedData[rowOff + rx] - 1e-6f) {
                            val localCx = rx + sw * 0.5f
                            val localCy = ry + sh * 0.5f
                            val dx = localCx - refCx
                            val dy = localCy - refCy
                            val origCx = if (abs(totalAngle) > 0.5f) {
                                refCx + dx * cosA - dy * sinA
                            } else {
                                localCx
                            }
                            val origCy = if (abs(totalAngle) > 0.5f) {
                                refCy + dx * sinA + dy * cosA
                            } else {
                                localCy
                            }
                            if (origCx in 0f..w.toFloat() && origCy in 0f..h.toFloat()) {
                                allCandidatePeaks.add(
                                    CandidatePeak(
                                        cx = origCx / w,
                                        cy = origCy / h,
                                        angle = ref.angleDegrees,
                                        scale = scale,
                                        score = s
                                    )
                                )
                            }
                        }
                    }
                }

                scaledTempl.release()
                corrMat.release()
                combinedMap.release()
                dilateKernel.release()
                dilatedMap.release()
            }

            if (skewFeatures !== refFeatures) {
                skewFeatures.release()
                skewBgr.release()
            }
        }

        templMat.release()
        refFeatures.release()
        baseWorkBgr.release()
        bgrOrig.release()

        // Exact rotated-box NMS: accept in descending score order, enforcing <= 10% overlap
        // and minimum center-to-center spacing along the part's local axes.
        val baseHalfW = ref.width * 0.5f * 0.90f
        val baseHalfH = ref.height * 0.5f * 0.88f
        val tightenedRef = NormalizedRect(
            left = (ref.centerX - baseHalfW).coerceIn(0f, 1f),
            top = (ref.centerY - baseHalfH).coerceIn(0f, 1f),
            right = (ref.centerX + baseHalfW).coerceIn(0f, 1f),
            bottom = (ref.centerY + baseHalfH).coerceIn(0f, 1f),
            angleDegrees = ref.angleDegrees
        )

        val referenceBox = PartDetectionBox(
            id = "part_ref_${refCx.roundToInt()}_${refCy.roundToInt()}",
            rect = tightenedRef,
            confidence = 1.0f,
            isSelected = true,
            isManual = false
        )
        val accepted = arrayListOf(referenceBox)
        val acceptedRects = arrayListOf(tightenedRef)

        val refRad = Math.toRadians(refAngle.toDouble())
        val refCos = cos(refRad).toFloat()
        val refSin = sin(refRad).toFloat()

        allCandidatePeaks
            .sortedByDescending { it.score }
            .forEach { peak ->
                val ncx = peak.cx
                val ncy = peak.cy

                // Two distinct parts of size (ref.width, ref.height) must be separated by at least
                // 76% of width horizontally OR 76% of height vertically in the part's coordinate frame.
                val tooCloseToExisting = acceptedRects.any { existing ->
                    val dx = ncx - existing.centerX
                    val dy = ncy - existing.centerY
                    val localDx = abs(dx * refCos + dy * refSin) / ref.width.coerceAtLeast(1e-4f)
                    val localDy = abs(-dx * refSin + dy * refCos) / ref.height.coerceAtLeast(1e-4f)
                    max(localDx, localDy) < 0.76f
                }
                if (tooCloseToExisting) return@forEach

                val rect = NormalizedRect(
                    left = (ncx - baseHalfW).coerceIn(0f, 1f),
                    top = (ncy - baseHalfH).coerceIn(0f, 1f),
                    right = (ncx + baseHalfW).coerceIn(0f, 1f),
                    bottom = (ncy + baseHalfH).coerceIn(0f, 1f),
                    angleDegrees = ref.angleDegrees
                )

                if (!BoxGeometry.overlapsAny(rect, acceptedRects, aspect)) {
                    acceptedRects.add(rect)
                    val px = (ncx * w).roundToInt()
                    val py = (ncy * h).roundToInt()
                    accepted.add(
                        PartDetectionBox(
                            id = "part_${px}_${py}_${ref.angleDegrees.roundToInt()}",
                            rect = rect,
                            confidence = peak.score.coerceIn(0f, MAX_AUTO_CONFIDENCE),
                            isSelected = true,
                            isManual = false
                        )
                    )
                }
            }

        // Calibrate non-reference peak confidences so the UI sensitivity slider (15%..92%)
        // smoothly filters primary detections when dragged below 60% and reveals secondary
        // candidates when dragged above 60%.
        val primaryIndices = accepted.indices.filter { idx ->
            idx > 0 && accepted[idx].confidence >= PRIMARY_MATCH_SCORE
        }
        val primaryCount = primaryIndices.size
        primaryIndices.forEachIndexed { rankIdx, boxIdx ->
            val rawScore = accepted[boxIdx].confidence
            val rankFrac = if (primaryCount <= 1) {
                1f
            } else {
                1f - rankIdx.toFloat() / (primaryCount - 1).toFloat()
            }
            val spreadConf = 0.52f + 0.40f * rankFrac
            val calibrated = (0.25f * rawScore + 0.75f * spreadConf).coerceIn(0.50f, MAX_AUTO_CONFIDENCE)
            accepted[boxIdx] = accepted[boxIdx].copy(confidence = calibrated)
        }

        val secondaryIndices = accepted.indices.filter { idx ->
            idx > 0 && accepted[idx].confidence < PRIMARY_MATCH_SCORE
        }
        val secondaryCount = secondaryIndices.size
        secondaryIndices.forEachIndexed { rankIdx, boxIdx ->
            val rawScore = accepted[boxIdx].confidence
            val rankFrac = if (secondaryCount <= 1) {
                1f
            } else {
                1f - rankIdx.toFloat() / (secondaryCount - 1).toFloat()
            }
            val spreadConf = 0.16f + 0.28f * rankFrac
            val calibrated = (0.40f * rawScore + 0.60f * spreadConf).coerceIn(0.15f, 0.45f)
            accepted[boxIdx] = accepted[boxIdx].copy(confidence = calibrated)
        }

        val rowHeight = max(ref.height * 0.75f, 0.04f)
        val sortedPeaks = accepted.sortedWith(
            compareBy<PartDetectionBox> { (it.rect.centerY / rowHeight).roundToInt() }
                .thenBy { it.rect.centerX }
        )

        val active = applySensitivityAndSelections(
            allMatchedPeaks = sortedPeaks,
            sensitivity = sensitivity,
            deselectedIds = emptySet(),
            manualBoxes = emptyList(),
            imageAspect = aspect,
            buffer = buffer
        )

        return PlateAnalysisResult(
            referenceRect = ref,
            candidateRects = detectCandidateParts(buffer),
            allMatchedPeaks = sortedPeaks,
            activeDetections = active
        )
    }

    /**
     * Maps the sensitivity slider (0.10..0.95) to the minimum match confidence.
     * Higher sensitivity -> lower confidence threshold -> more elements detected.
     * - s = 0.15 (15%) -> minConfidence = 0.93 (strictest: only reference + top match)
     * - s = 0.60 (60%) -> minConfidence = 0.48 (default: all primary matches)
     * - s = 0.92 (92%) -> minConfidence = 0.16 (permissive: includes secondary candidates)
     */
    fun confidenceThresholdFor(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(0.10f, 0.95f)
        return (1.08f - 1.00f * s).coerceIn(0.14f, 0.96f)
    }

    /**
     * Filters [allMatchedPeaks] according to [sensitivity] while preserving user-deselected boxes and
     * manually added boxes. Automatic boxes that overlap any manual box by more than 10% are dropped.
     */
    fun applySensitivityAndSelections(
        allMatchedPeaks: List<PartDetectionBox>,
        sensitivity: Float,
        deselectedIds: Set<String>,
        manualBoxes: List<PartDetectionBox>,
        imageAspect: Float = 1f,
        buffer: PixelBuffer? = null
    ): List<PartDetectionBox> {
        val minConfidence = confidenceThresholdFor(sensitivity)
        val manualRects = manualBoxes.map { it.rect }

        val autoBoxes = allMatchedPeaks
            .filter { it.confidence >= minConfidence || it.confidence >= 0.99f }
            .filter { manualRects.isEmpty() || !BoxGeometry.overlapsAny(it.rect, manualRects, imageAspect) }
            .map { box ->
                box.copy(isSelected = !deselectedIds.contains(box.id))
            }

        val updatedManual = manualBoxes.map { box ->
            box.copy(isSelected = !deselectedIds.contains(box.id))
        }

        return autoBoxes + updatedManual
    }

    // ---------------------------------------------------------------------------------------------
    // OpenCV Learned Foreground Probability & Regional Verification Internals
    // ---------------------------------------------------------------------------------------------

    /**
     * Color-agnostic model learned from the user's reference box in CIE LAB space.
     * Defines the vector from build-plate background `(bgL, bgA, bgB)` to part foreground `(fgL, fgA, fgB)`
     * so every pixel in the image can be mapped to a foreground probability `P_fg in [0, 1]` that is
     * immune to build-plate glare, stickers, and printer frame clutter.
     */
    private class LearnedColorModel(
        val bgL: Float,
        val bgA: Float,
        val bgB: Float,
        val fgL: Float,
        val fgA: Float,
        val fgB: Float,
        val isChromatic: Boolean,
        val cA: Float,
        val cB: Float,
        val invChromaNormSq: Float,
        val dLRef: Float,
        val refMeanPfg: Float,
        val refQuadPfg: FloatArray // TL, TR, BL, BR mean foreground probability
    )

    private class FeatureStack(
        val w: Int,
        val h: Int,
        val mergedFeatures: Mat,
        val pFgArr: FloatArray,
        val pFgBlurArr: FloatArray,
        val colorModel: LearnedColorModel
    ) {
        fun release() {
            mergedFeatures.release()
        }
    }

    private fun buildOpenCvFeatureStack(
        bgrMat: Mat,
        tplRect: Rect,
        existingModel: LearnedColorModel?
    ): FeatureStack {
        val w = bgrMat.cols()
        val h = bgrMat.rows()

        val labMat = Mat()
        Imgproc.cvtColor(bgrMat, labMat, Imgproc.COLOR_BGR2Lab)
        val labChannels = ArrayList<Mat>(3)
        Core.split(labMat, labChannels)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val lClahe = Mat()
        clahe.apply(labChannels[0], lClahe)

        val lFloat = Mat()
        val aFloat = Mat()
        val bFloat = Mat()
        // Convert OpenCV 8-bit LAB L (0..255) to true CIE L* (0..100) so L*, a*, b* share uniform perceptual units
        lClahe.convertTo(lFloat, CvType.CV_32FC1, 100.0 / 255.0, 0.0)
        labChannels[1].convertTo(aFloat, CvType.CV_32FC1, 1.0, -128.0)
        labChannels[2].convertTo(bFloat, CvType.CV_32FC1, 1.0, -128.0)

        val edgeMat = computeOpenCvSobelMagnitude(lFloat, aFloat, bFloat)

        val lArr = FloatArray(w * h)
        val aArr = FloatArray(w * h)
        val bArr = FloatArray(w * h)
        val eArr = FloatArray(w * h)
        lFloat.get(0, 0, lArr)
        aFloat.get(0, 0, aArr)
        bFloat.get(0, 0, bArr)
        edgeMat.get(0, 0, eArr)

        val model = existingModel ?: learnColorModelFromTemplate(lArr, aArr, bArr, w, h, tplRect)

        // Compute per-pixel foreground probability P_fg in [0, 1] in true CIE L*a*b* space.
        val pFgArr = FloatArray(w * h)
        val pFgEdgeArr = FloatArray(w * h)
        val bgL = model.bgL
        val bgA = model.bgA
        val bgB = model.bgB

        if (model.isChromatic) {
            val cA = model.cA
            val cB = model.cB
            val invCNormSq = model.invChromaNormSq
            for (i in 0 until w * h) {
                val da = aArr[i] - bgA
                val db = bArr[i] - bgB
                val projC = (da * cA + db * cB) * invCNormSq
                if (projC <= 0.18f) {
                    pFgArr[i] = 0f
                    pFgEdgeArr[i] = 0f
                } else {
                    val orthoC = abs(da * cB - db * cA) * invCNormSq
                    val lDiff = abs(lArr[i] - model.fgL)
                    val lGate = if (lDiff <= 42f) 1f else exp(-((lDiff - 42f) * (lDiff - 42f)) / 320f)
                    val ramp = ((projC - 0.18f) / 0.36f).coerceIn(0f, 1f)
                    val p = (ramp * exp(-2.8f * orthoC * orthoC) * lGate).coerceIn(0f, 1f)
                    pFgArr[i] = p
                    pFgEdgeArr[i] = p * min(1f, eArr[i] / 32f)
                }
            }
        } else {
            val dLRef = model.dLRef
            val invDL = 1f / (if (abs(dLRef) < 6f) 6f else dLRef)
            for (i in 0 until w * h) {
                val projL = (lArr[i] - bgL) * invDL
                if (projL <= 0.22f) {
                    pFgArr[i] = 0f
                    pFgEdgeArr[i] = 0f
                } else {
                    val da = aArr[i] - bgA
                    val db = bArr[i] - bgB
                    val chromaSq = da * da + db * db
                    val ramp = ((projL - 0.22f) / 0.45f).coerceIn(0f, 1f)
                    val p = (ramp * exp(-chromaSq / 240f)).coerceIn(0f, 1f)
                    pFgArr[i] = p
                    pFgEdgeArr[i] = p * min(1f, eArr[i] / 32f)
                }
            }
        }

        val pFgMat = Mat(h, w, CvType.CV_32FC1)
        val pFgEdgeMat = Mat(h, w, CvType.CV_32FC1)
        val structEdgeMat = Mat()
        pFgMat.put(0, 0, pFgArr)
        pFgEdgeMat.put(0, 0, pFgEdgeArr)
        edgeMat.convertTo(structEdgeMat, CvType.CV_32FC1, 0.22 / 36.0, 0.0)

        // Envelope Gaussian smoothing using OpenCV so thin 2px wireframe/sprue lines match across
        // perspective warps while preserving left/right and top/bottom part geometry.
        val minDim = min(tplRect.width, tplRect.height)
        val kSize = (max(5, (minDim * 0.36f).roundToInt()) or 1).toDouble()
        val sigma = max(1.5, minDim * 0.11)
        val blurSize = Size(kSize, kSize)

        val pFgBlur = Mat()
        val pFgEdgeBlur = Mat()
        val structEdgeBlur = Mat()
        Imgproc.GaussianBlur(pFgMat, pFgBlur, blurSize, sigma)
        Imgproc.GaussianBlur(pFgEdgeMat, pFgEdgeBlur, blurSize, sigma)
        Imgproc.GaussianBlur(structEdgeMat, structEdgeBlur, blurSize, sigma)

        val pFgBlurArr = FloatArray(w * h)
        pFgBlur.get(0, 0, pFgBlurArr)

        val finalModel = if (existingModel == null) {
            var sumP = 0f
            val qSum = FloatArray(4)
            val qCnt = IntArray(4)
            val midX = tplRect.x + tplRect.width * 0.5f
            val midY = tplRect.y + tplRect.height * 0.5f
            for (y in tplRect.y until (tplRect.y + tplRect.height)) {
                val row = y * w
                val qy = if (y < midY) 0 else 2
                for (x in tplRect.x until (tplRect.x + tplRect.width)) {
                    val idx = row + x
                    val v = pFgBlurArr[idx]
                    sumP += v
                    val q = qy + if (x < midX) 0 else 1
                    qSum[q] += v
                    qCnt[q]++
                }
            }
            val total = (tplRect.width * tplRect.height).coerceAtLeast(1)
            val meanP = (sumP / total).coerceAtLeast(0.04f)
            val quadP = FloatArray(4) { q -> qSum[q] / qCnt[q].coerceAtLeast(1) }
            LearnedColorModel(
                bgL = model.bgL,
                bgA = model.bgA,
                bgB = model.bgB,
                fgL = model.fgL,
                fgA = model.fgA,
                fgB = model.fgB,
                isChromatic = model.isChromatic,
                cA = model.cA,
                cB = model.cB,
                invChromaNormSq = model.invChromaNormSq,
                dLRef = model.dLRef,
                refMeanPfg = meanP,
                refQuadPfg = quadP
            )
        } else {
            model
        }

        // Merge into 3-channel CV_32FC3 Mat for OpenCV Imgproc.matchTemplate(TM_CCOEFF_NORMED)
        val merged = Mat()
        Core.merge(listOf(pFgBlur, pFgEdgeBlur, structEdgeBlur), merged)

        labMat.release()
        labChannels.forEach { it.release() }
        lClahe.release()
        lFloat.release()
        aFloat.release()
        bFloat.release()
        edgeMat.release()
        pFgMat.release()
        pFgEdgeMat.release()
        structEdgeMat.release()
        pFgBlur.release()
        pFgEdgeBlur.release()
        structEdgeBlur.release()

        return FeatureStack(
            w = w,
            h = h,
            mergedFeatures = merged,
            pFgArr = pFgArr,
            pFgBlurArr = pFgBlurArr,
            colorModel = finalModel
        )
    }

    private fun learnColorModelFromTemplate(
        lArr: FloatArray,
        aArr: FloatArray,
        bArr: FloatArray,
        w: Int,
        h: Int,
        tplRect: Rect
    ): LearnedColorModel {
        // 1. Estimate build-plate background LAB around tplRect using low-chroma samples
        val cx = tplRect.x + tplRect.width / 2
        val cy = tplRect.y + tplRect.height / 2
        val rxOuter = max(14, (tplRect.width * 1.8f).roundToInt())
        val ryOuter = max(14, (tplRect.height * 1.8f).roundToInt())
        val rxInner = (tplRect.width * 0.52f).roundToInt()
        val ryInner = (tplRect.height * 0.52f).roundToInt()

        val sL = ArrayList<Float>(512)
        val sA = ArrayList<Float>(512)
        val sB = ArrayList<Float>(512)

        for (y in (cy - ryOuter).coerceAtLeast(0)..(cy + ryOuter).coerceAtMost(h - 1) step 2) {
            val row = y * w
            for (x in (cx - rxOuter).coerceAtLeast(0)..(cx + rxOuter).coerceAtMost(w - 1) step 2) {
                if (abs(x - cx) <= rxInner && abs(y - cy) <= ryInner) continue
                val idx = row + x
                sL.add(lArr[idx])
                sA.add(aArr[idx])
                sB.add(bArr[idx])
            }
        }
        val bgL: Float
        val bgA: Float
        val bgB: Float
        if (sL.size >= 16) {
            // Sort neighborhood samples by chroma magnitude so adjacent colored parts in a grid are ignored
            val order = sL.indices.sortedBy { i -> hypot(sA[i].toDouble(), sB[i].toDouble()) }
            val take = max(8, order.size / 2)
            var sumL = 0f
            var sumA = 0f
            var sumB = 0f
            for (k in 0 until take) {
                val idx = order[k]
                sumL += sL[idx]
                sumA += sA[idx]
                sumB += sB[idx]
            }
            bgL = sumL / take
            bgA = sumA / take
            bgB = sumB / take
        } else {
            bgL = 20f
            bgA = 0f
            bgB = 0f
        }

        // 2. Separate foreground part pixels from background hole pixels inside tplRect via Otsu in CIE L*a*b*
        val nTpl = tplRect.width * tplRect.height
        val chromaDists = FloatArray(nTpl)
        val totalDists = FloatArray(nTpl)
        var maxChromaD = 0f
        var maxTotalD = 0f
        var k = 0
        for (y in tplRect.y until (tplRect.y + tplRect.height)) {
            val row = y * w
            for (x in tplRect.x until (tplRect.x + tplRect.width)) {
                val idx = row + x
                val dl = lArr[idx] - bgL
                val da = aArr[idx] - bgA
                val db = bArr[idx] - bgB
                val dc = hypot(da.toDouble(), db.toDouble()).toFloat()
                val dt = sqrt(dl * dl + 2.2f * dc * dc)
                chromaDists[k] = dc
                totalDists[k] = dt
                if (dc > maxChromaD) maxChromaD = dc
                if (dt > maxTotalD) maxTotalD = dt
                k++
            }
        }

        val useChromaSplit = maxChromaD >= 12f
        val activeDists = if (useChromaSplit) chromaDists else totalDists
        val activeMax = if (useChromaSplit) maxChromaD else maxTotalD
        val thresh = if (activeMax >= 6f) otsuThreshold(activeDists, activeMax).coerceAtLeast(4f) else 4f

        var fgCount = 0
        var fgL = 0f
        var fgA = 0f
        var fgB = 0f
        k = 0
        for (y in tplRect.y until (tplRect.y + tplRect.height)) {
            val row = y * w
            for (x in tplRect.x until (tplRect.x + tplRect.width)) {
                val d = activeDists[k++]
                if (d >= thresh) {
                    val idx = row + x
                    fgCount++
                    fgL += lArr[idx]
                    fgA += aArr[idx]
                    fgB += bArr[idx]
                }
            }
        }
        if (fgCount == 0) {
            fgL = bgL + 30f
            fgA = bgA
            fgB = bgB
        } else {
            fgL /= fgCount
            fgA /= fgCount
            fgB /= fgCount
        }

        val cA = fgA - bgA
        val cB = fgB - bgB
        val dC = hypot(cA.toDouble(), cB.toDouble()).toFloat()
        val dLRef = fgL - bgL
        val isChromatic = dC >= 10f

        return LearnedColorModel(
            bgL = bgL,
            bgA = bgA,
            bgB = bgB,
            fgL = fgL,
            fgA = fgA,
            fgB = fgB,
            isChromatic = isChromatic,
            cA = cA,
            cB = cB,
            invChromaNormSq = 1f / (dC * dC).coerceAtLeast(16f),
            dLRef = dLRef,
            refMeanPfg = 0.5f,
            refQuadPfg = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        )
    }

    /**
     * Verifies that the candidate window centered at ([cx], [cy]) of size ([sw], [sh]) has a
     * foreground fill density (`meanPfg`) and 4-quadrant distribution (`quadPfg`) matching the
     * reference part. Rejects windows hanging half-off the edge of a part or straddling two parts.
     */
    private fun evaluateRegionalGate(
        stack: FeatureStack,
        model: LearnedColorModel,
        cx: Int,
        cy: Int,
        sw: Int,
        sh: Int
    ): Float {
        val w = stack.w
        val h = stack.h
        val x0 = (cx - sw / 2).coerceIn(0, w - 1)
        val x1 = (x0 + sw - 1).coerceIn(x0, w - 1)
        val y0 = (cy - sh / 2).coerceIn(0, h - 1)
        val y1 = (y0 + sh - 1).coerceIn(y0, h - 1)

        val stepX = max(1, sw / 10)
        val stepY = max(1, sh / 10)
        val midX = (x0 + x1) * 0.5f
        val midY = (y0 + y1) * 0.5f

        var q0Sum = 0f
        var q1Sum = 0f
        var q2Sum = 0f
        var q3Sum = 0f
        var q0Cnt = 0
        var q1Cnt = 0
        var q2Cnt = 0
        var q3Cnt = 0
        var sumP = 0f
        var totalCnt = 0

        var y = y0
        while (y <= y1) {
            val row = y * w
            val topHalf = y < midY
            var x = x0
            while (x <= x1) {
                val v = stack.pFgBlurArr[row + x]
                sumP += v
                totalCnt++
                if (topHalf) {
                    if (x < midX) { q0Sum += v; q0Cnt++ } else { q1Sum += v; q1Cnt++ }
                } else {
                    if (x < midX) { q2Sum += v; q2Cnt++ } else { q3Sum += v; q3Cnt++ }
                }
                x += stepX
            }
            y += stepY
        }

        if (totalCnt == 0) return 0f
        val candMeanP = sumP / totalCnt
        val refMeanP = model.refMeanPfg
        val ratio = candMeanP / refMeanP.coerceAtLeast(0.03f)
        if (ratio < 0.36f) {
            return (0.10f + 0.16f * (ratio / 0.36f)).coerceIn(0.09f, 0.26f)
        }

        // Overall foreground fill density similarity (tolerant of illumination falloff across the build plate)
        val excess = when {
            ratio < 0.72f -> (0.72f - ratio) / 0.36f
            ratio > 1.45f -> (ratio - 1.45f) / 0.65f
            else -> 0f
        }
        val fillSim = exp(-excess * excess)

        // Illumination-invariant 4-quadrant spatial balance similarity (rejects windows hanging off the edge of a part)
        val cQ0 = q0Sum / q0Cnt.coerceAtLeast(1)
        val cQ1 = q1Sum / q1Cnt.coerceAtLeast(1)
        val cQ2 = q2Sum / q2Cnt.coerceAtLeast(1)
        val cQ3 = q3Sum / q3Cnt.coerceAtLeast(1)
        val candQuadSum = (cQ0 + cQ1 + cQ2 + cQ3).coerceAtLeast(0.08f)
        val rQ = model.refQuadPfg
        val refQuadSum = (rQ[0] + rQ[1] + rQ[2] + rQ[3]).coerceAtLeast(0.08f)
        val quadDiff =
            abs(cQ0 / candQuadSum - rQ[0] / refQuadSum) +
            abs(cQ1 / candQuadSum - rQ[1] / refQuadSum) +
            abs(cQ2 / candQuadSum - rQ[2] / refQuadSum) +
            abs(cQ3 / candQuadSum - rQ[3] / refQuadSum)
        val quadSim = exp(-quadDiff * quadDiff * 1.8f)

        return (fillSim * quadSim).coerceIn(0f, 1f)
    }

    private fun computeOpenCvSobelMagnitude(lFloat: Mat, aFloat: Mat, bFloat: Mat): Mat {
        fun channelGradMag(ch: Mat): Mat {
            val gx = Mat()
            val gy = Mat()
            val mag = Mat()
            Imgproc.Sobel(ch, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(ch, gy, CvType.CV_32F, 0, 1, 3)
            Core.magnitude(gx, gy, mag)
            gx.release()
            gy.release()
            return mag
        }

        val magL = channelGradMag(lFloat)
        val magA = channelGradMag(aFloat)
        val magB = channelGradMag(bFloat)

        val combined = Mat()
        Core.addWeighted(magL, 0.22, magA, 0.25, 0.0, combined)
        Core.addWeighted(combined, 1.0, magB, 0.25, 0.0, combined)

        magL.release()
        magA.release()
        magB.release()
        return combined
    }

    private fun pixelBufferToBgrMat(buffer: PixelBuffer): Mat {
        val w = buffer.width
        val h = buffer.height
        val mat = Mat(h, w, CvType.CV_8UC3)
        val bytes = ByteArray(w * h * 3)
        val pixels = buffer.pixels
        var bi = 0
        for (i in 0 until w * h) {
            val argb = pixels[i]
            bytes[bi++] = (argb and 0xFF).toByte()         // B
            bytes[bi++] = ((argb shr 8) and 0xFF).toByte()  // G
            bytes[bi++] = ((argb shr 16) and 0xFF).toByte() // R
        }
        mat.put(0, 0, bytes)
        return mat
    }

    private fun estimateGlobalLabBackground(
        lMat: Mat,
        aMat: Mat,
        bMat: Mat,
        w: Int,
        h: Int
    ): Triple<Double, Double, Double> {
        val lBytes = ByteArray(w * h)
        val aBytes = ByteArray(w * h)
        val bBytes = ByteArray(w * h)
        lMat.get(0, 0, lBytes)
        aMat.get(0, 0, aBytes)
        bMat.get(0, 0, bBytes)

        val sL = ArrayList<Int>(512)
        val sA = ArrayList<Int>(512)
        val sB = ArrayList<Int>(512)
        val step = max(2, min(w, h) / 28)
        for (y in (h / 10) until (h * 9 / 10) step step) {
            val row = y * w
            for (x in (w / 10) until (w * 9 / 10) step step) {
                val idx = row + x
                sL.add(lBytes[idx].toInt() and 0xFF)
                sA.add(aBytes[idx].toInt() and 0xFF)
                sB.add(bBytes[idx].toInt() and 0xFF)
            }
        }
        if (sL.isEmpty()) return Triple(40.0, 128.0, 128.0)
        sL.sort()
        sA.sort()
        sB.sort()
        return Triple(
            sL[sL.size / 2].toDouble(),
            sA[sA.size / 2].toDouble(),
            sB[sB.size / 2].toDouble()
        )
    }

    private fun otsuThreshold(values: FloatArray, maxValue: Float): Float {
        val bins = 64
        val hist = IntArray(bins)
        for (v in values) {
            val b = ((v / maxValue) * bins).toInt().coerceIn(0, bins - 1)
            hist[b]++
        }
        val total = values.size
        var sumAll = 0.0
        for (b in 0 until bins) sumAll += b.toDouble() * hist[b]

        var weightB = 0
        var sumB = 0.0
        var bestBetween = -1.0
        var bestT = 0
        for (t in 0 until bins) {
            weightB += hist[t]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break
            sumB += t.toDouble() * hist[t]
            val meanB = sumB / weightB
            val meanF = (sumAll - sumB) / weightF
            val between = weightB.toDouble() * weightF.toDouble() * (meanB - meanF) * (meanB - meanF)
            if (between > bestBetween) {
                bestBetween = between
                bestT = t
            }
        }
        return (bestT + 1) * maxValue / bins
    }

    private fun normalizeAngle(angle: Float): Float =
        ((angle + 180f) % 360f + 360f) % 360f - 180f

    private fun mergeOverlappingRects(
        rects: List<NormalizedRect>,
        iouThreshold: Float
    ): List<NormalizedRect> {
        if (rects.size <= 1) return rects
        val sorted = rects.sortedByDescending { it.area }.toMutableList()
        val result = mutableListOf<NormalizedRect>()
        while (sorted.isNotEmpty()) {
            var current = sorted.removeAt(0)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (current.iou(other) >= iouThreshold ||
                    (current.contains(other.centerX, other.centerY))
                ) {
                    current = NormalizedRect(
                        left = min(current.left, other.left),
                        top = min(current.top, other.top),
                        right = max(current.right, other.right),
                        bottom = max(current.bottom, other.bottom)
                    )
                    iterator.remove()
                }
            }
            result.add(current)
        }
        return result
    }
}
