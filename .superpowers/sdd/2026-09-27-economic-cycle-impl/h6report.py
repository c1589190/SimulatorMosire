"""h6report：把 h6agg 的三份聚合产物写成**一年期关账报表**（markdown 片段，供人抄进台账/提交信息）。

用法: python3 h6report.py > /tmp/h6report.md

★ 一切数字**原样**取自 /tmp/h6agg_tick{120,240,360}.json（不重算口径、不联网、不跑 Maven）；
  折算只在打印时 //1000。缺文件**明确打印"缺 <路径>"**，绝不静默跳过（同 h6cmp.py 的纪律）。
"""

import json
import os

TICKS = ('120', '240', '360')
AGG_FMT = '/tmp/h6agg_tick%s.json'


def load(t):
    p = AGG_FMT % t
    if not os.path.exists(p):
        raise SystemExit('缺 %s' % p)
    with open(p, encoding='utf-8') as fh:
        return json.load(fh)['total']


def row(label, fn):
    cells = ' | '.join(fn(d) for d in DATA)
    print('| %s | %s |' % (label, cells))


DATA = [load(t) for t in TICKS]

print('### 逐关账日（三国合计）')
print()
print('| 量 | tick120 | tick240 | tick360 |')
print('|---|---|---|---|')
row('人口', lambda d: '{:,}'.format(d['pop']))
row('其中城镇', lambda d: '{:,}（{:.1f}%）'.format(d['urban'], 100.0 * d['urban'] / d['pop']))
row('本期死亡', lambda d: '{:,}（{:.2f}‰）'.format(d['deaths'], 1000.0 * d['deaths'] / d['pop']))
row('本期出生', lambda d: '{:,}（{:.2f}‰）'.format(d['births'], 1000.0 * d['births'] / d['pop']))
row('粮库存（/1000）', lambda d: '{:,}'.format(d['goods']['grain'] // 1000))
row('纤维库存（/1000）', lambda d: '{:,}'.format(d['goods']['fiber'] // 1000))
row('工具库存（/1000）', lambda d: '{:,}'.format(d['goods']['tool'] // 1000))
row('本期缺口·粮（/1000）', lambda d: '{:,}'.format(d['unmet'].get('grain', 0) // 1000))
row('本期缺口·布（/1000）', lambda d: '{:,}'.format(d['unmet'].get('cloth', 0) // 1000))
row('债务条数', lambda d: '{:,}'.format(d['debtN']))
row('债务本金（/1000 粮）', lambda d: '{:,}'.format(d['debtP'] // 1000))
row('本期新借（/1000 粮）', lambda d: '{:,}'.format(d['newBorrowing'] // 1000))
row('本期已还（/1000 粮）', lambda d: '{:,}'.format(d['repaid'] // 1000))
row('本期应付息（/1000 粮）', lambda d: '{:,}'.format(d['interestDue'] // 1000))
row('货币（逐币种，毫）', lambda d: ' / '.join('%s %s' % (k, '{:,}'.format(v)) for k, v in d['money'].items()))
row('城市格', lambda d: '{:,}'.format(d['craftHex']))
row('其中**作坊当期产出布**的格', lambda d: '{:,}'.format(d['craftHexCloth']))
row('红灯格数（FOOD/CLOTH/MORTALITY/DEBT）',
    lambda d: '{}/{}/{}/{}（合计 {:,}）'.format(
        d['crisis'].get('FOOD', 0), d['crisis'].get('CLOTH', 0),
        d['crisis'].get('MORTALITY', 0), d['crisis'].get('DEBT', 0), d['hexesWithCrisis']))
print()
