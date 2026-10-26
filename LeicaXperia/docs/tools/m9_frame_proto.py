"""Prototype of libfullm9 m9::Frame pipeline (style + colorfix tiles). Usage: proto.py in.img out.png [low|high]"""
import sys, numpy as np, onnxruntime as ort
from PIL import Image
M = sys.argv[4] if len(sys.argv) > 4 else "/private/tmp/claude-501/-Users-lmao1-Desktop-camera/68d51ba1-2b98-422d-af9f-21e15ef9b900/scratchpad/models"
def reflect101(i, n):  # frameCreate: whole-sample mirror, edge not repeated
    i = np.asarray(i)
    if n < 2: return np.zeros_like(i)
    p = 2 * n - 2
    i = np.mod(i, p)
    return np.where(i >= n, p - i, i)
def reflect_half(i, n):  # styleInput: half-sample mirror (edge repeated)
    p = 2 * n
    i = np.mod(i, p)
    return np.where(i >= n, p - 1 - i, i)
def q8(v):  # v*127.5+128, round-half-even, clamp
    return np.clip(np.rint(v * 127.5 + 128.0), 0, 255).astype(np.uint8)
def n8(u):
    return (u.astype(np.float32) - 128.0) / 127.5
img = np.array(Image.open(sys.argv[1]).convert("RGB"))
H, W = img.shape[:2]
rot = H > W
if rot: img = np.rot90(img, -1)  # app rotates +90 (clockwise) when portrait   (UNCERTAIN direction irrelevant)
H, W = img.shape[:2]
s = min(1.0, 4096 / W, 3072 / H)
if s < 1:
    img = np.array(Image.fromarray(img).resize((max(1, int(W * s)), max(1, int(H * s))), Image.BILINEAR)); H, W = img.shape[:2]
ox, oy = int((4096 - W) / 2), int((3072 - H) / 2)
canvas = img[reflect101(np.arange(3072) - oy, H)][:, reflect101(np.arange(4096) - ox, W)]
small = np.clip(np.rint(canvas.reshape(768, 4, 1024, 4, 3).astype(np.int32).sum((1, 3)) / 16.0), 0, 255).astype(np.uint8)
ys, xs = reflect_half(np.arange(864) - 48, 768), reflect_half(np.arange(1120) - 48, 1024)
sin = n8(small[ys][:, xs]).transpose(2, 0, 1)
x = np.concatenate([sin, np.full((1, 864, 1120), -128 / 127.5, np.float32)])[None]
model = sys.argv[3] if len(sys.argv) > 3 else "high"
so = ort.SessionOptions()
st = ort.InferenceSession(f"{M}/{model}.onnx", so, providers=["CPUExecutionProvider"])
y = st.run(None, {"input": x})[0][0]
style = q8(y[:, 48:48 + 768, 48:48 + 1024]).transpose(1, 2, 0)  # 768x1024x3
up = np.repeat(np.repeat(style, 4, 0), 4, 1)  # nearest x4 -> 3072x4096
cf = ort.InferenceSession(f"{M}/colorfix.onnx", so, providers=["CPUExecutionProvider"])
out = np.zeros_like(canvas)
x0, x1 = ox // 512, (ox + W - 1) // 512
y0, y1 = oy // 512, (oy + H - 1) // 512
pad = lambda a: np.pad(a, ((16, 16), (16, 16), (0, 0)), constant_values=0)
cpad, upad = pad(n8(canvas)), pad(n8(up))
cpad[:16] = cpad[-16:] = cpad[:, :16] = cpad[:, -16:] = -128 / 127.5
upad[:16] = upad[-16:] = upad[:, :16] = upad[:, -16:] = -128 / 127.5
for r in range(y0, y1 + 1):
    for c in range(x0, x1 + 1):
        sl = (slice(r * 512, r * 512 + 544), slice(c * 512, c * 512 + 544))
        t = np.concatenate([upad[sl], cpad[sl]], 2).transpose(2, 0, 1)[None].astype(np.float32)
        o = cf.run(None, {"input": t})[0][0]
        out[r * 512:(r + 1) * 512, c * 512:(c + 1) * 512] = q8(o[:, 16:528, 16:528]).transpose(1, 2, 0)
res = out[oy:oy + H, ox:ox + W]
if rot: res = np.rot90(res, 1)
Image.fromarray(np.ascontiguousarray(res)).save(sys.argv[2])
Image.fromarray(np.ascontiguousarray(style)).save(sys.argv[2] + ".style.png")
print("ok", res.shape, "style mean", style.reshape(-1, 3).mean(0), "in mean", img.reshape(-1, 3).mean(0), "out mean", res.reshape(-1, 3).mean(0))
