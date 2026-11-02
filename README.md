# Kitaab

Android PDF reader with cross-device reading sync (Google Drive + Firebase).

**Build and updates:** GitHub Actions builds, tests and signs the app and publishes it to GitHub Releases.
The app updates itself from inside (Settings > App updates). To build, double-click `START-HERE.bat` or
`Rebuild-APK.bat` - see [SETUP.md](SETUP.md). No Android Studio or command line is needed.

- Version name: `version.properties`. Version code: `VERSION_CODE_OFFSET` + the GitHub run number.
- Workflows: `.github/workflows/build.yml` (tests + debug APK) and `release.yml` (signed release).
- Release notes containing `[required]` make the update required on every phone.
