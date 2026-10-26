import json,sys
for fn in sys.argv[1:]:
    d=json.load(open(fn)); print('==',fn.split('/')[-1],'top keys',list(d.keys()))
    for k in d: print(' ',k, 'subkeys', len(d[k]), list(d[k].keys())[:6])
    M=d['metering']; L=d['luma']; S=d['stylization']; D=d['drc']
    H='hist_target_by_lux.hist_short_long_adjustment.'
    for k in ['hist_lux_node','hist_base_bright_start_pct','hist_dark_tone_end_pct','hist_dr_node','hist_bright_tone_ref_target']: print(H+k, M[H+k])
    for k in ['base_target_lux_node','base_target_dr_node','base_target']: print(k, M['base_target_by_lux.'+k])
    print('enable_stylization', S.get('enable_stylization'), S.get('enable_stylization.value'))
    print('style lux', S.get('style_base.style_base_target_lux_node'), S.get('style_base.style_base_target_scale'))
    W='weight_luma_calculation.'
    for k in ['lux_node','bright_start','bright_end','bright_weight']: print(W+k, L[W+k])
    t=L[W+'center_wghted_mtr_tbl']; print('tbl len',len(t)); 
    for r in range(16): print('  ',t[r*16:(r+1)*16])
    print('drc', D['normal_drc_ctrl.nor_drc_lux_node'], D['normal_drc_ctrl.max_drc_gain'])
