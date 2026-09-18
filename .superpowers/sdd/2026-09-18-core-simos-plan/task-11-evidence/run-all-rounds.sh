#!/bin/bash
# 12 轮整体重跑（对着 post-spotless 的出货字节）。
set -u
cd /home/dev/SimulatorMosire
E=.superpowers/sdd/2026-09-18-core-simos-plan/task-11-evidence
M=$E/mutants
CB=simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java
TL=simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java
ET=simos-core/src/main/java/io/mosire/simos/core/observe/EventTypes.java
LOGD=$E/logs-postspotless
mkdir -p $LOGD

run() { # name src sel expect
  bash $M/mut-round.sh "$2" "$M/$1.$3.java" "-Dtest=$4" "$LOGD/$1.log" "$5"
  rc=$?
  if [ $rc -eq 0 ]; then echo "$1  杀死($5)"; else echo "$1  !!存活/作废 rc=$rc"; fi
}

run t11-m1  $CB CommandBus  CorrelationChainTest         CorrelationChainTest
run t11-m2  $TL Timeline    CorrelationChainTest         CorrelationChainTest
run t11-m2b $TL Timeline    CorrelationChainTest         CorrelationChainTest
run t11-m3  $CB CommandBus  CommandBusLoggingTest        CommandBusLoggingTest
run t11-m4  $CB CommandBus  CommandBusLoggingTest        CommandBusLoggingTest
run t11-m5  $CB CommandBus  CommandBusLoggingTest        CommandBusLoggingTest
run t11-m6  $ET EventTypes  EventTypesTest               EventTypesTest
run t10-m1  $CB CommandBus  OptimisticConcurrencyTest    OptimisticConcurrencyTest
run t10-m2  $CB CommandBus  CommandBusDispatchTest       CommandBusDispatchTest
run t10-m2b $CB CommandBus  CommandBusDispatchTest       CommandBusDispatchTest
run t10-m3  $CB CommandBus  OptimisticConcurrencyTest    OptimisticConcurrencyTest
run t10-m4  $CB CommandBus  CommandBusDispatchTest       CommandBusDispatchTest
echo "=== 12 轮结束 ==="
