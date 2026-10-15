#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批 M / V1 扫描器 —— 找出「JUnit `@Test` 里推进多 tick 全真世界模拟」的用例。

★ 判据（用户裁定 2026-10-15，设计书
`docs/superpowers/plans/2026-10-15-batch-M-multitick-out-of-tests.md` §2.1）：

    一个 `@Test` 里推进 **≥2 段** 或 **≥60 tick** ⇒ 违规（搬出 JUnit）。
    「段」= 一次**跨多日**的 `AdvanceTime` 推进（`EconomyCycleHealthTest` 是 30 天/段 × 8 段 = 240 天）。

★★ 本扫描器对"段"的落地口径（**唯一一处解释，写在这里**）：`multi_day_segments` 只数
**跨 >1 天**的推进；逐日推进（`advance(d, d+1)` / `advanceDays(N)`）算 **tick**、不算"段"。
依据：设计书 §1.3 明说「`src/test` 里 52 个类用它，绝大多数是 1 tick 契约验 ⇒ **别误伤它们**」——
若把每次 `AdvanceTime` 都当一段，则"连推两天验并发冲突/回放"这类合法契约验会被误判。
两条判据各管一种坏形态：**≥2 段**抓"少次多日的大推进"（240 天分 8 段），
**≥60 tick** 抓"多次单日的推进"（`GovGenesisZ6Test` 的 120 天逐日）。
凡 `>= 2` 次推进但按上面口径**不违规**的用例，一律打成 `BOUNDARY` 列出来给人工复核 —— 不让口径悄悄吞掉样本。

★ `UNRESOLVED`：某次推进的日跨度**静态解不出来**（开区间 `TimeRange.since`、非定长循环、
外部类的推进器……）⇒ 当场报 UNRESOLVED 并让进程 **非零退出**（**绝不当作 0**）。
「读不到 ⇒ 判据静默变弱」是本仓明令禁止的形态（见类注里的 W4b 加牙）。

用法：
    python3 tools/scan-multitick-tests.py                 # 扫 simos-*/src/test/java/**/*.java
    python3 tools/scan-multitick-tests.py --format=json
    python3 tools/scan-multitick-tests.py --include='*EconomyCycleHealth*'
    python3 tools/scan-multitick-tests.py --self-test     # 自证：已知样本逐条对期望

退出码：0 = 无 VIOLATION 且无 UNRESOLVED（BOUNDARY 不算红）· 1 = 有 VIOLATION ·
        2 = 有 UNRESOLVED · 3 = 自证失败 / 用法错误。

★ 它不是编译期分析器：解析的是源码文本（去注释 + 常量折叠 + 定长 `for` 展开 + 同文件/夹具内联），
解不出来的**如实报 UNRESOLVED**，不猜。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, field

MAX_LOOP_PRODUCT = 50_000  # 循环展开上限（防炸）——超了报 UNRESOLVED
DEFAULT_ATTESTATIONS = "tools/multitick-scan-attestations.txt"

# ── 词法工具 ────────────────────────────────────────────────────────────────────────────


def strip_comments(text: str) -> str:
    """把 // 与 /* */ 注释换成等长空白（保留行号与偏移）。"""
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = out[i + 1] = " "
                i += 2
        else:
            i += 1
    return "".join(out)


def match_paren(text: str, open_idx: int) -> int:
    """`text[open_idx] == '('` ⇒ 返回配对 `)` 的下标；找不到 ⇒ -1。"""
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def match_brace(text: str, open_idx: int) -> int:
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_top(text: str, sep: str = ",", track_angle: bool = True) -> list[str]:
    """按顶层分隔符切（含泛型 `<>` 与括号嵌套）。

    ★ `track_angle=False`：`for (long day = 0L; day < DAYS; day++)` 里的 `<` 是比较、不是泛型 ——
    若还按泛型计数，`;` 会被当成"泛型内部"而切不开（实测踩到：整个 for 头被当成 1 段 ⇒ 循环不展开 ⇒ 少算 tick）。
    """
    args: list[str] = []
    depth = 0
    angle = 0
    start = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "<" and track_angle:
            angle += 1
        elif c == ">" and track_angle:
            angle = max(0, angle - 1)
        elif c == sep and depth == 0 and angle == 0:
            args.append(text[start:i].strip())
            start = i + 1
        i += 1
    tail = text[start:].strip()
    if tail or args:
        args.append(tail)
    return args


WRAPPER_SUFFIXES = ("SimosTimestamp.of", "Optional.of", "Optional.empty")


def strip_wrapper(expr: str) -> str:
    """剥掉 `Optional.of(...)` / `SimosTimestamp.of(...)` 这类单层包装（★ 上界与下界都要剥）。"""
    e = expr.strip()
    for _ in range(4):
        m = re.match(r"^[A-Za-z_$][\w$.]*\s*\(", e)
        if not m or not e.endswith(")"):
            return e
        open_idx = m.end() - 1
        close = match_paren(e, open_idx)
        if close != len(e) - 1:
            return e
        name = e[:open_idx].strip()
        if not name.endswith(WRAPPER_SUFFIXES):
            return e
        e = e[open_idx + 1 : close].strip()
    return e


def split_args(text: str) -> list[str]:
    """按顶层逗号切参数。"""
    return split_top(text, ",")


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _only_annotations_since_test(prefix: str) -> bool:
    """`prefix` 里最后一个 `@Test` 之后到方法签名之间，只允许出现注解（不是匿名类/别的方法）。"""
    idx = prefix.rfind("@Test")
    if idx < 0:
        return False
    rest = prefix[idx + len("@Test") :]
    while rest.strip():
        rest = rest.lstrip()
        m = re.match(r"@[\w.]+", rest)
        if not m:
            return False
        rest = rest[m.end() :]
        if rest.lstrip().startswith("("):
            rest = rest.lstrip()
            close = match_paren(rest, 0)
            if close < 0:
                return False
            rest = rest[close + 1 :]
    return True


# ── 表达式求值（只支持常量算术；解不出 ⇒ None）────────────────────────────────────────


PRIMITIVE_TYPES = {"int", "long", "short", "byte", "char", "double", "float"}

TOKEN_RE = re.compile(r"\s*(\d[\w_]*|[A-Za-z_$][\w$.]*|[()+\-*/])")


class ExprError(Exception):
    pass


def tokenize(expr: str):
    tokens = []
    pos = 0
    while pos < len(expr):
        m = TOKEN_RE.match(expr, pos)
        if not m:
            if expr[pos:].strip() == "":
                break
            raise ExprError(f"无法词法化：{expr[pos:]!r}")
        tokens.append(m.group(1))
        pos = m.end()
    return tokens


def parse_number(tok: str):
    t = tok.replace("_", "")
    if t.endswith(("L", "l")):
        t = t[:-1]
    if t.endswith(("f", "F", "d", "D")):
        t = t[:-1]
    if re.fullmatch(r"\d+", t):
        return int(t)
    if re.fullmatch(r"0[xX][0-9a-fA-F]+", t):
        return int(t, 16)
    raise ExprError(f"不是整数字面量：{tok}")


class Evaluator:
    """常量折叠：env（循环变量/局部量）> 文件内 static final > 外部类 static final。"""

    def __init__(self, resolver):
        self.resolver = resolver  # (qualified_name) -> int|None

    def eval(self, expr: str, env: dict[str, int]):
        tokens = tokenize(expr)
        self.tokens = tokens
        self.pos = 0
        self.env = env
        value = self._add()
        if self.pos != len(tokens):
            raise ExprError(f"多余 token：{tokens[self.pos:]}")
        return value

    def _add(self):
        value = self._mul()
        while self.pos < len(self.tokens) and self.tokens[self.pos] in "+-":
            op = self.tokens[self.pos]
            self.pos += 1
            rhs = self._mul()
            value = value + rhs if op == "+" else value - rhs
        return value

    def _mul(self):
        value = self._unary()
        while self.pos < len(self.tokens) and self.tokens[self.pos] in "*/":
            op = self.tokens[self.pos]
            self.pos += 1
            rhs = self._unary()
            value = value * rhs if op == "*" else (value // rhs if rhs else None)
            if value is None:
                raise ExprError("除零")
        return value

    def _unary(self):
        if self.pos < len(self.tokens) and self.tokens[self.pos] == "-":
            self.pos += 1
            return -self._unary()
        return self._atom()

    def _atom(self):
        tok = self.tokens[self.pos]
        self.pos += 1
        if tok == "(":
            # Java 强制类型转换：(int) (…) / (long) (…)
            if (
                self.pos + 1 < len(self.tokens)
                and self.tokens[self.pos] in PRIMITIVE_TYPES
                and self.tokens[self.pos + 1] == ")"
            ):
                self.pos += 2
                return self._unary()
            value = self._add()
            if self.tokens[self.pos] != ")":
                raise ExprError("括号不配对")
            self.pos += 1
            return value
        if re.match(r"\d", tok):
            return parse_number(tok)
        if tok in self.env:
            return self.env[tok]
        resolved = self.resolver(tok)
        if resolved is None:
            raise ExprError(f"解不出的标识符：{tok}")
        return resolved


# ── 源文件解析 ──────────────────────────────────────────────────────────────────────────


@dataclass
class Method:
    name: str
    params: list[str]
    body: str
    line: int
    offset: int
    is_test: bool
    display: str = ""


@dataclass
class CallSite:
    kind: str  # "advance" | "days" | "helper"
    line: int
    text: str
    offset: int = 0
    from_expr: str | None = None
    to_expr: str | None = None
    days_expr: str | None = None
    open_range: bool = False
    name: str | None = None
    args: list[str] = field(default_factory=list)


CONST_RE = re.compile(
    r"static\s+final\s+(?:long|int|SimosTimestamp)\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);", re.S
)
METHOD_RE = re.compile(
    r"(?P<mods>(?:public|private|protected|static|final|synchronized|native|abstract)\s+)*"
    r"(?P<ret>[A-Za-z_$][\w$.<>,\[\]\s]*?)\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\s*\(",
)


class JavaFile:
    """一个 .java 文件的粗解析：常量表 + 方法表（含嵌套类里的方法）。"""

    def __init__(self, path: str, text: str, repo: "Repo"):
        self.path = path
        self.raw = text
        self.text = strip_comments(text)
        self.repo = repo
        self.package = ""
        m = re.search(r"^\s*package\s+([\w.]+)\s*;", self.text, re.M)
        if m:
            self.package = m.group(1)
        self.class_name = os.path.basename(path)[:-5]
        self.static_imports: dict[str, str] = {}  # 局部名 -> 声明它的类（简单名）
        for im in re.finditer(r"import\s+static\s+[\w.]*\.([A-Za-z_$][\w$]*)\s*;", self.text):
            owner = im.group(0)
            owner = owner[owner.index("static") + 6 :].strip().rstrip(";").rsplit(".", 1)[0]
            self.static_imports[im.group(1)] = owner.split(".")[-1]
        self.consts: dict[str, str] = {}
        for cm in CONST_RE.finditer(self.text):
            self.consts[cm.group(1)] = cm.group(2).strip()
        self.methods: list[Method] = []
        self._parse_methods()

    # -- 方法表 ---------------------------------------------------------------------
    def _parse_methods(self) -> None:
        text = self.text
        # ★ 手写扫描（不用 finditer）：守卫打回一次假签名时，必须**只前进 1 个字符**再试 ——
        #   否则真签名落在被打回的那段里就永远不会被匹配到（实测踩到：DecisionDocsApiTest 一个 @Test 都认不出来）。
        pos = 0
        while True:
            m = METHOD_RE.search(text, pos)
            if not m:
                return
            pos = m.start() + 1
            name = m.group("name")
            if name in ("if", "for", "while", "switch", "catch", "return", "new", "synchronized"):
                continue
            ret = m.group("ret").strip()
            if not ret or ret.split()[-1] in ("if", "for", "while", "return", "new"):
                continue
            # ★ 两道守卫（实测踩到）：正则会把注解里的词吞进"返回类型"，例如
            #   `@Test\n  void multiDay…(` 会被匹配成 ret="Test\n  void"、name="multiDay…"，
            #   m.start() 落在 `@` 之后 ⇒ 别处按"方法起点"读到的注解前缀是断的、@Test 判不出来。
            if text[: m.start()].rstrip().endswith(("@", ".")):
                continue
            if re.search(r"\s", ret) and re.search(
                r"\b(?:void|public|private|protected|static|final|class|record|enum|interface|"
                r"return|new|if|for|while)\b",
                ret,
            ):
                continue
            open_idx = m.end() - 1
            close_idx = match_paren(text, open_idx)
            if close_idx < 0:
                continue
            after = text[close_idx + 1 :].lstrip()
            if after.startswith("throws "):
                tm = re.match(r"throws\s+[\w$.,\s]+?\{", after)
                if not tm:
                    continue
                after = after[tm.end() - 1 :]
            if not after.startswith("{"):
                continue  # 接口/抽象声明
            brace = close_idx + 1 + (len(text[close_idx + 1 :]) - len(after))
            body_end = match_brace(text, brace)
            if body_end < 0:
                continue
            # @Test 判定：注解紧跟方法签名之前
            # ★ 不能用固定窗口（如"前 400 字"）：长 javadoc 被去注释后是一大段空白，会把 @Test 挤出窗口
            #   ⇒ 实测漏判整类用例（TimeAdvanceTest 23 个 @Test 一个都没认出来）。改成从**整个前缀**里 rfind。
            is_test = _only_annotations_since_test(text[: m.start()])
            display = ""
            dm = re.search(
                r'@DisplayName\(\s*"((?:[^"\\]|\\.)*)"', text[max(0, m.start() - 2000) : m.start()]
            )
            if dm:
                display = dm.group(1)
            params = [p.strip() for p in split_args(text[open_idx + 1 : close_idx]) if p.strip()]
            pos = body_end + 1
            self.methods.append(
                Method(
                    name=name,
                    params=params,
                    body=text[brace + 1 : body_end],
                    line=line_of(text, m.start()),
                    offset=m.start(),
                    is_test=is_test,
                    display=display,
                )
            )

    # -- 调用点 ---------------------------------------------------------------------
    def find_call_sites(self, body: str, base_offset: int = 0) -> list[CallSite]:
        sites: list[CallSite] = []

        # ① new AdvanceTime(...) —— 从里面找 new TimeRange(...)
        for m in re.finditer(r"new\s+AdvanceTime\s*\(", body):
            open_idx = m.end() - 1
            close_idx = match_paren(body, open_idx)
            if close_idx < 0:
                continue
            inner = body[open_idx + 1 : close_idx]
            tr = re.search(r"new\s+TimeRange\s*\(", inner)
            from_expr = to_expr = None
            open_range = False
            if tr:
                to_idx = tr.end() - 1
                to_close = match_paren(inner, to_idx)
                targs = split_args(inner[to_idx + 1 : to_close])
                if len(targs) >= 2:
                    from_expr = strip_wrapper(targs[0])
                    second = strip_wrapper(targs[1])
                    if second == "empty" or second == "" or "empty" in targs[1]:
                        open_range = True
                    else:
                        to_expr = second
            else:
                open_range = True
            sites.append(
                CallSite(
                    kind="advance",
                    line=line_of(self.raw, base_offset + m.start()),
                    text=m.group(0),
                    offset=m.start(),
                    from_expr=from_expr,
                    to_expr=to_expr,
                    open_range=open_range,
                )
            )

        # ② <recv>.advanceDays(<n>) / advanceDays(<n>)
        for m in re.finditer(r"(?:[\w$.]+\s*\.\s*)?\badvanceDays\s*\(", body):
            if self._is_declaration(body, m.start()):
                continue
            open_idx = m.end() - 1
            close_idx = match_paren(body, open_idx)
            if close_idx < 0:
                continue
            args = split_args(body[open_idx + 1 : close_idx])
            if not args:
                continue
            # 两个形态：fixture 的 advanceDays(long days)；本仓还有
            # advanceDays(expectedRevision, fromTick, days) / (expectedRevision, days)
            days_expr = args[-1] if len(args) in (1, 2, 3) else None
            sites.append(
                CallSite(
                    kind="days",
                    line=line_of(self.raw, base_offset + m.start()),
                    text=body[m.start() : close_idx + 1],
                    offset=m.start(),
                    days_expr=days_expr,
                )
            )

        # ③ 同文件/外部类里的推进助手（名字命中 + 体里有推进构造）
        for m in re.finditer(r"(?:([\w$.]+)\s*\.\s*)?\b([A-Za-z_$][\w$]*)\s*\(", body):
            name = m.group(2)
            if name in ("if", "for", "while", "switch", "catch", "new", "return", "advanceDays"):
                continue
            if self._is_declaration(body, m.start()):
                continue
            open_idx = m.end() - 1
            close_idx = match_paren(body, open_idx)
            if close_idx < 0:
                continue
            target = self.repo.find_advancing_method(self, name)
            if not target:
                continue
            sites.append(
                CallSite(
                    kind="helper",
                    line=line_of(self.raw, base_offset + m.start()),
                    text=body[m.start() : close_idx + 1],
                    offset=m.start(),
                    name=name,
                    args=split_args(body[open_idx + 1 : close_idx]),
                )
            )
        sites.sort(key=lambda s: s.line)
        return sites

    TYPE_BEFORE_RE = re.compile(
        r"(?:^|[;{}(\s])(?:public|private|protected|static|final|synchronized|abstract|native|"
        r"long|int|void|boolean|double|float|char|byte|short|String)\s+$"
    )

    @classmethod
    def _is_declaration(cls, body: str, offset: int) -> bool:
        """`long advanceDays(...) {` 这种**声明处**不算调用点（调用处前面是 `.` / `=` / `(` / 行首）。"""
        return bool(cls.TYPE_BEFORE_RE.search(body[max(0, offset - 200) : offset]))


class Repo:
    def __init__(self, root: str):
        self.root = root
        self.files: dict[str, JavaFile] = {}
        self.by_class: dict[str, JavaFile] = {}
        self._method_index: dict[str, dict[str, Method]] = {}
        self._const_cache: dict[tuple[str, str], int | None] = {}

    def load(self, path: str) -> JavaFile:
        if path in self.files:
            return self.files[path]
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
        jf = JavaFile(path, text, self)
        self.files[path] = jf
        self.by_class[jf.class_name] = jf
        return jf

    def index_sources(self) -> None:
        for dirpath, _dirnames, filenames in os.walk(self.root):
            if "/target/" in dirpath or "/.git" in dirpath or "/.agents/" in dirpath:
                continue
            slash = dirpath.replace(os.sep, "/")
            if "/src/test/java" not in slash and "/src/main/java" not in slash:
                continue
            for fn in filenames:
                if fn.endswith(".java"):
                    self.load(os.path.join(dirpath, fn))

    # -- 常量解析 -------------------------------------------------------------------
    def resolve_constant(self, name: str, jf: JavaFile) -> int | None:
        key = (jf.path, name)
        if key in self._const_cache:
            return self._const_cache[key]
        self._const_cache[key] = None  # 防环
        value = None
        if "." in name:
            cls, field = name.rsplit(".", 1)
            cls = cls.split(".")[-1]
            target = self.by_class.get(cls)
            if target is None:
                for cand in self.files.values():
                    if cand.class_name == cls:
                        target = cand
                        break
            if target is not None:
                value = self.resolve_constant(field, target)
        else:
            expr = jf.consts.get(name)
            if expr is None and name in jf.static_imports:
                target = self.by_class.get(jf.static_imports[name])
                if target is not None and target is not jf:
                    value = self.resolve_constant(name, target)
                    self._const_cache[key] = value
                    return value
            if expr is not None:
                ev = Evaluator(lambda ref: self.resolve_constant(ref, jf))
                plus = re.fullmatch(
                    r"\s*([A-Za-z_$][\w$]*)\s*\.\s*plus\s*\((.*)\)\s*", expr, re.S
                )
                try:
                    if plus:
                        base = self.resolve_constant(plus.group(1), jf)
                        value = (
                            None
                            if base is None
                            else base + ev.eval(strip_wrapper(plus.group(2)), {})
                        )
                    else:
                        value = ev.eval(strip_wrapper(expr), {})
                except ExprError:
                    value = None
        self._const_cache[key] = value
        return value

    # -- 推进助手（★ 只认**同文件**里的助手；跨文件的推进器一律不猜，宁可报 UNRESOLVED）------
    def advancing_methods(self, jf: JavaFile) -> dict[str, Method]:
        """不动点：直接含推进构造的方法 ∪ 调用它们的方法（`runOnce() → advance(…)` 这种两层要跟上，
        ★ 否则一次推进都读不到、却打出 OK —— 那正是"判据静默变弱"）。"""
        cached = self._method_index.get(jf.path)
        if cached is not None:
            return cached
        advancing: dict[str, Method] = {}
        for method in jf.methods:
            if method.is_test:
                continue
            if _advances(method.body):
                advancing.setdefault(method.name, method)
        changed = True
        while changed:
            changed = False
            for method in jf.methods:
                if method.is_test or method.name in advancing:
                    continue
                if any(
                    re.search(rf"\b{re.escape(n)}\s*\(", method.body) for n in advancing
                ):
                    advancing[method.name] = method
                    changed = True
        self._method_index[jf.path] = advancing
        return advancing

    def find_advancing_method(self, jf: JavaFile, name: str) -> Method | None:
        method = self.advancing_methods(jf).get(name)
        if method is not None:
            return method
        # ★ `import static …EconomyCycleReading.advance;` 这种：推进助手住在**另一个类**里。
        #   不认它 ⇒ 调用点被整段跳过 ⇒ tick 静默算 0（实测踩到，正是"判据静默变弱"）。
        owner = jf.static_imports.get(name)
        if owner:
            target = self.by_class.get(owner)
            if target is not None:
                return self.advancing_methods(target).get(name)
        return None


def _advances(body: str) -> bool:
    return bool(re.search(r"new\s+AdvanceTime\s*\(", body) or re.search(r"advanceDays\s*\(", body))


# ── 主分析 ──────────────────────────────────────────────────────────────────────────────


@dataclass
class Result:
    file: str
    line: int
    cls: str
    method: str
    ticks: int
    multi_day_segments: int
    advance_calls: int
    verdict: str
    evidence: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


class Analyzer:
    def __init__(self, repo: Repo):
        self.repo = repo
        self.unresolved_reasons: list[str] = []
        self._max_advance_days: int | None = None

    def max_advance_days(self, jf: JavaFile) -> int:
        """引擎自己的推进天数上限（{@code TimeAdvance.MAX_ADVANCE_DAYS}）—— 从生产源码解析，不写死。"""
        if self._max_advance_days is None:
            value = self.repo.resolve_constant("TimeAdvance.MAX_ADVANCE_DAYS", jf)
            self._max_advance_days = value if value else 36500
        return self._max_advance_days

    # 定长 for 展开：把 `for (T v = A; v <op> B; v++) { body }` 展开成 body×trips，v 代入数值。
    # ★ 解不出的循环**原样留着**（不整车放弃）：它里面的调用点会在区间求值处解不出 ⇒ 如实报 UNRESOLVED。
    def unroll(self, text: str, jf: JavaFile, depth: int = 0) -> str | None:
        if depth > 8:
            return text
        for _pass in range(200):
            changed = False
            pos = 0
            while True:
                m = re.compile(r"\bfor\s*\(").search(text, pos)
                if not m:
                    break
                open_idx = m.end() - 1
                close_idx = match_paren(text, open_idx)
                if close_idx < 0:
                    break
                after = text[close_idx + 1 :].lstrip()
                body_start = close_idx + 1 + (len(text[close_idx + 1 :]) - len(after))
                body_end = match_brace(text, body_start) if text[body_start:].startswith("{") else -1
                replacement = None
                if body_end > 0:
                    parts = split_top(text[open_idx + 1 : close_idx], ";", track_angle=False)
                    if len(parts) == 3:
                        init, cond, _step = parts
                        im = re.match(
                            r"\s*(?:long|int|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(.+)$", init, re.S
                        )
                        cm = re.match(
                            r"\s*([A-Za-z_$][\w$]*)\s*(<=|<)\s*(.+?)\s*$", cond, re.S
                        )
                        if im and cm and cm.group(1) == im.group(1):
                            ev = Evaluator(lambda ref: self.repo.resolve_constant(ref, jf))
                            try:
                                lo = ev.eval(im.group(2), {})
                                hi = ev.eval(cm.group(3), {})
                                trips = hi - lo + 1 if cm.group(2) == "<=" else hi - lo
                                if 0 <= trips and trips * max(1, len(text)) <= MAX_LOOP_PRODUCT * 8:
                                    body = text[body_start + 1 : body_end]
                                    replacement = "\n".join(
                                        re.sub(
                                            rf"\b{re.escape(im.group(1))}\b", str(lo + k), body
                                        )
                                        for k in range(trips)
                                    )
                            except ExprError:
                                replacement = None
                if replacement is None:
                    pos = m.end()  # 解不出 ⇒ 跳过它，继续找下一个循环
                    continue
                text = text[: m.start()] + replacement + text[body_end + 1 :]
                changed = True
                pos = m.start()
            if not changed:
                return text
        return text

    def analyze_method(self, jf: JavaFile, method: Method) -> Result:
        result = Result(
            file=os.path.relpath(jf.path, self.repo.root),
            line=method.line,
            cls=jf.class_name,
            method=method.name,
            ticks=0,
            multi_day_segments=0,
            advance_calls=0,
            verdict="OK",
            evidence=[],
        )
        unrolled = self.unroll(method.body, jf)
        if unrolled is None:
            result.notes.append("有解不出的 for/while 结构")
        body = unrolled if unrolled is not None else method.body
        self._walk(jf, method, body, method.body, {}, result, depth=0, label="")
        self._verdict(result)
        return result

    def _walk(
        self,
        jf: JavaFile,
        method: Method,
        body: str,
        original: str,
        env: dict[str, int],
        result: Result,
        depth: int,
        label: str = "",
    ) -> None:
        if depth > 4:
            result.notes.append("内联深度超限")
            result.verdict = "UNRESOLVED"
            return
        env = dict(env)
        # 顺序扫描：局部常量声明更新 env；调用点按序处理
        events = []
        for m in re.finditer(
            r"(?:long|int)\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);", body
        ):
            events.append((m.start(), "decl", m))
        for site in jf.find_call_sites(body):
            events.append((site.offset, "site", site))
        events.sort(key=lambda e: e[0])
        ev = Evaluator(lambda ref: self.repo.resolve_constant(ref, jf))
        for _pos, kind, payload in events:
            if kind == "decl":
                name = payload.group(1)
                expr = payload.group(2).strip()
                try:
                    env[name] = ev.eval(expr, env)
                except ExprError:
                    env.pop(name, None)
                continue
            site: CallSite = payload
            if site.kind == "helper":
                target = self.repo.find_advancing_method(jf, site.name or "")
                if target is None:
                    result.notes.append(
                        f"[{label or '本方法'}] 调 {site.name}(…) 解不出发起方（多重载/外部类）"
                    )
                    result.verdict = "UNRESOLVED"
                    continue
                bound = dict(env)
                body2 = target.body
                for param, arg in zip(target.params, site.args):
                    pname = param.split()[-1]
                    try:
                        value = ev.eval(arg, env)
                    except ExprError:
                        # 实参不是常量（对象/方法调用）⇒ 原样代入：它若真参与日跨度，
                        # 后面的区间求值会解不出并**如实报 UNRESOLVED**（不在这里提前放过）
                        body2 = re.sub(rf"\b{re.escape(pname)}\b", f"({arg})", body2)
                        continue
                    bound[pname] = value
                    body2 = re.sub(rf"\b{re.escape(pname)}\b", str(value), body2)
                unrolled2 = self.unroll(body2, jf)
                if unrolled2 is None:
                    result.notes.append(f":{site.line} 助手 {site.name} 内有解不出的循环")
                    result.verdict = "UNRESOLVED"
                    continue
                self._walk(
                    jf,
                    method,
                    unrolled2,
                    body2,
                    bound,
                    result,
                    depth + 1,
                    label=(label + site.name + "()>"),
                )
                continue
            if site.kind == "days":
                try:
                    days = ev.eval(site.days_expr or "", env)
                except ExprError:
                    result.notes.append(
                        f"[{label or '本方法'}] advanceDays({site.days_expr}) 解不出"
                    )
                    result.verdict = "UNRESOLVED"
                    continue
                # advanceDays(N) = N 次单日推进（夹具 World#advanceDays 的语义）
                result.advance_calls += days
                result.ticks += days
                result.evidence.append(
                    f"[{label or '本方法'}] advanceDays({site.days_expr}) = {days} × 1 天 ⇒ "
                    f"tick += {days}，推进次数 += {days}，多日段 += 0"
                )
                continue
            if site.open_range or site.to_expr is None:
                # ★ 引擎契约（simos-core/.../advance/TimeAdvance.java:231-233，④ 第 0 项 = 纯语法层，
                #   先于装库/推进）：缺 to 的推进**必被 Rejected** ⇒ 一天都不会走 ⇒ 计 0 tick（不是"猜 0"）。
                result.notes.append(
                    f"[{label or '本方法'}] 开区间推进（缺 to）⇒ 引擎纯语法层必拒（TimeAdvance.java:231-233），"
                    f"计 0 tick"
                )
                continue
            try:
                lo = ev.eval(site.from_expr or "", env)
                hi = ev.eval(site.to_expr, env)
            except ExprError as exc:
                result.notes.append(
                    f"[{label or '本方法'}] 推进区间解不出（{site.from_expr} → {site.to_expr}）：{exc}"
                )
                result.verdict = "UNRESOLVED"
                continue
            span = hi - lo
            if span < 1 or span > self.max_advance_days(jf):
                # ★ 引擎契约（TimeAdvance.java:240-245，同一层纯语法判定）：1 ≤ N ≤ MAX_ADVANCE_DAYS，
                #   越界**必被 Rejected** ⇒ 计 0 tick（并留下具名 note，不当成"没看见"）。
                result.notes.append(
                    f"[{label or '本方法'}] 意图推进 {lo} → {hi} = {span} 天落在引擎上限 "
                    f"[1, {self.max_advance_days(jf)}] 之外 ⇒ 纯语法层必拒，计 0 tick"
                )
                continue
            result.advance_calls += 1
            result.ticks += span
            if span > 1:
                result.multi_day_segments += 1
            result.evidence.append(
                f"[{label or '本方法'}] 推进 {lo} → {hi} = {span} 天"
                f"（{'多日段 +1' if span > 1 else '逐日/tick'}）"
            )

    @staticmethod
    def _verdict(result: Result) -> None:
        if result.verdict == "UNRESOLVED":
            return
        if result.ticks >= 60 or result.multi_day_segments >= 2:
            result.verdict = "VIOLATION"
        elif result.advance_calls >= 2:
            result.verdict = "BOUNDARY"
        else:
            result.verdict = "OK"


# ── 自证样本（V1 的"扫描器要自证"）────────────────────────────────────────────────────


SELF_TEST = [
    # (文件后缀, @Test 方法名, 期望 tick, 期望多日段, 期望判定)
    # ★ 批 M 搬迁后（2026-10-15）的真值 —— 原来是 240 tick / 120 tick 的三条，现在门禁里只剩 smoke：
    ("app/time/EconomyCycleHealthTest.java",
     "oneSegmentInRealThreePowersWorldKeepsMoneyConservedAndMarketOpen", 30, 1, "OK"),
    ("app/gov/GovGenesisZ6Test.java",
     "threeDaysAdvanceWithoutContractFailureAndCommitmentsIntact", 3, 0, "BOUNDARY"),
    ("app/time/SegmentEquivalenceAcrossEconomyGovSocialTest.java",
     "fiveDayAdvanceEqualsFiveChainedSingleDayAdvances", 10, 1, "BOUNDARY"),
    ("app/gov/GovGenesisZ6Test.java",
     "genesisTick0HasGovOfficeUnitsCommitmentsTreasuryAndZeroTreasuryPopulation", 0, 0, "OK"),
    ("app/gov/GovGenesisZ6Test.java",
     "genesisIsDeterministicAndNonEmptyStoreIsNeverReinitialized", 0, 0, "OK"),
    ("app/time/Z7RemittanceE2ETest.java",
     "rateFiveHundredRemitsOnCloseAndCentralPaysSameTickAfterTreasuryDrained", 30, 1, "OK"),
    ("app/time/Z7RemittanceE2ETest.java",
     "rateZeroIsTaxResistanceWithoutAnyTransferOrDayEvent", 30, 1, "OK"),
    ("core/advance/TimeAdvanceTest.java",
     "multiDayAdvanceIsAcceptedAndSettlesTheWholeSpan", 3, 1, "OK"),
    # ★ 两条"字面违规、人工核后具名豁免"的样本（理由见 tools/multitick-scan-attestations.txt）：
    ("core/advance/TimeAdvanceTest.java",
     "absurdlyLongAdvanceIsRejectedWithTheCapInTheReason", 36500, 1, "VIOLATION"),
    ("core/advance/AdvanceStopProtocolTest.java",
     "stoppedWorldIsReallyAtThatTickAndCanBeAdvancedFurther", 10, 2, "VIOLATION"),
    ("app/UnitExtensionEndToEndTest.java",
     "advancingTheTopCarriesTheWholeFormationToTheSameHex", 23, 0, "BOUNDARY"),
    ("app/ShellSmokeTest.java",
     "advanceThroughTheShellMovesTheUnitAndReplays", 1, 0, "OK"),
    ("app/tools/write/GovPayToolTest.java",
     "allZeroAmountsAreRejectedByTheAmountGateBeforeAnyWorldLookup", 0, 0, "OK"),
    ("app/gui/DecisionDocsApiTest.java",
     "aDecisionMakerSeesTheUnionOfTaggedAndAffiliationDocsAndNeverTheUnowned", 0, 0, "OK"),
]

# ★ 独立跑法（搬出 JUnit 的那三段）必须是**非用例**：surefire 的缺省 include 收不到它们。
NON_TEST_SAMPLES = [
    ("app/sim/EconomyCycleHealthSim.java", "run"),
    ("app/sim/GovGenesis120DaySim.java", "run"),
    ("app/sim/SegmentEquivalenceSim.java", "run"),
    ("app/sim/MultiTickSimRunner.java", "main"),
]


def load_attestations(repo: Repo, path: str) -> dict[str, str]:
    """具名豁免表：`<相对路径>#<方法名> | <理由>`（★ 理由必填 —— 没有理由的豁免不许存在）。

    它不是"把红扫成绿"的开关：被豁免的条目**照样打印**（ATTESTED 行 + 理由），过期条目（匹配不到任何
    finding）会让扫描**报错退出**（防"陈年豁免"悄悄留在表里）。
    """
    full = path if os.path.isabs(path) else os.path.join(repo.root, path)
    table: dict[str, str] = {}
    if not os.path.exists(full):
        return table
    with open(full, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            key, _, reason = line.partition("|")
            key = key.strip()
            reason = reason.strip()
            if not key or not reason:
                print(f"★ 豁免表格式错（缺 key 或理由）：{line}")
                raise SystemExit(3)
            table[key] = reason
    return table


def collect(repo: Repo, patterns: list[str] | None):
    paths = []
    for dirpath, _dirnames, filenames in os.walk(repo.root):
        if "/target/" in dirpath or "/.git" in dirpath or "/.agents/" in dirpath:
            continue
        if "/src/test/java" not in dirpath.replace(os.sep, "/"):
            continue
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            full = os.path.join(dirpath, fn)
            if patterns and not any(p in full for p in patterns):
                continue
            with open(full, "r", encoding="utf-8") as handle:
                text = handle.read()
            if not re.search(r"advanceAndDrain|AdvanceTime|advanceDays\s*\(", text):
                continue
            paths.append(full)
    return sorted(paths)


def run_scan(repo: Repo, patterns: list[str] | None):
    analyzer = Analyzer(repo)
    results: list[Result] = []
    non_test: list[str] = []
    paths = collect(repo, patterns)
    for path in paths:
        jf = repo.load(path)
        rel = os.path.relpath(path, repo.root)
        if re.search(r"\bstatic\s+void\s+main\s*\(", jf.text) and _advances(jf.text):
            non_test.append(f"NON_TEST {rel}：含 main(...) 且推进世界（刻意不在门禁里）")
        for method in jf.methods:
            if method.is_test:
                results.append(analyzer.analyze_method(jf, method))
    return paths, results, non_test


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--format", choices=["table", "json"], default="table")
    parser.add_argument("--include", action="append", default=None)
    parser.add_argument("--allow-unresolved", action="store_true")
    parser.add_argument("--attestations", default=DEFAULT_ATTESTATIONS)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--quiet-ok", action="store_true")
    args = parser.parse_args(argv)

    repo = Repo(os.path.abspath(args.root))
    repo.index_sources()

    if args.self_test:
        return self_test(repo)

    paths, results, non_test = run_scan(repo, args.include)
    attestations = load_attestations(repo, args.attestations)
    used: set[str] = set()
    for r in results:
        if r.verdict != "VIOLATION":
            continue
        key = f"{r.file}#{r.method}"
        reason = attestations.get(key) or attestations.get(f"{r.file}@{r.line}#{r.method}")
        if reason:
            used.add(key)
            r.verdict = "VIOLATION(ATTESTED)"
            r.notes.append(f"★ 具名豁免：{reason}")
    stale = sorted(k for k in attestations if k not in used)
    violations = [r for r in results if r.verdict == "VIOLATION"]
    attested = [r for r in results if r.verdict == "VIOLATION(ATTESTED)"]
    unresolved = [r for r in results if r.verdict == "UNRESOLVED"]
    boundary = [r for r in results if r.verdict == "BOUNDARY"]

    if args.format == "json":
        print(
            json.dumps(
                {
                    "scanned_files": len(paths),
                    "tests": len(results),
                    "results": [r.__dict__ for r in results],
                    "non_test": non_test,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        print(f"# 扫到的文件（含推进构造的测试源）：{len(paths)}")
        for path in paths:
            print(f"#   {os.path.relpath(path, repo.root)}")
        print()
        head = f"{'判定':<10} {'tick':>5} {'多日段':>6} {'推进次数':>7}  用例"
        print(head)
        print("-" * len(head))
        for r in sorted(results, key=lambda r: -r.ticks):
            if args.quiet_ok and r.verdict == "OK":
                continue
            print(
                f"{r.verdict:<10} {r.ticks:>5} {r.multi_day_segments:>6} "
                f"{r.advance_calls:>7}  {r.cls}#{r.method}  ({r.file}:{r.line})"
            )
        print()
        for r in violations + attested + boundary + unresolved:
            print(f"── {r.verdict} {r.cls}#{r.method} ({r.file}:{r.line})")
            for line in r.evidence:
                print(f"     {line}")
            for line in r.notes:
                print(f"     ★ {line}")
        for line in non_test:
            print(line)

    if stale:
        print("★ 豁免表里有**过期条目**（匹配不到任何用例 ⇒ 不许留着）：")
        for key in stale:
            print(f"   {key}")
    print(
        f"SCANNED_FILES={len(paths)} TESTS={len(results)} VIOLATIONS={len(violations)} "
        f"VIOLATION_ATTESTED={len(attested)} BOUNDARY={len(boundary)} UNRESOLVED={len(unresolved)} "
        f"ATTESTATIONS_STALE={len(stale)}"
    )
    if stale:
        return 3
    if unresolved and not args.allow_unresolved:
        return 2
    if violations:
        return 1
    return 0


def self_test(repo: Repo) -> int:
    failures = 0
    paths = collect(repo, None)
    all_java = sorted(repo.files.keys())
    print(
        f"# 自证 ①：仓库里扫到 {len(paths)} 个含推进构造的测试源文件 / "
        f"{len(all_java)} 个 java 源文件（>0 才算真的在读文件）"
    )
    if not paths or not all_java:
        print("SELF_TEST FAIL：一个文件都没扫到")
        return 3
    for suffix, method_name, ticks, segments, verdict in SELF_TEST:
        target = None
        for path in all_java:
            if path.replace(os.sep, "/").endswith(suffix):
                target = path
                break
        if target is None:
            print(f"SELF_TEST FAIL：找不到文件 *{suffix}")
            failures += 1
            continue
        jf = repo.load(target)
        method = next((m for m in jf.methods if m.name == method_name), None)
        if method is None:
            print(f"SELF_TEST FAIL：{suffix} 里找不到 @Test {method_name}")
            failures += 1
            continue
        if not method.is_test:
            # ★ 这一条是"自证的自证"：只比 tick/判定会漏掉"整个类一个 @Test 都没认出来"（实测踩到）
            print(f"SELF_TEST FAIL：{suffix} 的 {method_name} 没被认成 @Test（扫描面静默变小）")
            failures += 1
            continue
        analyzer = Analyzer(repo)
        result = analyzer.analyze_method(jf, method)
        ok = (
            result.ticks == ticks
            and result.multi_day_segments == segments
            and result.verdict == verdict
        )
        flag = "PASS" if ok else "FAIL"
        print(
            f"SELF_TEST {flag} {jf.class_name}#{method_name}："
            f"tick={result.ticks}（期望 {ticks}）· 多日段={result.multi_day_segments}（期望 {segments}）· "
            f"判定={result.verdict}（期望 {verdict}）"
        )
        if not ok:
            failures += 1
            for line in result.evidence:
                print(f"       {line}")
    total = len(SELF_TEST)
    for suffix, method_name in NON_TEST_SAMPLES:
        target = next(
            (p for p in all_java if p.replace(os.sep, "/").endswith(suffix)), None
        )
        if target is None:
            print(f"SELF_TEST FAIL：找不到独立跑法文件 *{suffix}")
            failures += 1
            total += 1
            continue
        jf = repo.load(target)
        method = next((m for m in jf.methods if m.name == method_name), None)
        total += 1
        if method is None:
            print(f"SELF_TEST FAIL：{suffix} 里找不到 {method_name}(…)")
            failures += 1
        elif method.is_test:
            print(f"SELF_TEST FAIL：{suffix}#{method_name} 被认成了 @Test（独立跑法不许进 surefire）")
            failures += 1
        else:
            print(f"SELF_TEST PASS（非用例）{jf.class_name}#{method_name}(…)：surefire 收不到")
    print(
        f"SELF_TEST {'PASS' if failures == 0 else 'FAIL'}：{total - failures}/{total} 个样本逐值相同"
    )
    return 0 if failures == 0 else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
