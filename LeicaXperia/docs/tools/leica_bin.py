import sys, struct
def parse(path):
    raw=open(path,'rb').read(); p=[0]
    def u():
        v=struct.unpack_from('<H',raw,p[0])[0]; p[0]+=2; return v
    n=u(); sizes=[u() for _ in range(n)]
    meta=u(); lutOff=u(); g=u(); nz=u(); zs=[u() for _ in range(nz)]
    hdr=dict(nScenes=n,sceneSizes=sizes,metaOff=meta,lutOff=lutOff,grid=g,nZoom=nz,zoomSizes=zs,headerEnd=p[0])
    def tree():
        out=[]
        for _ in range(u()):
            lo,hi=u(),u(); subs=[(u(),u(),u()) for _ in range(u())]
            out.append((lo,hi,subs))
        return out
    off=meta; scenes={}; mx=0
    for s in sizes:
        p[0]=off; name=raw[off:off+64].split(b'\0')[0].decode(); p[0]+=64
        t=tree(); scenes[name]=(off,t)
        for b in t:
            for c in b[2]: mx=max(mx,c[2]+1)
        off+=s
    zooms=[]
    for s in zs:
        p[0]=off; z=struct.unpack_from('<f',raw,off)[0]; p[0]+=4; zooms.append((off,z,tree())); off+=s
    shadeOff=lutOff+mx*17**3*3
    return raw,hdr,scenes,zooms,mx,shadeOff,off
for path in sys.argv[1:]:
    raw,hdr,scenes,zooms,mx,shadeOff,endmeta=parse(path)
    print(path.split('/')[-1], len(raw), hdr, 'lutCount',mx,'shadeOff',hex(shadeOff),'metaEnd',hex(endmeta))
    print(' header strings region:', raw[hdr['headerEnd']:hdr['metaOff']].rstrip(b'\0')[:200])
    for k,(o,t) in scenes.items():
        print(' scene',k,hex(o))
        for lo,hi,subs in t: print('   lux',lo,hi,' cct->idx',subs)
    for o,z,t in zooms:
        print(' zoom',hex(o),z)
        for lo,hi,subs in t: print('   lux',lo,hi,' cct->idx',subs)
    rem=len(raw)-shadeOff; print(' trailing',rem,'=',rem/32,'x 8 floats')
    for i in range(rem//32):
        print('  shade',i,[round(x,5) for x in struct.unpack_from('<8f',raw,shadeOff+32*i)])
