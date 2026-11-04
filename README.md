# 3D Plate Piece Counter (Android)

A native Android application built with **Kotlin**, **Jetpack Compose (Material 3)**, **CameraX**, and **Room** for counting 3D-printed parts across multiple printer build plates.

## Key Features
1. **Multi-Plate Camera & Gallery Capture**:
   - Capture photos of one or multiple 3D printer build plates in a batch using the built-in CameraX viewfinder (with build-plate alignment guides), import photos from the device gallery, or launch a built-in **3-Plate Demo** batch.
2. **Choose How a Single Part Looks (`PlatePartDetector`)**:
   - Auto-segments candidate parts on the build plate into dashed boxes so you can tap any part—or drag and resize the interactive **Amber Reference Box**—to define how a single element looks.
   - Runs 100% offline, on-device multi-channel template & contour matching (Zero-Mean Normalized Cross-Correlation + opponent color contrast + Sobel edge magnitude + Non-Maximum Suppression) to locate all matching parts on the plate.
3. **Semitransparent Rectangles, Pinch-to-Zoom & Tap-to-Deselect**:
   - All detected elements on the plate are highlighted in **semitransparent Emerald rectangles** and **selected by default**.
   - Smooth **pinch-to-zoom (`1x`–`6x`) and pan** (plus one-handed zoom controls) to inspect small 3D-printed elements.
   - **Tap any element rectangle** to deselect it (switches to a muted rose dashed box excluded from the count) or tap again to re-select it.
   - **Long-press** (or toggle `+ Add Box`) to manually add a missed part, and adjust the **Sensitivity Slider** in real time.
4. **Element Naming & Previous Element Suggestions**:
   - After confirming the selected elements on a plate, the app prompts for the element name and automatically **suggests the previously named element** (1-tap reuse banner + pre-filled input + history chips).
5. **Sum by Element Name, Copy to Clipboard & Local Device Storage**:
   - Automatically sums all selected elements by name across all plates in the session.
   - One-tap **Copy Summed List to Clipboard** formatted for easy pasting.
   - Automatically stored in a local **Room SQLite database** on the device so past counts can be searched and viewed anytime from the Home screen.

## Building & Running
1. Open `C:\Users\Michal\Documents\piece-counter` in **Android Studio** (Iguana / Jellyfish / Koala or newer, JDK 17).
2. Let Android Studio sync Gradle and run the `app` configuration on an Android device or emulator (`minSdk 26`).
3. Run unit tests via Android Studio or Gradle:
   ```powershell
   ./gradlew testDebugUnitTest assembleDebug
   ```
