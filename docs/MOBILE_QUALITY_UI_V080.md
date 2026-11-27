# RF Lab v0.8.0-dev: geometry quality + minimal scan UI

**Experimental, completely on-device.** This increment responds to two field-test
failures: inaccurate/unreliable 3D geometry and a text-heavy scanner. Existing
RF/acoustic/magnetic/Smart Home features remain in place. No cloud processing.

## New geometry pipeline

1. Select ARCore's default supported camera lens (typically the main camera)
   rather than automatically switching to the widest lens. If ARCore offers
   a higher-resolution 30fps CPU camera configuration on the *same* camera ID,
   prefer it only when AUTOMATIC Depth is still supported. This improves
   input-image detail but does not itself guarantee geometric accuracy.
2. Use the stored calibrated v0.7 RGB-D keyframes, poses and raw/full depth.
3. For each mesh vertex, reproject the world-space point into up to 4 nearby
   *different* viewpoints with a useful camera baseline. Compare its predicted
   depth to the observed depth in that image. Report confirmed/unobserved/
   contradicted observations separately. Strong raw contradictions, and
   contradictions on interpolated depths, are rejected.
4. Fit large approximately planar horizontal and arbitrary-orientation
   vertical structures from **confident RAW points only** with robust
   clustering/RANSAC and least-squares PCA refinement.
5. Move **only interpolated Full Depth** vertices up to 4 cm onto supported
   structural planes; never silently move the raw observed vertices.
6. Render the resulting camera-textured patches with OpenGL depth testing.
   Expose counts for confirmed/rejected and plane-regularized observations.

This is multi-view geometric validation + architectural plane constraints,
*not* a TSDF volume, SLAM loop closure, an AI depth model or Gaussian Splatting.
Still-overlapping frame meshes can cause z-fighting. Missing areas will not be
invented; drift and depth bias can still produce inaccurate dimensions.

## Minimal, camera-first UI

- No vertical scrolling on the scan screen. Live camera occupies approximately
  58%, colour 3D map approximately 42% of the available workspace, adapting
  from vertical portrait to side-by-side landscape layouts.
- Short floating tracking/coverage indicator and one-sentence scan guidance.
- Primary bottom controls: Scan/Stop and Finish.
- Overlay actions: RGB/height colours, 3D mesh.
- Overflow menu: detailed diagnostics and explicit private raw-data ZIP export.
- First setup stage opens the camera prominently, shows a taller 3D preview,
  and removes repetitive paragraphs and explanatory cards.
- Mesh viewer fills the display; compact title/close button and small quality
  readout are overlays.

## Pixel 10 Pro XL validation protocol

1. Save existing home data and RGB-D keyframes before installing. Reinstalling
   a differently signed debug APK can delete private local recordings.
2. Scan the **same room** under the **same illumination** with v0.7 and v0.8.
   Move laterally, keeping depth targets 0.5-5 m away where possible.
3. In v0.8 confirm that **camera and 3D map are visible simultaneously** and
   no scrolling is required to reach Scan/Stop or Finish.
4. Record raw/full depth counts, keyframes and rejected/confirmed 3D vertices
   from the on-device mesh viewer; check that walls and floor become flatter
   and artifacts are reduced.
5. Compare at least **three tape- or laser-measured reference dimensions**:
   two perpendicular wall lengths and a shorter furniture measurement.
   Record signed error in centimeters, not just subjective appearance.
6. Check colour alignment, dark and blank walls, reflective surfaces,
   memory/thermal stability, and handling of tracking loss.
7. Confirm RF/smart-home workflows and scan JSON backups still operate.

## Still necessary before accepting metric quality

- Correct large errors in ARCore pose estimation and register repeated passes
  of the room (local bundle adjustment / loop closure).
- Implement a memory-bounded *true* surface-fusion representation (TSDF or
  surfel) that does not stack independent depth-mesh patches.
- Add an independently sourced **on-device depth model** such as a suitable
  LiteRT Depth Anything 3 Small conversion (model weights and licensing must
  be verified, runtime/FP16 and camera aspect measured on Pixel 10 Pro XL).
- Use monocular neural depth only for **missing geometry**, with scale aligned
  to raw ARCore measurements and multi-view consistency checks. Never use
  unconstrained AI estimates as reliable metric distances.
- Gaussian Splatting should come after geometric validation as a visual
  enhancement, not a substitute for metric scanning.
