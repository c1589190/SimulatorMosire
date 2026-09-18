#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Task 11 的变异体生成器。

纪律（CLAUDE.md 形态 1）：
  * 变异体一律**按文本精确替换从当前原件生成**，不手抄——手抄出来的"变异体"可能顺手把别处也改了，
    甚至可能因为抄错而与原件字节相同（那会让 javac 编的仍是原件，三向全绿）。
  * 每个锚点必须**恰好命中一次**，否则当场失败。宁可不产出，也不产出一份说不清的差分。

生成物只作证据，不进版本库的源码树；由 mut-round.sh 按**规范文件名**推送到工作树。
"""

import os
import sys

ROOT = "/home/dev/SimulatorMosire"
EVID = os.path.join(
    ROOT, ".superpowers/sdd/2026-09-18-core-simos-plan/task-11-evidence/mutants"
)

FILES = {
    "CommandBus": "simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java",
    "Timeline": "simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java",
    "EventTypes": "simos-core/src/main/java/io/mosire/simos/core/observe/EventTypes.java",
}


def edit(text, old, new, count=1):
    got = text.count(old)
    if got != count:
        sys.exit(
            "!! 锚点命中 %d 次（期望 %d）——拒绝产出：\n---\n%s\n---" % (got, count, old[:300])
        )
    return text.replace(old, new)


# ── Task 11 自有护栏的变异体（裁定 42：新增护栏必须自带变异轮） ─────────────────────

T11_M1 = ("CommandBus", [
    ("return EventRow.of(type, envelope.initiator(), payload, envelope.correlationId());",
     "return EventRow.of(type, envelope.initiator(), payload, envelope.commandId());"),
], "★ 判据二的全部：事件的 correlationId 换成 commandId")

T11_M2 = ("Timeline", [
    ("""  public void appendRevision(RevisionRow row, List<EventRow> events) {
    store.inTransaction(
        connection -> {
          insert(connection, row);
          EventStore.insertAll(connection, events);
          return null;
        });
  }""",
     """  public void appendRevision(RevisionRow row, List<EventRow> events) {
    store.inTransaction(
        connection -> {
          insert(connection, row);
          return null;
        });
    store.inTransaction(
        connection -> {
          EventStore.insertAll(connection, events);
          return null;
        });
  }"""),
], "原子性：拆两事务，**先 revision 后事件**")

T11_M2B = ("Timeline", [
    ("""  public void appendRevision(RevisionRow row, List<EventRow> events) {
    store.inTransaction(
        connection -> {
          insert(connection, row);
          EventStore.insertAll(connection, events);
          return null;
        });
  }""",
     """  public void appendRevision(RevisionRow row, List<EventRow> events) {
    store.inTransaction(
        connection -> {
          EventStore.insertAll(connection, events);
          return null;
        });
    store.inTransaction(
        connection -> {
          insert(connection, row);
          return null;
        });
  }"""),
], "原子性：拆两事务，**先事件后 revision**（另一序）")

T11_M3 = ("CommandBus", [
    ("""      case CommandResult.Committed committed ->
          LOG.info(
              "命令提交: type={} commandId={} correlationId={} 新坐标={}@{}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              committed.ref().branch().value(),
              committed.ref().revision().value());""",
     """      case CommandResult.Committed committed -> {
        // 变异 m3：删掉提交日志（仍是穷尽 switch，故能编译）
      }"""),
], "§7.3：删掉「命令提交」那条日志")

T11_M4 = ("CommandBus", [
    ("""              "命令冲突: type={} commandId={} correlationId={} 真实head={}@{}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              conflict.current().branch().value(),
              conflict.current().revision().value());""",
     """              "命令冲突: type={} commandId={} correlationId={} 真实head={}@{}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              envelope.branch().value(),
              envelope.expectedRevision().value());"""),
], "§7.3：冲突日志印**期望值**而非真实 head（日志就失去诊断价值）")

T11_M5 = ("CommandBus", [
    ("""              "命令提交: type={} commandId={} correlationId={} 新坐标={}@{}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              committed.ref().branch().value(),
              committed.ref().revision().value());""",
     """              "命令提交: type={} commandId={} correlationId={} 新坐标={}@{} payload={}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              committed.ref().branch().value(),
              committed.ref().revision().value(),
              envelope.payloadJson());"""),
], "§8.1：提交日志带上**载荷明文**（哨兵串必须让用例红）")

T11_M6 = ("EventTypes", [
    ("          TIMELINE_CONFLICT);",
     '          TIMELINE_CONFLICT,\n          "simos.revision.created");'),
], "C20：ALL 里混进不存在的 simos.revision.created")

# ── Task 10 的五个变异体，**按新源码重新生成**（Task 11 改了 CommandBus，旧差分已失效） ──

T10_M1 = ("CommandBus", [
    ("""      Optional<RevisionId> current = timeline.head(envelope.branch());
      if (current.isEmpty() || current.get().compareTo(envelope.expectedRevision()) != 0) {
        return current
            .<CommandResult>map(
                now -> conflict(envelope, trace, new StateRef(envelope.branch(), now)))
            .orElseGet(() -> reject(envelope, trace, "分支不存在: " + envelope.branch().value()));
      }
      return commit(envelope, trace, new StateRef(envelope.branch(), current.get()), changeSet);""",
     """      Optional<RevisionId> current = timeline.head(envelope.branch());
      return commit(envelope, trace, new StateRef(envelope.branch(), current.get()), changeSet);"""),
], "判据三：删掉 ③ 锁内复查（只剩 ① 的快速失败）")

T10_M2 = ("CommandBus", [
    ("    if (head.isEmpty() || head.get().compareTo(envelope.expectedRevision()) != 0) {",
     "    if (head.isEmpty() || false) {"),
], "① 的「过期快失败」半边改成恒 false")

T10_M2B = ("CommandBus", [
    ("    if (head.isEmpty() || head.get().compareTo(envelope.expectedRevision()) != 0) {",
     "    if (head.get().compareTo(envelope.expectedRevision()) != 0) {"),
], "① 的「分支存在性」半边（去掉 isEmpty 短路）")

T10_M3 = ("CommandBus", [
    ("""    StateRef base = new StateRef(envelope.branch(), head.get());
    SimulationState state = stateLoader.load(base);
    return switch (handler.get().handle(state, envelope.payloadJson())) {
      case HandlerOutcome.Rejected rejected -> reject(envelope, trace, rejected.reason());
      case HandlerOutcome.Applied applied -> commitUnderLock(envelope, trace, applied.changeSet());
    };""",
     """    synchronized (commitLock) {
      StateRef base = new StateRef(envelope.branch(), head.get());
      SimulationState state = stateLoader.load(base);
      return switch (handler.get().handle(state, envelope.payloadJson())) {
        case HandlerOutcome.Rejected rejected -> reject(envelope, trace, rejected.reason());
        case HandlerOutcome.Applied applied -> commitUnderLock(envelope, trace, applied.changeSet());
      };
    }"""),
], "C17：把 handler 挪进锁内（并发名存实亡，但 R7 仍会过）")

T10_M4 = ("CommandBus", [
    ("            new RevisionId(base.revision().value() + 1),",
     "            new RevisionId(base.revision().value() + 2),"),
], "revision 号 +1 改成 +2")

MUTANTS = [
    ("t11-m1", T11_M1), ("t11-m2", T11_M2), ("t11-m2b", T11_M2B),
    ("t11-m3", T11_M3), ("t11-m4", T11_M4), ("t11-m5", T11_M5), ("t11-m6", T11_M6),
    ("t10-m1", T10_M1), ("t10-m2", T10_M2), ("t10-m2b", T10_M2B),
    ("t10-m3", T10_M3), ("t10-m4", T10_M4),
]


def main():
    orig_dir = os.path.join(EVID, "orig")
    os.makedirs(orig_dir, exist_ok=True)

    texts = {}
    for name, rel in FILES.items():
        with open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
            texts[name] = handle.read()
        # 原件快照：装置拿它当"开跑前的干净世界"参照
        with open(os.path.join(orig_dir, name + ".java"), "w", encoding="utf-8") as handle:
            handle.write(texts[name])
        print("orig  %-12s %s" % (name, rel))

    for name, (target, edits, why) in MUTANTS:
        text = texts[target]
        for old, new in edits:
            text = edit(text, old, new)
        if text == texts[target]:
            sys.exit("!! %s 与原件字节相同——这一轮不算数" % name)
        out = os.path.join(EVID, "%s.%s.java" % (name, target))
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(text)
        print("mut   %-9s -> %-11s %s" % (name, target, why))


if __name__ == "__main__":
    main()
