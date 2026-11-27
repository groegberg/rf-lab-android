# RF Lab v0.6.0-dev — on-device RGB-D foundation

This is the **first implementation milestone**, not a completed textured mesh,
TSDF reconstruction, on-device neural depth model or Gaussian Splatting system.
The existing RF, magnetometer, audio and Smart Home functions remain in place.

## Implemented in this branch

- Sample more ARCore Raw Depth per *new* timestamp, rather than an ~14×11
  sparse grid. Configurable bounded target ~3,200 raw candidates per frame.
- Sample ~1,000 extra candidates from interpolated Full Depth. These
  are lower-confidence estimates, and **never overwrite raw** in the same voxel.
- Align depth texture coordinates to CPU camera image pixels using ARCore
  transformCoordinates2d(TEXTURE_NORMALIZED, IMAGE_PIXELS) to handle crop
  differences; sample YUV_420_888 RGB when a CPU image is available.
- Limit capture attempts to roughly 4 per second to protect AR tracking.
- Confidence-weighted, 5-cm, 80,000-cell RGB-D *point* fusion.
- Render real camera colours or switch back to the height-colour view.
- Display point, coloured, measured, Raw/Full frame diagnostics.
- Persist RGB/source/observations in optional scan JSON fields with the
  unchanged original backup schema (legacy v0.5 records remain readable).
- Add JVM unit tests for voxel indexing, raw-depth precedence and RGB averaging.

## Pixel 10 Pro XL field test

1. Open guided **3D Scan**, use good even light, point at textured surfaces.
2. Move *sideways* slowly; avoid mirrors, glass and glossy flat panels.
3. Verify live Raw, Full and RGB counts are non-zero (RGB may be temporarily
   missing while ARCore cannot provide a CPU YUV image).
4. Rotate the view and toggle camera/height colours. Check that RGB follows
   physical features correctly (no rotated or stretched colour patterns).
5. Scan a wall, floor and piece of furniture; finish and reopen the result.
6. Test room marking and full-home backup/export/import. Existing v0.5.0 scans
   must remain readable.
7. Compare reference dimensions measured by tape or laser. Document tracking
   loss, drift, incomplete surfaces and memory/thermal behaviour.

## Known limitations

- **Still a coloured point cloud**, drawn on Android Canvas; not a mesh.
- No occlusion-correct photogrammetry, TSDF volume, AI depth or Splat training.
- RGB sampling does not save full photographic keyframes. Persisting calibrated
  RGB-D keyframes is the next prerequisite for on-device Gaussian Splatting.
- Full Depth is interpolated; its confidence is set conservatively, not claimed
  to be an observed Raw Depth probability.
- A single continuous ARCore session only; scan sessions must not be combined
  until registration and loop closure are implemented.
- No measured on-device performance or centimetre-accuracy guarantee yet.
- Old legacy JSONL depth logs retain a small subset of candidate points;
  the guided scan receives all samples.

## Next milestones

1. Save sampled calibrated RGB-D keyframes without blocking the GL thread.
2. Add a native sparse TSDF or surfel fusion engine with mesh/texture extraction.
3. Benchmark a compact neural depth model on Pixel 10 Pro XL.
4. Implement mobile Gaussian-splat initialization, local optimization and GPU
   rendering as an optional *visual* layer over the metric geometry.
5. Add registration, loop closure and geometry-based room polygons.
