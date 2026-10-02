# shared (harness, common UI)

- Common Compose screens only: result display, landmark overlay, settings.
- No camera code and no platform camera dependencies here. Frames come from `androidApp` / `iosApp`.
- Depends on `landmarkkit` public API only; never reach into its `internal` types.
