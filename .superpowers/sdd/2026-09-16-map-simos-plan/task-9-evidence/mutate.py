"""Task 9 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

用法：
  python3 mutate.py <m9v-N>      # 把第 N 条变异写进 $REPO
  python3 mutate.py --files <id> # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

★ 装置形态继承 Task 4~8（同一套坑照旧）：
  ① 变异不得留下**未使用的 import** —— checkstyle 挂在 validate（`mvn test` 会跑到），
     会抢在 surefire 之前失败，"红"就不是断言红了。故各轮的变异体都**保持两个 import 被引用**
     （m9v-2 的写死阈值版仍走 TerrainCatalog.KEYS + TerrainType 的循环，正是为了这个）；
  ② 不按变异文件名拷入 —— 变异体**就写在目标类名的文件里**；
  ③ 每轮**显式声明**文件集合，run.sh 的自证是"只有声明的这些变了"。

★ 本任务与 Task 8 的不同：变异对象是**纯函数分类器**（无参数 record、无共适应面）。
  唯一跨文件的一轮是 m9v-2：判据要求"分类器自带一套阈值 + 词表挪一点"**同时**发生 ——
  只有两处一起变，"查表"与"自带数"才分得开（只改词表两处都绿，只改分类器无从分叉）。
"""

import sys

REPO = "/tmp/m9lab/repo"
GEN = "simos-map/src/main/java/io/mosire/simos/map/generate/"
TER = "simos-map/src/main/java/io/mosire/simos/map/terrain/"
CLS = GEN + "TerrainClassifier.java"
CAT = TER + "TerrainCatalog.java"

# 变异体共用的锚点（原件文本，逐字取自 spotless 后的工作树）
ANCHOR_LOOKUP = """    String candidate = TerrainCatalog.KEYS.getLast();
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      if (height < t.maxHeight()) {
        candidate = t.key();
        break;
      }
    }
"""

ANCHOR_GATE = """    if (DESERT.equals(candidate) && humidity >= DESERT_MAX_HUMIDITY) {
      return DESERT_FALLBACK;
    }
"""


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
    if mid == "m9v-2":
        return [CLS, CAT]
    return [CLS]


def mutate(mid):
    # ── m9v-1：沙漠带恒退 plains ⇒ desert 再也产不出来（GSimulator 的 L9：词表 7 项、分类器只产 6 项）──
    if mid == "m9v-1":
        replace_once(
            CLS,
            "    if (DESERT.equals(candidate) && humidity >= DESERT_MAX_HUMIDITY) {",
            "    if (DESERT.equals(candidate)) { // 变异：沙漠的产出路径被删掉（恒退 plains）",
        )

    # ── m9v-2：分类器里写死一套高度阈值（数组手抄旧边界）+ 词表中间边界 0.30 → 0.32 ──
    elif mid == "m9v-2":
        replace_once(
            CLS,
            ANCHOR_LOOKUP,
            """    double[] hardcodedMax = {0.30, 0.45, 0.55, 0.65, 0.78, 0.90, 1.00}; // 变异：私有的第二套阈值
    String candidate = TerrainCatalog.KEYS.getLast();
    int band = 0;
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      if (height < hardcodedMax[band++]) {
        candidate = t.key();
        break;
      }
    }
""",
        )
        # 只挪**中间**的那条共享边界（ocean.maxHeight 与 plains.minHeight 是同一个字面量）：
        replace_once(CAT, '"#1F5FA0", 0.00, 0.30,', '"#1F5FA0", 0.00, 0.32,')  # ocean 带尾
        replace_once(CAT, '"#9CCB5B", 0.30, 0.45,', '"#9CCB5B", 0.32, 0.45,')  # plains 带头

    # ── m9v-3：去掉沙漠的低湿度门（落 desert 带一律 desert）──
    elif mid == "m9v-3":
        replace_once(
            CLS,
            ANCHOR_GATE,
            "",
        )

    # ── m9v-4（R-9d 的等价形态）："全不中 ⇒ 退末带" 改成 "全不中 ⇒ 常量 plains" ──
    elif mid == "m9v-4":
        replace_once(
            CLS,
            "    String candidate = TerrainCatalog.KEYS.getLast();",
            '    String candidate = "plains"; // 变异：全不中 ⇒ 兜底常量（原为退末带）',
        )

    # ── m9v-5：让 classify 对一段输入抛异常（负高度）──
    elif mid == "m9v-5":
        replace_once(
            CLS,
            "    String candidate = TerrainCatalog.KEYS.getLast();",
            """    if (height < 0.0) { // 变异：对域外负值抛异常
      throw new IllegalArgumentException("高度不得为负: " + height);
    }
    String candidate = TerrainCatalog.KEYS.getLast();""",
        )

    # ── m9v-6：把 ocean 带也加上气候门（同一道低湿度门）──
    elif mid == "m9v-6":
        replace_once(
            CLS,
            "    if (DESERT.equals(candidate) && humidity >= DESERT_MAX_HUMIDITY) {",
            '    if (("ocean".equals(candidate) || DESERT.equals(candidate)) // 变异：ocean 也加门\n'
            "        && humidity >= DESERT_MAX_HUMIDITY) {",
        )

    else:
        raise AssertionError(f"未知变异 id: {mid}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--files":
        print(" ".join(files_of(sys.argv[2])))
        sys.exit(0)
    mutate(sys.argv[1])
