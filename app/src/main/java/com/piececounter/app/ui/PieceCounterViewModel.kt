package com.piececounter.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.piececounter.app.data.repository.SessionRepository
import com.piececounter.app.domain.model.CountingSession
import com.piececounter.app.domain.model.PlateItem
import com.piececounter.app.domain.vision.BoxGeometry
import com.piececounter.app.domain.vision.NormalizedRect
import com.piececounter.app.domain.vision.PartDetectionBox
import com.piececounter.app.domain.vision.PlatePartDetector
import com.piececounter.app.util.ImageStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PlateEditorMode {
    /** User chooses how a single part looks (tap a candidate box or drag/resize the template box). */
    DEFINE_SINGLE_PART,
    /** All matched elements are shown in semitransparent rectangles (selected by default); tap to deselect/select. */
    SELECT_AND_COUNT
}

data class ActivePlateRuntimeCache(
    val plateId: String,
    val bitmap: Bitmap,
    val pixelBuffer: PlatePartDetector.PixelBuffer,
    val candidateRects: List<NormalizedRect>,
    val allMatchedPeaks: List<PartDetectionBox>,
    val deselectedIds: Set<String> = emptySet(),
    val manualBoxes: List<PartDetectionBox> = emptyList()
)

data class EditorUiState(
    val activeSession: CountingSession? = null,
    val currentPlateIndex: Int = 0,
    val editorMode: PlateEditorMode = PlateEditorMode.DEFINE_SINGLE_PART,
    val activeBitmap: Bitmap? = null,
    val candidateRects: List<NormalizedRect> = emptyList(),
    val isAnalyzing: Boolean = false,
    val isManualAddMode: Boolean = false,
    val showNamingDialog: Boolean = false,
    val lastNamedElement: String = "",
    val pendingCameraPaths: List<String> = emptyList(),
    val isAddingPlatesToExistingSession: Boolean = false
) {
    val currentPlate: PlateItem?
        get() = activeSession?.plates?.getOrNull(currentPlateIndex)

    val hasNextPlate: Boolean
        get() = activeSession != null && currentPlateIndex < activeSession.plates.lastIndex

    val hasPreviousPlate: Boolean
        get() = activeSession != null && currentPlateIndex > 0
}

class PieceCounterViewModel(
    private val repository: SessionRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val allSessions: StateFlow<List<CountingSession>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredSessions: StateFlow<List<CountingSession>> = combine(
        allSessions,
        _searchQuery
    ) { sessions, query ->
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            sessions
        } else {
            sessions.filter { session ->
                session.title.lowercase(Locale.ROOT).contains(q) ||
                    session.plates.any { it.elementName.lowercase(Locale.ROOT).contains(q) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val dbElementNames: StateFlow<List<String>> = repository.recentElementNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editorState = MutableStateFlow(EditorUiState())
    val editorState: StateFlow<EditorUiState> = _editorState.asStateFlow()

    /**
     * Combined list of recently named elements (current session + device history),
     * with the most recently named element first.
     */
    val suggestedElementNames: StateFlow<List<String>> = combine(
        _editorState,
        dbElementNames
    ) { editor, dbNames ->
        val combined = LinkedHashSet<String>()
        if (editor.lastNamedElement.isNotBlank()) {
            combined.add(editor.lastNamedElement.trim())
        }
        editor.activeSession?.plates?.asReversed()?.forEach { plate ->
            if (plate.elementName.isNotBlank()) {
                combined.add(plate.elementName.trim())
            }
        }
        dbNames.forEach { name ->
            if (name.isNotBlank()) {
                combined.add(name.trim())
            }
        }
        combined.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val runtimeCacheByPlateId = mutableMapOf<String, ActivePlateRuntimeCache>()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Begins a fresh camera capture batch (or adds plates to an existing session if [appendToCurrentSession] is true).
     */
    fun startCameraBatch(appendToCurrentSession: Boolean = false) {
        _editorState.update { state ->
            state.copy(
                pendingCameraPaths = emptyList(),
                isAddingPlatesToExistingSession = appendToCurrentSession && state.activeSession != null
            )
        }
    }

    fun addCapturedPhotoPath(path: String) {
        _editorState.update { state ->
            state.copy(pendingCameraPaths = state.pendingCameraPaths + path)
        }
    }

    fun removeCapturedPhotoPath(path: String) {
        _editorState.update { state ->
            state.copy(pendingCameraPaths = state.pendingCameraPaths - path)
        }
    }

    /**
     * Imports one or more gallery URIs into internal storage and starts/appends them to the session.
     */
    fun importGalleryUris(
        context: Context,
        uris: List<Uri>,
        appendToCurrentSession: Boolean,
        onReadyToEdit: () -> Unit
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _editorState.update { it.copy(isAnalyzing = true) }
            val paths = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    ImageStorageHelper.copyUriToInternalFile(context, uri)
                }
            }
            if (paths.isNotEmpty()) {
                createOrAppendPlatesFromPaths(paths, appendToCurrentSession)
                onReadyToEdit()
            } else {
                _editorState.update { it.copy(isAnalyzing = false) }
            }
        }
    }

    /**
     * Generates a 3-plate synthetic 3D printer session so the user can test the complete flow immediately.
     */
    fun startDemoSession(context: Context, onReadyToEdit: () -> Unit) {
        viewModelScope.launch {
            _editorState.update { it.copy(isAnalyzing = true) }
            val paths = withContext(Dispatchers.IO) {
                ImageStorageHelper.generateDemoPlateFiles(context)
            }
            createOrAppendPlatesFromPaths(paths, appendToExisting = false, customTitle = "Demo 3D Print Batch")
            onReadyToEdit()
        }
    }

    /**
     * Finalizes the batch of photos captured in the Camera screen and opens the Plate Editor on the first new plate.
     */
    fun proceedFromCameraBatch(onReadyToEdit: () -> Unit) {
        val state = _editorState.value
        val paths = state.pendingCameraPaths
        if (paths.isEmpty()) return
        viewModelScope.launch {
            createOrAppendPlatesFromPaths(
                paths = paths,
                appendToExisting = state.isAddingPlatesToExistingSession
            )
            onReadyToEdit()
        }
    }

    private suspend fun createOrAppendPlatesFromPaths(
        paths: List<String>,
        appendToExisting: Boolean,
        customTitle: String? = null
    ) {
        val now = System.currentTimeMillis()
        val existingSession = if (appendToExisting) _editorState.value.activeSession else null

        val sessionId = existingSession?.sessionId ?: UUID.randomUUID().toString()
        val startIndex = existingSession?.plates?.size ?: 0
        val defaultTitle = customTitle ?: existingSession?.title ?: run {
            val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(now))
            "Liczenie stołów — $fmt"
        }

        val newPlates = paths.mapIndexed { idx, path ->
            PlateItem(
                plateId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                plateOrderIndex = startIndex + idx,
                imagePath = path,
                elementName = "",
                referenceRect = NormalizedRect(0.38f, 0.38f, 0.56f, 0.56f),
                detections = emptyList(),
                sensitivity = 0.60f
            )
        }

        val updatedSession = CountingSession(
            sessionId = sessionId,
            title = defaultTitle,
            createdAtEpochMs = existingSession?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            plates = (existingSession?.plates ?: emptyList()) + newPlates
        )

        repository.saveSession(updatedSession)

        _editorState.update { state ->
            state.copy(
                activeSession = updatedSession,
                currentPlateIndex = startIndex,
                editorMode = PlateEditorMode.DEFINE_SINGLE_PART,
                pendingCameraPaths = emptyList(),
                isAddingPlatesToExistingSession = false,
                showNamingDialog = false
            )
        }

        loadAndAnalyzeCurrentPlate(forceInitialProposal = true)
    }

    /**
     * Opens an existing saved session from the Home screen for viewing or editing.
     */
    fun openSavedSession(sessionId: String, onLoaded: () -> Unit) {
        viewModelScope.launch {
            val session = repository.getSession(sessionId) ?: return@launch
            val lastNamed = session.plates.lastOrNull { it.elementName.isNotBlank() }?.elementName ?: ""
            _editorState.update {
                it.copy(
                    activeSession = session,
                    currentPlateIndex = 0,
                    editorMode = PlateEditorMode.SELECT_AND_COUNT,
                    lastNamedElement = lastNamed.ifBlank { it.lastNamedElement },
                    showNamingDialog = false
                )
            }
            onLoaded()
        }
    }

    /**
     * Selects a specific plate in the current session to inspect/edit in PlateEditorScreen.
     */
    fun selectPlateForEditing(plateIndex: Int, startInDefineMode: Boolean = false) {
        val session = _editorState.value.activeSession ?: return
        if (plateIndex !in session.plates.indices) return
        val targetPlate = session.plates[plateIndex]
        val mode = if (startInDefineMode || targetPlate.detections.isEmpty()) {
            PlateEditorMode.DEFINE_SINGLE_PART
        } else {
            PlateEditorMode.SELECT_AND_COUNT
        }
        _editorState.update {
            it.copy(
                currentPlateIndex = plateIndex,
                editorMode = mode,
                isManualAddMode = false,
                showNamingDialog = false
            )
        }
        viewModelScope.launch {
            loadAndAnalyzeCurrentPlate(forceInitialProposal = targetPlate.detections.isEmpty())
        }
    }

    /**
     * Loads the current plate's bitmap and runs candidate segmentation + template matching.
     */
    private suspend fun loadAndAnalyzeCurrentPlate(forceInitialProposal: Boolean) {
        val state = _editorState.value
        val plate = state.currentPlate ?: return

        _editorState.update { it.copy(isAnalyzing = true) }

        val cached = runtimeCacheByPlateId[plate.plateId]
        if (cached != null && !forceInitialProposal && plate.detections.isNotEmpty()) {
            _editorState.update {
                it.copy(
                    activeBitmap = cached.bitmap,
                    candidateRects = cached.candidateRects,
                    isAnalyzing = false
                )
            }
            return
        }

        withContext(Dispatchers.Default) {
            val bitmap = cached?.bitmap ?: ImageStorageHelper.loadBitmapFromPath(plate.imagePath)
            if (bitmap == null) {
                _editorState.update { it.copy(isAnalyzing = false) }
                return@withContext
            }

            val pixelBuffer = cached?.pixelBuffer ?: PlatePartDetector.bitmapToPixelBuffer(bitmap)
            val candidates = cached?.candidateRects ?: PlatePartDetector.detectCandidateParts(pixelBuffer)

            val initialRef = if (forceInitialProposal && plate.detections.isEmpty()) {
                PlatePartDetector.suggestInitialReferenceBox(candidates)
            } else {
                plate.referenceRect
            }

            val analysis = PlatePartDetector.matchPartsFromReference(
                buffer = pixelBuffer,
                referenceRect = initialRef,
                sensitivity = plate.sensitivity
            )

            // If plate already had saved detections from DB, preserve their deselected/manual state
            val existingDetections = plate.detections
            val deselectedIds: Set<String>
            val manualBoxes: List<PartDetectionBox>
            val finalDetections: List<PartDetectionBox>

            if (!forceInitialProposal && existingDetections.isNotEmpty()) {
                deselectedIds = existingDetections.filter { !it.isSelected }.map { it.id }.toSet()
                manualBoxes = existingDetections.filter { it.isManual }
                finalDetections = existingDetections
            } else {
                deselectedIds = emptySet()
                manualBoxes = emptyList()
                finalDetections = analysis.activeDetections
            }

            runtimeCacheByPlateId[plate.plateId] = ActivePlateRuntimeCache(
                plateId = plate.plateId,
                bitmap = bitmap,
                pixelBuffer = pixelBuffer,
                candidateRects = candidates,
                allMatchedPeaks = analysis.allMatchedPeaks,
                deselectedIds = deselectedIds,
                manualBoxes = manualBoxes
            )

            val updatedPlate = plate.copy(
                referenceRect = initialRef,
                detections = finalDetections
            )
            updateCurrentPlateInSession(updatedPlate, persistToDb = true)

            _editorState.update {
                it.copy(
                    activeBitmap = bitmap,
                    candidateRects = candidates,
                    isAnalyzing = false
                )
            }
        }
    }

    /**
     * Updates the reference bounding box (when user taps a candidate box or drags/resizes the template rectangle).
     */
    fun updateReferenceRect(newRect: NormalizedRect, runMatchImmediately: Boolean = false) {
        val plate = _editorState.value.currentPlate ?: return
        val clamped = newRect.clamp(minSize = 0.015f)
        val updatedPlate = plate.copy(referenceRect = clamped)
        updateCurrentPlateInSession(updatedPlate, persistToDb = false)

        if (runMatchImmediately) {
            runTemplateMatchingAndSwitchToCount()
        }
    }

    /**
     * Runs template matching using the user's chosen single-part reference box and transitions
     * to [PlateEditorMode.SELECT_AND_COUNT] with all matched parts selected by default.
     */
    fun runTemplateMatchingAndSwitchToCount() {
        val state = _editorState.value
        val plate = state.currentPlate ?: return
        val cache = runtimeCacheByPlateId[plate.plateId] ?: return

        viewModelScope.launch {
            _editorState.update { it.copy(isAnalyzing = true) }
            withContext(Dispatchers.Default) {
                val result = PlatePartDetector.matchPartsFromReference(
                    buffer = cache.pixelBuffer,
                    referenceRect = plate.referenceRect,
                    sensitivity = plate.sensitivity
                )
                runtimeCacheByPlateId[plate.plateId] = cache.copy(
                    allMatchedPeaks = result.allMatchedPeaks,
                    deselectedIds = emptySet(),
                    manualBoxes = emptyList()
                )
                val updatedPlate = plate.copy(
                    referenceRect = result.referenceRect,
                    detections = result.activeDetections // All isSelected = true by default!
                )
                updateCurrentPlateInSession(updatedPlate, persistToDb = true)
                _editorState.update {
                    it.copy(
                        editorMode = PlateEditorMode.SELECT_AND_COUNT,
                        isAnalyzing = false,
                        isManualAddMode = false
                    )
                }
            }
        }
    }

    fun setEditorMode(mode: PlateEditorMode) {
        _editorState.update { it.copy(editorMode = mode, isManualAddMode = false) }
    }

    fun toggleManualAddMode() {
        _editorState.update { it.copy(isManualAddMode = !it.isManualAddMode) }
    }

    /**
     * Toggles selection of a single detected element at normalized image coordinate (normX, normY)
     * or adds a manual part if [isManualAddMode] is active.
     */
    fun onPlatePhotoTap(normX: Float, normY: Float) {
        val state = _editorState.value
        val plate = state.currentPlate ?: return

        val cache = runtimeCacheByPlateId[plate.plateId]
        val aspect = cache?.let { PlatePartDetector.imageAspect(it.pixelBuffer) } ?: 1f

        if (state.editorMode == PlateEditorMode.DEFINE_SINGLE_PART) {
            // If user taps inside a candidate part box, snap the reference box to that candidate
            val hitCandidate = state.candidateRects
                .filter { it.contains(normX, normY, imageAspect = aspect) }
                .minByOrNull { it.area }

            if (hitCandidate != null) {
                updateReferenceRect(
                    hitCandidate.copy(angleDegrees = plate.referenceRect.angleDegrees),
                    runMatchImmediately = false
                )
            } else {
                // Center the current reference box size at the tapped point, keeping its current angle
                val halfW = (plate.referenceRect.width * 0.5f).coerceAtLeast(0.03f)
                val halfH = (plate.referenceRect.height * 0.5f).coerceAtLeast(0.03f)
                val moved = NormalizedRect(
                    left = (normX - halfW).coerceIn(0f, 1f - 2 * halfW),
                    top = (normY - halfH).coerceIn(0f, 1f - 2 * halfH),
                    right = (normX + halfW).coerceIn(2 * halfW, 1f),
                    bottom = (normY + halfH).coerceIn(2 * halfH, 1f),
                    angleDegrees = plate.referenceRect.angleDegrees
                )
                updateReferenceRect(moved, runMatchImmediately = false)
            }
            return
        }

        // In SELECT_AND_COUNT mode: check if the user tapped an existing element rectangle
        // Include a small touch forgiveness margin around small boxes so tapping tiny parts is reliable
        val touchMargin = 0.015f
        val exactHits = plate.detections.filter {
            it.rect.contains(normX, normY, margin = 0f, imageAspect = aspect)
        }
        val candidatesToHit = exactHits.ifEmpty {
            plate.detections.filter { box ->
                box.rect.contains(normX, normY, margin = touchMargin, imageAspect = aspect)
            }
        }

        val hitBox = candidatesToHit.minByOrNull {
            val dx = (it.rect.centerX - normX) * aspect
            val dy = it.rect.centerY - normY
            dx * dx + dy * dy
        }

        if (hitBox != null) {
            toggleElementSelection(hitBox.id)
        } else if (state.isManualAddMode) {
            addManualElementAt(normX, normY)
        }
    }

    /**
     * Toggles a single detected element between selected (counted) and deselected (excluded).
     */
    fun toggleElementSelection(boxId: String) {
        val plate = _editorState.value.currentPlate ?: return
        val cache = runtimeCacheByPlateId[plate.plateId]

        val updatedDetections = plate.detections.map { box ->
            if (box.id == boxId) box.copy(isSelected = !box.isSelected) else box
        }

        if (cache != null) {
            val newDeselected = updatedDetections.filter { !it.isSelected }.map { it.id }.toSet()
            runtimeCacheByPlateId[plate.plateId] = cache.copy(deselectedIds = newDeselected)
        }

        updateCurrentPlateInSession(plate.copy(detections = updatedDetections), persistToDb = true)
    }

    /**
     * Long-pressing on the photo (or tapping in "+ Add" mode) adds a new selected element box
     * matching the reference part's dimensions centered at (normX, normY), provided it does not
     * overlap any existing detection by more than 10%.
     */
    fun addManualElementAt(normX: Float, normY: Float) {
        val plate = _editorState.value.currentPlate ?: return
        val cache = runtimeCacheByPlateId[plate.plateId]
        val aspect = cache?.let { PlatePartDetector.imageAspect(it.pixelBuffer) } ?: 1f

        val halfW = (plate.referenceRect.width * 0.5f).coerceIn(0.02f, 0.25f)
        val halfH = (plate.referenceRect.height * 0.5f).coerceIn(0.02f, 0.25f)
        val newRect = NormalizedRect(
            left = (normX - halfW).coerceIn(0f, 1f - 2 * halfW),
            top = (normY - halfH).coerceIn(0f, 1f - 2 * halfH),
            right = (normX + halfW).coerceIn(2 * halfW, 1f),
            bottom = (normY + halfH).coerceIn(2 * halfH, 1f),
            angleDegrees = plate.referenceRect.angleDegrees
        ).clamp()

        // Check if this new box overlaps an existing detection by more than 10%
        val overlappingExisting = plate.detections.firstOrNull { existing ->
            BoxGeometry.overlapRatio(newRect, existing.rect, aspect) > BoxGeometry.MAX_OVERLAP_RATIO
        }
        if (overlappingExisting != null) {
            // If the overlapping box was deselected, re-select it instead of stacking an overlapping box
            if (!overlappingExisting.isSelected) {
                toggleElementSelection(overlappingExisting.id)
            }
            return
        }

        val manualBox = PartDetectionBox(
            id = "manual_${System.currentTimeMillis()}",
            rect = newRect,
            confidence = 1.0f,
            isSelected = true,
            isManual = true
        )

        if (cache != null) {
            runtimeCacheByPlateId[plate.plateId] = cache.copy(
                manualBoxes = cache.manualBoxes + manualBox
            )
        }

        val updatedDetections = plate.detections + manualBox
        updateCurrentPlateInSession(plate.copy(detections = updatedDetections), persistToDb = true)
    }

    fun selectAllOnCurrentPlate() {
        val plate = _editorState.value.currentPlate ?: return
        val cache = runtimeCacheByPlateId[plate.plateId]
        if (cache != null) {
            runtimeCacheByPlateId[plate.plateId] = cache.copy(deselectedIds = emptySet())
        }
        val updated = plate.detections.map { it.copy(isSelected = true) }
        updateCurrentPlateInSession(plate.copy(detections = updated), persistToDb = true)
    }

    fun deselectAllOnCurrentPlate() {
        val plate = _editorState.value.currentPlate ?: return
        val cache = runtimeCacheByPlateId[plate.plateId]
        val allIds = plate.detections.map { it.id }.toSet()
        if (cache != null) {
            runtimeCacheByPlateId[plate.plateId] = cache.copy(deselectedIds = allIds)
        }
        val updated = plate.detections.map { it.copy(isSelected = false) }
        updateCurrentPlateInSession(plate.copy(detections = updated), persistToDb = true)
    }

    /**
     * Adjusts detection sensitivity in real time while preserving manual deselections and manual additions.
     */
    fun updateSensitivity(newSensitivity: Float) {
        val plate = _editorState.value.currentPlate ?: return
        val clamped = newSensitivity.coerceIn(0.10f, 0.95f)
        val cache = runtimeCacheByPlateId[plate.plateId]

        if (cache != null) {
            val aspect = PlatePartDetector.imageAspect(cache.pixelBuffer)
            val filtered = PlatePartDetector.applySensitivityAndSelections(
                allMatchedPeaks = cache.allMatchedPeaks,
                sensitivity = clamped,
                deselectedIds = cache.deselectedIds,
                manualBoxes = cache.manualBoxes,
                imageAspect = aspect,
                buffer = cache.pixelBuffer
            )
            updateCurrentPlateInSession(
                plate.copy(sensitivity = clamped, detections = filtered),
                persistToDb = true
            )
        } else {
            val deselected = plate.detections.filter { !it.isSelected }.map { it.id }.toSet()
            val manuals = plate.detections.filter { it.isManual }
            val autos = plate.detections.filter { !it.isManual }
            val filtered = PlatePartDetector.applySensitivityAndSelections(
                allMatchedPeaks = autos,
                sensitivity = clamped,
                deselectedIds = deselected,
                manualBoxes = manuals
            )
            updateCurrentPlateInSession(
                plate.copy(sensitivity = clamped, detections = filtered),
                persistToDb = true
            )
            viewModelScope.launch {
                loadAndAnalyzeCurrentPlate(forceInitialProposal = false)
            }
        }
    }

    fun openNamingDialog() {
        _editorState.update { it.copy(showNamingDialog = true) }
    }

    fun dismissNamingDialog() {
        _editorState.update { it.copy(showNamingDialog = false) }
    }

    /**
     * Confirms the element name for the current plate, saves the session to the device database,
     * and either advances to the next plate in the batch or invokes [onAllPlatesFinished].
     */
    fun confirmPlateElementName(
        elementName: String,
        onAdvanceToNextPlate: () -> Unit,
        onAllPlatesFinished: () -> Unit
    ) {
        val state = _editorState.value
        val plate = state.currentPlate ?: return
        val cleanName = elementName.trim().ifEmpty {
            state.lastNamedElement.ifBlank { "Part 1" }
        }

        val updatedPlate = plate.copy(elementName = cleanName)
        updateCurrentPlateInSession(updatedPlate, persistToDb = true)

        val hasNext = state.hasNextPlate
        val nextIndex = state.currentPlateIndex + 1

        _editorState.update {
            it.copy(
                lastNamedElement = cleanName,
                showNamingDialog = false
            )
        }

        if (hasNext) {
            selectPlateForEditing(nextIndex)
            onAdvanceToNextPlate()
        } else {
            onAllPlatesFinished()
        }
    }

    fun updateSessionTitle(newTitle: String) {
        val session = _editorState.value.activeSession ?: return
        val clean = newTitle.trim().ifEmpty { session.title }
        val updated = session.copy(
            title = clean,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        _editorState.update { it.copy(activeSession = updated) }
        viewModelScope.launch {
            repository.saveSession(updated)
        }
    }

    fun deletePlateFromActiveSession(plateId: String) {
        val session = _editorState.value.activeSession ?: return
        val remaining = session.plates
            .filterNot { it.plateId == plateId }
            .mapIndexed { idx, p -> p.copy(plateOrderIndex = idx) }

        val updated = session.copy(
            plates = remaining,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        runtimeCacheByPlateId.remove(plateId)
        val newIdx = _editorState.value.currentPlateIndex.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
        _editorState.update {
            it.copy(
                activeSession = updated,
                currentPlateIndex = newIdx
            )
        }
        viewModelScope.launch {
            if (remaining.isEmpty()) {
                repository.deleteSession(session.sessionId)
            } else {
                repository.saveSession(updated)
            }
        }
    }

    fun deleteSavedSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_editorState.value.activeSession?.sessionId == sessionId) {
                _editorState.update { it.copy(activeSession = null) }
            }
        }
    }

    private fun updateCurrentPlateInSession(updatedPlate: PlateItem, persistToDb: Boolean) {
        val session = _editorState.value.activeSession ?: return
        val updatedPlates = session.plates.map { p ->
            if (p.plateId == updatedPlate.plateId) updatedPlate else p
        }
        val updatedSession = session.copy(
            plates = updatedPlates,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        _editorState.update { it.copy(activeSession = updatedSession) }
        if (persistToDb) {
            viewModelScope.launch {
                repository.saveSession(updatedSession)
            }
        }
    }

    companion object {
        fun provideFactory(repository: SessionRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PieceCounterViewModel(repository) as T
                }
            }
        }
    }
}
