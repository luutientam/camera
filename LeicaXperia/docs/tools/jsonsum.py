import json,sys
def summ(o,path='',depth=0,maxd=6):
    if isinstance(o,dict):
        for k,v in o.items(): summ(v,path+'.'+k if path else k,depth+1)
    elif isinstance(o,list):
        def shape(x):
            return [len(x)]+shape(x[0]) if isinstance(x,list) and x else []
        s=shape(o); flat=o
        while flat and isinstance(flat[0],list): flat=flat[0]
        print(f"{path}: list{s} {str(flat[:8])[:90]}")
    else: print(f"{path}: {str(o)[:90]}")
d=json.load(open(sys.argv[1])); 
if len(sys.argv)>2:
    for p in sys.argv[2].split('/'): d=d[p]
summ(d)
