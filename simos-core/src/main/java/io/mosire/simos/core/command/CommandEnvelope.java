package io.mosire.simos.core.command;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import java.util.Objects;

/**
 * 领域命令过边界的形式（spec §4.1，C16）：模块的命令**只以这个形态**到达 Core。
 *
 * <p>★ {@code payloadJson} 是**不透明文本**（C26）：Core 只按 {@link #type()} 找 handler，把载荷**逐字节**转交（R11）—— 不
 * parse、不 trim、不 re-serialize。Core 手里从头到尾只有"一个 type 字符串 + 一段字节"。
 *
 * <p>★ 身份三件套（{@code commandId} / {@code correlationId} / {@code initiator}）由**调用方**在信封上给出（C21/C22），
 * 原样落进 {@code revisions} 行的三个 NOT NULL 列。C22 的缺省口径（单命令链 {@code correlationId = commandId}）由调用方
 * 自行遵守，本类**不代填**——代填会把"调用方漏填"变成静默掩盖。
 *
 * <p>★ {@code type} 必须形如 {@code <namespace>.<Command>}（如 {@code "unit.RenameUnit"}）：Core 靠它把
 * handler 的 变更集装进 {@link io.mosire.simos.core.state.WorldChangeSet}（裁定 37）。校验点不在这里、在 {@code
 * CommandRegistry} 构造期——那里是装配错误该响的地方。
 */
public record CommandEnvelope(
    String commandId,
    String correlationId,
    String initiator,
    BranchId branch,
    RevisionId expectedRevision,
    String type,
    String payloadJson)
    implements Command {

  public CommandEnvelope {
    commandId = requireText(commandId, "commandId");
    correlationId = requireText(correlationId, "correlationId");
    initiator = requireText(initiator, "initiator");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    type = requireText(type, "type");
    Objects.requireNonNull(payloadJson, "payloadJson");
  }

  /** 空串是"有值但无意义"，与 null 一样当场炸——它会让 revision 行的可读性静默劣化。 */
  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isEmpty()) {
      throw new IllegalArgumentException(name + " 不得为空串");
    }
    return value;
  }
}
