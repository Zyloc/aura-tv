# Testing & Verification Rules

- **Always add integration tests:** Every new feature, workflow change, or bug fix MUST include end-to-end integration test coverage (e.g., under `probe/test/com/aura/tvprobe/integration/` and registered in `AuraTestSuiteRunner.java`), not just isolated unit tests.
- Run `./scripts/run_tests.sh` and confirm both unit and integration suites pass (`0 failed`) before considering work complete.
