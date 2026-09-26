"""v3curve：推进到指定 tick（一次 N 天）。

用法: python3 v3curve_advance.py <目标tick>
"""

import json
import sys
import time

import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simos_mcp import handshake, call, call_json  # noqa: E402


def timeline(sid):
    tl = call_json(sid, 'simos.timeline.revisions', {'branch': 'main'})
    nodes = tl.get('nodes') if isinstance(tl, dict) else tl
    if not nodes and isinstance(tl, dict):
        nodes = tl.get('revisions') or []
    return nodes


def main():
    target = int(sys.argv[1])
    sid = handshake()
    nodes = timeline(sid)
    head_rev = max(n['revision'] for n in nodes)
    head_tick = max(n['tick'] for n in nodes)
    print(f'当前: revision={head_rev} tick={head_tick} ⇒ 目标 tick={target}（+{target-head_tick} 天）')
    if target <= head_tick:
        print('目标不晚于当前 tick，无事可做')
        return
    t0 = time.time()
    r = call(sid, 'simos.advance', {
        'branch': 'main', 'expectedRevision': head_rev,
        'from': head_tick, 'to': target,
    })
    print(f'推进完成（{time.time()-t0:.1f}s）: {r[:400]}')
    nodes = timeline(sid)
    for n in nodes[-3:]:
        print('  rev', n['revision'], 'tick', n['tick'], n['commandType'])


if __name__ == '__main__':
    main()
