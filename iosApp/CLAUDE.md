# iosApp (harness)

- AVCaptureSession capture in Swift lives here. Convert `CVPixelBuffer` with `LandmarkKit`'s `Frame` helper, then pass to the detector.
- Owns the test `.task` model (in the app bundle) and passes its path to `LandmarkKit` config.
- Cannot be built on Windows: report "iOS unverified" after edits (see root CLAUDE.md).
