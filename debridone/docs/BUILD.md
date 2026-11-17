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

The macOS libmpv runtime is stored with Git LFS. `scripts/debridone/fetch-macos-runtime.sh` fetches
it for you: from this repository or upstream Nuvio Desktop with Git LFS, or over HTTPS when Git LFS
is not installed. Each file is checked against the SHA-256 recorded in the repository.

Manga's extension host runs on Node.js. `scripts/debridone/fetch-node-runtime.sh <platform>`
(`darwin-arm64`, `darwin-x64` or `linux-x64`) downloads the official Node.js 22.23.4 build from
nodejs.org, checks it against the SHA-256 recorded in the script and unpacks it under
`debridone/modules/manga/build/node-runtime/`. `dev.sh` and `build-macos-dev-dmg.sh` call it for
you; a Node.js installed on your machine is not used. The host's own packages are installed from
`debridone/modules/manga/host/package-lock.json` (with install scripts off) by the Gradle task
`:debridone:modules:manga:prepareMangaHost`, which needs the package registry once.

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
`macos-15` and `macos-26` (both arm64) and `macos-15-intel` (x86_64), smoke-tests the app bundle
(it must stay running and create its databases), runs the Manga and Music self-tests inside the
packaged app, starts the bundled Node.js from the mounted DMG, and uploads the DMGs as artifacts. Apple Silicon is
built on two images because GitHub's Apple Silicon runners are short on capacity at times and
cancel a job that gets no machine. Started by hand (Actions › Run workflow), the workflow can
build one architecture only.

It runs on every push to `nuvio-rebuild` that changes more than documentation. If no run appears
under the repository's Actions tab, check that Actions is enabled for the repository, then push a
commit to `nuvio-rebuild` yourself. The "Run workflow" button only appears once the workflow file
is on the default branch, so it becomes available after `nuvio-rebuild` is made the default branch.

The Keychain test runs in its own job, so a failure there is visible but does not hold back the
DMG. Installing a build is described in `INSTALL.md`; what a passing run does and does not prove
is in `LIMITATIONS.md`.

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
