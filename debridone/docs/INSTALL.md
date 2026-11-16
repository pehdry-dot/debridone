# Installing DebridOne on a Mac

DebridOne is not signed with an Apple Developer ID and is not notarized. These are development
builds. Two ways to get one.

## A. Build it on your Mac

You need the Xcode command-line tools and a JDK 17 or newer. Git LFS is optional.

```bash
xcode-select --install
```

```bash
git clone --branch nuvio-rebuild --depth 1 https://github.com/pehdry-dot/debridone.git
```

```bash
cd debridone
```

```bash
./scripts/debridone/build-macos-dev-dmg.sh
```

The script downloads the bundled video runtime (checking every file against its recorded
SHA-256), builds for your Mac's processor and prints where the `.dmg` and the `.app` are. The
first build downloads the build dependencies and takes a while.

To keep the JDK and Gradle's caches out of your home folder, point the script at them:

```bash
JAVA_HOME=/path/to/jdk-17 GRADLE_USER_HOME="$PWD/.gradle-home" ./scripts/debridone/build-macos-dev-dmg.sh
```

Open the `.dmg` and drag DebridOne to Applications. An app you built yourself opens with a
double-click.

## B. Download a build made by GitHub Actions

Open the repository's Actions tab, pick the latest successful "DebridOne macOS dev build" run and
download the artifact for your Mac (`arm64` for Apple Silicon, `x86_64` for Intel). You need to be
signed in to GitHub. Unzip it, open the `.dmg` and drag DebridOne to Applications.

macOS blocks a downloaded app that is not notarized, and may describe it as damaged. To allow it,
remove the download flag once after copying the app to Applications:

```bash
xattr -dr com.apple.quarantine /Applications/DebridOne.app
```

Alternatively, try to open the app, then go to System Settings › Privacy & Security and choose
"Open Anyway". (Right-click › Open no longer bypasses this on recent macOS versions.)

## If you already have an app called DebridOne

The earlier web prototype was also named DebridOne. Dragging the new app into Applications
replaces it. Rename the old one first if you want to keep it.

Both keep data in `~/Library/Application Support/DebridOne`. The new app only adds its own
`accounts`, `modules` and `backups` folders there and does not read or change the prototype's
files. The prototype's library and accounts are not imported.

## Removing DebridOne

Delete `/Applications/DebridOne.app`. To remove your data as well, delete the `accounts`,
`modules` and `backups` folders in `~/Library/Application Support/DebridOne` and the folder
`~/Library/Caches/DebridOne`.
