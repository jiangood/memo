# Literal Memo – Agent Guide

## Project Overview
Minimalist Android note app (Kotlin/Jetpack Compose) with Git-based sync. No folders, no tags, no archive — just markdown files in `pile/` and `trash/`.

## Tech Stack
- **Kotlin** + **Jetpack Compose** (Material 3)
- **Hilt** (DI) + **KSP** (annotation processing)
- **Markwon** (rendering)
- **DataStore Preferences** (settings) + **EncryptedSharedPreferences** (GitHub token)
- **Target**: Android 8.0+ (API 26), compileSdk/targetSdk 35
- **No Google APIs, no Firebase, no tracking**

## Build & Run
```powershell
# Debug
./gradlew assembleDebug

# Release (requires signing config in local.properties or env vars)
./gradlew assembleRelease

# Install debug APK
./gradlew installDebug
```
Release signing: `local.properties` keys `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, or CI env vars `CI_KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`.

**IMPORTANT:** Local build requires ~2GB+ available RAM. On resource-constrained machines (e.g., <1GB RAM), use CI instead (see CI section below).

## CI / GitHub Actions

| Workflow | File | Trigger | Artifact |
|---|---|---|---|
| **Debug Build** | `.github/workflows/debug.yml` | Push/PR to `main` or `develop` | `debug-apk` (Actions artifact) |
| **Release Build** | `.github/workflows/release.yml` | Push tag `v*` | Uploaded to GitHub Release |

### Debug workflow
Fires on every push/PR to `main`/`develop`. Builds unsigned debug APK and uploads as a workflow artifact — downloadable from the Actions page.

### Release workflow
Fires when pushing a version tag (`v*`). Builds a signed release APK and attaches it to the corresponding GitHub Release.

**Required secrets for Release:**
| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 literal-memo.jks` output |
| `STORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`literal-memo`) |
| `KEY_PASSWORD` | Key password |

Set these in the repo → Settings → Secrets and variables → Actions.

### Local CI testing
CI workflows cannot be run locally. To verify YAML syntax: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/<file>.yml'))"`.

## Key Architecture

| Path | Role |
|---|---|
| `app/src/main/java/fumi/day/literalmemo/` | All source |
| `data/repository/MemoRepositoryImpl.kt` | File-backed memo CRUD (reads/writes `filesDir/pile/`) |
| `data/github/GitHubSyncManager.kt` | Two-way sync logic (local ↔ remote) |
| `data/github/GitHubRepository.kt` | Raw GitHub REST API calls |
| `data/git/GitForge.kt` | Interface abstraction for forge API |
| `ui/navigation/NavGraph.kt` | Routes: list → edit, settings |
| `ui/list/MemoListScreen.kt` | Main list + search + swipe-to-delete |
| `ui/edit/MemoEditScreen.kt` | Markdown editor |
| `ui/settings/SettingsScreen.kt` | Git config, theme, fonts |
| `data/prefs/UserPreferences.kt` | Settings via DataStore + EncryptedSharedPrefs |
| `data/DefaultMemoInitializer.kt` | Copies default memos from assets on first launch |

## Testing
- Unit tests: `./gradlew testDebugUnitTest` (only boilerplate exists)
- Instrumented tests: `./gradlew connectedDebugAndroidTest` (only boilerplate exists)

## Conventions
- **Kotlin code style**: `kotlin.code.style=official` in gradle.properties
- **File naming**: Memos stored as `<timestamp>.md` files in `pile/` directory
- **Sync**: Auto-sync on `onResume()` and after editing; first-to-sync-wins on conflict
- **No backup** (`android:allowBackup="false"`)
- **ProGuard** enabled for release; Hilt, ViewModel, coroutines kept
- **Version**: Bump in `app/build.gradle.kts` `versionCode`/`versionName`

## Important Constraints
- `AGENTS.md` and `CLAUDE.md` are gitignored — do not commit
- App namespace: `fumi.day.literalmemo`
- Min SDK 26 → no Java 8+ APIs that require higher (but `java.time` is available)
- EncryptedSharedPreferences for token storage only; general prefs in DataStore
- Default memos shipped in `assets/default_memos/` (README.md, User_Guide.md, Markdown_Guide.md)
