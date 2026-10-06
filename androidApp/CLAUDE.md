# androidApp (app module)

- CameraX capture lives here. Convert frames with `landmarkkit`'s `Frame` helpers, then pass to the detector.
- Owns the test `.task` model (in this app's assets) and passes its name/path to `landmarkkit` config.
