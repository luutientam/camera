"""Extracts and converts the colour assets of "Leica Port 8.9.apk" into app/src/main/assets.

Run with the scratchpad venv (numpy + pillow):
    python -I tools/prepare_assets.py "<path to Leica Port 8.9.apk>" app/src/main/assets
"""
import io
import json
import struct
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

apk_path, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)
apk = zipfile.ZipFile(apk_path)


def write(rel, data):
    p = out_dir / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    print(f"{rel:40s} {len(data):>10,d}")


def write_clu1(rel, lut):
    """lut: (n, n, n, 3) float indexed [b, g, r] -> CLU1 big-endian, R fastest."""
    n = lut.shape[0]
    head = b"CLU1" + struct.pack(">i3f3f", n, 0, 0, 0, 1, 1, 1)
    write(rel, head + lut.astype(">f4").tobytes())


# 1. cameralook tone LUTs (CLU1, kept bit exact) + preview thumbnails
for i in range(64):
    name = f"assets/cameralook/tone-{i}.lut"
    if name not in apk.namelist():
        break
    write(f"looks/tone-{i}.lut", apk.read(name))
    intro = f"assets/cameralook/intro-{i}.jpg"
    if intro in apk.namelist():
        im = Image.open(io.BytesIO(apk.read(intro))).convert("RGB")
        im.thumbnail((360, 360), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "WEBP", quality=82)
        write(f"looks/thumb-{i}.webp", buf.getvalue())

# 2. Leica scene/lux/CCT LUT banks
for src, dst in [
    ("assets/fullm9/leica_filter_param_m9_preview.bin", "leica/m9_preview.bin"),
    ("assets/fullm9/leica_filter_param_m9_snapshot.bin", "leica/m9_snapshot.bin"),
    ("assets/donor120/leica_filter_param_m10r_snapshot.bin", "leica/m10r_snapshot.bin"),
    ("assets/legend18/leica_filter_param_m3.bin", "leica/m3.bin"),
]:
    write(dst, apk.read(src))

# 3. "huaijiu" (nostalgia) 512x512 GPUImage LUT -> 33^3 CLU1
vp = zipfile.ZipFile(io.BytesIO(apk.read("assets/vp/huaijiu.zip")))
png = np.asarray(Image.open(io.BytesIO(vp.read("filter.png"))).convert("RGB")).astype(np.float64) / 255.0
cube = np.zeros((64, 64, 64, 3))
for b in range(64):
    ty, tx = divmod(b, 8)
    cube[b] = png[ty * 64:(ty + 1) * 64, tx * 64:(tx + 1) * 64]  # [g, r]


def trilinear(c, r, g, b):
    x = np.stack([r, g, b], -1) * 63
    i0 = np.clip(np.floor(x).astype(int), 0, 62)
    f = x - i0
    out = 0
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[..., 0] if dr else 1 - f[..., 0]) * (f[..., 1] if dg else 1 - f[..., 1]) * (f[..., 2] if db else 1 - f[..., 2])
                out = out + w[..., None] * c[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out


g33 = np.linspace(0, 1, 33)
B, G, R = np.meshgrid(g33, g33, g33, indexing="ij")
write_clu1("looks/huaijiu.lut", trilinear(cube, R, G, B))
thumb = Image.open(io.BytesIO(vp.read("cover.png"))).convert("RGB") if "cover.png" in vp.namelist() else None
if thumb is not None:
    thumb.thumbnail((360, 360), Image.LANCZOS)
    buf = io.BytesIO(); thumb.save(buf, "WEBP", quality=82)
    write("looks/thumb-huaijiu.webp", buf.getvalue())

# 4. neural models that run on plain ONNX Runtime (no QNN HTP needed)
for src, dst in [
    ("assets/donor120/m9_distill.onnx", "models/m9_distill.onnx"),
    ("assets/donor120/m9fb_styletrans_high.onnx", "models/m9_styletrans_high.onnx"),
]:
    write(dst, apk.read(src))

# 5. film grain / vignette shader reference and style params
write("leica/styletrans_params.json", apk.read("assets/fullm9/styletrans_params.json"))
for name in ["M3", "M9"]:
    src = f"assets/tone-intro/{name}.png"
    if src in apk.namelist():
        im = Image.open(io.BytesIO(apk.read(src))).convert("RGB")
        im.thumbnail((360, 360), Image.LANCZOS)
        buf = io.BytesIO(); im.save(buf, "WEBP", quality=82)
        write(f"looks/thumb-{name.lower()}.webp", buf.getvalue())

print("done")
