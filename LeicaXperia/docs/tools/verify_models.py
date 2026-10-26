import sys, onnx, onnxruntime as ort, numpy as np, time, os, hashlib
d=sys.argv[1]
for name,(c,h,w) in {"low":(4,864,1120),"high":(4,864,1120),"colorfix":(6,544,544)}.items():
    p=f"{d}/{name}.onnx"; m=onnx.load(p); onnx.checker.check_model(m, full_check=True)
    so=ort.SessionOptions(); so.add_free_dimension_override_by_name("height",h); so.add_free_dimension_override_by_name("width",w)
    s=ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])
    x=np.random.uniform(-1.0039,1.0,(1,c,h,w)).astype(np.float32)
    if c==4: x[:,3]=-1.003921627998352
    t=time.time(); y=s.run(None,{"input":x})[0]; dt=time.time()-t
    print(name, os.path.getsize(p), hashlib.sha256(open(p,'rb').read()).hexdigest()[:16], "in", s.get_inputs()[0].name, s.get_inputs()[0].shape, "out", s.get_outputs()[0].name, y.shape, f"[{y.min():.4f},{y.max():.4f}]", f"{dt:.2f}s", "params", sum(np.prod(i.dims) for i in m.graph.initializer))
