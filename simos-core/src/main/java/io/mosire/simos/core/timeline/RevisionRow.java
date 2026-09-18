package io.mosire.simos.core.timeline;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code revisions} 表一行的内存形态（spec §3.2 的 schema 镜像，C12）：列名/表名的共享常量与行映射归 {@link Timeline}， 本 record
 * 只管 「一行是什么」。DDL 由 {@code SqliteStore}（Task 5）在打开时建——本类型**不是**第二份 schema 来源， 它镜像的是同一份冻结 schema
 * 的读取结果。
 *
 * <p>★ {@code parent} 是 {@code Optional<StateRef>}：创世行 {@code (main, 1)} 的 {@code parent_branch} /
 * {@code parent_revision} **均为 NULL**（spec §3.2），映射成 {@link Optional#empty()}；两列**只有一列**为 NULL
 * 是库被绕过本类写坏的 信号， 行映射处显式炸掉（spec §3.2 的复合外键对「任一列为 NULL」的行不生效，SQL 标准如此，防不住绕行者）。
 *
 * <p>★ {@code changesetJson} 是**已序列化的文本**（谁序列化、按什么配置：见 {@link Timeline#changeSetJson}）——本类型原样携带，
 * 不解析、不校验它的内容。
 *
 * <p>★ {@code commandId} / {@code correlationId} / {@code initiator} / {@code commandType} 对应 spec
 * §3.2 的四个 NOT NULL 文本列（形态与来源见 C21 / C22）：它们由调用方（命令层）给出，本类型只携带。
 */
public record RevisionRow(
    BranchId branch,
    RevisionId revision,
    Optional<StateRef> parent,
    SimosTimestamp timestamp,
    String commandId,
    String correlationId,
    String initiator,
    String commandType,
    String changesetJson) {

  public RevisionRow {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(commandType, "commandType");
    Objects.requireNonNull(changesetJson, "changesetJson");
  }
}
