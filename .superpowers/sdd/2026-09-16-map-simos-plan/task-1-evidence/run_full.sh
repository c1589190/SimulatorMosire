#!/bin/sh
# 变异实验室完整跑测：/tmp/hexmut/run_full.sh <tag> [变异体文件路径]
# - 每次先还原三个 main 源文件，装入变异体（若有），核 md5，再删 target 重编跑测
# - 全部输出（含 md5 段）tee 进 /tmp/hexmut/log-<tag>.txt
LAB=/tmp/hexmut
SRCDIR=$LAB/simos-map/src/main/java/io/mosire/simos/map/hex
cd "$LAB" || exit 99
tag="$1"
mutant="$2"
LOG="$LAB/log-$tag.txt"

{
  echo "##### tag=$tag   $(date -Is)"
  cp "$LAB"/ref/HexCoord.java "$LAB"/ref/HexDirection.java "$LAB"/ref/HexGrid.java "$SRCDIR"/
  if [ -n "$mutant" ]; then
    target=$(basename "$mutant"); target=${target#*.}
    cp "$mutant" "$SRCDIR/$target"
    echo "装入变异体：$mutant  ->  $target"
    echo "md5(装入的源文件) = $(md5sum "$SRCDIR/$target" | awk '{print $1}')"
    echo "md5(原件参照)     = $(md5sum "$LAB/ref/$target" | awk '{print $1}')"
  else
    echo "（未装入变异体——这是「改前」基线）"
  fi
  echo "源文件目录内容：$(ls "$SRCDIR" | tr '\n' ' ')"
  echo "源文件 md5："
  md5sum "$SRCDIR"/HexCoord.java "$SRCDIR"/HexDirection.java "$SRCDIR"/HexGrid.java
  echo
  rm -rf simos-map/target
  ./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test 2>&1
  echo "### maven_exit=$?"
  echo "### 编译产物 md5（变异是否真进了字节码）"
  md5sum simos-map/target/classes/io/mosire/simos/map/hex/*.class 2>&1
  echo "### tag=$tag 结束"
} | tee "$LOG"
