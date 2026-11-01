# NotifyHook Agent Instructions

## Build Rules

- When performing any build for verification or delivery, bump the app version in `app/build.gradle.kts` first so the generated artifact always matches a new release iteration.
- When the task requires a release build, use the obfuscated release flow instead of a plain release build.
- Prefer `./build-obfuscated-release.sh` in WSL/Linux and `build-obfuscated-release.bat` on Windows because these scripts produce the obfuscated release APK together with the related mapping file needed for debugging after `app/build.gradle.kts` enables R8 for the release build.

## Documentation Rules

- Keep `docs/ARCHITECTURE.md` in sync with structural changes (modules, data flow, storage keys, tech stack).
- Record significant decisions as ADRs in `docs/adr/` (context, alternatives, decision, consequences) and list them in `docs/adr/README.md`.
- Stored models serialized by Gson (`Hook`, `HookDelivery`, `HookLog`, `HttpHeader`) must keep default values for every constructor parameter; add a legacy-JSON case to `StoredModelCompatibilityTest` when adding fields. See ADR 0004.
