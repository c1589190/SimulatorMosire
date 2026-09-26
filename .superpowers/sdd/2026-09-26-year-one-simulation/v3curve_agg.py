"""v3curve：逐格聚合三国读数（并发版）。

用法: python3 v3curve_agg.py <label>
产物: /tmp/v3curve_<label>.json
"""

import json
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simos_mcp import handshake, call_json  # noqa: E402

GUI = 'http://127.0.0.1:5827'
NATIONS = ['德意志第二帝国', '奥斯特马克侯国', '霍赫兰伯国']
AGE = ['0-14', '15-59', '60+']
POOL = 16


def get(path):
    return json.load(urllib.request.urlopen(GUI + path, timeout=120))


def fetch_hex(q, r):
    """取一格的 social + economy（两个接口）。"""
    return q, r, get(f'/api/social/population?q={q}&r={r}'), get(f'/api/economy/hex?q={q}&r={r}')


def merge(dst, src):
    """递归合并（叶子是数字 ⇒ 相加；dict ⇒ 下探）。crisisEvidence 是列表不参与。"""
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
    return {
        'hex': 0, 'pop': 0, 'urban': 0, 'rural': 0,
        'age': {k: 0 for k in AGE}, 'male': 0, 'female': 0,
        'avail': 0, 'alloc': 0, 'stressMax': 0, 'stressAvgSum': 0,
        'goods': {}, 'unmet': {}, 'crisis': {}, 'crisisEvidence': [],
        'debtN': 0, 'debtP': 0, 'interestDue': 0, 'newBorrowing': 0,
        'land': 0, 'ind': 0, 'farm': 0, 'weave': 0, 'craft': 0,
        'deaths': 0, 'births': 0,
        'classes': {},   # slot -> {pop, land, grain, cloth, debtP, unmet, income, consumed}
        'hexesWithCrisis': 0,
    }


def add_class(a, slot, row, flow, ind_id=''):
    c = a['classes'].setdefault(slot, {
        'pop': 0, 'land': 0, 'grain': 0, 'cloth': 0, 'debtN': 0,
        'unmetGrain': 0, 'unmetCloth': 0, 'income': {}, 'consumed': {},
        'deaths': 0, 'births': 0, 'newBorrowing': 0, 'interestDue': 0,
        'repaid': 0, 'taxPaid': 0, 'netSurplus': 0,
        'byInd': {},
    })
    if ind_id:
        kind = ('weave' if ind_id.startswith('weave')
                else 'craft' if ind_id.startswith('craft') else 'farm')
        d = c['byInd'].setdefault(kind, {'pop': 0, 'land': 0, 'grain': 0, 'cloth': 0,
                                         'unmetGrain': 0, 'unmetCloth': 0,
                                         'deaths': 0, 'births': 0})
        d['pop'] += row.get('population', 0)
        d['land'] += row.get('landMilliMu', 0) // 1000
        g0 = row.get('goods') or {}
        d['grain'] += g0.get('grain', 0)
        d['cloth'] += g0.get('cloth', 0)
        un0 = (flow or {}).get('unmetNeed') or {}
        d['unmetGrain'] += un0.get('grain', 0)
        d['unmetCloth'] += un0.get('cloth', 0)
        d['deaths'] += (flow or {}).get('deaths', 0)
        d['births'] += (flow or {}).get('births', 0)
    c['pop'] += row.get('population', 0)
    c['land'] += row.get('landMilliMu', 0) // 1000
    g = row.get('goods') or {}
    c['grain'] += g.get('grain', 0)
    c['cloth'] += g.get('cloth', 0)
    c['debtN'] += len(row.get('debts') or [])  # ★ debts 是 id 字符串列表，本金只在格级
    f = flow or {}
    un = f.get('unmetNeed') or {}
    c['unmetGrain'] += un.get('grain', 0)
    c['unmetCloth'] += un.get('cloth', 0)
    c['deaths'] += f.get('deaths', 0)
    c['births'] += f.get('births', 0)
    c['newBorrowing'] += f.get('newBorrowing', 0)
    c['interestDue'] += f.get('interestDue', 0)
    c['repaid'] += f.get('repaid', 0)
    c['taxPaid'] += f.get('taxPaid', 0)
    c['netSurplus'] += f.get('netSurplus', 0)
    for k, v in (f.get('income') or {}).items():
        c['income'][k] = c['income'].get(k, 0) + v
    for k, v in (f.get('consumed') or {}).items():
        c['consumed'][k] = c['consumed'].get(k, 0) + v


def agg_nation(sid):
    pass


def main():
    label = sys.argv[1]
    sid = handshake()
    out = {}
    total = blank()
    for nation in NATIONS:
        a = blank()
        hexes = call_json(sid, 'simos.map.region', {'regionId': nation})['hexes']
        coords = [(h['q'], h['r']) for h in hexes]
        with ThreadPoolExecutor(max_workers=POOL) as ex:
            results = list(ex.map(lambda c: fetch_hex(*c), coords))
        for q, r, s, e in results:
            a['hex'] += 1
            g = s.get('groups') or {}
            a['pop'] += g.get('total', 0)
            a['urban'] += g.get('urban', 0)
            a['rural'] += g.get('rural', 0)
            for k, v in (g.get('ageBrackets') or {}).items():
                a['age'][k] = a['age'].get(k, 0) + v
            sx = g.get('sex') or {}
            a['male'] += sx.get('MALE', 0)
            a['female'] += sx.get('FEMALE', 0)
            st = g.get('physiologicalStress') or {}
            a['stressMax'] = max(a['stressMax'], st.get('max', 0))
            a['stressAvgSum'] += st.get('average', 0)
            lb = s.get('labor') or {}
            a['avail'] += lb.get('availableMilli', 0)
            a['alloc'] += lb.get('allocatedMilli', 0)
            cr = s.get('crisis') or []
            if cr:
                a['hexesWithCrisis'] += 1
            for c in cr:
                k = c.get('kind') if isinstance(c, dict) else str(c)
                a['crisis'][k] = a['crisis'].get(k, 0) + 1
                if len(a['crisisEvidence']) < 3:
                    a['crisisEvidence'].append({'q': q, 'r': r, 'kind': k,
                                                'evidence': c.get('evidence')})
            if not e.get('activated'):
                continue
            a['land'] += e.get('landMilliMu', 0) // 1000
            a['debtN'] += e.get('debtCount', 0)
            a['debtP'] += e.get('debtPrincipal', 0)
            for k, v in (e.get('goods') or {}).items():
                a['goods'][k] = a['goods'].get(k, 0) + v
            for ind in e.get('industries', []):
                a['ind'] += 1
                i = ind['id']
                if i.startswith('weave'):
                    a['weave'] += 1
                elif i.startswith('craft'):
                    a['craft'] += 1
                else:
                    a['farm'] += 1
                for c in ind.get('classes', []):
                    add_class(a, c.get('slot', '?'), c, c.get('flow'), i)
                    f = c.get('flow') or {}
                    a['deaths'] += f.get('deaths', 0)
                    a['births'] += f.get('births', 0)
                    a['interestDue'] += f.get('interestDue', 0)
                    a['newBorrowing'] += f.get('newBorrowing', 0)
                    for k, v in (f.get('unmetNeed') or {}).items():
                        a['unmet'][k] = a['unmet'].get(k, 0) + v
        out[nation] = a
        merge(total, a)

    json.dump({'label': label, 'nations': out, 'total': total},
              open(f'/tmp/v3curve_{label}.json', 'w'), ensure_ascii=False)
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
              f"死{c['deaths']:>6,} 生{c['births']:>6,} 新借{c['newBorrowing']//1000:>8,} 息{c['interestDue']//1000:>7,}")
    print('阶层 × 产业:')
    for slot in sorted(total['classes']):
        for kind in ('farm', 'weave', 'craft'):
            d = total['classes'][slot].get('byInd', {}).get(kind)
            if not d:
                continue
            print(f"  {slot:<9}/{kind:<6} 人口{d['pop']:>9,} 地{d['land']:>8,}亩 粮{d['grain']//1000:>9,} "
                  f"布{d['cloth']//1000:>8,} 缺粮{d['unmetGrain']//1000:>8,} 缺布{d['unmetCloth']//1000:>8,} "
                  f"死{d['deaths']:>6,} 生{d['births']:>6,}")


if __name__ == '__main__':
    main()
