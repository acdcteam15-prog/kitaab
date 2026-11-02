# Kitaab — change report

This zip is the **complete project**. Build outputs (`build/`, `.gradle/`) are left out; Android Studio regenerates them.

**53 new · 33 modified · 39 untouched · 0 deleted**

## Verification performed

| Check | Result |
|---|---|
| Sync-rule unit tests (`./gradlew testDebugUnitTest`) | 18 / 18 pass |
| Live checks on real code: crop, curl geometry, Arabic mapping, titles, brightness | 19 / 19 pass |
| Live checks: smart shelves rules, reminder timing (incl. month rollover) | 12 / 12 pass |
| Backup ↔ restore field round-trip | All fields match |
| DB migrations 3→4→5→6 on a real SQLite v3 database | Pass — data kept, duplicates removed |
| Kotlin syntax (all files) / XML / resources / view bindings | Pass |
| Android APIs vs minSdk 26 | Pass (1 earlier issue fixed) |
| Full APK build | **Not possible here** (Android SDK blocked) — run a Gradle build first |

## New files

- `app/proguard-rules.pro` — R8 keep rules (file was missing)
- `app/src/main/baseline-prof.txt` — Startup baseline profile
- `app/src/main/java/com/pdfreader/app/data/backup/BackupManager.kt` — Backup to Drive (keeps 8) + merge-safe restore
- `app/src/main/java/com/pdfreader/app/data/local/HighlightDao.kt`
- `app/src/main/java/com/pdfreader/app/data/local/SyncEventDao.kt`
- `app/src/main/java/com/pdfreader/app/data/model/Highlight.kt`
- `app/src/main/java/com/pdfreader/app/data/model/SyncEvent.kt`
- `app/src/main/java/com/pdfreader/app/data/sync/DriveStore.kt` — Drive file operations + modified-time cache
- `app/src/main/java/com/pdfreader/app/data/sync/SyncMerge.kt` — Pure sync conflict rules (unit-tested)
- `app/src/main/java/com/pdfreader/app/sync/NetworkMonitor.kt` — Validated-internet monitor; sync when back online
- `app/src/main/java/com/pdfreader/app/ui/finished/FinishedShelfActivity.kt` — Finished bookcase + Year in books
- `app/src/main/java/com/pdfreader/app/ui/home/CoverRowAdapter.kt`
- `app/src/main/java/com/pdfreader/app/ui/home/GoalRingView.kt` — Daily goal ring on Home
- `app/src/main/java/com/pdfreader/app/ui/reader/BrightnessController.kt` — Perceptual brightness, extra dim, HUD
- `app/src/main/java/com/pdfreader/app/ui/reader/CropDetector.kt` — Margin detection + crop zoom maths
- `app/src/main/java/com/pdfreader/app/ui/reader/HighlightDrawView.kt` — Highlighter drag overlay
- `app/src/main/java/com/pdfreader/app/ui/reader/PageBitmapCache.kt` — Page images for the curl (crop-aware)
- `app/src/main/java/com/pdfreader/app/ui/reader/PageCurlLayout.kt` — Finger-following page curl + brightness edge gesture
- `app/src/main/java/com/pdfreader/app/ui/reader/PinMarkerView.kt` — Ribbon / line / band marker with animations
- `app/src/main/java/com/pdfreader/app/ui/reader/SeekPreviewRenderer.kt` — Seekbar page thumbnails
- `app/src/main/java/com/pdfreader/app/widget/ReadingWidget.kt` — Home-screen widget
- `app/src/main/java/com/pdfreader/app/worker/DownloadWorker.kt` — Background downloads with progress / Wi-Fi only
- `app/src/main/java/com/pdfreader/app/worker/GoalReminderWorker.kt` — Daily goal reminder notification
- `app/src/main/res/anim/hold_home.xml`
- `app/src/main/res/anim/item_fade_up.xml`
- `app/src/main/res/anim/layout_fade_up.xml`
- `app/src/main/res/anim/reader_exit.xml`
- `app/src/main/res/drawable/bg_badge_done.xml`
- `app/src/main/res/drawable/bg_badge_ok.xml`
- `app/src/main/res/drawable/bg_cover_scrim.xml`
- `app/src/main/res/drawable/bg_header.xml`
- `app/src/main/res/drawable/bg_hero.xml`
- `app/src/main/res/drawable/bg_hud.xml`
- `app/src/main/res/drawable/bg_hud_track.xml`
- `app/src/main/res/drawable/bg_pill.xml`
- `app/src/main/res/drawable/bg_reader_bottom.xml`
- `app/src/main/res/drawable/bg_reader_top.xml`
- `app/src/main/res/drawable/bg_search.xml`
- `app/src/main/res/drawable/bg_skeleton.xml`
- `app/src/main/res/drawable/bg_spine.xml`
- `app/src/main/res/drawable/bg_widget.xml`
- `app/src/main/res/drawable/ic_palette.xml`
- `app/src/main/res/drawable/ic_tune.xml`
- `app/src/main/res/layout/activity_finished_shelf.xml`
- `app/src/main/res/layout/item_book_cover.xml`
- `app/src/main/res/layout/item_menu_row.xml`
- `app/src/main/res/layout/item_menu_tile.xml`
- `app/src/main/res/layout/item_shelf_book.xml`
- `app/src/main/res/layout/sheet_reader_menu.xml`
- `app/src/main/res/layout/widget_reading.xml` — Widget layout
- `app/src/main/res/values-night/colors.xml`
- `app/src/main/res/xml/widget_reading_info.xml` — Widget definition
- `app/src/test/java/com/pdfreader/app/data/sync/SyncMergeTest.kt` — 18 unit tests for the sync rules

## Modified files

- `app/build.gradle.kts` — shrinkResources, profileinstaller, unit tests
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/pdfreader/app/KitaabApplication.kt` — App-wide sync triggers, weekly auto-backup, reminder schedule
- `app/src/main/java/com/pdfreader/app/data/local/AppDatabase.kt` — DB v6 + migrations 3→4, 4→5, 5→6 (destructive fallback removed)
- `app/src/main/java/com/pdfreader/app/data/local/BookCategoryDao.kt`
- `app/src/main/java/com/pdfreader/app/data/local/BookmarkDao.kt`
- `app/src/main/java/com/pdfreader/app/data/local/ProgressDao.kt`
- `app/src/main/java/com/pdfreader/app/data/model/Book.kt`
- `app/src/main/java/com/pdfreader/app/data/model/Bookmark.kt`
- `app/src/main/java/com/pdfreader/app/data/model/ReadingProgress.kt`
- `app/src/main/java/com/pdfreader/app/data/repository/DriveRepository.kt`
- `app/src/main/java/com/pdfreader/app/data/repository/ProgressRepository.kt` — Sync engine: one lock, push-then-pull, read-merge-write, field-level merge, bookmark/highlight sync, history, other-device signal
- `app/src/main/java/com/pdfreader/app/sync/FirebaseSyncManager.kt`
- `app/src/main/java/com/pdfreader/app/ui/home/BookAdapter.kt`
- `app/src/main/java/com/pdfreader/app/ui/home/HomeActivity.kt` — Home redesign, smart shelves, goal ring & reminder, backup/restore, downloads, other-device prompt, cover transition, widget open
- `app/src/main/java/com/pdfreader/app/ui/reader/BookmarkAdapter.kt`
- `app/src/main/java/com/pdfreader/app/ui/reader/ReaderActivity.kt` — Reader: checkpoints, marker, finished, curl, Arabic order, crop, brightness, immersive, seek preview, options sheet, highlights, daily page count
- `app/src/main/java/com/pdfreader/app/ui/settings/SettingsActivity.kt`
- `app/src/main/java/com/pdfreader/app/ui/stats/StatsActivity.kt`
- `app/src/main/java/com/pdfreader/app/ui/stats/SyncLogActivity.kt` — Sync History screen
- `app/src/main/java/com/pdfreader/app/utils/PreferenceManager.kt`
- `app/src/main/java/com/pdfreader/app/worker/SyncPullWorker.kt`
- `app/src/main/java/com/pdfreader/app/worker/SyncWorker.kt`
- `app/src/main/res/layout/activity_home.xml`
- `app/src/main/res/layout/activity_reader.xml`
- `app/src/main/res/layout/activity_settings.xml`
- `app/src/main/res/layout/item_book.xml`
- `app/src/main/res/menu/menu_home.xml`
- `app/src/main/res/menu/menu_reader.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`

## Untouched files

- `.gitignore`
- `app/google-services.json`
- `app/settings.gradle.kts`
- `app/src/main/java/com/pdfreader/app/data/model/BookCategory.kt`
- `app/src/main/java/com/pdfreader/app/data/model/ReadingTheme.kt`
- `app/src/main/java/com/pdfreader/app/ui/auth/LoginActivity.kt`
- `app/src/main/java/com/pdfreader/app/ui/shelves/ShelfDetailActivity.kt`
- `app/src/main/java/com/pdfreader/app/ui/shelves/ShelvesActivity.kt`
- `app/src/main/java/com/pdfreader/app/ui/stats/HeatmapActivity.kt`
- `app/src/main/java/com/pdfreader/app/utils/WakeLockManager.kt`
- `app/src/main/res/drawable/ic_bookmark_filled.xml`
- `app/src/main/res/drawable/ic_bookmark_outline.xml`
- `app/src/main/res/drawable/ic_launcher_background.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/res/drawable/ic_pdf_placeholder.xml`
- `app/src/main/res/drawable/pin_indicator.xml`
- `app/src/main/res/layout/activity_heatmap.xml`
- `app/src/main/res/layout/activity_login.xml`
- `app/src/main/res/layout/activity_shelf_detail.xml`
- `app/src/main/res/layout/activity_shelves.xml`
- `app/src/main/res/layout/activity_stats.xml`
- `app/src/main/res/layout/activity_sync_log.xml`
- `app/src/main/res/layout/bottom_sheet_book_details.xml`
- `app/src/main/res/layout/item_bookmark.xml`
- `app/src/main/res/layout/item_shelf.xml`
- `app/src/main/res/layout/item_sync_log.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `build.gradle.kts`
- `gradle.properties`
- `gradle/gradle-daemon-jvm.properties`
- `gradle/libs.versions.toml`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `local.properties`
- `settings.gradle.kts`
