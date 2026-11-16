# Building DebridOne

DebridOne targets macOS (Apple Silicon first, Intel supported by the same build). Linux can run
the app from source for development; that is how the automated checks in this repository run.

## Requirements

| | macOS | Linux (development only) |
|---|---|---|
| JDK | Temurin 17 or newer | JDK 21 to run Gradle, plus a JDK 17 for the vendored media player toolchain |
| Native tools | Xcode command-line tools | `g++`, `pkg-config`, `cmake` |
| Libraries | bundled libmpv runtime (Git LFS) | `libmpv-dev libwebkit2gtk-4.1-dev libgtk-3-dev libx11-dev libxcomposite-dev libxext-dev libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev` |
| Other | Git LFS | — |

No Android SDK is needed for the desktop app. `local.properties` is optional.

## Get the source

```bash
git clone --branch nuvio-rebuild https://github.com/pehdry-dot/debridone.git
cd debridone
```

The macOS libmpv runtime is stored with Git LFS. The build scripts fetch it for you and fall back
to upstream Nuvio Desktop's LFS storage if this repository does not hold the objects.

## Run from source

```bash
./scripts/debridone/dev.sh
```

Use a throw-away data folder instead of your real one:

```bash
./scripts/debridone/dev.sh --sandbox
```

## Build an unsigned development DMG (on a Mac)

```bash
./scripts/debridone/build-macos-dev-dmg.sh
```

The DMG is written under `composeApp/build/compose/`. It is **not signed with a Developer ID and
not notarized**, so macOS will refuse to open it by double-click the first time. Right-click the
app and choose Open, or remove the quarantine flag:

```bash
xattr -dr com.apple.quarantine /Applications/DebridOne.app
```

## CI builds

`.github/workflows/debridone-macos-dev.yml` runs the tests, then builds unsigned DMGs on
`macos-15` (arm64) and `macos-15-intel` (x86_64), smoke-tests the app bundle (it must stay running
and create its databases) and uploads the DMGs as artifacts. Start it from the Actions tab or by
pushing to `nuvio-rebuild`.

## Signing and notarization

Not set up. Upstream's Gradle tasks for it are kept (`notarizeReleaseDmgWithKeychain`, driven by
`scripts/build-macos-release-dmgs.sh`). They need an Apple Developer ID Application certificate
and notarization credentials, supplied as:

- `NUVIO_MACOS_SIGNING_IDENTITY`
- `NUVIO_MACOS_NOTARY_KEYCHAIN_PROFILE` (or `NUVIO_MACOS_NOTARY_APPLE_ID`,
  `NUVIO_MACOS_NOTARY_TEAM_ID`, `NUVIO_MACOS_NOTARY_PASSWORD`)

Do not describe a build as signed or notarized unless `codesign -dv` and `spctl --assess` confirm it.

## Optional keys (`local.properties`)

Streaming works from your addons without any keys. These enable Nuvio's optional integrations and
must be your own; upstream's keys are not in the source tree.

| Key | Enables |
|---|---|
| `TMDB_API_KEY` | TMDB metadata enrichment |
| `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` | Trakt (redirect URI `debridone://auth/trakt`) |
| `SIMKL_CLIENT_ID` | Simkl (redirect URI `debridone://auth/simkl`) |
| `MDBLIST_CLIENT_ID`, `PREMIUMIZE_CLIENT_ID` | MDBList, Premiumize sign-in |
| `NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY` | A Nuvio-compatible sync server. Setting these switches accounts back to upstream's remote mode (untested in DebridOne). |

Never commit `local.properties`.

## Regenerating brand images

```bash
python3 scripts/debridone/generate-brand-assets.py
```
