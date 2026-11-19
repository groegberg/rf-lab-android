#!/usr/bin/env python3
"""Independent, offline sparse reconstruction of an RF Lab photo-session ZIP.

Requires pycolmap 4.2.1. Uses the saved camera model and JPEGs; the OpenVINS
poses, IMU, and DA3 output are never used to register or scale the model.
"""
import argparse
import ast
import csv
import json
import math
from pathlib import Path
import re
from zipfile import ZipFile

import pycolmap as colmap


def camera_seed(archive):
    yaml = archive.read("external_calibration/kalibr_imucam_chain.yaml").decode()
    values = []
    for key in ("intrinsics", "distortion_coeffs"):
        match = re.search(r"^\s*" + key + r":\s*(\[[^\n\]]*\])", yaml, re.M)
        coeffs = ast.literal_eval(match.group(1)) if match else []
        if len(coeffs) != 4 or not all(math.isfinite(v) for v in coeffs):
            raise ValueError(f"Expected four finite {key} values in the camera seed")
        values.extend(coeffs)
    if not re.search(r"^\s*distortion_model:\s*radtan\s*$", yaml, re.M):
        raise ValueError("This benchmark expects a pinhole camera with radtan distortion")
    return ",".join(map(str, values))


def reconstruct(session, output):
    # A separate directory prevents mixing image IDs and prior feature databases.
    output.mkdir(parents=True, exist_ok=False)
    images = output / "rgb"
    images.mkdir()
    stamps = {}
    with ZipFile(session) as archive:
        reader = colmap.ImageReaderOptions()
        reader.camera_model = "OPENCV"
        reader.camera_params = camera_seed(archive)
        names = sorted(n for n in archive.namelist() if re.fullmatch(r"img_\d+\.jpg", n))
        if not 2 <= len(names) <= 180 or len(set(names)) != len(names):
            raise ValueError("Expected 2–180 distinct RF Lab JPEG frames")
        for name in names:
            (images / name).write_bytes(archive.read(name))
            stamps[name] = int(json.loads(archive.read(name[:-4]+".json"))["timestamp_ns"])

    database = output / "features.db"
    extraction = colmap.FeatureExtractionOptions()
    colmap.set_random_seed(11)
    extraction.num_threads = 1
    extraction.sift.max_num_features = 3500
    matching = colmap.FeatureMatchingOptions()
    matching.num_threads = 1
    verification = colmap.TwoViewGeometryOptions()
    verification.ransac.random_seed = 11
    colmap.extract_features(database, images, camera_mode=colmap.CameraMode.SINGLE,
                            reader_options=reader, extraction_options=extraction, device=colmap.Device.cpu)
    colmap.match_exhaustive(database, matching_options=matching, verification_options=verification,
                            device=colmap.Device.cpu)
    model = None
    runs = []
    for seed in (11, 29, 47):
        colmap.set_random_seed(seed)
        mapper = colmap.IncrementalPipelineOptions()
        mapper.num_threads = 1
        mapper.mapper.num_threads = 1
        mapper.random_seed = seed
        mapper.mapper.random_seed = seed
        sparse = output / f"sparse-{seed}"
        sparse.mkdir()
        models = colmap.incremental_mapping(database, images, sparse, mapper)
        sizes = sorted((m.num_reg_images() for m in models.values()), reverse=True)
        runs.append(dict(seed=seed, component_sizes=sizes))
        for candidate in models.values():
            if model is None or candidate.num_reg_images() > model.num_reg_images():
                model = candidate
        if model is not None and model.num_reg_images() == len(stamps):
            break
    if model is None:
        raise ValueError("No connected sparse reconstruction could be registered")
    frames = sorted((model.image(i) for i in model.reg_image_ids()), key=lambda f: stamps[f.name])
    initial = frames[0].cam_from_world().rotation
    angles = [math.degrees((f.cam_from_world().rotation * initial.inverse()).angle()) for f in frames]
    model.export_PLY(output / "Punktwolke.ply")
    with (output / "Kamerapfad.csv").open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(("image", "timestamp_ns", "x_relative", "y_relative", "z_relative", "rotation_from_start_deg"))
        for frame, angle in zip(frames, angles):
            writer.writerow((frame.name, stamps[frame.name], *frame.projection_center(), angle))
    report = dict(input_frames=len(stamps), runs=runs, registered_frames=len(frames),
                  sparse_points=model.num_points3D(), max_rotation_deg=max(angles),
                  mean_reprojection_error_px=model.compute_mean_reprojection_error(), metric_scale=False)
    (output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path)
    parser.add_argument("output", type=Path, help="new output directory")
    args = parser.parse_args()
    print(json.dumps(reconstruct(args.session, args.output), indent=2))
