import sys, onnx
from onnx import numpy_helper
m=onnx.load(sys.argv[1])
consts={}
for n in m.graph.node:
    if n.op_type=="Constant":
        for a in n.attribute:
            if a.t: consts[n.output[0]]=numpy_helper.to_array(a.t)
inits={i.name: numpy_helper.to_array(i) for i in m.graph.initializer}
nodes=[n for n in m.graph.node if n.op_type!="Constant"]
sel = nodes[-int(sys.argv[2]):]
for n in sel:
    ins=[]
    for i in n.input:
        if i in consts: ins.append((i[-20:], consts[i].tolist() if consts[i].size<12 else consts[i].shape))
        elif i in inits: ins.append((i[-30:], inits[i].shape))
        else: ins.append(i[-30:])
    attrs={a.name: onnx.helper.get_attribute_value(a) for a in n.attribute}
    print(n.op_type, ins, "->", [o[-30:] for o in n.output], attrs)
