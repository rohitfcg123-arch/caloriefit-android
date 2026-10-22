# CalorieFit Android + Health Connect

This is a native Android wrapper for the existing CalorieFit GitHub Pages app. It reads today's aggregate steps from Health Connect after the user grants the READ_STEPS permission.

## Build on GitHub (no laptop required)

The repository workflow `.github/workflows/build-android.yml` builds a debug APK and uploads it as a GitHub Actions artifact. Open the repository's Actions tab, open the latest **Build CalorieFit Android APK** run, and download the APK artifact.

## Important
- Health Connect must be available and have steps data. On Android 14+ with supported system updates, Health Connect may record on-device steps once an app is granted READ_STEPS.
- The web app is loaded from GitHub Pages and communicates with this wrapper through a JavaScript bridge.
- This is an unsigned debug APK for personal testing, not a Play Store release.
