import sys, onnx, onnxruntime as ort, numpy as np, time
from onnx import numpy_helper
name=sys.argv[2]; H=int(sys.argv[3]); W=int(sys.argv[4])
path=f"{sys.argv[1]}/{name}.onnx"
m=onnx.load(path)
consts={}
for n in m.graph.node:
    if n.op_type=="Constant":
        for a in n.attribute:
            if a.t: consts[n.output[0]]=numpy_helper.to_array(a.t)
shown=0
for n in m.graph.node:
    if n.op_type in("Constant",): continue
    if n.op_type in ("Conv","Clip") and shown>40: continue
    ins=[(i, consts[i].tolist() if i in consts and consts[i].size<12 else (consts[i].shape if i in consts else '')) for i in n.input]
    attrs={a.name: onnx.helper.get_attribute_value(a) for a in n.attribute}
    print(n.op_type, n.name, ins, "->", list(n.output), {k:(v if not isinstance(v,bytes) else v[:30]) for k,v in attrs.items() if k!='value'})
    shown+=1
    if shown>int(sys.argv[5]): break
s=ort.InferenceSession(path, providers=["CPUExecutionProvider"])
for i in s.get_inputs(): print(" IN ", i.name, i.shape)
for o in s.get_outputs(): print(" OUT", o.name, o.shape)
feeds={i.name: np.random.rand(1,i.shape[1],H,W).astype(np.float32) for i in s.get_inputs()}
t=time.time(); outs=s.run(None,feeds); print("run", [o.shape for o in outs], [(float(o.min()),float(o.max())) for o in outs], time.time()-t)
