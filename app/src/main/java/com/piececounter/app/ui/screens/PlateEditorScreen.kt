package com.piececounter.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piececounter.app.domain.vision.NormalizedRect
import com.piececounter.app.domain.vision.PartDetectionBox
import com.piececounter.app.ui.EditorUiState
import com.piececounter.app.ui.PlateEditorMode
import com.piececounter.app.ui.theme.AmberReference
import com.piececounter.app.ui.theme.AmberReferenceFill
import com.piececounter.app.ui.theme.CyanAccent
import com.piececounter.app.ui.theme.EmeraldSelected
import com.piececounter.app.ui.theme.EmeraldSelectedFill
import com.piececounter.app.ui.theme.RoseDeselected
import com.piececounter.app.ui.theme.RoseDeselectedFill
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class RefDragHandle {
    NONE,
    MOVE,
    ROTATE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlateEditorScreen(
    state: EditorUiState,
    suggestedNames: List<String>,
    onSelectPlateIndex: (Int) -> Unit,
    onSetEditorMode: (PlateEditorMode) -> Unit,
    onUpdateReferenceRect: (NormalizedRect) -> Unit,
    onRunMatchFromReference: () -> Unit,
    onPhotoTap: (Float, Float) -> Unit,
    onLongPressAddPart: (Float, Float) -> Unit,
    onToggleManualAddMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onOpenNamingDialog: () -> Unit,
    onDismissNamingDialog: () -> Unit,
    onConfirmElementName: (String) -> Unit,
    onAddAnotherPlatePhoto: () -> Unit,
    onOpenSummary: () -> Unit,
    onBack: () -> Unit
) {
    val session = state.activeSession
    val plate = state.currentPlate
    val totalPlates = session?.plates?.size ?: 1
    val plateNumber = state.currentPlateIndex + 1

    var zoomScale by remember(plate?.plateId) { mutableFloatStateOf(1f) }
    var panOffset by remember(plate?.plateId) { mutableStateOf(Offset.Zero) }

    fun clampPan(offset: Offset, scale: Float, viewW: Float, viewH: Float): Offset {
        if (scale <= 1.01f) return Offset.Zero
        val maxPanX = (viewW * (scale - 1f)) * 0.55f
        val maxPanY = (viewH * (scale - 1f)) * 0.55f
        return Offset(
            x = offset.x.coerceIn(-maxPanX, maxPanX),
            y = offset.y.coerceIn(-maxPanY, maxPanY)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Plate $plateNumber of $totalPlates",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (plate?.elementName?.isNotBlank() == true) {
                                plate.elementName
                            } else if (state.editorMode == PlateEditorMode.DEFINE_SINGLE_PART) {
                                "Step 1: Frame a single part"
                            } else {
                                "Step 2: Tap boxes to toggle"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (plate != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(EmeraldSelected)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${plate.selectedCount} / ${plate.totalDetectedCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (totalPlates > 1) {
                        TextButton(
                            onClick = onOpenSummary,
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Text(
                                text = "List",
                                maxLines = 1
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Multi-Plate Quick Switcher Strip + Mode Switcher
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (totalPlates > 1 && session != null) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            itemsIndexed(session.plates, key = { _, p -> p.plateId }) { idx, p ->
                                val isCurrent = idx == state.currentPlateIndex
                                FilterChip(
                                    selected = isCurrent,
                                    onClick = { onSelectPlateIndex(idx) },
                                    label = {
                                        val labelText = if (p.elementName.isNotBlank()) {
                                            "P${idx + 1}: ${p.elementName} (${p.selectedCount})"
                                        } else {
                                            "Plate ${idx + 1} (${p.selectedCount})"
                                        }
                                        Text(
                                            text = labelText,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = if (p.isNamed) {
                                        {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    } else null
                                )
                            }
                        }
                    }

                    // Mode Toggle Bar: 1. Single Part vs 2. Select Parts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.editorMode == PlateEditorMode.DEFINE_SINGLE_PART,
                            onClick = { onSetEditorMode(PlateEditorMode.DEFINE_SINGLE_PART) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = "1. Single Part",
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CropFree,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = state.editorMode == PlateEditorMode.SELECT_AND_COUNT,
                            onClick = { onSetEditorMode(PlateEditorMode.SELECT_AND_COUNT) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = "2. Select (${plate?.selectedCount ?: 0})",
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Interactive Zoomable & Pannable Plate Photo Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1B1F26))
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = state.activeBitmap
                if (state.isAnalyzing || bitmap == null || plate == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = EmeraldSelected)
                        Text(
                            text = "Analyzing build plate...",
                            color = Color(0xFFE2E8F0),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    InteractivePlateCanvas(
                        bitmap = bitmap,
                        editorMode = state.editorMode,
                        referenceRect = plate.referenceRect,
                        candidateRects = state.candidateRects,
                        detections = plate.detections,
                        zoomScale = zoomScale,
                        panOffset = panOffset,
                        onZoomPanChange = { newScale, newPan, viewW, viewH ->
                            val clampedScale = newScale.coerceIn(1f, 6f)
                            zoomScale = clampedScale
                            panOffset = clampPan(newPan, clampedScale, viewW, viewH)
                        },
                        onUpdateReferenceRect = onUpdateReferenceRect,
                        onPhotoTap = onPhotoTap,
                        onLongPress = onLongPressAddPart
                    )

                    // Helper instruction pill at top of canvas
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xB81B2028)
                    ) {
                        Text(
                            text = if (state.editorMode == PlateEditorMode.DEFINE_SINGLE_PART) {
                                "Drag corners to resize • Top knob to rotate • Pinch to zoom"
                            } else if (state.isManualAddMode) {
                                "Add mode: Tap on plate to place a box"
                            } else {
                                "Pinch to zoom • Tap box to deselect/select • Hold to add"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFF1F5F9),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Floating Zoom Controls
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xC41B2028)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val next = (zoomScale / 1.4f).coerceIn(1f, 6f)
                                    zoomScale = next
                                    if (next <= 1.02f) panOffset = Offset.Zero
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.ZoomOut,
                                    contentDescription = "Zoom out",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "${(zoomScale * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .clickable {
                                        zoomScale = 1f
                                        panOffset = Offset.Zero
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                            IconButton(
                                onClick = {
                                    zoomScale = (zoomScale * 1.4f).coerceIn(1f, 6f)
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.ZoomIn,
                                    contentDescription = "Zoom in",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Controls Panel
            if (plate != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    if (state.editorMode == PlateEditorMode.DEFINE_SINGLE_PART) {
                        DefineSinglePartBottomPanel(
                            candidateCount = state.candidateRects.size,
                            angleDegrees = plate.referenceRect.angleDegrees,
                            onAngleChange = { newAngle ->
                                onUpdateReferenceRect(plate.referenceRect.copy(angleDegrees = newAngle))
                            },
                            onConfirmSinglePart = onRunMatchFromReference
                        )
                    } else {
                        SelectAndCountBottomPanel(
                            selectedCount = plate.selectedCount,
                            totalDetectedCount = plate.totalDetectedCount,
                            sensitivity = plate.sensitivity,
                            isManualAddMode = state.isManualAddMode,
                            onSensitivityChange = onSensitivityChange,
                            onToggleManualAddMode = onToggleManualAddMode,
                            onSelectAll = onSelectAll,
                            onDeselectAll = onDeselectAll,
                            onRedefineSinglePart = {
                                onSetEditorMode(PlateEditorMode.DEFINE_SINGLE_PART)
                            },
                            onConfirmAndName = onOpenNamingDialog,
                            onAddAnotherPlate = onAddAnotherPlatePhoto
                        )
                    }
                }
            }
        }

        // Element Naming Dialog with Previous Element Suggestion
        if (state.showNamingDialog && plate != null) {
            ElementNamingDialog(
                plateNumber = plateNumber,
                totalPlates = totalPlates,
                selectedPieceCount = plate.selectedCount,
                currentElementName = plate.elementName,
                lastNamedElement = state.lastNamedElement,
                suggestedNames = suggestedNames,
                hasNextPlate = state.hasNextPlate,
                onDismiss = onDismissNamingDialog,
                onConfirmName = onConfirmElementName,
                onConfirmAndAddAnotherPlate = { chosenName ->
                    onConfirmElementName(chosenName)
                    onAddAnotherPlatePhoto()
                }
            )
        }
    }
}

@Composable
private fun InteractivePlateCanvas(
    bitmap: Bitmap,
    editorMode: PlateEditorMode,
    referenceRect: NormalizedRect,
    candidateRects: List<NormalizedRect>,
    detections: List<PartDetectionBox>,
    zoomScale: Float,
    panOffset: Offset,
    onZoomPanChange: (Float, Offset, Float, Float) -> Unit,
    onUpdateReferenceRect: (NormalizedRect) -> Unit,
    onPhotoTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val textMeasurer = rememberTextMeasurer()

    val currentEditorMode by rememberUpdatedState(editorMode)
    val currentReferenceRect by rememberUpdatedState(referenceRect)
    val currentZoomScale by rememberUpdatedState(zoomScale)
    val currentPanOffset by rememberUpdatedState(panOffset)
    val currentOnZoomPanChange by rememberUpdatedState(onZoomPanChange)
    val currentOnUpdateReferenceRect by rememberUpdatedState(onUpdateReferenceRect)
    val currentOnPhotoTap by rememberUpdatedState(onPhotoTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    fun computeImageFrame(cSize: Size): Pair<Offset, Size> {
        if (cSize.width <= 0f || cSize.height <= 0f) return Offset.Zero to Size.Zero
        val bmpW = bitmap.width.toFloat().coerceAtLeast(1f)
        val bmpH = bitmap.height.toFloat().coerceAtLeast(1f)
        val fitScale = min(cSize.width / bmpW, cSize.height / bmpH)
        val baseW = bmpW * fitScale
        val baseH = bmpH * fitScale
        val baseLeft = (cSize.width - baseW) * 0.5f
        val baseTop = (cSize.height - baseH) * 0.5f
        return Offset(baseLeft, baseTop) to Size(baseW, baseH)
    }

    fun normalizedToScreen(
        normX: Float,
        normY: Float,
        cSize: Size,
        scale: Float,
        pan: Offset
    ): Offset {
        val (baseOrigin, baseSize) = computeImageFrame(cSize)
        val cx = cSize.width * 0.5f
        val cy = cSize.height * 0.5f
        val bx = baseOrigin.x + normX * baseSize.width
        val by = baseOrigin.y + normY * baseSize.height
        return Offset(
            x = cx + (bx - cx) * scale + pan.x,
            y = cy + (by - cy) * scale + pan.y
        )
    }

    fun screenToNormalized(
        screenPt: Offset,
        cSize: Size,
        scale: Float,
        pan: Offset
    ): Offset {
        val (baseOrigin, baseSize) = computeImageFrame(cSize)
        if (baseSize.width <= 0f || baseSize.height <= 0f) return Offset(0.5f, 0.5f)
        val cx = cSize.width * 0.5f
        val cy = cSize.height * 0.5f
        val bx = (screenPt.x - cx - pan.x) / scale + cx
        val by = (screenPt.y - cy - pan.y) / scale + cy
        return Offset(
            x = (bx - baseOrigin.x) / baseSize.width,
            y = (by - baseOrigin.y) / baseSize.height
        )
    }

    fun clampLocalPan(offset: Offset, scale: Float, viewW: Float, viewH: Float): Offset {
        if (scale <= 1.01f) return Offset.Zero
        val maxPanX = (viewW * (scale - 1f)) * 0.55f
        val maxPanY = (viewH * (scale - 1f)) * 0.55f
        return Offset(
            x = offset.x.coerceIn(-maxPanX, maxPanX),
            y = offset.y.coerceIn(-maxPanY, maxPanY)
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cSize = Size(size.width.toFloat(), size.height.toFloat())

                    var localZoom = currentZoomScale
                    var localPan = currentPanOffset
                    var localRefRect = currentReferenceRect

                    val downPt = down.position
                    val downTimeMs = System.currentTimeMillis()
                    var lastPt = downPt
                    var isDraggingOrZooming = false
                    var maxPointers = 1
                    var longPressFired = false

                    var activeHandle = if (currentEditorMode == PlateEditorMode.DEFINE_SINGLE_PART) {
                        val tl = normalizedToScreen(localRefRect.left, localRefRect.top, cSize, localZoom, localPan)
                        val br = normalizedToScreen(localRefRect.right, localRefRect.bottom, cSize, localZoom, localPan)
                        val centerScreen = Offset((tl.x + br.x) * 0.5f, (tl.y + br.y) * 0.5f)

                        val negRad = -Math.toRadians(localRefRect.angleDegrees.toDouble())
                        val cosN = cos(negRad).toFloat()
                        val sinN = sin(negRad).toFloat()
                        val relX = downPt.x - centerScreen.x
                        val relY = downPt.y - centerScreen.y
                        val unrotPt = Offset(
                            x = centerScreen.x + relX * cosN - relY * sinN,
                            y = centerScreen.y + relX * sinN + relY * cosN
                        )

                        val tr = Offset(br.x, tl.y)
                        val bl = Offset(tl.x, br.y)
                        val tm = Offset((tl.x + br.x) * 0.5f, tl.y)
                        val bm = Offset((tl.x + br.x) * 0.5f, br.y)
                        val lm = Offset(tl.x, (tl.y + br.y) * 0.5f)
                        val rm = Offset(br.x, (tl.y + br.y) * 0.5f)
                        val rotKnob = Offset(tm.x, tl.y - 34.dp.toPx())

                        val cornerHitRadius = 34.dp.toPx()
                        val edgeHitRadius = 26.dp.toPx()
                        val rotHitRadius = 36.dp.toPx()

                        when {
                            hypot((unrotPt.x - rotKnob.x).toDouble(), (unrotPt.y - rotKnob.y).toDouble()) < rotHitRadius ->
                                RefDragHandle.ROTATE
                            hypot((unrotPt.x - tl.x).toDouble(), (unrotPt.y - tl.y).toDouble()) < cornerHitRadius ->
                                RefDragHandle.TOP_LEFT
                            hypot((unrotPt.x - tr.x).toDouble(), (unrotPt.y - tr.y).toDouble()) < cornerHitRadius ->
                                RefDragHandle.TOP_RIGHT
                            hypot((unrotPt.x - bl.x).toDouble(), (unrotPt.y - bl.y).toDouble()) < cornerHitRadius ->
                                RefDragHandle.BOTTOM_LEFT
                            hypot((unrotPt.x - br.x).toDouble(), (unrotPt.y - br.y).toDouble()) < cornerHitRadius ->
                                RefDragHandle.BOTTOM_RIGHT
                            hypot((unrotPt.x - tm.x).toDouble(), (unrotPt.y - tm.y).toDouble()) < edgeHitRadius ->
                                RefDragHandle.TOP
                            hypot((unrotPt.x - bm.x).toDouble(), (unrotPt.y - bm.y).toDouble()) < edgeHitRadius ->
                                RefDragHandle.BOTTOM
                            hypot((unrotPt.x - lm.x).toDouble(), (unrotPt.y - lm.y).toDouble()) < edgeHitRadius ->
                                RefDragHandle.LEFT
                            hypot((unrotPt.x - rm.x).toDouble(), (unrotPt.y - rm.y).toDouble()) < edgeHitRadius ->
                                RefDragHandle.RIGHT
                            unrotPt.x in tl.x..br.x && unrotPt.y in tl.y..br.y ->
                                RefDragHandle.MOVE
                            else -> RefDragHandle.NONE
                        }
                    } else {
                        RefDragHandle.NONE
                    }

                    do {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        val pointerCount = pressedChanges.size

                        if (pointerCount >= 2) {
                            maxPointers = max(maxPointers, pointerCount)
                            isDraggingOrZooming = true
                            activeHandle = RefDragHandle.NONE

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()

                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val prevZoom = localZoom
                                localZoom = (localZoom * zoomChange).coerceIn(1f, 6f)
                                val zoomRatio = localZoom / prevZoom
                                val cx = cSize.width * 0.5f
                                val cy = cSize.height * 0.5f
                                val focusShift = (centroid - Offset(cx, cy)) * (1f - zoomRatio)
                                localPan = clampLocalPan(
                                    offset = localPan * zoomRatio + panChange + focusShift,
                                    scale = localZoom,
                                    viewW = cSize.width,
                                    viewH = cSize.height
                                )
                                currentOnZoomPanChange(localZoom, localPan, cSize.width, cSize.height)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pointerCount == 1 && maxPointers == 1) {
                            val change = pressedChanges.first()
                            lastPt = change.position
                            val deltaPx = change.position - change.previousPosition
                            val distFromDown = (change.position - downPt).getDistance()

                            if (activeHandle != RefDragHandle.NONE && currentEditorMode == PlateEditorMode.DEFINE_SINGLE_PART) {
                                if (distFromDown > 2f || isDraggingOrZooming) {
                                    isDraggingOrZooming = true
                                    val (_, baseSize) = computeImageFrame(cSize)
                                    val effW = (baseSize.width * localZoom).coerceAtLeast(1f)
                                    val effH = (baseSize.height * localZoom).coerceAtLeast(1f)
                                    val minBox = 0.015f
                                    val r = localRefRect

                                    val negRad = -Math.toRadians(r.angleDegrees.toDouble())
                                    val cosN = cos(negRad).toFloat()
                                    val sinN = sin(negRad).toFloat()
                                    val localDxPx = deltaPx.x * cosN - deltaPx.y * sinN
                                    val localDyPx = deltaPx.x * sinN + deltaPx.y * cosN
                                    val dLocalNormX = localDxPx / effW
                                    val dLocalNormY = localDyPx / effH

                                    localRefRect = when (activeHandle) {
                                        RefDragHandle.ROTATE -> {
                                            val centerScreen = normalizedToScreen(
                                                r.centerX,
                                                r.centerY,
                                                cSize,
                                                localZoom,
                                                localPan
                                            )
                                            val rawDeg = Math.toDegrees(
                                                atan2(
                                                    (change.position.y - centerScreen.y).toDouble(),
                                                    (change.position.x - centerScreen.x).toDouble()
                                                )
                                            ).toFloat() + 90f
                                            val normDeg = ((rawDeg + 180f) % 360f + 360f) % 360f - 180f
                                            r.copy(angleDegrees = normDeg)
                                        }
                                        RefDragHandle.MOVE -> {
                                            val dGlobalNormX = deltaPx.x / effW
                                            val dGlobalNormY = deltaPx.y / effH
                                            val w = r.width
                                            val h = r.height
                                            val nl = (r.left + dGlobalNormX).coerceIn(0f, 1f - w)
                                            val nt = (r.top + dGlobalNormY).coerceIn(0f, 1f - h)
                                            NormalizedRect(nl, nt, nl + w, nt + h, r.angleDegrees)
                                        }
                                        RefDragHandle.TOP_LEFT -> NormalizedRect(
                                            left = (r.left + dLocalNormX).coerceIn(0f, r.right - minBox),
                                            top = (r.top + dLocalNormY).coerceIn(0f, r.bottom - minBox),
                                            right = r.right,
                                            bottom = r.bottom,
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.TOP_RIGHT -> NormalizedRect(
                                            left = r.left,
                                            top = (r.top + dLocalNormY).coerceIn(0f, r.bottom - minBox),
                                            right = (r.right + dLocalNormX).coerceIn(r.left + minBox, 1f),
                                            bottom = r.bottom,
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.BOTTOM_LEFT -> NormalizedRect(
                                            left = (r.left + dLocalNormX).coerceIn(0f, r.right - minBox),
                                            top = r.top,
                                            right = r.right,
                                            bottom = (r.bottom + dLocalNormY).coerceIn(r.top + minBox, 1f),
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.BOTTOM_RIGHT -> NormalizedRect(
                                            left = r.left,
                                            top = r.top,
                                            right = (r.right + dLocalNormX).coerceIn(r.left + minBox, 1f),
                                            bottom = (r.bottom + dLocalNormY).coerceIn(r.top + minBox, 1f),
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.LEFT -> NormalizedRect(
                                            left = (r.left + dLocalNormX).coerceIn(0f, r.right - minBox),
                                            top = r.top,
                                            right = r.right,
                                            bottom = r.bottom,
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.RIGHT -> NormalizedRect(
                                            left = r.left,
                                            top = r.top,
                                            right = (r.right + dLocalNormX).coerceIn(r.left + minBox, 1f),
                                            bottom = r.bottom,
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.TOP -> NormalizedRect(
                                            left = r.left,
                                            top = (r.top + dLocalNormY).coerceIn(0f, r.bottom - minBox),
                                            right = r.right,
                                            bottom = r.bottom,
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.BOTTOM -> NormalizedRect(
                                            left = r.left,
                                            top = r.top,
                                            right = r.right,
                                            bottom = (r.bottom + dLocalNormY).coerceIn(r.top + minBox, 1f),
                                            angleDegrees = r.angleDegrees
                                        )
                                        RefDragHandle.NONE -> r
                                    }
                                    currentOnUpdateReferenceRect(localRefRect)
                                    change.consume()
                                }
                            } else {
                                if (distFromDown > viewConfiguration.touchSlop) {
                                    isDraggingOrZooming = true
                                    if (localZoom > 1.01f) {
                                        localPan = clampLocalPan(
                                            offset = localPan + deltaPx,
                                            scale = localZoom,
                                            viewW = cSize.width,
                                            viewH = cSize.height
                                        )
                                        currentOnZoomPanChange(localZoom, localPan, cSize.width, cSize.height)
                                        change.consume()
                                    }
                                } else if (
                                    !isDraggingOrZooming &&
                                    !longPressFired &&
                                    currentEditorMode == PlateEditorMode.SELECT_AND_COUNT &&
                                    (System.currentTimeMillis() - downTimeMs) >= 550L
                                ) {
                                    longPressFired = true
                                    val norm = screenToNormalized(downPt, cSize, localZoom, localPan)
                                    if (norm.x in 0f..1f && norm.y in 0f..1f) {
                                        currentOnLongPress(norm.x, norm.y)
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!isDraggingOrZooming && maxPointers == 1 && !longPressFired) {
                        val norm = screenToNormalized(lastPt, cSize, localZoom, localPan)
                        if (norm.x in 0f..1f && norm.y in 0f..1f) {
                            val heldDuration = System.currentTimeMillis() - downTimeMs
                            if (heldDuration >= 550L && currentEditorMode == PlateEditorMode.SELECT_AND_COUNT) {
                                currentOnLongPress(norm.x, norm.y)
                            } else {
                                currentOnPhotoTap(norm.x, norm.y)
                            }
                        }
                    }
                }
            }
    ) {
        val cSize = size
        val topLeftScreen = normalizedToScreen(0f, 0f, cSize, zoomScale, panOffset)
        val bottomRightScreen = normalizedToScreen(1f, 1f, cSize, zoomScale, panOffset)
        val dstW = (bottomRightScreen.x - topLeftScreen.x).roundToInt().coerceAtLeast(1)
        val dstH = (bottomRightScreen.y - topLeftScreen.y).roundToInt().coerceAtLeast(1)

        // 1. Draw the 3D Printer Plate Bitmap
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(topLeftScreen.x.roundToInt(), topLeftScreen.y.roundToInt()),
            dstSize = IntSize(dstW, dstH)
        )

        if (editorMode == PlateEditorMode.DEFINE_SINGLE_PART) {
            // Draw auto-detected candidate parts in soft steel-blue dashed boxes
            candidateRects.forEach { cand ->
                val cTl = normalizedToScreen(cand.left, cand.top, cSize, zoomScale, panOffset)
                val cBr = normalizedToScreen(cand.right, cand.bottom, cSize, zoomScale, panOffset)
                val boxSize = Size(max(4f, cBr.x - cTl.x), max(4f, cBr.y - cTl.y))

                drawRoundRect(
                    color = CyanAccent.copy(alpha = 0.14f),
                    topLeft = cTl,
                    size = boxSize,
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawRoundRect(
                    color = CyanAccent.copy(alpha = 0.65f),
                    topLeft = cTl,
                    size = boxSize,
                    cornerRadius = CornerRadius(6f, 6f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    )
                )
            }

            // Draw the interactive Reference Part Rectangle (rotated by referenceRect.angleDegrees)
            val rTl = normalizedToScreen(referenceRect.left, referenceRect.top, cSize, zoomScale, panOffset)
            val rBr = normalizedToScreen(referenceRect.right, referenceRect.bottom, cSize, zoomScale, panOffset)
            val rSize = Size(max(8f, rBr.x - rTl.x), max(8f, rBr.y - rTl.y))
            val rCenter = Offset((rTl.x + rBr.x) * 0.5f, (rTl.y + rBr.y) * 0.5f)
            val topMid = Offset(rCenter.x, rTl.y)
            val rotHandleCenter = Offset(rCenter.x, rTl.y - 34.dp.toPx())

            rotate(degrees = referenceRect.angleDegrees, pivot = rCenter) {
                // Stem line from top edge to rotation handle
                drawLine(
                    color = AmberReference,
                    start = topMid,
                    end = rotHandleCenter,
                    strokeWidth = 2.dp.toPx()
                )
                // Rotation handle circle
                drawCircle(
                    color = Color.White,
                    radius = 10.dp.toPx(),
                    center = rotHandleCenter
                )
                drawCircle(
                    color = AmberReference,
                    radius = 8.dp.toPx(),
                    center = rotHandleCenter
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = rotHandleCenter
                )

                drawRoundRect(
                    color = AmberReferenceFill,
                    topLeft = rTl,
                    size = rSize,
                    cornerRadius = CornerRadius(8f, 8f)
                )
                drawRoundRect(
                    color = AmberReference,
                    topLeft = rTl,
                    size = rSize,
                    cornerRadius = CornerRadius(8f, 8f),
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // Draw 4 corner resize handles + 4 edge midpoint handles
                val cornerRadiusPx = 7.dp.toPx()
                listOf(
                    rTl,
                    Offset(rBr.x, rTl.y),
                    Offset(rTl.x, rBr.y),
                    rBr
                ).forEach { corner ->
                    drawCircle(color = Color.White, radius = cornerRadiusPx + 1.5f, center = corner)
                    drawCircle(color = AmberReference, radius = cornerRadiusPx, center = corner)
                }

                val edgeRadiusPx = 4.5.dp.toPx()
                listOf(
                    Offset((rTl.x + rBr.x) * 0.5f, rTl.y),
                    Offset((rTl.x + rBr.x) * 0.5f, rBr.y),
                    Offset(rTl.x, (rTl.y + rBr.y) * 0.5f),
                    Offset(rBr.x, (rTl.y + rBr.y) * 0.5f)
                ).forEach { edgeMid ->
                    drawCircle(color = Color.White, radius = edgeRadiusPx + 1.5f, center = edgeMid)
                    drawCircle(color = AmberReference, radius = edgeRadiusPx, center = edgeMid)
                }

                val angleLabel = if (referenceRect.angleDegrees.roundToInt() != 0) {
                    "1 PART (${referenceRect.angleDegrees.roundToInt()}°)"
                } else {
                    "1 PART"
                }
                val labelLayout = textMeasurer.measure(
                    text = angleLabel,
                    style = TextStyle(
                        color = Color(0xFF1E242B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                val badgeW = labelLayout.size.width + 14f
                val badgeH = labelLayout.size.height + 6f
                val badgeTop = rBr.y + 8f
                drawRoundRect(
                    color = AmberReference,
                    topLeft = Offset(rCenter.x - badgeW * 0.5f, badgeTop),
                    size = Size(badgeW, badgeH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawText(
                    textLayoutResult = labelLayout,
                    topLeft = Offset(rCenter.x - badgeW * 0.5f + 7f, badgeTop + 3f)
                )
            }
        } else {
            // Mode 2: SELECT_AND_COUNT — Draw every detected part in a soft semitransparent rectangle
            var selectedOrdinal = 0
            detections.forEach { box ->
                val bTl = normalizedToScreen(box.rect.left, box.rect.top, cSize, zoomScale, panOffset)
                val bBr = normalizedToScreen(box.rect.right, box.rect.bottom, cSize, zoomScale, panOffset)
                val bSize = Size(max(6f, bBr.x - bTl.x), max(6f, bBr.y - bTl.y))
                val bCenter = Offset((bTl.x + bBr.x) * 0.5f, (bTl.y + bBr.y) * 0.5f)

                if (box.isSelected) {
                    selectedOrdinal++
                    val currentOrdinal = selectedOrdinal
                    rotate(degrees = box.rect.angleDegrees, pivot = bCenter) {
                        drawRoundRect(
                            color = EmeraldSelectedFill,
                            topLeft = bTl,
                            size = bSize,
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        drawRoundRect(
                            color = EmeraldSelected,
                            topLeft = bTl,
                            size = bSize,
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        val numText = textMeasurer.measure(
                            text = "$currentOrdinal",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        val tagW = max(numText.size.width + 10f, 18f)
                        val tagH = numText.size.height + 4f
                        drawRoundRect(
                            color = EmeraldSelected,
                            topLeft = bTl,
                            size = Size(tagW, tagH),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawText(
                            textLayoutResult = numText,
                            topLeft = Offset(bTl.x + 5f, bTl.y + 2f)
                        )
                    }
                } else {
                    rotate(degrees = box.rect.angleDegrees, pivot = bCenter) {
                        drawRoundRect(
                            color = RoseDeselectedFill,
                            topLeft = bTl,
                            size = bSize,
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        drawRoundRect(
                            color = RoseDeselected,
                            topLeft = bTl,
                            size = bSize,
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(
                                width = 1.6.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                            )
                        )

                        val xText = textMeasurer.measure(
                            text = "×",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        val tagW = xText.size.width + 10f
                        val tagH = xText.size.height + 2f
                        drawRoundRect(
                            color = RoseDeselected.copy(alpha = 0.85f),
                            topLeft = bTl,
                            size = Size(tagW, tagH),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawText(
                            textLayoutResult = xText,
                            topLeft = Offset(bTl.x + 5f, bTl.y + 1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefineSinglePartBottomPanel(
    candidateCount: Int,
    angleDegrees: Float,
    onAngleChange: (Float) -> Unit,
    onConfirmSinglePart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberReference.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = null,
                    tint = AmberReference,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Frame a Single Part",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (candidateCount > 0) {
                        "Tap a dashed box or drag/rotate the rectangle around 1 part."
                    } else {
                        "Drag corners to resize and top handle (or slider) to set angle."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Angle Slider Row (-90° to +90° with 0° quick reset)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = null,
                tint = AmberReference,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Angle",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Slider(
                value = angleDegrees.coerceIn(-90f, 90f),
                onValueChange = onAngleChange,
                valueRange = -90f..90f,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
            )
            Text(
                text = "${angleDegrees.roundToInt()}°",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = AmberReference,
                maxLines = 1,
                modifier = Modifier.width(36.dp)
            )
            TextButton(
                onClick = { onAngleChange(0f) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) {
                Text(
                    text = "Reset",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }

        Button(
            onClick = onConfirmSinglePart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Count Matching Parts",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectAndCountBottomPanel(
    selectedCount: Int,
    totalDetectedCount: Int,
    sensitivity: Float,
    isManualAddMode: Boolean,
    onSensitivityChange: (Float) -> Unit,
    onToggleManualAddMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onRedefineSinglePart: () -> Unit,
    onConfirmAndName: () -> Unit,
    onAddAnotherPlate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Sensitivity Slider Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Sensitivity",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Slider(
                value = sensitivity,
                onValueChange = onSensitivityChange,
                valueRange = 0.15f..0.92f,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
            )
            Text(
                text = "${(sensitivity * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(38.dp)
            )
        }

        // Wrapping Quick Selection Tools FlowRow (prevents horizontal clipping on smaller screens)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = isManualAddMode,
                onClick = onToggleManualAddMode,
                label = {
                    Text(
                        text = if (isManualAddMode) "Adding..." else "+ Add",
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )

            AssistChip(
                onClick = onSelectAll,
                label = {
                    Text(
                        text = "All ($totalDetectedCount)",
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )

            AssistChip(
                onClick = onDeselectAll,
                label = {
                    Text(
                        text = "None",
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.RemoveDone,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )

            AssistChip(
                onClick = onRedefineSinglePart,
                label = {
                    Text(
                        text = "1-Part Box",
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.CropFree,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )
        }

        // Primary Confirm & Name CTA + Add Plate button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onAddAnotherPlate,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Default.AddAPhoto,
                    contentDescription = "Add another plate",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "+ Plate",
                    maxLines = 1
                )
            }

            Button(
                onClick = onConfirmAndName,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Name Element ($selectedCount)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElementNamingDialog(
    plateNumber: Int,
    totalPlates: Int,
    selectedPieceCount: Int,
    currentElementName: String,
    lastNamedElement: String,
    suggestedNames: List<String>,
    hasNextPlate: Boolean,
    onDismiss: () -> Unit,
    onConfirmName: (String) -> Unit,
    onConfirmAndAddAnotherPlate: (String) -> Unit
) {
    val previousSuggestion = lastNamedElement.ifBlank {
        suggestedNames.firstOrNull().orEmpty()
    }
    var nameInput by remember(currentElementName, previousSuggestion) {
        mutableStateOf(
            currentElementName.ifBlank { previousSuggestion }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Name Element (Plate $plateNumber)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$selectedPieceCount selected pcs on this plate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1-Tap Suggestion for the Previously Named Element
                if (previousSuggestion.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onConfirmName(previousSuggestion)
                            },
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "Previous element:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1
                                )
                                Text(
                                    text = previousSuggestion,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "Use Again",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Element Name", maxLines = 1) },
                    placeholder = {
                        Text(
                            text = "e.g. Cable Clip, Spacer...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                if (suggestedNames.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Recent names:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            suggestedNames.take(6).forEach { suggestion ->
                                AssistChip(
                                    onClick = { nameInput = suggestion },
                                    label = {
                                        Text(
                                            text = suggestion,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (nameInput.equals(suggestion, ignoreCase = true)) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val finalName = nameInput.trim().ifEmpty {
                            previousSuggestion.ifBlank { "Element $plateNumber" }
                        }
                        onConfirmName(finalName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (hasNextPlate) {
                            "Save & Next Plate (${plateNumber + 1}/$totalPlates)"
                        } else {
                            "Save & View Summary"
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!hasNextPlate) {
                    FilledTonalButton(
                        onClick = {
                            val finalName = nameInput.trim().ifEmpty {
                                previousSuggestion.ifBlank { "Element $plateNumber" }
                            }
                            onConfirmAndAddAnotherPlate(finalName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.AddAPhoto,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save & Add Another Plate",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel", maxLines = 1)
                }
            }
        },
        dismissButton = {}
    )
}
