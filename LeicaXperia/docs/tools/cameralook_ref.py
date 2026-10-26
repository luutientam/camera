"""Reference (numpy) re-implementation of com.codex.cameralook tone pipeline, for test vectors.
usage: cameralook_ref.py <lutdir> <toneIdx 0..22> <in.jpg> <out.jpg> [maxdim]"""
import numpy as np, struct, sys
from PIL import Image
f32=np.float32
PARAMS={ # idx: strength, grain, shadows, midtones, highlights, grainSize, roughness, colorVariation, vignette, midpoint, clarity, luma, rgb
0:(1.5,20,30,30,15,10,15,0,-20,25,0,[0,0,255,255],[0,0,255,255]),
1:(1.55,0,0,0,0,30,30,0,0,50,0,[0,0,255,255],[0,0,255,255]),
2:(1.5,0,0,0,0,30,30,0,0,50,0,[0,0,255,255],[0,0,255,255]),
3:(1.45,30,30,50,15,10,15,10,-45,30,0,[0,0,78,85,135,137,255,255],[0,0,255,255]),
4:(1.35,60,30,50,15,10,15,10,-45,30,0,[0,0,255,255],[0,0,255,255]),
5:(1.4,0,0,0,0,0,0,0,0,50,0,[0,0,255,255],[0,0,255,255]),
6:(1.35,0,0,0,0,0,0,0,0,50,0,[0,0,255,255],[0,0,255,255]),
7:(1.4,30,30,50,15,10,15,10,-10,50,0,[0,0,255,255],[0,0,255,255]),
8:(1.75,10,50,30,15,30,30,0,-63,10,-10,[5,0,89,88,255,241],[0,0,255,255]),
9:(1.5,50,45,30,15,10,15,10,-10,50,0,[0,0,255,255],[0,0,255,255]),
10:(1.7,20,50,30,15,30,30,0,-35,20,0,[0,0,255,255],[0,0,255,255]),
11:(1.5,25,50,30,15,30,30,0,-50,30,0,[0,0,255,255],[0,0,255,255]),
12:(1.4,50,40,30,15,35,30,0,0,50,0,[0,0,255,255],[0,0,105,109,197,206,255,255]),
13:(1.5,0,0,0,0,0,0,0,0,50,0,[0,0,255,255],[0,0,255,255]),
14:(1.4,60,55,20,15,10,30,0,-45,0,0,[0,0,149,148,193,189,255,255],[0,0,41,36,118,115,255,255]),
15:(1.45,40,40,30,15,20,20,20,-30,0,0,[0,0,255,255],[0,0,255,255]),
16:(1.45,0,0,0,0,30,30,0,0,50,0,[0,0,255,255],[0,0,255,255]),
17:(1.5,50,50,30,15,30,30,0,-40,30,0,[0,0,255,255],[0,0,255,255]),
18:(1.5,25,50,35,15,30,30,0,-35,35,0,[0,0,255,255],[0,0,255,255]),
19:(1.4,15,30,35,15,10,15,10,-11,28,-6,[0,0,255,255],[0,0,255,255]),
20:(1.45,25,50,30,15,30,30,0,-50,30,0,[0,0,255,255],[0,0,255,255]),
21:(1.4,10,50,30,15,30,30,0,0,50,-5,[0,0,255,255],[0,0,255,255]),
22:(1.45,6,6,6,6,6,6,0,-30,15,-10,[0,0,255,255],[0,0,255,255]),
}
KEYS='strength grain shadows midtones highlights grainSize roughness colorVariation vignette midpoint clarity luma rgb'.split()
def P(i): return dict(zip(KEYS,PARAMS[i]))
def load_lut(path):
    b=open(path,'rb').read(); assert b[:4]==b'CLU1'
    n=struct.unpack('>i',b[4:8])[0]; mn=np.array(struct.unpack('>3f',b[8:20]),f32); mx=np.array(struct.unpack('>3f',b[20:32]),f32)
    data=np.frombuffer(b[32:],'>f4').astype(f32).reshape(-1)  # index = (r + g*n + b*n*n)*3 + c
    return n,mn,mx,data
def clamp01(x): return np.minimum(np.maximum(x,f32(0)),f32(1)).astype(f32)
def lut_map(lut,rgb):  # rgb (...,3) float32 -> (...,3), tetrahedral, exactly as ToneTable.map
    n,mn,mx,data=lut
    f=[clamp01((rgb[...,i]-mn[i])/(mx[i]-mn[i]))*f32(n-1) for i in range(3)]
    i0=[np.minimum(n-2,f[i].astype(np.int32)) for i in range(3)]
    fr,fg,fb=[(f[i]-i0[i]).astype(f32) for i in range(3)]
    sR,sG,sB=3,3*n,3*n*n
    base=i0[0]*sR+i0[1]*sG+i0[2]*sB
    # choose tetrahedron
    c1=np.zeros_like(base); c2=np.zeros_like(base); w1=np.zeros_like(fr); w2=np.zeros_like(fr); w3=np.zeros_like(fr)
    conds=[ (fr>=fg)&(fg>=fb),            (sR, sR+sG, fr,fg,fb),
            (fr>=fg)&(fg<fb)&(fr>=fb),    (sR, sR+sB, fr,fb,fg),
            (fr>=fg)&(fg<fb)&(fr<fb),     (sB, sB+sR, fb,fr,fg),
            (fr<fg)&(fr>=fb),             (sG, sG+sR, fg,fr,fb),
            (fr<fg)&(fr<fb)&(fg>=fb),     (sG, sG+sB, fg,fb,fr),
            (fr<fg)&(fr<fb)&(fg<fb),      (sB, sB+sG, fb,fg,fr)]
    for k in range(0,12,2):
        m=conds[k]; a,b,x,y,z=conds[k+1]
        c1=np.where(m,a,c1); c2=np.where(m,b,c2); w1=np.where(m,x,w1); w2=np.where(m,y,w2); w3=np.where(m,z,w3)
    out=np.empty(rgb.shape,f32); all_=sR+sG+sB
    for c in range(3):
        v0=data[base+c]; v1=data[base+c1+c]; v2=data[base+c2+c]; v3=data[base+all_+c]
        out[...,c]=clamp01(v0+(v1-v0)*w1+(v2-v1)*w2+(v3-v2)*w3)
    return out
def curve(x,pts):
    x=clamp01(x)*f32(255); pts=[f32(p) for p in pts]
    out=np.full(x.shape,pts[-1]/f32(255),f32)
    done=np.zeros(x.shape,bool)
    m=x<=pts[0]; out=np.where(m,pts[1]/f32(255),out); done|=m
    for i in range(2,len(pts),2):
        m=(~done)&(x<=pts[i])
        v=(pts[i-1]+(pts[i+1]-pts[i-1])*(x-pts[i-2])/(pts[i]-pts[i-2]))/f32(255)
        out=np.where(m,v,out); done|=m
    return clamp01(out)
def intensify(orig,mapped,s):
    s=f32(min(1.8,max(1.0,s)))-f32(1)
    k=(s*(mapped-orig)).astype(f32)
    lim=np.where(k>=0,f32(1)-mapped,mapped).astype(f32)
    res=np.where(lim>0, mapped+(k*lim)/(lim+np.abs(k)), mapped)
    return clamp01(res)
def jround(x): return np.floor(x.astype(np.float64)+0.5).astype(np.int64)
def channel(x): return np.clip(jround(np.asarray(x,f32)*f32(255)),0,255).astype(np.uint8)
def toned(img8,lut,p):
    o=img8.astype(f32)/f32(255)
    m=lut_map(lut,o)
    for c in range(3): m[...,c]=curve(m[...,c],p['rgb'])
    Y=(f32(0.2126)*m[...,0]+f32(0.7152)*m[...,1]+f32(0.0722)*m[...,2]).astype(f32)
    Yc=curve(Y,p['luma'])
    out=np.empty_like(m)
    for c in range(3):
        v=np.where(Y>f32(1e-6), m[...,c]*Yc/np.where(Y>0,Y,1), Yc)
        out[...,c]=intensify(o[...,c],clamp01(v),p['strength'])
    return channel(out)
# ---- SpatialPass.detail (QualityPass.toneDetail)
def ylum(img): i=img.astype(np.int32); return ((i[...,0]*77+i[...,1]*150+i[...,2]*29)/f32(256)).astype(f32)
def noise_est(img):
    h,w,_=img.shape
    if w<2 or h<2: return f32(1.25)
    Y=ylum(img); hist=np.zeros(256,np.int64); cnt=0
    sy=max(1,h//32); sx=max(1,w//64)
    for y in range(0,h-1,sy):
        xs=np.arange(0,w-1,sx)
        v=np.abs(Y[y,xs]-Y[y,xs+1]-Y[y+1,xs]+Y[y+1,xs+1]); b=np.minimum(255,jround(v))
        np.add.at(hist,b,1); cnt+=len(xs)
    cum=0
    for i in range(256):
        cum+=hist[i]
        if cum>cnt//2: return f32(max(1.25,min(18.0,i*0.7413)))
    return f32(1.25)
def detail(img):
    h,w,_=img.shape; N=noise_est(img); thr=f32(max(0.8,0.7*N))
    Y=ylum(img)
    pad=lambda a,ax: a
    def hx(Y):  # [1 4 6 4 1]/16 horizontally, clamped
        idx=lambda d: np.clip(np.arange(w)+d,0,w-1)
        return ((Y[:,idx(-2)]+Y[:,idx(-1)]*4+Y[:,idx(0)]*6+Y[:,idx(1)]*4+Y[:,idx(2)])/16).astype(f32)
    Hb=hx(Y); ry=lambda d: np.clip(np.arange(h)+d,0,h-1)
    blur=((Hb[ry(-2)]+Hb[ry(-1)]*4+Hb[ry(0)]*6+Hb[ry(1)]*4+Hb[ry(2)])/16).astype(f32)
    cx=lambda d: np.clip(np.arange(w)+d,0,w-1)
    Yu=Y[ry(-1)]; Yd=Y[ry(1)]
    a0,a1,a2=Yu[:,cx(-1)],Yu,Yu[:,cx(1)]; b0,b1,b2=Y[:,cx(-1)],Y,Y[:,cx(1)]; c0,c1,c2=Yd[:,cx(-1)],Yd,Yd[:,cx(1)]
    d=b1-blur
    gx=(a2+2*b2+c2-a0-2*b0-c0)/8; gy=(c0+2*c1+c2-a0-2*a1-a2)/8
    g=np.sqrt(gx*gx+gy*gy).astype(f32); edge=g/(g+f32(2.5)*N+f32(2))
    amt=np.minimum(f32(4),np.maximum(f32(0),np.abs(d)-thr)*f32(0.55)*edge); amt=np.copysign(amt,d)
    lo=np.minimum(np.minimum(a1,c1),np.minimum(b0,b2)); hi=np.maximum(np.maximum(a1,c1),np.maximum(b0,b2))
    amt=np.maximum(np.minimum(b1,lo)-b1, np.minimum(np.maximum(b1,hi)-b1, amt))
    i=img.astype(np.int32); mn=i.min(-1); mx=i.max(-1)
    amt=np.maximum(-mn.astype(f32), np.minimum((255-mx).astype(f32), amt))
    return np.clip(jround(i.astype(f32)+amt[...,None]),0,255).astype(np.uint8)
# ---- film pass
M32=0xffffffff
def jhash(a,b,c):
    a=np.asarray(a,np.int64)&M32; b=np.asarray(b,np.int64)&M32; c=np.asarray(c,np.int64)&M32
    h=(a*0x1f123bb5+b*0x5f356495+c*0x6c8e9cf5)&M32
    h^=h>>16; h=(h*0x7feb352d)&M32; h^=h>>15; h=(h*0x846ca68b)&M32; h^=h>>16
    return ((h&0xffffff).astype(f32)/f32(8388607.5)-f32(1)).astype(f32)
def vnoise(x,y,seed,size,rough):
    s=f32(max(0,size))/f32(25)+f32(1)
    fx=(x.astype(f32)/s).astype(f32); fy=(y.astype(f32)/s).astype(f32); ix=fx.astype(np.int64); iy=fy.astype(np.int64); fx-=ix; fy-=iy
    v=(jhash(ix,iy,seed)*(1-fx)+jhash(ix+1,iy,seed)*fx)*(1-fy)+(jhash(ix,iy+1,seed)*(1-fx)+jhash(ix+1,iy+1,seed)*fx)*fy
    r=f32(min(0.7,max(0,rough)/100.0))
    return (v*(1-r)+jhash(x,y,seed+83)*r).astype(f32)
def film(img,p,seed):
    h,w,_=img.shape
    if p['grain']==0 and p['vignette']==0 and p['clarity']==0: return img
    L8=(img[...,0]*f32(0.2126)+img[...,1]*f32(0.7152)+img[...,2]*f32(0.0722))/f32(255)
    ry=lambda d: np.clip(np.arange(h)+d,0,h-1); cx=lambda d: np.clip(np.arange(w)+d,0,w-1)
    rowsum=lambda R: L8[R][:,cx(-1)]+2*L8[R]+L8[R][:,cx(1)]
    blur=((rowsum(ry(-1))+2*rowsum(ry(0))+rowsum(ry(1)))/f32(16)).astype(f32)
    rgb=img.astype(f32)/f32(255); L=(f32(0.2126)*rgb[...,0]+f32(0.7152)*rgb[...,1]+f32(0.0722)*rgb[...,2]).astype(f32)
    c=f32(max(-20,min(0,p['clarity']))/100); det=(L-blur)*c
    Y,X=np.mgrid[0:h,0:w]
    nx=(X*2+1-w).astype(f32)/max(1,w); ny=(Y*2+1-h).astype(f32)/max(1,h)
    dd=(nx*nx+ny*ny)*f32(0.5); mp=f32(max(0,min(0.85,p['midpoint']/100)))
    t=clamp01((dd-mp)/(1-mp)); t=t*t*(3-2*t)
    vig=f32(max(-1.25,min(1.25,p['vignette']/100)))*t; gain=np.power(2.0,vig.astype(np.float64)).astype(f32)
    base=np.where(L<0.5, p['shadows']+(p['midtones']-p['shadows'])*(L*2), p['midtones']+(p['highlights']-p['midtones'])*(L*2-1)).astype(f32)
    amp=f32(max(0,min(100,p['grain']))/100)*np.maximum(0,base)/f32(100)*f32(0.06)
    n0=vnoise(X,Y,seed,p['grainSize'],p['roughness'])*amp
    cv=f32(min(1,max(0,p['colorVariation'])/100))
    out=np.empty_like(rgb)
    for k in range(3):
        nk=vnoise(X,Y,seed+1+k,p['grainSize'],p['roughness'])*amp*cv if cv>0 else 0
        out[...,k]=(rgb[...,k]+det)*gain+n0+nk
    return channel(out)
def pipeline(img,lut,idx):
    p=P(idx); a=toned(img,lut,p); a=detail(a); return film(a,p,idx)
if __name__=='__main__':
    d,idx,src,dst=sys.argv[1],int(sys.argv[2]),sys.argv[3],sys.argv[4]
    im=Image.open(src).convert('RGB')
    if len(sys.argv)>5: im.thumbnail((int(sys.argv[5]),)*2)
    a=np.asarray(im); lut=load_lut(f'{d}/tone-{idx}.lut')
    Image.fromarray(pipeline(a,lut,idx)).save(dst,quality=95)
