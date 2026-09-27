"""H6 关账模拟：逐格**原始读数**快照（把"推进"与"聚合"解耦）。

★ 为什么要有它：聚合口径（`v3curve_agg.py`）在 H0–H5 之后已经过时（行不再挂在产业下、
   `ClassRow` 没有 goods、货币与市场是新字段）—— 若等到推进完 600 天才聚合，中间那四个
   关账日的状态**已经不存在了**（推进是覆盖式的）。故每个关账日**先落原始读数**，
   聚合随时可以拿新口径重算，原始数据一份不丢。

用法: python3 h6sim_dump.py <label>     产物: /tmp/h6raw_<label>.json
"""

import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '2026-09-26-year-one-simulation'))
from simos_mcp import handshake, call_json  # noqa: E402

GUI = 'http://127.0.0.1:5827'
NATIONS = ['德意志第二帝国', '奥斯特马克侯国', '霍赫兰伯国']
POOL = 16


def get(path):
    return json.load(urllib.request.urlopen(GUI + path, timeout=180))


def fetch_hex(q, r):
    return {
        'q': q,
        'r': r,
        'social': get(f'/api/social/population?q={q}&r={r}'),
        'economy': get(f'/api/economy/hex?q={q}&r={r}'),
    }


def main():
    label = sys.argv[1]
    sid = handshake()
    out = {'label': label, 'nations': {}}
    for nation in NATIONS:
        hexes = call_json(sid, 'simos.map.region', {'regionId': nation})['hexes']
        coords = [(h['q'], h['r']) for h in hexes]
        with ThreadPoolExecutor(max_workers=POOL) as ex:
            rows = list(ex.map(lambda c: fetch_hex(*c), coords))
        out['nations'][nation] = rows
        active = sum(1 for row in rows if row['economy'].get('activated'))
        print(f'  {nation}: {len(rows)} 格（经济已激活 {active}）', flush=True)
    path = f'/tmp/h6raw_{label}.json'
    with open(path, 'w') as fh:
        json.dump(out, fh, ensure_ascii=False)
    print(f'原始读数已存: {path}（{os.path.getsize(path) / 1e6:.1f} MB）', flush=True)


if __name__ == '__main__':
    main()
