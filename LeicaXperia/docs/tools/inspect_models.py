import sys, onnx, onnxruntime as ort, numpy as np, collections, time
for name in ("low","high","colorfix"):
    path = f"{sys.argv[1]}/{name}.onnx"
    m = onnx.load(path); onnx.checker.check_model(m)
    ops = collections.Counter(n.op_type for n in m.graph.node)
    print("==", name, "ir", m.ir_version, "opset", [(o.domain,o.version) for o in m.opset_import], "producer", m.producer_name, m.producer_version)
    print(" nodes", len(m.graph.node), "inits", len(m.graph.initializer), "ops", dict(ops))
    print(" metadata", [(p.key, p.value[:200]) for p in m.metadata_props])
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    for i in s.get_inputs(): print(" IN ", i.name, i.shape, i.type)
    for o in s.get_outputs(): print(" OUT", o.name, o.shape, o.type)
    feeds = {}
    for i in s.get_inputs():
        shp = [d if isinstance(d,int) else 1 for d in i.shape]
        feeds[i.name] = np.random.rand(*shp).astype(np.float32)
    t=time.time(); outs = s.run(None, feeds); dt=time.time()-t
    for o,v in zip(s.get_outputs(), outs): print(" run", o.name, v.shape, float(v.min()), float(v.max()), f"{dt:.2f}s")
