#!/bin/sh
# R1 修复轮变异实验室：/tmp/hexmut-r1/run_r1.sh <tag> [变异体路径]
# 纪律（逐条对应派单 §4）：
#   1. 只在 /tmp 副本里跑，工作树不动
#   2. 装盘前三方自证 md5：装入后的源文件 == 变异体产物，且 != 原件参照
#   3. 目标类名按**白名单**推（HexCoord/HexDirection/HexGrid），绝不是按变异文件名
#   4. 每轮先清掉规范名之外的 .java（上一轮残留会污染后续轮次）
#   5. 强制打印 COMPILATION ERROR 计数——不为 0 则该轮作废
#   6. 打印失败用例名 + 行号 + 编译产物 md5（证明变异真的进了字节码）
LAB=/tmp/hexmut-r1
SRCDIR=$LAB/simos-map/src/main/java/io/mosire/simos/map/hex
CANON="HexCoord.java HexDirection.java HexGrid.java"
cd "$LAB" || exit 99

tag="$1"
mutant="${2:-}"
LOG="$LAB/log-$tag.txt"
RAW="$LAB/raw-$tag.txt"

{
  echo "##### tag=$tag   $(date -Is)"

  # (4) 清理非规范文件名的 .java
  for f in "$SRCDIR"/*.java; do
    b=$(basename "$f")
    case " $CANON " in
      *" $b "*) ;;
      *) echo "[清理] 非规范文件: $b"; rm -f "$f" ;;
    esac
  done

  # 还原三个规范名
  cp "$LAB/ref-r1/HexCoord.java" "$LAB/ref-r1/HexDirection.java" "$LAB/ref-r1/HexGrid.java" "$SRCDIR"/
  echo "[还原] 三个规范名已从 ref-r1 拷入"

  # (3) 白名单推目标名 + (2) 三方自证
  if [ -n "$mutant" ]; then
    base=$(basename "$mutant")
    target=${base#*.}
    case " $CANON " in
      *" $target "*) ;;
      *) echo "!! 目标名不在白名单: $target —— 本轮作废"; exit 98 ;;
    esac
    cp "$mutant" "$SRCDIR/$target"
    md5_mutant=$(md5sum "$mutant" | awk '{print $1}')
    md5_installed=$(md5sum "$SRCDIR/$target" | awk '{print $1}')
    md5_ref=$(md5sum "$LAB/ref-r1/$target" | awk '{print $1}')
    echo "[装入] $mutant -> $target"
    echo "md5(变异体产物)   = $md5_mutant"
    echo "md5(装入后源文件) = $md5_installed"
    echo "md5(原件参照)     = $md5_ref"
    if [ "$md5_installed" = "$md5_mutant" ] && [ "$md5_installed" != "$md5_ref" ]; then
      echo "[三方自证] OK：装入的正是变异体，且与原件字节不同"
    else
      echo "!! [三方自证] 失败：装入的可能不是变异体 —— 本轮作废"; exit 97
    fi
  else
    echo "[基线] 未装入变异体"
    md5sum "$SRCDIR"/HexCoord.java "$SRCDIR"/HexDirection.java "$SRCDIR"/HexGrid.java
  fi
  echo "[目录内容] $(ls "$SRCDIR" | tr '\n' ' ')"

  rm -rf simos-map/target
  ./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test > "$RAW" 2>&1
  echo "### maven_exit=$?"
  cat "$RAW"
  # (5) 强制断言编译错误为 0
  echo "### COMPILATION_ERROR_COUNT=$(grep -c 'COMPILATION ERROR' "$RAW")"
  echo "### Tests run 行:"
  grep -E "^\[INFO\] Tests run:|^\[ERROR\] Tests run:" "$RAW"
  echo "### 失败用例（断言失败）："
  grep -E "^\[ERROR\]   [A-Za-z].*Test\." "$RAW"
  echo "### 编译产物 md5（变异是否真进了字节码）"
  md5sum simos-map/target/classes/io/mosire/simos/map/hex/*.class 2>&1
  echo "### tag=$tag 结束"
} | tee "$LOG"
