"""Task 4 变异实验室：按 id 把一条变异写进目标源文件。

用法： python3 mutate.py <m4v-N>
每条变异都是**唯一匹配**的精确替换；匹配数 != 1 直接抛，避免"以为改了其实没改"。
变异体**就写在目标文件里**（文件名天然是目标类名），不做"按变异文件名拷入"那种会变成编译错误的事。
"""

import sys

SRC = "/home/cna/SimulatorMosire/simos-map/src/main/java/io/mosire/simos"

MUTATIONS = {
    # ★ 规范序：删掉构造期的交换 ⇒ 两个方向不再是同一对象
    "m4v-1": (
        "map/pathway/EdgeRef.java",
        """    if (a.compareTo(b) > 0) {
      HexCoord t = a;
      a = b;
      b = t;
    }
""",
        """    // 变异 m4v-1：删掉构造期的规范序交换
""",
    ),
    # 删掉自环校验
    "m4v-2": (
        "map/pathway/EdgeRef.java",
        """    if (a.equals(b)) {
      throw new IllegalArgumentException("边不能自环: " + a);
    }
""",
        """    // 变异 m4v-2：删掉自环校验
""",
    ),
    # ★ 让构造器忽略传入的 id、改用内容派生的 id（"ID 不由内容派生"的钉子要抓的就是它）
    "m4v-3": (
        "map/pathway/Pathway.java",
        """    edges = List.copyOf(edges);
    props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
  }
""",
        """    edges = List.copyOf(edges);
    props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
    id = derivedId(edges); // 变异 m4v-3：忽略传入的 id
  }

  /** 变异 m4v-3：内容派生的 id —— 正是本任务要禁的那件事（改内容 ⇒ 换身份）。 */
  private static PathwayId derivedId(List<EdgeRef> edges) {
    return new PathwayId("derived-" + edges.size());
  }
""",
    ),
    # ★ 保序：改用 Map.copyOf（>=3 项时按哈希表序，不是插入序）
    "m4v-4": (
        "map/pathway/EdgeTags.java",
        """    byPathway = Collections.unmodifiableMap(copy);
""",
        """    byPathway = Map.copyOf(copy); // 变异 m4v-4：改用 Map.copyOf（不保序）
""",
    ),
    # ★ m4v-3 的补充轮：id 仍**部分**看传入值（前缀保留），但内容一变就换身份
    #   ⇒ `idsArePersistedNotDerived` 的**第二条**断言（保住 id、只换一个中间边）才有机会响；
    #   m4v-3 那轮红在第一条断言上，JUnit 到不了第二条。
    "m4v-6": (
        "map/pathway/Pathway.java",
        """    edges = List.copyOf(edges);
    props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
  }
""",
        """    edges = List.copyOf(edges);
    props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
    id = derivedId(id, edges); // 变异 m4v-6：id 里混进内容
  }

  /** 变异 m4v-6：以传入 id 为前缀、把内容哈希拼进去 —— 内容一变就换身份。 */
  private static PathwayId derivedId(PathwayId id, List<EdgeRef> edges) {
    return new PathwayId(id.value() + "-" + edges.hashCode());
  }
""",
    ),
    # ★ R-48-f 的钉子本身：删掉手写 toString（退回 record 默认实现）
    "m4v-5": (
        "map/pathway/PathwayId.java",
        """  /** 裸值。见类注释：它是**地址形式**，供变更集的 String key 使用，**不是**给人看的调试输出。 */
  @Override
  public String toString() {
    return value;
  }

""",
        "",
    ),
}


def main() -> None:
    mutation_id = sys.argv[1]
    relative_path, old, new = MUTATIONS[mutation_id]
    path = f"{SRC}/{relative_path}"
    text = open(path, encoding="utf-8").read()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{mutation_id}: 期望 1 处匹配，实得 {count} —— 变异未可靠落盘，作废")
    open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
    print(f"{mutation_id}: mutated {relative_path}")


main()
