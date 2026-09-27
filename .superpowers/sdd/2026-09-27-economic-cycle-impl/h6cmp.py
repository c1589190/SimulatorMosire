"""h6cmp：把两批（或多批）600 天三国模拟的 h6agg 聚合产物摆到一起，打印"曲线"（供关账报表直接抄）。

用法: python3 h6cmp.py <基线标签前缀> <新标签前缀> [更多标签前缀...]
  例: python3 h6cmp.py tick tick2      # 依次比 tick120..tick600 与 tick2120..tick2600
      python3 h6cmp.py tick tick       # 自比冒烟自检：Δ 表应当恒 +0
数据: /tmp/h6agg_<前缀><关账日>.json（h6agg.py 的产物；★ 只读、不联网、不碰 Java、不跑 Maven）
      关账日固定五个：120 / 240 / 360 / 480 / 600；缺文件**明确打印"缺 <路径>"**，绝不静默跳过。

★ 为什么这样取数：h6cmp 只是"摆桌子"，不重新聚合、不重算口径 —— 所有量都从 h6agg 的产物里
  原样搬，保证曲线与关账报表同源；连 /1000 折算也只在打印时做。
★ 全整数：比率用整数千分比（占比‰）；本脚本没有任何 float。
★ 唯一的折算例外：货币照 h6agg.py 的"货币(合计)"**原样**打印（不 /1000），
  这样这一行能与 h6agg 的输出逐字对表；其余库存/缺口/本金/新借/息/repaid 一律 /1000。
"""

import json
import os
import sys
import unicodedata

TICKS = ('120', '240', '360', '480', '600')      # 五个关账日
AGG_FMT = '/tmp/h6agg_%s.json'                   # 前缀 + 关账日


def dw(s):
    """字符串的**显示宽度**：汉字/全角按 2 列算 —— len() 把汉字算 1 列，直接用 len 会在等宽终端里错位。"""
    return sum(2 if unicodedata.east_asian_width(ch) in ('W', 'F') else 1 for ch in str(s))


def pad(s, w, right=False):
    """按显示宽度补空格（文字列左对齐、数字列右对齐），保证小表真的对齐。"""
    s = str(s)
    sp = ' ' * max(w - dw(s), 0)
    return sp + s if right else s + sp


def day_label(day):
    return f'tick{day}'


def n(t, *path, default=0):
    """按路径从 total 里取整数（字段缺失一律给 default，绝不让报表炸；非 int 也给 default）。"""
    cur = t
    for k in path:
        if not isinstance(cur, dict):
            return default
        cur = cur.get(k)
        if cur is None:
            return default
    return cur if isinstance(cur, int) and not isinstance(cur, bool) else default


# 指标 = (键, 显示名, 取数函数, 是否带正负号)；取数函数一律返回 int / (pos,tot) / 币种字典
def M(key, label, fn, signed=False, kind='int'):
    return {'key': key, 'label': label, 'fn': fn, 'signed': signed, 'kind': kind}


# ——— 四段曲线；★ 指标顺序就是关账清单的顺序，逐段可整块抄进报表 ———
SECTIONS = [
    ('1. 三问：人口 / 城乡 / 生死 / 压力', [
        M('hex', '格数', lambda t: n(t, 'hex')),
        M('pop', '人口', lambda t: n(t, 'pop')),
        M('urban', '城镇人口', lambda t: n(t, 'urban')),
        M('rural', '乡村人口', lambda t: n(t, 'rural')),
        M('births', '出生', lambda t: n(t, 'births')),
        M('deaths', '死亡', lambda t: n(t, 'deaths')),
        M('net', '净人口(生-死)', lambda t: n(t, 'births') - n(t, 'deaths'), signed=True),
        M('stressMax', '压力max', lambda t: n(t, 'stressMax')),
    ]),
    ('2. 粮：库存 / 本期缺口 / 每格平均', [
        M('grain_stock', 'grain 库存(/1000)', lambda t: n(t, 'goods', 'grain') // 1000),
        M('grain_unmet', 'grain 缺口(/1000)', lambda t: n(t, 'unmet', 'grain') // 1000),
        M('grain_per_hex', '每格平均粮(/1000)',
          lambda t: (n(t, 'goods', 'grain') // 1000) // max(n(t, 'hex'), 1)),
    ]),
    ('3. 城市缺口：布（城市格 = 有 craft 产业的格）', [
        M('cloth_stock', 'cloth 库存(/1000)', lambda t: n(t, 'goods', 'cloth') // 1000),
        M('cloth_unmet', 'cloth 缺口(/1000)', lambda t: n(t, 'unmet', 'cloth') // 1000),
        M('craft_ratio', '布入账>0 城市格/城市格',
          lambda t: (n(t, 'craftHexCloth'), n(t, 'craftHex')), kind='ratio'),
        M('craft_share', '布入账城市格 占比‰',
          lambda t: 1000 * n(t, 'craftHexCloth') // max(n(t, 'craftHex'), 1)),
        M('craft_hex', '城市格数(craft)', lambda t: n(t, 'craftHex')),
    ]),
    ('4. 债务曲线 + 货币 + 纤维/工具', [
        M('debtN', '债务条数', lambda t: n(t, 'debtN')),
        M('debtP', '本金(/1000)', lambda t: n(t, 'debtP') // 1000),
        M('newBorrowing', '新借(/1000)', lambda t: n(t, 'newBorrowing') // 1000),
        M('interestDue', '应付息(/1000)', lambda t: n(t, 'interestDue') // 1000),
        M('repaid', 'repaid(/1000)', lambda t: n(t, 'repaid') // 1000),
        M('money', '货币(逐币种, 原样)', lambda t: dict(t.get('money') or {}), kind='money'),
        M('fiber_stock', 'fiber 库存(/1000)', lambda t: n(t, 'goods', 'fiber') // 1000),
        M('tool_stock', 'tool 库存(/1000)', lambda t: n(t, 'goods', 'tool') // 1000),
        M('fiber_unmet', 'fiber 缺口(/1000)', lambda t: n(t, 'unmet', 'fiber') // 1000),
        M('tool_unmet', 'tool 缺口(/1000)', lambda t: n(t, 'unmet', 'tool') // 1000),
    ]),
]
ALL_METRICS = [m for _, ms in SECTIONS for m in ms]   # Δ 表按同一批指标逐项相减


def load(prefix):
    """读一个标签前缀的五个关账日：{关账日: agg 或 None}；缺/坏都明确打印，不静默。"""
    got = {}
    hits = 0
    for d in TICKS:
        path = AGG_FMT % (prefix + d)
        if not os.path.exists(path):
            print(f'  缺 {path}')
            got[d] = None
            continue
        try:
            with open(path) as fh:
                agg = json.load(fh)
        except (OSError, ValueError) as ex:
            print(f'  坏 {path}（读不动: {ex}）')
            got[d] = None
            continue
        if not isinstance(agg, dict) or 'total' not in agg:
            print(f'  坏 {path}（没有 total 字段，疑似不是 h6agg 的产物）')
            got[d] = None
            continue
        got[d] = agg
        hits += 1
    print(f'  批 {prefix}: 命中 {hits}/{len(TICKS)} 个关账日')
    return got


def render(v, signed=False):
    """单元格渲染：int -> 千分位（signed 时带正负号）；(pos,tot) -> pos/tot；dict -> 逐币种。"""
    if isinstance(v, tuple):
        pos, tot = v
        return f'{pos:+d}/{tot:+d}' if signed else f'{pos}/{tot}'
    if isinstance(v, dict):
        if not v:
            return '+0' if signed else '0'
        return ' '.join(f'{k} {x:+,}' if signed else f'{k} {x:,}' for k, x in sorted(v.items()))
    if signed or v < 0:
        return f'{v:+,}'
    return f'{v:,}'


def cell(data, prefix, day, spec, signed=False):
    """取一个单元：该批该日缺产物 -> '—'（缺哪个文件已在装载时打印过完整路径）。"""
    agg = data[prefix].get(day)
    if agg is None:
        return '—'
    return render(spec['fn'](agg['total']), signed)


def delta_cell(data, base, new, day, spec):
    """Δ 单元：新批 − 基线（int 相减；比率逐项相减；货币逐币种相减）。"""
    ab, an = data[base].get(day), data[new].get(day)
    if ab is None or an is None:
        return '—'
    vb, vn = spec['fn'](ab['total']), spec['fn'](an['total'])
    if isinstance(vb, tuple):
        return render((vn[0] - vb[0], vn[1] - vb[1]), signed=True)
    if isinstance(vb, dict):
        return render({k: vn.get(k, 0) - vb.get(k, 0) for k in sorted(set(vb) | set(vn))}, signed=True)
    return render(vn - vb, signed=True)


def days_with_data(data, prefixes):
    """有哪些关账日至少一批有产物（全缺的日子不占表格行）。"""
    return [d for d in TICKS if any(data[p].get(d) for p in prefixes)]


def print_table(title, metrics, prefixes, data, delta=None):
    """一段（或 Δ 表）一张等宽小表：每行 = 一个指标的一个关账日，列 = 各批。

    delta=None 时是原值表；delta=<基线前缀> 时是新批 − 基线。
    """
    days = days_with_data(data, prefixes if delta is None else [delta] + list(prefixes))
    print(title)
    if not days:
        print('  （这五个关账日里没有任何一批的产物）')
        print()
        return
    # 先把所有单元算成字符串，才能定列宽（纯文本对齐）
    head = ['指标', '关账日'] + list(prefixes)
    grid = []
    for spec in metrics:
        for d in days:
            if delta is None:
                vals = [cell(data, p, d, spec) for p in prefixes]
            else:
                vals = [delta_cell(data, delta, p, d, spec) for p in prefixes]
            grid.append([spec['label'], day_label(d)] + vals)
    w = [max(dw(head[i]), *(dw(r[i]) for r in grid)) for i in range(len(head))]
    print('  ' + '  '.join([pad(head[0], w[0]), pad(head[1], w[1])] +
                           [pad(head[i], w[i], right=True) for i in range(2, len(head))]))
    prev = None
    for r in grid:
        # 指标换块空一行（便于顺着列读曲线）；只有一天时不空，免得整张表全是空行
        if prev is not None and r[0] != prev and len(days) > 1:
            print()
        prev = r[0]
        print('  ' + '  '.join([pad(r[0], w[0]), pad(r[1], w[1])] +
                               [pad(r[i], w[i], right=True) for i in range(2, len(r))]))
    print()


def print_caveats(data, prefixes):
    """结构性 0 必须显式提醒：新形状里没有的键，不能读成"这一项是 0"。"""
    keys = set()
    for p in prefixes:
        for d in TICKS:
            agg = data[p].get(d)
            if agg:
                keys |= set((agg['total'].get('unmetKeys') or {}))
    lack = [k for k in ('fiber', 'tool') if k not in keys]
    if lack:
        print(f'  注: 源聚合 unmetNeed 的键集合 = {sorted(keys)}（没有 {"、".join(lack)}）⇒ '
              f'纤维/工具"缺口"行是结构性 0，不能读作"本期无缺口"')
        print()


def main():
    if len(sys.argv) < 3:
        print('用法: python3 h6cmp.py <基线标签前缀> <新标签前缀> [更多标签前缀...]', file=sys.stderr)
        print('  例: python3 h6cmp.py tick tick2    /    自比自检: python3 h6cmp.py tick tick', file=sys.stderr)
        return 2
    prefixes = sys.argv[1:]                       # ★ 保留用户给的顺序（允许重复 ⇒ 自比自检 Δ 恒 0）
    print('########## h6cmp ##########')
    print(f"基线: {prefixes[0]}    新批: {' '.join(prefixes[1:])}")
    print(f"关账日: {' '.join(day_label(d) for d in TICKS)}（标签 = 前缀 + 关账日）")
    print('装载:')
    data = {}
    for p in dict.fromkeys(prefixes):             # 同一前缀只装载一次
        data[p] = load(p)
    print()

    for title, metrics in SECTIONS:
        print_table(f'========== {title} ==========', metrics, prefixes, data)
    print_caveats(data, prefixes)

    if len(prefixes) < 2:
        print('Δ 表: 只给了一批 ⇒ 没有可比的新批（给两个前缀才出 Δ）')
    else:
        print_table(f'========== Δ 表：新批 - 基线（基线 = {prefixes[0]}）==========',
                    ALL_METRICS, prefixes[1:], data, delta=prefixes[0])
    return 0


if __name__ == '__main__':
    sys.exit(main())
