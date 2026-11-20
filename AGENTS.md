# Testing & Verification Rules

- **Always add integration tests:** Every new feature, workflow change, or bug fix MUST include end-to-end integration test coverage (e.g., under `probe/test/com/aura/tvprobe/integration/` and registered in `AuraTestSuiteRunner.java`), not just isolated unit tests.
- Run `./scripts/run_tests.sh` (with `AURA_SKIP_TV_CHECK=1` when not deploying) and confirm both unit and integration suites pass (`0 failed`) before considering work complete.
- **TV Deploy & Testing Policy:** Silent background deploys (`adb install -r`) and silent HTTP API testing (`/api/state`, non-companion `/api/chat` with session cleanup) are permitted without asking, provided they NEVER disturb playback, audio, overlays, or foreground apps. Never launch `AuraActivity`, send remote/DPAD keys, trigger TTS/overlays, or take disruptive actions on the TV without explicit permission.
