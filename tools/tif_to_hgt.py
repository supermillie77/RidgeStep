"""
Convert SRTM GeoTIFF (.tif) files to SRTM HGT binary format for use with SrtmHgtProvider.

Requires: pip install gdal numpy   (or: conda install gdal numpy)

Usage:
    python tools/tif_to_hgt.py

Output files go to:  tools/hgt/
Then copy them to the Android device at:
    /sdcard/Android/data/com.example.scottishhillnav/files/dem/

Using adb:
    adb push tools/hgt/N56W005.hgt /sdcard/Android/data/com.example.scottishhillnav/files/dem/
    adb push tools/hgt/N56W006.hgt /sdcard/Android/data/com.example.scottishhillnav/files/dem/
"""

import os
import struct
import numpy as np

try:
    from osgeo import gdal
except ImportError:
    raise SystemExit("gdal not found.  Run:  pip install gdal   or  conda install gdal")

TIF_FILES = {
    "n56_w005_1arc_v3.tif": "N56W005.hgt",
    "n56_w006_1arc_v3.tif": "N56W006.hgt",
}

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
OUT_DIR = os.path.join(SCRIPT_DIR, "hgt")
os.makedirs(OUT_DIR, exist_ok=True)

for tif_name, hgt_name in TIF_FILES.items():
    tif_path = os.path.join(PROJECT_ROOT, tif_name)
    hgt_path = os.path.join(OUT_DIR, hgt_name)

    if not os.path.exists(tif_path):
        print(f"SKIP (not found): {tif_path}")
        continue

    print(f"Converting {tif_name} → {hgt_name} …")
    ds = gdal.Open(tif_path)
    band = ds.GetRasterBand(1)
    data = band.ReadAsArray()          # shape: (rows, cols), float32 or int16

    rows, cols = data.shape
    print(f"  Size: {cols}x{rows} samples")

    # Clamp to signed 16-bit range; use -32768 for NODATA voids
    nodata = band.GetNoDataValue()
    if nodata is not None:
        data[data == nodata] = -32768

    data_int = np.clip(np.round(data), -32768, 32767).astype(np.int16)

    # HGT is big-endian, row-major, north-to-south (top row = north edge).
    # GDAL already returns rows north-to-south for north-up rasters.
    with open(hgt_path, "wb") as f:
        for row in data_int:
            f.write(struct.pack(f">{len(row)}h", *row))

    size_mb = os.path.getsize(hgt_path) / 1024 / 1024
    print(f"  Written: {hgt_path}  ({size_mb:.1f} MB)")

print("\nDone.  Push to device with:")
for _, hgt_name in TIF_FILES.items():
    print(f"  adb push tools/hgt/{hgt_name} "
          f"/sdcard/Android/data/com.example.scottishhillnav/files/dem/{hgt_name}")
