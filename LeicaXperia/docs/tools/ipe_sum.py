import json,sys,collections
d=json.load(open(sys.argv[1]))
print('top', list(d.keys()))
for t,v in d.items():
    if not isinstance(v,dict): print(t, type(v), str(v)[:200]); continue
    for k,arr in v.items():
        if not isinstance(arr,list): print(' ',t,k,str(arr)[:100]); continue
        e=arr[0]
        dims=len(e['path']); names=[p[0] for p in e['path']]
        ranges=[sorted(set((p[1],p[2]) for x in arr for p in [x['path'][i]])) for i in range(dims)]
        print(f" {t}/{k}: entries={len(arr)} datalen={len(e['data'])} pathnames={names} other_keys={[kk for kk in e if kk not in ('path','data')]}")
        for i,r in enumerate(ranges): print('    dim',i,names[i],r[:12], '...' if len(r)>12 else '')
