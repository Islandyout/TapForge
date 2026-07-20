# TapForge — auto clicker / swiper for Android

Automates taps, long-presses and swipes over ANY app or game using Android's
AccessibilityService gesture API (same mechanism AG Auto Clicker uses).
No ads, no premium locks, everything unlocked: multi-point, swipe, long-press,
loop limits, anti-detection jitter, save/load script.

## Get the APK (no Android Studio needed)

1. Create a new GitHub repo (e.g. `tapforge`) and upload this whole folder.
2. Go to the repo's **Actions** tab — the "Build APK" workflow runs automatically.
3. When it finishes (~3 min), open the run and download the **TapForge-debug-apk** artifact.
4. Unzip it, copy `app-debug.apk` to your phone, install (allow "install unknown apps").

## First-time setup on the phone

1. Open TapForge → tap **step 1** → find TapForge in the Accessibility list → switch ON.
2. Back in the app, tap **step 2** to show the floating controller bar.

## Using it

| Button | Action |
|---|---|
| ▶ | start / pause the loop |
| ＋ | add tap target (drag anywhere on screen) |
| ⇢ | add swipe (drag point A and point B) |
| ⊕ | add long-press target |
| － | remove last target |
| ⚙ | interval, swipe/hold duration, loop count, anti-detection jitter, save/load script |
| ✥ | drag the bar itself |

Targets fire in numbered order, then loop. During a run the markers go
semi-transparent and stop blocking touches so gestures pass through to the game.

## Notes

- Anti-detection = random ± ms on timing and ± px on position each action.
- Minimum interval is clamped to 10 ms (below ~40 ms some phones stutter).
- Many online games prohibit automation in their ToS — competitive multiplayer
  can get accounts banned. Single-player / idle grinding is where this shines.
