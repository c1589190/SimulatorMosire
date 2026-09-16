"""Task 11 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

用法：
  python3 mutate.py <m11v-N>      # 把第 N 条变异写进 $REPO
  python3 mutate.py --files <id>  # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

★ 装置形态继承 Task 4~10（同一套坑照旧）：
  ① 变异不得留下**未使用的 import**，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、编译在它之后，
     "红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
  ② 不按变异文件名拷入 —— 变异体**就写在目标类名的文件里**；
  ③ 每轮**显式声明**文件集合，run.sh 的自证是"只有声明的这些变了"。

★ 本任务的两条**预先声明的等价形态**（非事后辩解）：
  - m11v-1（"六邻在图纸里随便挑"）：字面用 RNG 随便挑会造出 X→Y 与 Y→X 互指的环（互指只要求互为对方候选，
    高度判据删掉后没有任何东西挡它），emitChain 的游标会无限走下去 —— 红的是**超时**，不是断言红。
    等价形态：候选 = 在图纸邻格里**坐标序更小**者、取最小 —— "不看高度"这条被保护判据照样删掉
    （上游/等高一应出现），而坐标严格降 ⇒ 无环 ⇒ 走的是断言红。
  - m11v-2（"起点改成随机格"）：本设计不走"从最高格起步"的路径，等价形态按补充文件的裁定取
    "只从随机挑的一个源走一条线、不走全网络"——产出的那条链不保证从最高格起（恰选中才绿，实测未选中）。

★ 其余注意：m11v-3 之后 emitChain 的 seed/n 形参、m11v-1 之后 rngFor、m11v-5 之后 SEED_SALT/Q_SALT
  均成无人调用 —— 形参/私有方法/私有常量，javac 与 checkstyle 都不报（SpotBugs 只在 verify 跑，本装置跑 test）。
"""

import sys

REPO = "/tmp/m11lab/repo"
GEN = "simos-map/src/main/java/io/mosire/simos/map/generate/"
CLS = GEN + "RiverBuilder.java"


def read(path):
    return open(f"{REPO}/{path}", encoding="utf-8").read()


def write(path, text):
    with open(f"{REPO}/{path}", "w", encoding="utf-8") as f:
        f.write(text)


def replace_once(path, old, new):
    """改且只改一处：old 必须**恰好出现一次**，否则抛（防"锚点漂移后改错地方"）。"""
    text = read(path)
    n = text.count(old)
    if n != 1:
        raise AssertionError(f"{path}: 锚点出现 {n} 次（要求恰好 1 次）:\n{old}")
    write(path, text.replace(old, new, 1))


def files_of(mid):
    return [CLS]


def mutate(mid):
    # ── 补充-1：出边不看高度（判据从『严格更低』换成坐标序；等价形态见模块注释）──
    if mid == "m11v-1":
        replace_once(
            CLS,
            """    double height = map.hexes().get(at).height();
    List<HexCoord> candidates = new ArrayList<>();
    for (HexCoord nb : at.neighbors()) {
      HexCell cell = map.hexes().get(nb);
      if (cell != null && cell.height() < height) {
        candidates.add(nb);
      }
    }
    if (candidates.isEmpty()) {
      return null;
    }
    candidates.sort(HexCoord::compareTo);
    return candidates.get(rngFor(seed, at).nextInt(candidates.size()));""",
            """    List<HexCoord> candidates = new ArrayList<>();
    for (HexCoord nb : at.neighbors()) {
      HexCell cell = map.hexes().get(nb);
      if (cell != null && nb.compareTo(at) < 0) { // 变异 m11v-1：不看高度（判据换成坐标序）
        candidates.add(nb);
      }
    }
    if (candidates.isEmpty()) {
      return null;
    }
    candidates.sort(HexCoord::compareTo);
    return candidates.get(0); // 变异 m11v-1：取自然序最小（坐标严格降 ⇒ 仍无环）""",
        )

    # ── 补充-2：只从随机挑的一个源走一条线，不走全网络 ──
    elif mid == "m11v-2":
        replace_once(
            CLS,
            """    int n = 0;
    for (HexCoord h : map.hexes().keySet().stream().sorted().toList()) {
      for (EdgeRef e : incident(h, out, in)) {
        if (!unused.contains(e)) {
          continue; // 已随早先的链消费
        }
        HexCoord other = e.a().equals(h) ? e.b() : e.a();
        // 触发边定向：h 的出边恰指向对端 ⇒ 从 h 起；否则对端流入 h，从对端起。
        HexCoord from = out.get(h) != null && out.get(h).equals(other) ? h : other;
        n++;
        emitChain(from, out, in, unused, seed, n, pathwaysUp, edgesUp);
      }
    }""",
            """    int n = 0;
    List<HexCoord> withEdges = new ArrayList<>();
    for (HexCoord h : map.hexes().keySet().stream().sorted().toList()) {
      if (out.containsKey(h) || in.containsKey(h)) {
        withEdges.add(h);
      }
    }
    if (!withEdges.isEmpty()) { // 变异 m11v-2：只从随机挑的一个源走一条线，不走全网络
      HexCoord picked = withEdges.get(new Random(seed).nextInt(withEdges.size()));
      HexCoord from = out.containsKey(picked) ? picked : in.get(picked).get(0);
      n++;
      emitChain(from, out, in, unused, seed, n, pathwaysUp, edgesUp);
    }""",
        )

    # ── 补充-3：所有河共用一个 PathwayId（GSimulator 的现状）──
    elif mid == "m11v-3":
        replace_once(
            CLS,
            '    PathwayId id = new PathwayId("river-" + seed + "-" + n);',
            '    PathwayId id = new PathwayId("river"); // 变异 m11v-3：所有河共用一个 ID',
        )

    # ── 补充-4：分支不切开（其余入边并进同一条线 ⇒ 线内出现度 3 的分叉）──
    # ★ 首版写成"链穿过分支继续"（去掉 break、沿出边继续走）——那条变异确实红，但红在
    #   edgesAreConsistent（两条链重复消费同一条边），branchesAreSeparatePathways 不红：
    #   沿出边走出来的链**结构上永远是路径**，单条线内度数恒 ≤ 2，"线内分叉"根本造不出来。
    #   本版把分支点的其余入边并进同一条线，v 在线内挂 3 条边 ⇒ 度数断言才是被保护的断言。
    elif mid == "m11v-4":
        replace_once(
            CLS,
            """      chain.add(new EdgeRef(cur, next));
      if (inDegree(in, next) >= 2) {
        break; // 分支点即端点：它的出边开新链
      }
      cur = next;""",
            """      chain.add(new EdgeRef(cur, next));
      if (inDegree(in, next) >= 2) {
        // 变异 m11v-4：分支不切开——其余入边并进同一条线（线内出现分叉）
        for (HexCoord up : in.get(next)) {
          if (!up.equals(cur)) {
            chain.add(new EdgeRef(up, next));
          }
        }
      }
      cur = next;""",
        )

    # ── 补充-5：随机源用常量种子，不看 seed ──
    elif mid == "m11v-5":
        replace_once(
            CLS,
            "    return new Random(seed * SEED_SALT + at.q() * Q_SALT + at.r());",
            "    return new Random(42L); // 变异 m11v-5：随机源不看 seed",
        )

    # ── 补充-6：空也走 Upsert（构造期拒空 ⇒ 应当场抛）──
    elif mid == "m11v-6":
        replace_once(
            CLS,
            "    return entries.isEmpty() ? new FieldDelta.Unchanged<>() : new FieldDelta.Upsert<>(entries);",
            "    return new FieldDelta.Upsert<>(entries); // 变异 m11v-6：空也走 Upsert",
        )

    # ── 补充-7：edges 的 key 用 groupId 而不是 PathwayId ──
    elif mid == "m11v-7":
        replace_once(
            CLS,
            '      edgesUp.put(e.toString(), new EdgeTags(Map.of(id.value(), Map.of())));',
            '      edgesUp.put(e.toString(), new EdgeTags(Map.of(RIVER_GROUP, Map.of())));'
            " // 变异 m11v-7：key 用 groupId",
        )

    else:
        raise AssertionError(f"未知变异 id: {mid}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--files":
        print(" ".join(files_of(sys.argv[2])))
        sys.exit(0)
    mutate(sys.argv[1])
