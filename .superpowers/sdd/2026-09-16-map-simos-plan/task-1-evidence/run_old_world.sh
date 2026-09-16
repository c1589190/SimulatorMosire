#!/bin/sh
# 修复前世界（b0bcf0e 的 main+test 源）里跑 R-M2 / R-M3：证明"R1-e 之前这两个变异是绿的"。
# 纪律同 run_r1.sh：只在 /tmp 副本、按白名单推目标名、三方 md5 自证、强制断编译错误为 0。
LAB=/tmp/hexmut-r1
REPO=/home/cna/SimulatorMosire
BASE=b0bcf0e
SRCDIR=$LAB/simos-map/src/main/java/io/mosire/simos/map/hex
TESTDIR=$LAB/simos-map/src/test/java/io/mosire/simos/map/hex
CANON="HexCoord.java HexDirection.java HexGrid.java"
cd "$LAB" || exit 99
tag="$1"; mutant="$2"; LOG="$LAB/log-$tag.txt"; RAW="$LAB/raw-$tag.txt"
{
  echo "##### tag=$tag（修复前世界：$BASE 的 main+test）   $(date -Is)"
  # 清非规范名 .java（main 与 test 各自）
  for d in "$SRCDIR" "$TESTDIR"; do
    for f in "$d"/*.java; do
      [ -e "$f" ] || continue
      b=$(basename "$f")
      case " $CANON " in *" $b "*) ;; *) echo "[清理] 非规范文件: $b"; rm -f "$f" ;; esac
    done
  done
  # 从 git 对象取出修复前的 main+test
  for n in $CANON; do git -C "$REPO" show "$BASE:simos-map/src/main/java/io/mosire/simos/map/hex/$n" > "$SRCDIR/$n"; done
  for n in $CANON; do git -C "$REPO" show "$BASE:simos-map/src/test/java/io/mosire/simos/map/hex/${n%.java}Test.java" > "$TESTDIR/${n%.java}Test.java"; done
  echo "[取出] $BASE 的 3 个 main + 3 个 test 已落盘；md5："
  md5sum "$SRCDIR"/*.java "$TESTDIR"/*.java
  # 白名单推目标名 + 三方自证
  base=$(basename "$mutant"); target=${base#*.}
  case " $CANON " in *" $target "*) ;; *) echo "!! 目标名不在白名单: $target —— 作废"; exit 98 ;; esac
  cp "$mutant" "$SRCDIR/$target"
  m1=$(md5sum "$mutant" | awk '{print $1}'); m2=$(md5sum "$SRCDIR/$target" | awk '{print $1}')
  m3=$(git -C "$REPO" show "$BASE:simos-map/src/main/java/io/mosire/simos/map/hex/$target" | md5sum | awk '{print $1}')
  echo "md5(变异体产物)=$m1"; echo "md5(装入后源文件)=$m2"; echo "md5($BASE 原件)=$m3"
  if [ "$m1" = "$m2" ] && [ "$m2" != "$m3" ]; then echo "[三方自证] OK：装入的是变异体，且与 $BASE 原件字节不同"; else echo "!! [三方自证] 失败 —— 作废"; exit 97; fi
  echo "[目录内容] main=$(ls "$SRCDIR" | tr '\n' ' ') test=$(ls "$TESTDIR" | tr '\n' ' ')"
  rm -rf simos-map/target
  ./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test > "$RAW" 2>&1
  echo "### maven_exit=$?"
  cat "$RAW"
  echo "### COMPILATION_ERROR_COUNT=$(grep -c 'COMPILATION ERROR' "$RAW")"
  echo "### Tests run 行:"; grep -E "^\[INFO\] Tests run:|^\[ERROR\] Tests run:" "$RAW"
  echo "### 失败用例（断言失败）："; grep -E "^\[ERROR\]   [A-Za-z].*Test\." "$RAW"
  echo "### 编译产物 md5"; md5sum simos-map/target/classes/io/mosire/simos/map/hex/*.class 2>&1
  echo "### tag=$tag 结束"
} | tee "$LOG"
