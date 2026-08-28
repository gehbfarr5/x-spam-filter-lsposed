# 0.3.0 Android emulator UI acceptance request

```yaml
platform: android
device_stage: emulator
operation_type: development_test
preferred_backend: appium
fallback_backend: none
risk_level: low
requires_real_hardware: false
requires_user_confirmation: false
app:
  package_or_bundle_id: dev.xspamfilter.lsposed
  build_path: /Users/jin/Documents/Codex/2026-08-27/https-github-com-gehbfarr5-x-spam/outputs/x-spam-filter-0.3.0-debug.apk
  sha256: f5bc1113a34c7b5bced1df05a4881ee6cf9229cc57953f892e50298a14ab7749
  test_build_path: /Users/jin/Documents/Codex/2026-08-27/https-github-com-gehbfarr5-x-spam/outputs/x-spam-filter-0.3.0-debug-androidTest.apk
  test_sha256: dcd73c847c307b23e89bfcd2165e74142cecb1aa2a8efc97b3e172b2ce77de3d
test:
  scenario: Material 3 multi-source subscription UI and startup health
  preconditions: PLK110_API_36; explicit emulator-* serial; install exact SHA-256 candidate
  success_criteria: all checks below PASS with no crash, clipping, overlap, or inaccessible action
evidence:
  screenshot: true
  page_source: true
  logs: true
  video: false
```

The external orchestrator must use the Android emulator Appium backend with AVD
`PLK110_API_36`, an explicit `emulator-*` serial, package
`dev.xspamfilter.lsposed`, and activity `dev.xspamfilter.lsposed.MainActivity`.
It must install the exact APK and checksum above and must not route to a physical
device.

Required checks:

1. Cold-launch in portrait and confirm there is no provider/startup crash. Capture
   Overview with safe status/navigation insets and all four navigation targets.
2. Open Sources. Capture the screen and verify `ZPVIP 内置快照`, `ZPVIP 在线词库`,
   `x-comment-blocker 常规词库`, and `我的规则` are present. Both remote presets
   must remain disabled before their first confirmed sync.
3. Tap `订阅新来源`. Capture the dialog and verify name, HTTPS rule-file address,
   optional license, cancel, and add actions. Focus the URL field and confirm the
   IME does not cover either dialog action.
4. Enter an invalid non-HTTPS address and verify adding it produces an explicit
   validation message without creating a source.
5. Add a valid GitHub blob-form URL and verify it appears as a disabled source
   requiring an update check. Remove that user-added source and verify the preset
   sources remain intact.
6. Repeat Sources and the add-source dialog in dark mode and at the harness's
   increased font scale. Verify readable contrast, 48 dp interaction targets,
   scrolling, and no clipped labels or controls.
7. Run `MainActivityTest` from the generated test APK and record the result plus
   app crash logs.

The stock emulator cannot prove LSPosed injection or heartbeat delivery. Those
remain a OnePlus 15 gate after this UI task passes.
