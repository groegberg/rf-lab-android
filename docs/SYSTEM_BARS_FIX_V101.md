# RF Lab v1.0.1-dev — Pixel 10 system bars / cutouts

## Problem

Pixel 10 Pro XL / modern Android targetSdk 35 forces edge-to-edge
application content. Several custom UI screens drew controls under the
system status bar at the top, the navigation/gesture bar at the bottom,
or display cutouts in landscape.

Setting `window.statusBarColor` does not itself reserve usable content
space under Android 15 edge-to-edge enforcement.

## Implementation

A shared `SystemBarsSafeArea` installed *after setContentView*:
- Explicitly opts into edge-to-edge with
  `window.setDecorFitsSystemWindows(false)`.
- Reads dynamically dispatched `WindowInsets.Type.statusBars()`,
  `navigationBars()` and `displayCutout()`.
- Adds those insets to each original root view padding instead of
  overwriting author-provided spacing. The original padding is captured
  exactly once; repeated insets dispatches do not stack.
- Supports landscape left/right cutouts, device rotation and gesture
  navigation without fixed status-bar height assumptions.
- Returns unconsumed WindowInsets so embedded Android views can still
  react if necessary.

Applied to all user-facing app activities:
- Camera2 photo capture, gallery, individual photo + DA3 review.
- Five-method Depth Anything/photostereo comparison.
- Legacy ARCore scan, 3D textured mesh viewer.
- Guided setup, spatial home, main application, diagnostics.
- Magnetic and acoustic sensing screens.

No changes to AI weights, camera/IMU metadata or private captured data.

## Manual phone QA

1. Install v1.0.1 and open camera scanner with gesture navigation enabled.
2. Verify back button, title, photo counter and status do **not overlap**
   the top bar (clock, battery icons and selfie camera area).
3. Verify camera shutter, Auto switch and Gallery remain fully visible
   and tappable above the bottom gesture region.
4. Open gallery, photo review and DA3 depth comparison; ensure all top
   controls, nav arrows and lower buttons are tappable.
5. Switch portrait -> landscape; verify side cutouts and navigation insets.
6. Test three-button navigation as well as gesture navigation if available.
7. Open Spatial Home / ARCore / magnetic / acoustic screens; check inset
   padding and scroll behaviour with Android font/display scaling.
8. Confirm screenshots show the full controls and no data was deleted.

Automated JVM tests cover idempotent padding math and landscape insets.
Android CI confirms compilation; only the actual Pixel device test can
confirm system inset dispatch and final UI placement.
