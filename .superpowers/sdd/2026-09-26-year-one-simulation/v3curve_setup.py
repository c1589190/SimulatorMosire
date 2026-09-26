"""v3curve：种三国 + 打印创世状态。"""

import json
import re
import sys

import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simos_mcp import handshake, call  # noqa: E402

NATIONS = ['德意志第二帝国', '奥斯特马克侯国', '霍赫兰伯国']

sid = handshake()
print(f'MCP 握手 ✓ sid={sid[:8]}')

for n in NATIONS:
    r = call(sid, 'simos.worldgen.initialize',
             {'nation': n, 'army': True, 'dryRun': False})
    m = re.search(r'"revision":(\d+).*?"commandCount":(\d+)', r)
    print(f'  {n}: ' + (f'✓ revision={m.group(1)} cmd={m.group(2)}' if m else f'⚠ {r[:200]}'))

tl = json.loads(call(sid, 'simos.timeline.revisions', {'branch': 'main'}))
if isinstance(tl, dict):
    nodes = tl.get('nodes') or tl.get('revisions') or []
else:
    nodes = tl
print('时间线:')
for nd in nodes:
    print('  rev', nd.get('revision'), 'tick', nd.get('tick'), nd.get('commandType'))
