import sys, numpy as np
a=np.fromfile(sys.argv[1],np.uint8); b=np.fromfile(sys.argv[2],np.uint8)
d=np.nonzero(a!=b)[0]; print('first',hex(d[0]),'last',hex(d[-1]))
print(a[0x20:0x400].tobytes().replace(b'\0',b'.')[:120]); print(b[0x20:0x400].tobytes().replace(b'\0',b'.')[:120])
L=114; n=17**3*3
A=a[4096:4096+L*n].reshape(L,17,17,17,3).astype(int); B=b[4096:4096+L*n].reshape(L,17,17,17,3).astype(int)
print('mean abs diff per lut', np.abs(A-B).mean(axis=(1,2,3,4)).round(2)[:20])
# identity check: R-fastest => index [b][g][r]
g=np.linspace(0,255,17)
idd=np.stack(np.meshgrid(g,g,g,indexing='ij')[::-1],-1)  # [b,g,r] -> (r,g,b)
print('snapshot lut0 vs identity mean', np.abs(B[0]-idd).mean().round(2), 'if B-fastest', np.abs(B[0]-idd[...,::-1]).mean().round(2))
print('corner samples lut0 [b,g,r]:', B[0,0,0,16], B[0,0,16,0], B[0,16,0,0], B[0,16,16,16], B[0,8,8,8])
