#!/bin/bash
# Task 3 变异实验室。每轮：干净世界（恢复原件 + 清 target/{classes,test-classes}）→ 变异 → md5 自证 → 跑全量模块测试 → 断言无编译错误
ROOT=/home/cna/SimulatorMosire
SRC=$ROOT/simos-map/src/main/java/io/mosire/simos
PRISTINE=/tmp/m3lab/pristine
LAB=/tmp/m3lab
export PATH="$PATH"

declare -A TARGET=(
  [m3v-1]=map/region/Region.java
  [m3v-2]=map/region/Region.java
  [m3v-3]=map/region/Region.java
  [m3v-10]=map/region/Region.java
  [m3v-4]=map/region/RegionBoundary.java
  [m3v-5]=map/hex/HexVertex.java
  [m3v-6]=map/region/RegionBoundary.java
  [m3v-7]=map/region/RegionBoundary.java
  [m3v-8]=map/region/RegionBoundary.java
  [m3v-9]=map/region/RegionIndex.java
)
ORDER="${ROUNDS:-m3v-1 m3v-2 m3v-3 m3v-4 m3v-5 m3v-6 m3v-7 m3v-8 m3v-9 m3v-10}"

restore_pristine() {
  cp -rf $PRISTINE/map $SRC/
}

for id in $ORDER; do
  echo "=================== ROUND $id ==================="
  restore_pristine
  rm -rf $ROOT/simos-map/target/classes $ROOT/simos-map/target/test-classes
  rel=${TARGET[$id]}
  before=$(md5sum "$SRC/$rel" | awk '{print $1}')
  python3 $LAB/mutate.py "$id" || { echo "!! 变异脚本失败 —— 作废"; continue; }
  after=$(md5sum "$SRC/$rel" | awk '{print $1}')
  echo "md5 原件=$before  变异体=$after"
  if [ "$before" == "$after" ]; then
    echo "!! 变异体与原件字节相同 —— 本轮作废"
    continue
  fi
  echo "自证：字节不同 OK"
  (cd $ROOT && timeout 900 ./mvnw -pl simos-map -am test > $LAB/logs/$id.log 2>&1)
  ce=$(grep -c "COMPILATION ERROR" $LAB/logs/$id.log)
  echo "COMPILATION ERROR count = $ce"
  if [ "$ce" != "0" ]; then
    echo "!! 编译错误 —— 本轮作废（不是断言失败）"
    grep -A3 "COMPILATION ERROR" $LAB/logs/$id.log | head -20
    continue
  fi
  echo "--- 红了哪些用例（Tests run 行里 Failures/Errors 非 0 的）---"
  grep -E "Tests run:" $LAB/logs/$id.log | grep -vE "Failures: 0, Errors: 0" | sed 's/^\[INFO\] //;s/^\[ERROR\] //'
  echo "--- 失败详情 ---"
  grep -E "^\[ERROR\]" $LAB/logs/$id.log | grep -vE "Tests run:|Reactor|BUILD|mvnw|help|^-|^\[ERROR\] $|For more information|Full documentation|\[Help 1\]|after earlier|To see|-> \[Help" | head -25
  cp $LAB/logs/$id.log $LAB/logs/$id.kept
done

echo "=================== 恢复干净世界 ==================="
restore_pristine
rm -rf $ROOT/simos-map/target/classes $ROOT/simos-map/target/test-classes
md5sum $SRC/map/hex/HexVertex.java $SRC/map/region/Region.java $SRC/map/region/RegionBoundary.java $SRC/map/region/RegionIndex.java
