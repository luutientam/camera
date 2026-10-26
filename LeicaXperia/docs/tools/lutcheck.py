import sys, numpy as np
d=open(sys.argv[1],'rb').read()
lut=np.frombuffer(d,np.uint8,17**3*3,0x1000).reshape(17,17,17,3).astype(float)  # [a][b][c][byte], c fastest
g=np.arange(17)*255/16
for byte in range(3):
    for ax,name in ((0,'slowest'),(1,'mid'),(2,'fastest')):
        m=lut[...,byte].mean(axis=tuple(i for i in range(3) if i!=ax))
        print(f"byte{byte} vs {name}: corr={np.corrcoef(m,g)[0,1]:.3f} range={m.min():.0f}-{m.max():.0f}")
print("grey diag", [tuple(lut[i,i,i].astype(int)) for i in (0,4,8,12,16)])
