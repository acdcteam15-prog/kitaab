# Kitaab - build and update setup (no Android Studio needed)

You only use two files on Windows. Double-click them. Nothing is built on your PC: GitHub builds and signs the app.

## First time

1. Unzip the project somewhere easy (for example `C:\Kitaab`). Keep `START-HERE.bat` next to the `app` folder.
2. Double-click **START-HERE.bat**.
3. Choose **1) Full setup and build**. The wizard then asks, one at a time:
   1. It installs **Git** and the **GitHub CLI** if they are missing (a few minutes).
   2. **GitHub sign-in:** a browser opens. Sign in (free account) and type the code the window shows.
   3. **Repository:** choose 1 for a new one, or 2 to use one that already exists (for example when you unzip a new version of the project).
      Pick **public** if you want every phone to update without any token.
   4. **Signing key.** This decides whether the app already on your phone can update in place:
      - **1 - Android Studio debug key:** choose this if the app on your phone was installed from Android Studio. The app updates in place and your data stays.
      - **2 - your own .jks file:** the wizard asks for the file, password and alias.
      - **3 - a new key:** the app on the phone must be uninstalled once (its data is lost), then the new APK is installed.
      The key is stored in GitHub as secrets. The wizard never prints it.
   5. It uploads the project and asks for **release notes** (one short line). Add `[required]` to force an update on every phone.
   6. It starts the build and waits. The first build takes about 10-15 minutes.
4. When it says **DONE**, the folder `APK` opens with the signed APK. Install it on your phone once.

## Every later update

1. Change the app files (or paste a new zip over the project folder).
2. Optionally change `VERSION_NAME` in `version.properties` (for example `1.2.0`).
3. Double-click **Rebuild-APK.bat**, type the release notes, wait for **DONE**.

Phones that already have version 1.1.0 or newer show an **Update available** banner on the Home screen
and a notification (checked every 12 hours, and when the app opens). Tap it, then **Download and install**.
The first time, Android asks you to allow installing from Kitaab: tap the button, switch it on, press Back.

## If the build fails

The wizard writes a short **build-error.txt** next to START-HERE.bat. Send me its text and I fix it.

## If you closed the window while a build was running

Run **START-HERE.bat** and choose **3) Follow a build that is already running**.

## Good to know

- **Private repository:** the app needs a GitHub token to see updates. In the app: Settings > App updates > GitHub token.
  Create it on github.com (Settings > Developer settings > Fine-grained tokens), only for this repository, permission *Contents: Read-only*.
- **Same key every time.** If the key changes, Android refuses the update. Keep a copy of the key file and of `setup\state.json`.
- **Google sign-in:** the key's SHA-1 must be registered in the Firebase / Google Cloud console. The debug key (option 1) already is, if the app works today. For option 2 or 3 the wizard prints the SHA-1 to add.
- `google-services.json` is part of the project (the build needs it). In a public repository it is visible; restrict the API key to the app's package and SHA-1 in Google Cloud.
- Delete the stray `Booky\Booky` folder inside the project if you still have it (an old zip left it behind). It is ignored by git.
