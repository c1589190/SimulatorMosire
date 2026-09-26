"""B1/B2 修复后的最小 probe（端口 5745/5847）。

判据 ①：0-14 档与新生儿批次的压力不再恒 0（读 changeset 的 social.groups）
判据 ②：同一格在两个相位读到的**满足率类**红灯集合一致（FOOD/CLOTH/DEBT）
"""

import json
import sqlite3
import sys
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

MCP = 'http://127.0.0.1:5745/mcp'
GUI = 'http://127.0.0.1:5847'
DB = '/home/cna/.claude/jobs/3473dae6/tmp/simos-probe2/simos.db'
NATIONS = ['德意志第二帝国', '奥斯特马克侯国', '霍赫兰伯国']
# B2 的病只出在"分子至今累计、分母整周期"的类别上（MORTALITY/LABOR_BURDEN 本就随相位增长）
SATISFACTION_KINDS = {'FOOD', 'CLOTH', 'DEBT'}


def _post(body, sid=None, timeout=900):
    h = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
    if sid:
        h['Mcp-Session-Id'] = sid
    req = urllib.request.Request(MCP, data=json.dumps(body).encode(), headers=h)
    r = urllib.request.urlopen(req, timeout=timeout)
    return dict(r.headers), r.read().decode()


def handshake():
    h, _ = _post({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
                  'params': {'protocolVersion': '2024-11-05', 'capabilities': {},
                             'clientInfo': {'name': 'probe2', 'version': '1'}}})
    sid = h.get('Mcp-session-id') or h.get('Mcp-Session-Id')
    _post({'jsonrpc': '2.0', 'method': 'notifications/initialized'}, sid)
    return sid


def call(sid, name, args):
    _, raw = _post({'jsonrpc': '2.0', 'id': 9, 'method': 'tools/call',
                    'params': {'name': name, 'arguments': args}}, sid)
    d = [l[6:] for l in raw.splitlines() if l.startswith('data: ')]
    o = json.loads(d[-1])
    r = o.get('result', {})
    t = ' '.join(c.get('text', '') for c in r.get('content', []))
    if r.get('isError'):
        raise RuntimeError(f'{name}: {t[:400]}')
    return t


def timeline(sid):
    tl = json.loads(call(sid, 'simos.timeline.revisions', {'branch': 'main'}))
    nodes = tl.get('nodes') if isinstance(tl, dict) else tl
    return max(n['revision'] for n in nodes), max(n['tick'] for n in nodes)


def advance_to(sid, target):
    rev, tick = timeline(sid)
    if target <= tick:
        print(f'  已在 tick={tick}，无需推进')
        return
    print(f'  推 {tick} → {target}（rev={rev}）')
    call(sid, 'simos.advance',
         {'branch': 'main', 'expectedRevision': rev, 'from': tick, 'to': target})


def get(p):
    return json.load(urllib.request.urlopen(GUI + p, timeout=120))


def read_crisis(sid, hexes):
    """逐格读满足率类红灯集合。"""
    def one(h):
        s = get(f"/api/social/population?q={h['q']}&r={h['r']}")
        kinds = {c['kind'] for c in (s.get('crisis') or [])}
        return (h['q'], h['r']), frozenset(kinds & SATISFACTION_KINDS), s

    with ThreadPoolExecutor(max_workers=16) as ex:
        return list(ex.map(one, hexes))


def group_stress():
    """判据 ①：从 changeset 读批次压力，按 (前缀, 档位) 分组。"""
    con = sqlite3.connect(f'file:{DB}?mode=ro', uri=True)
    row = con.execute('select changeset_json from revisions order by revision desc limit 1').fetchone()
    d = json.loads(row[0])
    ent = d['modules']['social']['groups']['entries']
    buckets = defaultdict(list)
    for k, v in ent.items():
        parts = k.split(':')
        res, br = parts[0], parts[3]
        if br.startswith('b'):
            tag = f'{res} 新生儿(:b*)'
        elif br in ('0', '1', '2'):
            tag = f'{res} 档{br}'
        else:
            continue
        buckets[tag].append(v['physiologicalStress'])
    return {t: (len(a), min(a), max(a)) for t, a in sorted(buckets.items())}


if __name__ == '__main__':
    cmd = sys.argv[1]
    sid = handshake()
    if cmd == 'seed':
        for n in NATIONS:
            print(f'  {n}: ' + call(sid, 'simos.worldgen.initialize',
                                   {'nation': n, 'army': True, 'dryRun': False})[:80])
    elif cmd == 'run':
        t1, t2 = int(sys.argv[2]), int(sys.argv[3])
        # 判据 ② 的两个相位
        advance_to(sid, t1)
        hexes = json.loads(call(sid, 'simos.map.region', {'regionId': '奥斯特马克侯国'}))['hexes']
        a = read_crisis(sid, hexes)
        print(f'  [相位 A] tick={t1}')
        advance_to(sid, t2)
        b = read_crisis(sid, hexes)
        print(f'  [相位 B] tick={t2}')
        amap = {k: v for k, v, _ in a}
        bmap = {k: v for k, v, _ in b}
        diff = [k for k in amap if amap[k] != bmap[k]]
        union_a = set().union(*amap.values()) if amap else set()
        union_b = set().union(*bmap.values()) if bmap else set()
        print(json.dumps({
            'tickA': t1, 'tickB': t2, 'hexes': len(amap),
            'unionA': sorted(union_a), 'unionB': sorted(union_b),
            'perHexDiff': len(diff), 'diffSample': diff[:5],
        }, ensure_ascii=False, indent=1))
        json.dump({'tickA': t1, 'tickB': t2,
                   'a': {f'{q},{r}': sorted(v) for (q, r), v, _ in a},
                   'b': {f'{q},{r}': sorted(v) for (q, r), v, _ in b}},
                  open('/home/cna/.claude/jobs/3473dae6/tmp/probe2_crisis.json', 'w'),
                  ensure_ascii=False)
    elif cmd == 'stress':
        print(json.dumps(group_stress(), ensure_ascii=False, indent=1))
