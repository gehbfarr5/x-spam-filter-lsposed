# 0.2.0 Android emulator UI acceptance request

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
  build_path: /Users/jin/Documents/Codex/2026-08-27/https-github-com-gehbfarr5-x-spam/outputs/x-spam-filter-0.2.0-debug.apk
test:
  scenario: Material 3 rule-management UI and local rule lifecycle
  preconditions: PLK110_API_36; explicit emulator-* serial; install exact SHA-256 candidate
  success_criteria: all checks below PASS with no crash, clipping, overlap, or inaccessible primary action
evidence:
  screenshot: true
  page_source: true
  logs: true
  video: false
```

The external orchestrator must use
`mobile-automation-infra/scripts/android-emulator-test.sh` with the exact APK,
package `dev.xspamfilter.lsposed`, activity
`dev.xspamfilter.lsposed.MainActivity`, AVD `PLK110_API_36`, and an explicit
`emulator-*` serial. It must not route to a physical device.

Required checks:

1. Launch the app in portrait. Capture Overview and verify safe status/navigation
   insets, four navigation targets, readable health card, metrics, diagnostic card,
   and snapshot card.
2. Open Sources. Verify Built-in, Community, and My Rules cards. Community must
   remain disabled before first confirmed sync; `Check update` must be visible.
3. Open Rules. Search, add one literal rule, add one ALL_OF rule (`她 + 骚 + 好看`),
   and verify invalid regex `[` is rejected without creating a snapshot.
4. Open the rule tester and verify
   `比她好看的没她骚比她骚的没她好看 @hh131wvu` is blocked after the ALL_OF
   rule is active, with source/type shown.
5. Verify the keyboard does not cover the dialog action at the largest supported
   font scale used by the harness.
6. Toggle the custom rule, confirm a new snapshot appears, and roll back to the
   preceding snapshot.
7. Open Logs and verify the empty state. Start and stop the 15-minute diagnostic
   mode; confirm Overview reports the correct state.
8. Repeat primary navigation in dark mode. Capture Overview, Rules, and the rule
   tester. Dynamic colors may vary, but contrast and hierarchy must remain clear.
9. Run `MainActivityTest` from the generated test APK. Record result and app logs.

No existing OnePlus 15 result may be carried forward: 0.2.0 changes the APK and
its checksum, provider, database, UI, and Hook bootstrap path.
