# RF Lab v0.9.0-dev — Five methods on one phone

Goal: compare five local depth procedures against the SAME stored RGB
keyframes. All calculations run on the Pixel, without Cloud or PC.
The 55 MB Depth Anything 3 Small model is fetched and SHA-256 verified
at APK BUILD TIME, then bundled; no internet connection is needed for inference.

## Instructions

1. Back up existing home state plus the separate RGB-D ZIP before installing
   a differently signed debug APK.
2. Record one small, well-lit room in Guided Scan, moving sideways to obtain
   different camera viewpoints. Prefer furniture with visual texture.
3. Choose "5 Tiefenverfahren vergleichen" from the scanner overflow menu or
   the first Spatial Home setup stage.
4. Switch method tabs without changing the input photo. Navigate other
   keyframes with the previous/next buttons. Compare coverage, errors,
   runtime and whether output represents real metres.

## Methods and limitations

- ARCore Raw — measured-but-sparse ARCore visual depth with confidence; metres.
- ARCore Full — ARCore interpolated metric depth; can hallucinate surfaces.
- Foto-Stereo — real two-view photometric RGB plane sweep on this phone.
  Uses camera images and TRACKING POSES, but does NOT consult ARCore depth.
  Uses 52 inverse-depth candidates and photometric ambiguity rejection.
  Returns metric depth only where matching was possible; large gaps expected.
  This is NOT independent feature-based SLAM or bundle adjustment.
- Depth Anything 3 Small — real on-device LiteRT inference from one photo.
  NO ARCore depth is used. Output is RELATIVE DEPTH, NOT METRES.
  Camera frame is rotated/center-cropped to match the 504x896 model input.
  This is single-image DA3, NOT the full multi-view DA3 or GS head.
- Mehrbild-Check — geometric reprojection checks ARCore Raw/Full from
  neighbouring views. Green = confirmed, red = contradicted, grey = unknown.
  This is diagnostic, not a standalone depth estimator.

## Model provenance and verification

Model: ByteDance-Seed Depth Anything 3 Small, Apache-2.0.
Android conversion: litert-community/Depth-Anything-3-Small-LiteRT.
Exact Hugging Face revision: 1ebd31737fcb7842d61ad22722b59cd8053829f2.
File: da3_small_gpu_fp16.tflite, exactly 55,035,456 bytes.
SHA-256: e170369a72ba1bba7486a4d2de555639fccd0595a9bb5b5349f7733ed4aebd1f.
Runtime: LiteRT 2.3.0, GPU with explicit CPU fallback.
Input: RGB, float32 NCHW 1x3x896x504, ImageNet mean/std.
Output: float32 relative depth 1x1x896x504, percentile-colorized.
No downloaded model data is sent from the app.

Source:
https://github.com/ByteDance-Seed/Depth-Anything-3
https://huggingface.co/litert-community/Depth-Anything-3-Small-LiteRT
https://github.com/google-ai-edge/litert-samples

## Compare responsibly

Record one richly textured and one low-texture room with the same lighting.
Use three independently measured tape/laser references for a useful
geometry benchmark. ARCore and RGB-stereo metric depths may be compared
to metre-scale reference distances. DA3 cannot yet be scored in
centimetres without robust scale alignment.

The comparison app is experimental: it does not yet produce one globally
fused/optimized TSDF mesh from these alternative methods. Camera poses
are still supplied by ARCore tracking; only depth differs. RGB-stereo has
a single comparison partner and needs parallax. The DA3 native Android
GPU performance is not yet validated on Pixel 10 Pro XL hardware.
The previous RF and smart-home functions remain unchanged.

Next: robust DA3 scale anchors and uncertainty; independent optical pose
recovery/SfM; true fused geometric mesh; then optional Gaussian Splatting.
