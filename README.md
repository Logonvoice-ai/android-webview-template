# android-webview-template

Single source-of-truth Android project used by the build pipeline to
generate a per-customer WebView app. **This repo never changes per app.**
Everything that varies between apps is injected before each CI build.

## How injection works

The backend does NOT edit Kotlin/XML source per app. It only rewrites
**`gradle.properties`**, replacing the `{{TOKEN}}` placeholders with real
values, then commits/pushes (or, in the scalable version, passes them as
`-P` flags to `gradle` directly — see note at the bottom).

| Placeholder | Type | Example | Used for |
|---|---|---|---|
| `{{APP_NAME}}` | string | `My Store` | App label, splash |
| `{{PACKAGE_NAME}}` | string | `com.mycompany.store` | `applicationId` / namespace |
| `{{VERSION_NAME}}` | string | `1.0.0` | Play Store version |
| `{{VERSION_CODE}}` | int | `1` | Play Store version code |
| `{{START_URL}}` | string | `https://store.com` | First URL loaded, and the domain that stays in-app (external domains open in system browser) |
| `{{THEME_COLOR}}` | hex color | `#0A84FF` | Status bar, splash background, progress bar, offline retry button |
| `{{SPLASH_ENABLED}}` | bool | `true` | Whether SplashActivity delays ~900ms before showing the WebView |
| `{{PERMISSION_CAMERA}}` | bool | `true` | Gates camera capture + `getUserMedia()` requests from the page |
| `{{PERMISSION_LOCATION}}` | bool | `false` | Gates `navigator.geolocation` requests from the page |
| `{{PERMISSION_STORAGE}}` | bool | `false` | Gates read/write storage permission on API ≤32 |

All permission-driven behavior is enforced at **runtime** in
`MainActivity.requestRuntimePermission`, not by conditionally editing the
manifest. All three dangerous permissions are always declared in the
manifest (see note in `AndroidManifest.xml`) but only requested if the
matching `BuildConfig` flag is true. This keeps the manifest static and
build-order-independent, at the cost of unused permissions showing up in
Play Console for apps that don't need them — acceptable for MVP, flagged
as a future upgrade.

## App icon

Custom icons are dropped in as file overwrites, not placeholders:

- `app/src/main/res/drawable/ic_launcher_foreground.xml` (or a rasterized
  PNG at the same logical path) — replace with the user's uploaded icon.
- `app/src/main/res/drawable/ic_launcher_background.xml` — usually left
  as the theme-color fill, or replaced if the user supplies a background.
- Legacy (API 24–25) `mipmap-hdpi/mdpi/xhdpi/xxhdpi/xxxhdpi/ic_launcher.png`
  are **not yet included** in this template — API 26+ adaptive icons are
  covered via `mipmap-anydpi-v26`. Generating the legacy PNG set (e.g. via
  a resize script in the pipeline) is a known follow-up item.

## Build types

All three come from the same build, no separate configuration:

```
./gradlew assembleDebug     # debug.apk   (testing, unsigned, .debug suffix)
./gradlew assembleRelease   # release.apk (signed if SIGNING_* env vars present)
./gradlew bundleRelease     # release.aab (Play Store upload)
```

Release signing reads from environment variables
(`SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`) — see `app/build.gradle`. These are injected as
CI secrets, never committed.

## What's intentionally NOT here yet

- Legacy launcher icon PNGs (see above).
- Push notifications / native plugin system — the `WebViewApplication`
  class is the seam for this later.
- The actual injection script and CI workflow live one layer up, since
  they're pipeline concerns, not template concerns:
  `.github/workflows/build.yml` in this repo triggers the build itself;
  the *rewriting* of `gradle.properties` happens in the backend before
  it pushes to this repo (or, in the per-user-repo model, to a fork/clone
  of it).

## Two deployment models

1. **MVP (current)**: backend clones this repo per build, rewrites
   `gradle.properties`, commits to a throwaway branch/repo, and triggers
   this repo's own `repository_dispatch` workflow.
2. **Scale (future)**: backend calls `gradle -PPACKAGE_NAME=... -PSTART_URL=...`
   directly on a Docker build worker with this repo checked out read-only —
   no git writes at all, no per-user repos, no GitHub API rate limits.
   The `gradle.properties` placeholder scheme works unchanged for both.
