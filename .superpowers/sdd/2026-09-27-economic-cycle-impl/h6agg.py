"""h6agg：把 600 天三国模拟的**逐格原始读数**聚合成"曲线"口径的报表。

用法: python3 h6agg.py <label>        例: python3 h6agg.py tick120
输入: /tmp/h6raw_<label>.json（h6sim_dump.py 落的原始读数；★ 不联网、不连 MCP、不碰 Maven）
产物: /tmp/h6agg_<label>.json          {"label":..., "nations":{...}, "total":{...}}

★ 口径来自 .superpowers/sdd/2026-09-26-year-one-simulation/v3curve_agg.py（活服务器逐格拉取版）：
  **只换数据的来路与形状，不换口径** —— 同一批合计字段、同样的 /1000 折算（只在打印时）、
  同样的打印排版、同样的 NATIONS 顺序。这样 H6 的曲线才能与 H0–H5 的曲线直接对比。

★ 为什么这样取数（H0–H5 改造后的三处硬差异）：
  1. 阶层行现在在**格 entry 级**（economy["classes"]），**不再挂在 industries[*] 下**，
     且行里没有任何"产业"字段（只有 residence/slot）⇒ 旧的"阶层 × 产业"交叉表**没有数据支撑**，
     改成"阶层 × 居住类型"（rural / urban）。产业计数仍按 industries[*]["id"] 前缀分 farm/weave/craft。
  2. **钱**：格级真值是 actorMoneyTotal（逐币种），行级是 actorMoney（逐币种）。
     格级那个 "money":0 是**结构性 0 的旧字段**（正在被收口、可能随时消失）⇒ 本脚本一律不读它，
     免得曲线里混进一个"看起来永远没钱"的假读数。阶层行里的 "money":0 同理不读。
  3. 新增三段（关账三问 + 城市缺口 + 债务曲线）：货币 / 市场去重 / 纤维-工具产业链判据。
     产业链判据**不发明口径**：布是否入账就用现成的 classes[].flow.income["cloth"]，
     "城市格"就用现成的 industries[].id 前缀 craft。

★ 一切整数运算：读进来的叶子统一 _i() 收口成 int（防服务端给 1.0 这种浮点渗进曲线），
  折算只在打印时 //1000，全脚本没有 float。
"""

import json
import sys

NATIONS = ['德意志第二帝国', '奥斯特马克侯国', '霍赫兰伯国']  # ★ 顺序照旧，曲线可比
AGE = ['0-14', '15-59', '60+']
RAW_FMT = '/tmp/h6raw_%s.json'
OUT_FMT = '/tmp/h6agg_%s.json'


def _i(v):
    """读数叶子 -> int（本项目一切整数运算；服务端万一给浮点也在此收口，绝不外泄 float）。"""
    if isinstance(v, bool) or v is None:
        return 0
    if isinstance(v, int):
        return v
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def merge(dst, src):
    """递归合并（叶子是数字 ⇒ 相加；dict ⇒ 下探）。crisisEvidence 是列表不参与（照旧）。"""
    for k, v in src.items():
        if k == 'crisisEvidence':
            continue
        if isinstance(v, dict):
            merge(dst.setdefault(k, {}), v)
        elif isinstance(v, list):
            continue
        else:
            dst[k] = dst.get(k, 0) + v


def blank():
    """一国（或合计）的空白累计器；字段名与旧脚本 v3curve_agg.blank() 一一对应。"""
    return {
        'hex': 0, 'pop': 0, 'urban': 0, 'rural': 0,
        'age': {k: 0 for k in AGE}, 'male': 0, 'female': 0,
        'avail': 0, 'alloc': 0, 'stressMax': 0, 'stressAvgSum': 0,
        'goods': {}, 'unmet': {}, 'crisis': {}, 'crisisEvidence': [],
        'debtN': 0, 'debtP': 0, 'interestDue': 0, 'newBorrowing': 0, 'repaid': 0,
        'land': 0, 'ind': 0, 'farm': 0, 'weave': 0, 'craft': 0,
        'deaths': 0, 'births': 0,
        'classes': {},   # slot -> {pop, land, grain, cloth, debtN, unmetGrain, unmetCloth, ..., byRes{居住:同左}}
        'hexesWithCrisis': 0,
        # ——— 以下为 H6 新增（旧脚本没有；不影响旧字段与旧排版）———
        'money': {},            # 币种 -> actorMoneyTotal 合计（★ 不读格级结构性 0 的 money）
        'marketNumeraire': {},  # 计价货币 -> 出现格数（去重后要看得见"每格单一计价货币"）
        'marketPrices': {},     # 价格表规范串 -> 出现格数（去重后要看得见"固定价"）
        'commodityIds': {},     # 商品 id -> 出现格数；最终 finalize 成该国的并集列表
        'craftHex': 0,          # 有 craft 产业的格数（判据里的"城市格总数"）
        'craftHexCloth': 0,     # 其中"城镇行 income[cloth] 之和 > 0"的格数（作坊没停工）
        'craftHexTool': 0,      # 其中"城镇行 income[tool] 之和 > 0"的格数（补充证据）
        'hexNoSocial': 0,       # social 读数缺失的格数（诊断；正常为 0）
        'incomeKeys': {},       # flow.income 里真实出现过的商品 -> 行数（判断某列是不是结构性 0）
        'unmetKeys': {},        # flow.unmetNeed 里真实出现过的商品 -> 行数（同上）
    }


def fmt_money(d):
    """逐币种钱的紧凑打印（★ 钱一律来自 actorMoneyTotal / actorMoney）。"""
    if not d:
        return '-'
    return ' '.join(f'{k} {v:,}' for k, v in sorted(d.items()))


def price_key(prices):
    """价格表的规范键：把 {"cloth":5,...} 变成可去重、可排序的字符串（去重靠它）。"""
    return json.dumps(sorted((k, _i(v)) for k, v in prices.items()), ensure_ascii=False)


def income_sum(rows, commodity):
    """一组阶层行的 flow.income[商品] 求和 —— 作坊是否停工的判据就用它，不另发明口径。"""
    return sum(_i(((r.get('flow') or {}).get('income') or {}).get(commodity, 0)) for r in rows)


def add_class(a, slot, row, flow, residence):
    """把一条阶层行并进（slot）与（slot × 居住类型）。

    ★ 与旧脚本的差别：旧脚本的第三参是产业 id（ind_id），新形状的行里没有产业 ⇒
      换成 residence。行里也**没有 landMilliMu**，故阶层"地"结构性为 0（新形状无对应物）。
    """
    c = a['classes'].setdefault(slot, {
        'pop': 0, 'land': 0, 'grain': 0, 'cloth': 0, 'debtN': 0,
        'unmetGrain': 0, 'unmetCloth': 0, 'income': {}, 'consumed': {},
        'deaths': 0, 'births': 0, 'newBorrowing': 0, 'interestDue': 0,
        'repaid': 0, 'taxPaid': 0, 'netSurplus': 0,
        'money': {},     # 币种 -> actorMoney 合计
        'byRes': {},     # 居住类型 -> 同结构的子行（替代旧的 byInd）
    })
    res = residence or '?'
    d = c['byRes'].setdefault(res, {'pop': 0, 'land': 0, 'grain': 0, 'cloth': 0,
                                    'unmetGrain': 0, 'unmetCloth': 0,
                                    'deaths': 0, 'births': 0, 'money': {}})
    g = row.get('goods') or {}
    un = (flow or {}).get('unmetNeed') or {}
    f = flow or {}
    for tgt in (c, d):
        tgt['pop'] += _i(row.get('population', 0))
        tgt['land'] += _i(row.get('landMilliMu', 0)) // 1000
        tgt['grain'] += _i(g.get('grain', 0))
        tgt['cloth'] += _i(g.get('cloth', 0))
        tgt['unmetGrain'] += _i(un.get('grain', 0))
        tgt['unmetCloth'] += _i(un.get('cloth', 0))
        tgt['deaths'] += _i(f.get('deaths', 0))
        tgt['births'] += _i(f.get('births', 0))
        for k, v in (row.get('actorMoney') or {}).items():   # ★ 钱：行级 actorMoney
            tgt['money'][k] = tgt['money'].get(k, 0) + _i(v)
    c['debtN'] += len(row.get('debts') or [])   # ★ debts 是 id 列表 ⇒ 数条数；本金只在格级
    c['newBorrowing'] += _i(f.get('newBorrowing', 0))
    c['interestDue'] += _i(f.get('interestDue', 0))
    c['repaid'] += _i(f.get('repaid', 0))
    c['taxPaid'] += _i(f.get('taxPaid', 0))
    c['netSurplus'] += _i(f.get('netSurplus', 0))
    for k, v in (f.get('income') or {}).items():
        c['income'][k] = c['income'].get(k, 0) + _i(v)
    for k, v in (f.get('consumed') or {}).items():
        c['consumed'][k] = c['consumed'].get(k, 0) + _i(v)


def agg_nation(rows):
    """一国的逐格聚合。读数的取法逐条对齐旧脚本，只按新形状改来路。"""
    a = blank()
    for row in rows:
        q, r = row.get('q'), row.get('r')
        s = row.get('social') or {}
        e = row.get('economy') or {}
        a['hex'] += 1
        if not s:
            a['hexNoSocial'] += 1
        g = s.get('groups') or {}
        a['pop'] += _i(g.get('total', 0))
        a['urban'] += _i(g.get('urban', 0))
        a['rural'] += _i(g.get('rural', 0))
        for k, v in (g.get('ageBrackets') or {}).items():
            a['age'][k] = a['age'].get(k, 0) + _i(v)
        sx = g.get('sex') or {}
        a['male'] += _i(sx.get('MALE', 0))
        a['female'] += _i(sx.get('FEMALE', 0))
        st = g.get('physiologicalStress') or {}
        a['stressMax'] = max(a['stressMax'], _i(st.get('max', 0)))
        a['stressAvgSum'] += _i(st.get('average', 0))
        lb = s.get('labor') or {}
        a['avail'] += _i(lb.get('availableMilli', 0))
        a['alloc'] += _i(lb.get('allocatedMilli', 0))
        cr = s.get('crisis') or []
        if cr:
            a['hexesWithCrisis'] += 1
        for c in cr:
            k = c.get('kind') if isinstance(c, dict) else str(c)
            a['crisis'][k] = a['crisis'].get(k, 0) + 1
            if len(a['crisisEvidence']) < 3:
                a['crisisEvidence'].append({'q': q, 'r': r, 'kind': k,
                                            'evidence': (c or {}).get('evidence') if isinstance(c, dict) else None})
        if not e.get('activated'):
            continue   # ★ 照旧：未激活的格不计土地/债务/商品/产业/阶层
        a['land'] += _i(e.get('landMilliMu', 0)) // 1000
        a['debtN'] += _i(e.get('debtCount', 0))
        a['debtP'] += _i(e.get('debtPrincipal', 0))
        for k, v in (e.get('goods') or {}).items():
            a['goods'][k] = a['goods'].get(k, 0) + _i(v)
        # ★ 钱：格级真值 actorMoneyTotal（逐币种）；结构性 0 的 e["money"] 不读
        for k, v in (e.get('actorMoneyTotal') or {}).items():
            a['money'][k] = a['money'].get(k, 0) + _i(v)
        # ★ 市场：numeraire 与价格表去重 + commodityIds 并集（判据要看得见）
        mk = e.get('market') or {}
        if mk.get('numeraire'):
            a['marketNumeraire'][mk['numeraire']] = a['marketNumeraire'].get(mk['numeraire'], 0) + 1
        if isinstance(mk.get('prices'), dict) and mk['prices']:
            kk = price_key(mk['prices'])
            a['marketPrices'][kk] = a['marketPrices'].get(kk, 0) + 1
        for cid in (e.get('commodityIds') or []):
            a['commodityIds'][cid] = a['commodityIds'].get(cid, 0) + 1
        # 产业计数：照旧按 id 前缀分 farm/weave/craft（不再下探 classes）
        has_craft = False
        for ind in (e.get('industries') or []):
            a['ind'] += 1
            i = ind.get('id', '')
            if i.startswith('weave'):
                a['weave'] += 1
            elif i.startswith('craft'):
                a['craft'] += 1
                has_craft = True
            else:
                a['farm'] += 1
        # ★ 阶层行在格 entry 级：一行同时喂"阶层合计"与"阶层 × 居住"
        cls = e.get('classes') or []
        for c in cls:
            f = c.get('flow') or {}
            add_class(a, c.get('slot', '?'), c, f, c.get('residence'))
            # 国级：照旧只从阶层流里取这四项 + 缺口
            a['deaths'] += _i(f.get('deaths', 0))
            a['births'] += _i(f.get('births', 0))
            a['interestDue'] += _i(f.get('interestDue', 0))
            a['newBorrowing'] += _i(f.get('newBorrowing', 0))
            a['repaid'] += _i(f.get('repaid', 0))
            for k, v in (f.get('unmetNeed') or {}).items():
                a['unmet'][k] = a['unmet'].get(k, 0) + _i(v)
            # ★ 记账"哪些键真的出现过"：免得把"新形状里根本没这个键"误读成"这一项为 0"
            for k in (f.get('income') or {}):
                a['incomeKeys'][k] = a['incomeKeys'].get(k, 0) + 1
            for k in (f.get('unmetNeed') or {}):
                a['unmetKeys'][k] = a['unmetKeys'].get(k, 0) + 1
        # ★ 产业链判据②：城市格（有 craft 产业的格）里作坊是否停工 = 城镇四行 income[cloth] 之和 > 0
        if has_craft:
            a['craftHex'] += 1
            urban = [c for c in cls if (c.get('residence') or '?') == 'urban']
            if income_sum(urban, 'cloth') > 0:
                a['craftHexCloth'] += 1
            if income_sum(urban, 'tool') > 0:
                a['craftHexTool'] += 1
    return a


def finalize(a):
    """把 commodityIds 的"出现格数"字典定稿成排序后的并集列表（打印与落盘都用它）。"""
    a['commodityIds'] = sorted(a['commodityIds'])
    return a


def main():
    if len(sys.argv) < 2:
        print('用法: python3 h6agg.py <label>   例: python3 h6agg.py tick120', file=sys.stderr)
        return 2
    label = sys.argv[1]
    raw_path = RAW_FMT % label
    with open(raw_path) as fh:
        raw = json.load(fh)
    print(f'原始读数: {raw_path}（label={raw.get("label")}）')

    out = {}
    total = blank()
    for nation in NATIONS:   # ★ 顺序照旧；缺国就报出来，不静默跳过
        rows = (raw.get('nations') or {}).get(nation)
        if rows is None:
            print(f'  !! 原始读数里没有 {nation}', file=sys.stderr)
            rows = []
        a = agg_nation(rows)
        out[nation] = a
        merge(total, a)

    out = {n: finalize(a) for n, a in out.items()}
    total = finalize(total)

    with open(OUT_FMT % label, 'w') as fh:
        json.dump({'label': label, 'nations': out, 'total': total}, fh, ensure_ascii=False)
    print(f'聚合已存: {OUT_FMT % label}')

    # ——————————————— 以下打印排版照旧（与 v3curve_agg.py 逐字对齐）———————————————
    print(f'########## {label} ##########')
    for n, a in out.items():
        print(f"{n[:8]:<9} 格{a['hex']:>4} 人口{a['pop']:>10,} 城{a['urban']:>8,} 乡{a['rural']:>9,} "
              f"0-14 {a['age']['0-14']:>9,} 15-59 {a['age']['15-59']:>9,} 60+ {a['age']['60+']:>8,} "
              f"压力max {a['stressMax']:>5}")
    print(f"{'合计':<9} 格{total['hex']:>4} 人口{total['pop']:>10,} 城{total['urban']:>8,} 乡{total['rural']:>9,} "
          f"0-14 {total['age']['0-14']:>9,} 15-59 {total['age']['15-59']:>9,} 60+ {total['age']['60+']:>8,} "
          f"压力max {total['stressMax']:>5}")
    print(f"生死: 出生 {total['births']:,} / 死亡 {total['deaths']:,} / 净 {total['births']-total['deaths']:+,}")
    print(f"劳动: 可用 {total['avail']:,} 已分配 {total['alloc']:,}")
    print(f"土地 {total['land']:,} 亩  产业 {total['ind']}（农 {total['farm']} / 织 {total['weave']} / 作坊 {total['craft']}）")
    print(f"债务 {total['debtN']:,} 条 / 本金 {total['debtP']//1000:,} 粮 / 新借 {total['newBorrowing']//1000:,} / 应付息 {total['interestDue']//1000:,}")
    print('商品库存(/1000):', {k: v // 1000 for k, v in sorted(total['goods'].items())})
    print('本期缺口(/1000):', {k: v // 1000 for k, v in sorted(total['unmet'].items())})
    print('红灯(格数):', dict(sorted(total['crisis'].items())), f"涉及格 {total['hexesWithCrisis']}")
    for ev in total['crisisEvidence'][:3]:
        print('   样例:', ev['q'], ev['r'], ev['kind'], json.dumps(ev['evidence'], ensure_ascii=False)[:220])
    print('阶层（合计）:')
    for slot, c in sorted(total['classes'].items()):
        print(f"  {slot:<9} 人口{c['pop']:>9,} 地{c['land']:>8,}亩 粮{c['grain']//1000:>9,} 布{c['cloth']//1000:>8,} "
              f"债{c['debtN']:>6,}条 缺粮{c['unmetGrain']//1000:>8,} 缺布{c['unmetCloth']//1000:>8,} "
              f"死{c['deaths']:>6,} 生{c['births']:>6,} 新借{c['newBorrowing']//1000:>8,} 息{c['interestDue']//1000:>7,} "
              f"钱 {fmt_money(c['money'])}")
    print('阶层 × 居住:')   # ★ 旧脚本这里是"阶层 × 产业"；新形状行里没有产业 ⇒ 换成居住类型
    for slot in sorted(total['classes']):
        for res in sorted(total['classes'][slot].get('byRes', {})):
            d = total['classes'][slot]['byRes'][res]
            print(f"  {slot:<9}/{res:<6} 人口{d['pop']:>9,} 地{d['land']:>8,}亩 粮{d['grain']//1000:>9,} "
                  f"布{d['cloth']//1000:>8,} 缺粮{d['unmetGrain']//1000:>8,} 缺布{d['unmetCloth']//1000:>8,} "
                  f"死{d['deaths']:>6,} 生{d['births']:>6,} 钱 {fmt_money(d['money'])}")

    # ——————————————— 新增段 1/3：货币（actorMoneyTotal 逐币种 + 逐阶层 actorMoney）———————————————
    print(f"货币(合计): {dict(sorted(total['money'].items()))}")
    for n, a in out.items():
        print(f"  {n[:8]:<9} 货币 {fmt_money(a['money'])}")
    # ——————————————— 新增段 2/3：市场（numeraire / prices 去重 + commodityIds 并集）———————————————
    print(f"市场计价货币(numeraire 去重, 值=出现格数): {dict(sorted(total['marketNumeraire'].items()))}")
    print(f"市场价格表(去重 {len(total['marketPrices'])} 组):")
    for kk, cnt in sorted(total['marketPrices'].items(), key=lambda kv: -kv[1]):
        print(f"  出现 {cnt:>5} 格: {dict(json.loads(kk))}")
    ids = total['commodityIds']
    # ★ 只报事实（格级库存与本期缺口都是 0），"是不是留位商品"由判据去下结论
    idle = [k for k in ids if total['goods'].get(k, 0) == 0 and total['unmet'].get(k, 0) == 0]
    print(f"commodityIds 并集({len(ids)}): {ids}   其中格级库存与本期缺口皆 0: {idle}")
    # ——————————————— 新增段 3/3：纤维/工具产业链（城市缺口 + 作坊是否停工）———————————————
    print('纤维/工具 库存与缺口(/1000):')
    for n in list(out) + ['合计']:
        a = total if n == '合计' else out[n]
        print(f"  {n[:8]:<9} 纤维 库存{a['goods'].get('fiber', 0)//1000:>9,} 缺{a['unmet'].get('fiber', 0)//1000:>9,} "
              f" 工具 库存{a['goods'].get('tool', 0)//1000:>9,} 缺{a['unmet'].get('tool', 0)//1000:>9,}")
    print('作坊是否停工（城市格 = 有 craft 产业的格；判据 = 该格城镇行 income["cloth"] 之和 > 0）:')
    for n in list(out) + ['合计']:
        a = total if n == '合计' else out[n]
        print(f"  {n[:8]:<9} 布入账>0 的城市格 {a['craftHexCloth']:>4} / {a['craftHex']:<4} "
              f"工具入账>0 的城市格 {a['craftHexTool']:>4} / {a['craftHex']:<4}")
    # ★ 结构性 0 必须显式提醒：否则"缺 0"会被读成"没有缺口"，那是把新形状的键集合误当结论
    if 'tool' not in total['incomeKeys']:
        print(f"  !! flow.income 的键集合是 {sorted(total['incomeKeys'])}（没有 tool）⇒ " 
              f"'工具入账' 列结构性为 0，只能说明这条读数里没有工具产出量，不能读作工具作坊停工")
    lack = [k for k in ('fiber', 'tool') if k not in total['unmetKeys']]
    if lack:
        print(f"  !! flow.unmetNeed 的键集合是 {sorted(total['unmetKeys'])}（没有 {'/'.join(lack)}）⇒ "
              f"纤维/工具那两列'缺'是结构性 0，不能读作'本期无纤维/工具缺口'")
    return 0


if __name__ == '__main__':
    sys.exit(main())
