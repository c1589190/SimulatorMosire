#!/bin/bash
# 变异实验室单轮跑测：/tmp/terrainmut/run.sh <tag> <变异体文件路径|-> <目标类名|->
# 每轮：① 恢复干净世界（还原全部原件、清掉白名单之外的 .java）② 比 md5 ③ 清 target ④ 跑测 ⑤ 断言无编译错误
LAB=/tmp/terrainmut
MAIN=$LAB/simos-map/src/main/java/io/mosire/simos/map/terrain
TEST=$LAB/simos-map/src/test/java/io/mosire/simos/map/terrain
cd "$LAB" || exit 99
tag="$1"
mutant="$2"
target="$3"
LOG="$LAB/log-$tag.txt"

{
  echo "##### tag=$tag   $(date -Is)"

  # ① 干净世界：先把两边目录清空，再只放规范名的那几个文件
  rm -f "$MAIN"/*.java "$TEST"/*.java
  cp "$LAB"/ref/TerrainType.java "$LAB"/ref/TerrainCatalog.java "$MAIN"/
  cp "$LAB"/ref/TerrainCatalogTest.java "$TEST"/
  # ② 白名单：清掉规范名之外的 .java（有则打印文件名）
  find "$MAIN" "$TEST" -name '*.java' \
      ! -name 'TerrainType.java' ! -name 'TerrainCatalog.java' ! -name 'TerrainCatalogTest.java' \
      -print -delete
  echo "=== 干净世界 md5（原件）==="
  md5sum "$MAIN"/*.java "$TEST"/*.java

  # ③ 装入变异体（按白名单推到目标类名，绝不按变异文件名落盘）
  if [ "$mutant" != "-" ]; then
    cp "$mutant" "$MAIN/$target"
    echo "=== 装入：$mutant  ->  $MAIN/$target ==="
    echo "md5(落盘的变异体) = $(md5sum "$MAIN/$target" | awk '{print $1}')"
    echo "md5(原件参照)     = $(md5sum "$LAB/ref/$target" | awk '{print $1}')"
    echo "字节数 变异体/原件 = $(stat -c%s "$MAIN/$target") / $(stat -c%s "$LAB/ref/$target")"
  else
    echo "=== 基线轮（未装入变异体）==="
  fi

  # ④ 清 target（防上一轮遗留的 .class 活到本轮）
  rm -rf simos-map/target simos-util/target
  ./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TerrainCatalogTest test 2>&1
  echo "### maven_exit=$?"
  echo "### 编译产物 md5（证明变异真进了字节码）"
  md5sum simos-map/target/classes/io/mosire/simos/map/terrain/*.class 2>&1
} | tee "$LOG"

n=$(grep -c "COMPILATION ERROR" "$LOG")
echo "### COMPILATION ERROR 计数 = $n" | tee -a "$LOG"
if [ "$n" -ne 0 ]; then
  echo "### 本轮作废：出现编译错误，测试结果不算数" | tee -a "$LOG"
  exit 2
fi
echo "### tag=$tag 结束" | tee -a "$LOG"
