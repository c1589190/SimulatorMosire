#!/usr/bin/env bash
# ★★ W1 收尾批（2026-10-11）：判别力探针 —— **不跑 Maven test / 不跑 JUnit / 不跑门禁**，
#    只用 javac+java 直调被测代码路径（EconomyCodec / EconomyChangeSet / 两个测试类的夹具工厂）。
#
# 它回答三件事（账本 §W1 收尾批-五 的真命令与真输出就是本脚本产的）：
#   ① 干净世界：新增夹具与新增断言面是否**全绿**（45 条谓词）；
#   ② 改坏 `MarketTable.isEmpty()` 的 @JsonIgnore：**有市场的载荷**是否当场露 `"empty"` + 读不回；
#      同一变异体在**无市场的载荷**（= 改动前的旧夹具形态）上是否恒绿（= "等价存活"的实证）；
#   ③ 改坏变更集（统计簿 diff 抹平 + apply 漏重建）：往返断言面是否当场红。
#
# ★ 变异体**只在 /tmp 的源码副本上生成** ⇒ 工作树自始至终不被写入（账本里那一轮"就地改坏 ⇒ 还原 ⇒ 比 md5"
#   是同一变异体的另一次执行形态，其 md5 记在账本与 mutant-md5.txt）。
# 前置：`tools/mvn-lock.sh -DskipTests test-compile -am` 已跑过（target/classes 是最新），且
#      `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` 在（提供 Jackson 等全部依赖）。
set -euo pipefail

PROBE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$PROBE_DIR/../../../.." && pwd)"
ECON="$ROOT/simos-economy/src/main/java/io/mosire/simos/economy"
CP="$ROOT/simos-economy/target/test-classes:$ROOT/simos-economy/target/classes:$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"
WORK=/tmp/w1-final-probe

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/src" "$WORK/mut-codec" "$WORK/mut-delta"

echo "### 0. 编译探针（classpath = 测试类 + 主类 + shade 依赖）"
javac -proc:none -cp "$CP" -d "$WORK/classes" "$PROBE_DIR"/W1Probe.java \
  "$PROBE_DIR"/W1FixtureProbe.java "$PROBE_DIR"/W1CodecFixtureProbe.java "$PROBE_DIR"/W1LogProbe.java

echo "### 1. 干净世界（期望：45 GREEN / 0 RED）"
java -cp "$WORK/classes:$CP" W1Probe
java -cp "$WORK/classes:$CP" io.mosire.simos.economy.change.W1FixtureProbe
java -cp "$WORK/classes:$CP" W1CodecFixtureProbe
java -cp "$WORK/classes:$CP" W1LogProbe

echo "### 2. 变异体 M-codec（摘掉 MarketTable.isEmpty() 上的 @JsonIgnore）"
cp "$ECON/model/MarketTable.java" "$WORK/src/MarketTable.java"
python3 - "$WORK/src/MarketTable.java" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = "  @JsonIgnore\n  public boolean isEmpty() {"
assert s.count(old) == 1, "变异点定位失败"
open(p, 'w', encoding='utf-8').write(s.replace(old, "  public boolean isEmpty() {"))
print("  已改坏：", p)
PY
javac -proc:none -cp "$CP" -d "$WORK/mut-codec" "$WORK/src/MarketTable.java"
echo "  -- 同一变异体 × 无市场载荷（旧夹具形态）⇒ 期望恒绿；× 有市场载荷（新夹具形态）⇒ 期望露 empty"
java -cp "$WORK/classes:$WORK/mut-codec:$CP" W1Probe codec || true

echo "### 3. 变异体 M-delta（统计簿 diff 恒 Unchanged + apply 漏掉 marketTables 重建）"
cp "$ECON/change/EconomyChangeSet.java" "$WORK/src/EconomyChangeSet.java"
python3 - "$WORK/src/EconomyChangeSet.java" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
o1 = "FieldDelta.diff(base.marketRoundStats(), target.marketRoundStats()));"
o2 = "FieldDelta.rebuild(base.marketTables(), cs.marketTables(), MarketZoneId::parse),"
assert s.count(o1) == 1 and s.count(o2) == 1, "变异点定位失败"
s = s.replace(o1, "new FieldDelta.Unchanged<>());").replace(o2, "base.marketTables(),")
open(p, 'w', encoding='utf-8').write(s)
print("  已改坏：", p)
PY
javac -proc:none -cp "$WORK/mut-codec:$CP" -d "$WORK/mut-delta" "$WORK/src/EconomyChangeSet.java"
echo "  -- 期望：cs.marketRoundStats.changed / 往返相等 当场 RED"
java -cp "$WORK/classes:$WORK/mut-delta:$CP" io.mosire.simos.economy.change.W1FixtureProbe || true

echo "### 4. 工作树自证：这两个文件必须与 pristine 逐字节相同（md5 见账本 §五-3）"
md5sum "$ECON/model/MarketTable.java" "$ECON/change/EconomyChangeSet.java"
git -C "$ROOT" status --porcelain
