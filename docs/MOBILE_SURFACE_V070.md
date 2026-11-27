# RF Lab Spatial Home v0.7.0-dev — fully on-device camera-textured surfaces

Development milestone built on v0.6.0-dev. All processing stays inside
the Android phone: no server, no external GPU, no root.

## Implemented

- Motion-aware ARCore RGB-D keyframe selection (~1.6 s minimum spacing,
  ~18 cm baseline or camera rotation; 6 s still-scene fallback).
- Each keyframe contains a real CPU camera image as JPEG, full (interpolated)
  and raw (sparse) 16-bit depth in millimeters, raw confidence, metric pose,
  calibrated depth intrinsics, and ARCore texture-to-CPU-image mapping.
- All image bytes are copied while the ARCore frame is current. JPEG
  conversion and ZIP persistence are on a bounded background executor.
  Maximum 180 keyframes and 320 MiB of archives.
- On-device triangulation rejects missing depth and major discontinuities;
  at each vertex sufficiently confident raw depth takes priority.
- GL ES 2.0 viewer draws the actual camera image as a texture on triangles
  with a depth buffer, orbit and pinch-to-zoom.
- Preview loads a representative subset of up to 36 keyframes to stay
  within conservative Android memory/GPU budgets.
- Explicit RGB-D-Rohdaten sichern (.zip) export uses Android SAF, separate
  from the small ordinary Home JSON backup because it includes indoor
  photographs, poses and geometry.
- Existing colour-point cloud, RF, magnetometer, acoustic, smart-home and
  simulation features remain available.

## Pixel 10 Pro XL field test

1. Export the previous Home state; debug APK signatures may differ.
2. Open the guided 3D scanner in good light and move sideways slowly,
   capturing floor, walls and furniture from several viewpoints.
3. Check that Raw / Full / RGB and keyframe counts grow. Aim initially
   for 10-20 captured RGB-D keyframes.
4. Stop the scan and select Kameratexturiertes 3D-Mesh ansehen.
5. Rotate/zoom; verify colour belongs to the right wall/furniture, is
   correctly oriented and does not show large false connecting triangles.
6. Compare tape/laser-measured distances to camera/geometry results,
   documenting tracking drift, frame overlap, holes and thermal behaviour.
7. Use RGB-D-Rohdaten sichern (.zip) to save the complete original
   keyframes to user-chosen storage before reinstalling the APK.
8. Unzip the export: manifest.json and keyframes/kf_*.zip, each inner ZIP
   has meta.json, color.jpg, full_depth16_le.bin, raw_depth16_le.bin and
   raw_confidence8.bin. The files are local/offline.
9. Reopen the app and verify it can display its locally stored mesh frames;
   previous v0.5/v0.6 scan/room files must still load.

## Limits and precision

- This is a calibrated, camera-textured multi-view surface mesh assembled
  from individual depth frames. It is NOT a globally optimised or fused TSDF
  mesh. Overlapping patches can z-fight, wobble or have pose drift.
- Full depth is interpolated and may be inaccurate on mirrors, glass or
  textureless walls. Raw Depth comes from ARCore optical estimation, not LiDAR.
- No bundle adjustment, loop closure, multi-session alignment, metric
  reference validation, neural Depth Anything or Gaussian Splatting training.
- The maximum of 36 frames applies only to rendering; keyframes are
  stored separately up to their documented limit.
- The new keyframe ZIP export is separate and currently export-only; a
  full re-import feature needs implementation later.
- Hardware performance, ARCore capture stability, exact texture UV
  orientation and spatial accuracy require testing on Pixel 10 Pro XL.

## Next increment

Build a GPU/CPU sparse on-device TSDF or surfel fusion backend with
view-consistent texture selection, pose refinement, and room-plane fitting.
Gaussian Splatting can then enhance photorealistic rendering without
becoming the basis for metric distance measurements.
