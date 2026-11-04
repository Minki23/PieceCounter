package com.piececounter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.piececounter.app.ui.PieceCounterViewModel
import com.piececounter.app.ui.screens.CameraCaptureScreen
import com.piececounter.app.ui.screens.HomeScreen
import com.piececounter.app.ui.screens.PlateEditorScreen
import com.piececounter.app.ui.screens.SessionSummaryScreen
import com.piececounter.app.ui.theme.PieceCounterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PieceCounterApplication
        setContent {
            PieceCounterTheme {
                val viewModel: PieceCounterViewModel = viewModel(
                    factory = PieceCounterViewModel.provideFactory(app.repository)
                )
                PieceCounterAppContent(viewModel = viewModel)
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val EDITOR = "editor"
    const val SUMMARY = "summary"
}

@Composable
private fun PieceCounterAppContent(
    viewModel: PieceCounterViewModel
) {
    val context = LocalContext.current
    val navController = rememberNavController()

    val sessions by viewModel.filteredSessions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val editorState by viewModel.editorState.collectAsState()
    val suggestedNames by viewModel.suggestedElementNames.collectAsState()

    var appendGalleryToCurrentSession by remember { mutableStateOf(false) }

    val multiPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importGalleryUris(
                context = context,
                uris = uris,
                appendToCurrentSession = appendGalleryToCurrentSession,
                onReadyToEdit = {
                    navController.navigate(Routes.EDITOR) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                sessions = sessions,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onStartCameraBatch = {
                    viewModel.startCameraBatch(appendToCurrentSession = false)
                    navController.navigate(Routes.CAMERA)
                },
                onPickFromGallery = {
                    appendGalleryToCurrentSession = false
                    multiPhotoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onStartDemoBatch = {
                    viewModel.startDemoSession(context) {
                        navController.navigate(Routes.EDITOR)
                    }
                },
                onOpenSession = { sessionId ->
                    viewModel.openSavedSession(sessionId) {
                        navController.navigate(Routes.SUMMARY)
                    }
                },
                onDeleteSession = viewModel::deleteSavedSession
            )
        }

        composable(Routes.CAMERA) {
            CameraCaptureScreen(
                capturedPaths = editorState.pendingCameraPaths,
                onPhotoCaptured = viewModel::addCapturedPhotoPath,
                onRemoveCapturedPhoto = viewModel::removeCapturedPhotoPath,
                onProceedWithPlates = {
                    viewModel.proceedFromCameraBatch {
                        navController.navigate(Routes.EDITOR) {
                            popUpTo(Routes.CAMERA) { inclusive = true }
                        }
                    }
                },
                onPickFromGallery = {
                    appendGalleryToCurrentSession = editorState.isAddingPlatesToExistingSession
                    multiPhotoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.EDITOR) {
            PlateEditorScreen(
                state = editorState,
                suggestedNames = suggestedNames,
                onSelectPlateIndex = { idx ->
                    viewModel.selectPlateForEditing(idx)
                },
                onSetEditorMode = viewModel::setEditorMode,
                onUpdateReferenceRect = { rect ->
                    viewModel.updateReferenceRect(rect, runMatchImmediately = false)
                },
                onRunMatchFromReference = viewModel::runTemplateMatchingAndSwitchToCount,
                onPhotoTap = viewModel::onPlatePhotoTap,
                onLongPressAddPart = viewModel::addManualElementAt,
                onToggleManualAddMode = viewModel::toggleManualAddMode,
                onSelectAll = viewModel::selectAllOnCurrentPlate,
                onDeselectAll = viewModel::deselectAllOnCurrentPlate,
                onSensitivityChange = viewModel::updateSensitivity,
                onOpenNamingDialog = viewModel::openNamingDialog,
                onDismissNamingDialog = viewModel::dismissNamingDialog,
                onConfirmElementName = { elementName ->
                    viewModel.confirmPlateElementName(
                        elementName = elementName,
                        onAdvanceToNextPlate = { /* Stays in EDITOR on next plate */ },
                        onAllPlatesFinished = {
                            navController.navigate(Routes.SUMMARY) {
                                launchSingleTop = true
                            }
                        }
                    )
                },
                onAddAnotherPlatePhoto = {
                    viewModel.startCameraBatch(appendToCurrentSession = true)
                    navController.navigate(Routes.CAMERA)
                },
                onOpenSummary = {
                    navController.navigate(Routes.SUMMARY) {
                        launchSingleTop = true
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SUMMARY) {
            SessionSummaryScreen(
                session = editorState.activeSession,
                onEditPlate = { plateIndex ->
                    viewModel.selectPlateForEditing(plateIndex)
                    navController.navigate(Routes.EDITOR) {
                        launchSingleTop = true
                    }
                },
                onDeletePlate = viewModel::deletePlateFromActiveSession,
                onAddPlateCamera = {
                    viewModel.startCameraBatch(appendToCurrentSession = true)
                    navController.navigate(Routes.CAMERA)
                },
                onAddPlateGallery = {
                    appendGalleryToCurrentSession = true
                    multiPhotoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onUpdateSessionTitle = viewModel::updateSessionTitle,
                onBackToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
    }
}
