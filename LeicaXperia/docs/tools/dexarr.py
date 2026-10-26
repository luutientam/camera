import sys,struct
d=open(sys.argv[1],'rb').read()
for off,n in [(int(a,16),int(b)) for a,b in (x.split(':') for x in sys.argv[2:])]:
    assert d[off:off+2]==b'\x00\x03', d[off:off+8].hex()
    w,cnt=struct.unpack_from('<HI',d,off+2)
    vals=struct.unpack_from('<%dd'%cnt,d,off+8) if w==8 else None
    print(hex(off), w, cnt, vals)
