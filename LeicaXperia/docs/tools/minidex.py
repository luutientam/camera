# Minimal Dalvik disassembler (pure python) - usage: minidex.py file.dex 'Lclass;' [method]
import struct,sys
d=open(sys.argv[1],'rb').read()
def u4(o): return struct.unpack_from('<I',d,o)[0]
def uleb(o):
    r=0;s=0
    while True:
        b=d[o];o+=1;r|=(b&0x7f)<<s;s+=7
        if b<0x80: return r,o
def mutf8(o):
    _,o=uleb(o); e=d.index(b'\0',o); return d[o:e].decode('utf-8','replace')
H=dict(zip(['string_ids_size','string_ids_off','type_ids_size','type_ids_off','proto_ids_size','proto_ids_off','field_ids_size','field_ids_off','method_ids_size','method_ids_off','class_defs_size','class_defs_off'],struct.unpack_from('<12I',d,0x38)))
S=lambda i: mutf8(u4(H['string_ids_off']+4*i))
T=lambda i: S(u4(H['type_ids_off']+4*i))
def F(i):
    c,t,n=struct.unpack_from('<HHI',d,H['field_ids_off']+8*i); return '%s.%s:%s'%(T(c),S(n),T(t))
def P(i):
    sh,rt,po=struct.unpack_from('<III',d,H['proto_ids_off']+12*i)
    ps=''
    if po:
        n=u4(po); ps=''.join(T(struct.unpack_from('<H',d,po+4+2*k)[0]) for k in range(n))
    return '(%s)%s'%(ps,T(rt))
def M(i):
    c,p,n=struct.unpack_from('<HHI',d,H['method_ids_off']+8*i); return '%s.%s:%s'%(T(c),S(n),P(p))
# opcode table: name, format
ops={}
def reg(rng,names,fmt):
    for k,n in zip(rng,names): ops[k]=(n,fmt)
reg([0],['nop'],'10x');reg([1],['move'],'12x');reg([2],['move/from16'],'22x');reg([3],['move/16'],'32x')
reg([4],['move-wide'],'12x');reg([5],['move-wide/from16'],'22x');reg([6],['move-wide/16'],'32x')
reg([7],['move-object'],'12x');reg([8],['move-object/from16'],'22x');reg([9],['move-object/16'],'32x')
reg(range(0xa,0xe),['move-result','move-result-wide','move-result-object','move-exception'],'11x')
reg([0xe],['return-void'],'10x');reg(range(0xf,0x12),['return','return-wide','return-object'],'11x')
reg([0x12],['const/4'],'11n');reg([0x13],['const/16'],'21s');reg([0x14],['const'],'31i');reg([0x15],['const/high16'],'21h')
reg([0x16],['const-wide/16'],'21s');reg([0x17],['const-wide/32'],'31i');reg([0x18],['const-wide'],'51l');reg([0x19],['const-wide/high16'],'21hw')
reg([0x1a],['const-string'],'21c_s');reg([0x1b],['const-string/jumbo'],'31c');reg([0x1c],['const-class'],'21c_t')
reg([0x1d],['monitor-enter'],'11x');reg([0x1e],['monitor-exit'],'11x');reg([0x1f],['check-cast'],'21c_t');reg([0x20],['instance-of'],'22c_t')
reg([0x21],['array-length'],'12x');reg([0x22],['new-instance'],'21c_t');reg([0x23],['new-array'],'22c_t')
reg([0x24],['filled-new-array'],'35c_t');reg([0x25],['filled-new-array/range'],'3rc_t');reg([0x26],['fill-array-data'],'31t')
reg([0x27],['throw'],'11x');reg([0x28],['goto'],'10t');reg([0x29],['goto/16'],'20t');reg([0x2a],['goto/32'],'30t')
reg([0x2b],['packed-switch'],'31t');reg([0x2c],['sparse-switch'],'31t')
reg(range(0x2d,0x32),['cmpl-float','cmpg-float','cmpl-double','cmpg-double','cmp-long'],'23x')
reg(range(0x32,0x38),['if-eq','if-ne','if-lt','if-ge','if-gt','if-le'],'22t')
reg(range(0x38,0x3e),['if-eqz','if-nez','if-ltz','if-gez','if-gtz','if-lez'],'21t')
reg(range(0x44,0x52),['aget','aget-wide','aget-object','aget-boolean','aget-byte','aget-char','aget-short','aput','aput-wide','aput-object','aput-boolean','aput-byte','aput-char','aput-short'],'23x')
reg(range(0x52,0x60),['iget','iget-wide','iget-object','iget-boolean','iget-byte','iget-char','iget-short','iput','iput-wide','iput-object','iput-boolean','iput-byte','iput-char','iput-short'],'22c_f')
reg(range(0x60,0x6e),['sget','sget-wide','sget-object','sget-boolean','sget-byte','sget-char','sget-short','sput','sput-wide','sput-object','sput-boolean','sput-byte','sput-char','sput-short'],'21c_f')
reg(range(0x6e,0x73),['invoke-virtual','invoke-super','invoke-direct','invoke-static','invoke-interface'],'35c_m')
reg(range(0x74,0x79),['invoke-virtual/range','invoke-super/range','invoke-direct/range','invoke-static/range','invoke-interface/range'],'3rc_m')
reg(range(0x7b,0x90),['neg-int','not-int','neg-long','not-long','neg-float','neg-double','int-to-long','int-to-float','int-to-double','long-to-int','long-to-float','long-to-double','float-to-int','float-to-long','float-to-double','double-to-int','double-to-long','double-to-float','int-to-byte','int-to-char','int-to-short'],'12x')
bin_=['add','sub','mul','div','rem','and','or','xor','shl','shr','ushr']
names=[f'{o}-int' for o in bin_]+[f'{o}-long' for o in bin_]+[f'{o}-float' for o in bin_[:5]]+[f'{o}-double' for o in bin_[:5]]
reg(range(0x90,0x90+len(names)),names,'23x')
reg(range(0xb0,0xb0+len(names)),[n+'/2addr' for n in names],'12x')
reg(range(0xd0,0xd8),[n+'/lit16' for n in ['add-int','rsub-int','mul-int','div-int','rem-int','and-int','or-int','xor-int']],'22s')
reg(range(0xd8,0xe3),[n+'/lit8' for n in ['add-int','rsub-int','mul-int','div-int','rem-int','and-int','or-int','xor-int','shl-int','shr-int','ushr-int']],'22b')
reg([0xfa],['invoke-polymorphic'],'45cc');reg([0xfc],['invoke-custom'],'35c_m')
sizes={'10x':1,'12x':1,'11n':1,'11x':1,'10t':1,'20t':2,'22x':2,'21t':2,'21s':2,'21h':2,'21hw':2,'21c_s':2,'21c_t':2,'21c_f':2,'23x':2,'22b':2,'22t':2,'22s':2,'22c_t':2,'22c_f':2,'30t':3,'32x':3,'31i':3,'31t':3,'31c':3,'35c_t':3,'35c_m':3,'3rc_t':3,'3rc_m':3,'51l':5,'45cc':4}
def fl(x): return struct.unpack('<f',struct.pack('<I',x&0xffffffff))[0]
def dbl(x): return struct.unpack('<d',struct.pack('<Q',x&0xffffffffffffffff))[0]
def s16(x): return x-0x10000 if x&0x8000 else x
def s32(x): return x-(1<<32) if x&0x80000000 else x
def dis(off,n):
    W=struct.unpack_from('<%dH'%n,d,off); out=[]; pc=0
    while pc<n:
        w=W[pc]; op=w&0xff
        if op==0 and w!=0:
            if w==0x100: sz=W[pc+1]; out.append('%04x: packed-switch-data first=%d targets=%s'%(pc,s32(W[pc+2]|W[pc+3]<<16),[s32(W[pc+4+2*k]|W[pc+5+2*k]<<16) for k in range(sz)])); pc+=4+2*sz; continue
            if w==0x200: sz=W[pc+1]; keys=[s32(W[pc+2+2*k]|W[pc+3+2*k]<<16) for k in range(sz)]; tg=[s32(W[pc+2+2*sz+2*k]|W[pc+3+2*sz+2*k]<<16) for k in range(sz)]; out.append('%04x: sparse-switch-data %s'%(pc,list(zip(keys,tg)))); pc+=2+4*sz; continue
            if w==0x300:
                ew=W[pc+1]; cnt=W[pc+2]|W[pc+3]<<16; raw=d[off+2*(pc+4):off+2*(pc+4)+ew*cnt]
                if ew==4: vals=['%.7g/%d'%(f,i) for f,i in zip(struct.unpack('<%df'%cnt,raw),struct.unpack('<%di'%cnt,raw))]
                elif ew==8: vals=['%.10g'%v for v in struct.unpack('<%dd'%cnt,raw)]
                elif ew==2: vals=list(struct.unpack('<%dh'%cnt,raw))
                else: vals=list(raw)
                out.append('%04x: array-data w=%d %s'%(pc,ew,vals)); pc+=4+(ew*cnt+1)//2; continue
        name,fmt=ops.get(op,('op%02x'%op,'10x')); a=(w>>8)&0xff; A=a&0xf; B=a>>4
        if fmt=='10x': s=name
        elif fmt=='12x': s=f'{name} v{A}, v{B}'
        elif fmt=='11n': v=B-16 if B&8 else B; s=f'{name} v{A}, #{v}'
        elif fmt=='11x': s=f'{name} v{a}'
        elif fmt=='10t': t=a-256 if a&0x80 else a; s=f'{name} {pc+t:04x}'
        elif fmt=='20t': s=f'{name} {pc+s16(W[pc+1]):04x}'
        elif fmt=='22x': s=f'{name} v{a}, v{W[pc+1]}'
        elif fmt=='21t': s=f'{name} v{a}, {pc+s16(W[pc+1]):04x}'
        elif fmt=='21s': v=s16(W[pc+1]); s=f'{name} v{a}, #{v}'
        elif fmt=='21h': v=W[pc+1]<<16; s=f'{name} v{a}, #0x{v:08x} ;f={fl(v):.9g}'
        elif fmt=='21hw': v=W[pc+1]<<48; s=f'{name} v{a}, ;d={dbl(v):.12g}'
        elif fmt=='21c_s': s=f'{name} v{a}, {S(W[pc+1])!r}'
        elif fmt=='21c_t': s=f'{name} v{a}, {T(W[pc+1])}'
        elif fmt=='21c_f': s=f'{name} v{a}, {F(W[pc+1])}'
        elif fmt=='23x': b=W[pc+1]&0xff; c=W[pc+1]>>8; s=f'{name} v{a}, v{b}, v{c}'
        elif fmt=='22b': b=W[pc+1]&0xff; c=W[pc+1]>>8; c=c-256 if c&0x80 else c; s=f'{name} v{a}, v{b}, #{c}'
        elif fmt=='22t': s=f'{name} v{A}, v{B}, {pc+s16(W[pc+1]):04x}'
        elif fmt=='22s': s=f'{name} v{A}, v{B}, #{s16(W[pc+1])}'
        elif fmt=='22c_t': s=f'{name} v{A}, v{B}, {T(W[pc+1])}'
        elif fmt=='22c_f': s=f'{name} v{A}, v{B}, {F(W[pc+1])}'
        elif fmt=='30t': s=f'{name} {pc+s32(W[pc+1]|W[pc+2]<<16):04x}'
        elif fmt=='32x': s=f'{name} v{W[pc+1]}, v{W[pc+2]}'
        elif fmt=='31i': v=W[pc+1]|W[pc+2]<<16; s=f'{name} v{a}, #{s32(v)} (0x{v:08x}) ;f={fl(v):.9g}'
        elif fmt=='31t': s=f'{name} v{a}, {pc+s32(W[pc+1]|W[pc+2]<<16):04x}'
        elif fmt=='31c': s=f'{name} v{a}, {S(W[pc+1]|W[pc+2]<<16)!r}'
        elif fmt=='51l': v=W[pc+1]|W[pc+2]<<16|W[pc+3]<<32|W[pc+4]<<48; s=f'{name} v{a}, #{v} ;d={dbl(v):.12g}'
        elif fmt in('35c_t','35c_m','45cc'):
            cnt=B; G=A; idx=W[pc+1]; r=W[pc+2]; regs=[r&0xf,(r>>4)&0xf,(r>>8)&0xf,(r>>12)&0xf,G][:cnt]
            ref=T(idx) if fmt=='35c_t' else M(idx); s=f'{name} {{{", ".join("v%d"%x for x in regs)}}}, {ref}'
        elif fmt in('3rc_t','3rc_m'):
            idx=W[pc+1]; c=W[pc+2]; ref=T(idx) if fmt=='3rc_t' else M(idx); s=f'{name} {{v{c} .. v{c+a-1}}}, {ref}'
        out.append(f'{pc:04x}: {s}'); pc+=sizes[fmt]
    return out
want=sys.argv[2]; meth=sys.argv[3] if len(sys.argv)>3 else None
for ci in range(H['class_defs_size']):
    co=H['class_defs_off']+32*ci
    cidx,acc,sup,ifo,src,ann,cdo,sv=struct.unpack_from('<8I',d,co)
    if T(cidx)!=want or not cdo: continue
    o=cdo; sf,o=uleb(o); inf,o=uleb(o); dm,o=uleb(o); vm,o=uleb(o)
    fi=0
    for k in range(sf+inf):
        x,o=uleb(o); fi+=x if k not in (sf,) else 0
        if k==sf: fi=x
        _,o=uleb(o)
    # (field listing skipped)
    for grp,cnt in (('direct',dm),('virtual',vm)):
        mi=0
        for k in range(cnt):
            x,o=uleb(o); mi = x if k==0 else mi+x; a_,o=uleb(o); code,o=uleb(o)
            nm=M(mi)
            if meth and ('.'+meth+':') not in nm: continue
            print('==',grp,nm)
            if code:
                rs,ins,outs,tries,dbg,isz=struct.unpack_from('<HHHHII',code,0) if False else struct.unpack_from('<HHHHII',d,code)
                print('   regs=%d ins=%d'%(rs,ins))
                for l in dis(code+16,isz): print('   '+l)
