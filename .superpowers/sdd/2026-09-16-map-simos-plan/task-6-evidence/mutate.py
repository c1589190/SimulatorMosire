"""Task 6 变异实验室：按 id 把一条变异写进目标源文件。

用法： python3 mutate.py <m6v-N>
每条变异是**若干步唯一匹配**的精确替换；匹配数 != 1 直接抛，避免"以为改了其实没改"。
变异体**就写在目标类名的文件里**（不按变异文件名拷入 —— 那会让"红"变成编译错误，不算数）。

★ 五轮的目标文件都是同一个 `map/change/MapChangeSet.java`（brief 的四行变异表 + 一行 spec 钉子）。

★ 从 Task 5 继承的两条坑：① 变异不得留下**未使用的 import**（checkstyle 挂在 validate，
   会抢在 surefire 之前失败，"红"就不是断言红了）；② 改签名要补兼容构造器，否则连编译都过不去。
   本文件的五条变异都不动签名、不删 import —— 故都是**一步**。
"""

import sys

SRC = "/home/cna/SimulatorMosire/simos-map/src/main/java/io/mosire/simos"

MUTATIONS = {
    # brief 表第 1 行：`between` 里 hexes 的比较被摘掉（恒 Unchanged）。
    # 期望红的**必须包含** betweenDetectsChangedHexValue（同 key 不同 value 是本类型的核心判别力）。
    # 现场记（2026-09-17）：`diff` 的“组件名”参数在加 Patch 变体时删掉了（它只被那份已删的异常消息
    # 用着，是死参数），故第 1/2 轮的匹配串去掉了尾参。
    "m6v-1": (
        "map/change/MapChangeSet.java",
        [
            (
                """        diff(base.hexes(), target.hexes()),
""",
                """        new FieldDelta.Unchanged<HexCell>(), // 变异 m6v-1：hexes 的比较被摘掉
""",
            )
        ],
    ),
    # brief 表第 2 行：terrainTypes 的比较被摘掉。
    # ★ R-48-i：期望红**必须来自** betweenDetectsChangedTerrainType —— 若三条 betweenDetects* 全
    #   围着 hexes 转，摘掉 terrainTypes 的比较会**全绿**（护栏是装饰）。
    "m6v-2": (
        "map/change/MapChangeSet.java",
        [
            (
                """        diff(base.terrainTypes(), target.terrainTypes()),
""",
                """        new FieldDelta.Unchanged<TerrainType>(), // 变异 m6v-2：terrainTypes 的比较被摘掉
""",
            )
        ],
    ),
    # brief 表第 3 行：两边相等时 between 返回 null（"全 Unchanged"退化成 null）。
    "m6v-3": (
        "map/change/MapChangeSet.java",
        [
            (
                """    Objects.requireNonNull(target, "target");
    return new MapChangeSet(
""",
                """    Objects.requireNonNull(target, "target");
    if (base.equals(target)) {
      return null; // 变异 m6v-3：全等时返回 null（该给"全 Unchanged"的地方给了 null）
    }
    return new MapChangeSet(
""",
            )
        ],
    ),
    # brief 表第 4 行：isEmpty() 只看 hexes。
    # ★★ R-48-k：GSimulator 的 MapResolver 只在 !diff.isEmpty() 时才调 applyDiff，而它的 isEmpty()
    #   排除了 edges ⇒"只改了一条边"根本不进 apply。本变异就是那个缺陷的复刻 ——
    #   它**只**能被 emptyDiffStillEntersApply 的**后半**（只改 edges ⇒ isEmpty() 必须为 false）抓住。
    "m6v-4": (
        "map/change/MapChangeSet.java",
        [
            (
                """    return !(hexes.changed()
        || regions.changed()
        || cities.changed()
        || terrainTypes.changed()
        || pathways.changed()
        || pathwayGroups.changed()
        || edges.changed());
""",
                """    return !hexes.changed(); // 变异 m6v-4：只看 hexes（老仓 isEmpty() 漏 edges 的复刻）
""",
            )
        ],
    ),
    # ★ R-48-e（brief 表之外，自己加的）：apply 把 spec 换成 empty() 的种子，而不是从 base 取。
    #   spec 不进变更集 ⇒"两边 spec 相同"的夹具看不见这一条，只有 applyTakesSpecFromBase 这种
    #   **两边 spec 分叉**的用例才抓得住。
    "m6v-5": (
        "map/change/MapChangeSet.java",
        [
            (
                """        base.spec());
""",
                """        io.mosire.simos.map.generate.GenerationSpec.defaults(0L)); // 变异 m6v-5：spec 不从 base 取
""",
            )
        ],
    ),
    # ★★ Patch 变体（控制器裁决后新增的第四条变体）——两轮，不铺开。
    #   m6v-6：混合分支**只返回 Upsert、丢掉 removals** —— 正是老仓那类静默数据损失的形态。
    #     期望红：betweenDetectsAddedAndRemovedHex（两侧都在那两条断言）+ patchIsNotSilentlyHalfApplied。
    "m6v-6": (
        "map/change/MapChangeSet.java",
        [
            (
                """    return new FieldDelta.Patch<>(
        new FieldDelta.Upsert<>(upserts), new FieldDelta.Remove<>(removals));
""",
                """    return new FieldDelta.Upsert<>(upserts); // 变异 m6v-6：丢掉 removals（静默丢一半）
""",
            )
        ],
    ),
    #   m6v-7：rebuild 的 Patch 分支**跳过 removals**（只做 upserts）。
    #     期望红：mixedChangeRoundTrips（往返对不上）+ patchIsNotSilentlyHalfApplied（被删的键还在）。
    "m6v-7": (
        "map/change/MapChangeSet.java",
        [
            (
                """      return rebuild(rebuild(base, patch.removals(), parse), patch.upserts(), parse);
""",
                """      return rebuild(base, patch.upserts(), parse); // 变异 m6v-7：跳过 removals
""",
            )
        ],
    ),
}


def main() -> None:
    mutation_id = sys.argv[1]
    relative_path, steps = MUTATIONS[mutation_id]
    path = f"{SRC}/{relative_path}"
    text = open(path, encoding="utf-8").read()
    for old, new in steps:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{mutation_id}: 期望 1 处匹配，实得 {count} —— 变异未可靠落盘，作废")
        text = text.replace(old, new, 1)
    open(path, "w", encoding="utf-8").write(text)
    print(f"{mutation_id}: mutated {relative_path}（{len(steps)} 步）")


main()
