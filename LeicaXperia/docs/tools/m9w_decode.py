"""Decoder for com.codex.fullm9.PackedWeights .m9w -> .onnx (exact port of PackedWeights.model)."""
import sys, struct, hashlib, os
import numpy as np

MAGIC = b"M9W1801\x00"
EXPECTED = {  # name -> (size, sha256) from PackedWeights / ManifestHashes
    "low": (15709687, "89f7e4e515467a6f47c9d74538a08d25ed7e148474fca2e3713db5e9259cf406"),
    "high": (15636032, "61eedf9d72141c0f5262a2b2d7791ea27d36b77cfb63a8312b007e447b14da73"),
    "colorfix": (0x01da1eb2, "381e0c48c241b3a1eaf09843ab9cb43610a96eae7708c0316b17a8661620b74f"),
}

def decode(data: bytes, name: str) -> bytes:
    exp_size, exp_hash = EXPECTED[name]
    p = 0
    if data[:8] != MAGIC: raise IOError("Packed model header")
    p = 8
    size, nsec = struct.unpack_from(">ii", data, p); p += 8
    if size != exp_size: raise IOError("Packed model header")
    if not (1 <= nsec <= 2048): raise IOError("Packed model sections")
    ident = data[p:p+32].hex(); p += 32
    if ident != exp_hash: raise IOError("Packed model identity")
    out = bytearray(); written = 0
    stats = {"literal": 0, "quant": 0, "lit_bytes": 0, "quant_vals": 0}
    for _ in range(nsec):
        t = data[p]; p += 1
        if t == 0:
            (n,) = struct.unpack_from(">i", data, p); p += 4
            if n < 0 or n > size - written: raise IOError("Packed literal bounds")
            out += data[p:p+n]; p += n; written += n
            stats["literal"] += 1; stats["lit_bytes"] += n
        elif t == 1:
            rows, cols = struct.unpack_from(">ii", data, p); p += 8
            nbytes = rows * cols * 4
            if not (1 <= rows <= 4096 and 1 <= cols <= 1048576 and nbytes <= size - written):
                raise IOError("Packed weight bounds")
            scales = np.frombuffer(data, ">f8", rows, p).astype(np.float64); p += 8 * rows
            if not np.all(np.isfinite(scales)): raise IOError("Packed scale")
            q = np.frombuffer(data, np.int8, rows * cols, p).reshape(rows, cols); p += rows * cols
            f = (q.astype(np.float64) * scales[:, None]).astype("<f4")
            out += f.tobytes(); written += nbytes
            stats["quant"] += 1; stats["quant_vals"] += rows * cols
        else:
            raise IOError("Packed section type")
    if written != size or p != len(data): raise IOError("Packed model length")
    h = hashlib.sha256(out).hexdigest()
    if h != exp_hash: raise IOError("Model digest mismatch: " + name)
    return bytes(out), stats

if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    for name in ("low", "high", "colorfix"):
        data = open(os.path.join(src, name + ".m9w"), "rb").read()
        onnx_bytes, st = decode(data, name)
        open(os.path.join(dst, name + ".onnx"), "wb").write(onnx_bytes)
        print(name, len(onnx_bytes), st, "sha256 OK")
