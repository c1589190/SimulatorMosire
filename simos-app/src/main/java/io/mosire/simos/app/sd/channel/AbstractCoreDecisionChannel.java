package io.mosire.simos.app.sd.channel;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.spi.IssueDirectiveHandler;
import io.mosire.simos.sd.channel.ActorId;
import io.mosire.simos.sd.channel.ChannelAdmission;
import io.mosire.simos.sd.channel.DecisionChannel;
import io.mosire.simos.sd.channel.DecisionRequest;
import io.mosire.simos.sd.spi.DirectiveIntentInfo;
import io.mosire.simos.sd.spi.PutInfoHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * app 侧渠道共用的落点适配（spec §十三.3，D5）：把渠道输入经 {@link CoreSimos#submit} 落成真 revision。
 *
 * <p>★ **渠道只是适配器**：不预设"立刻生效"、不绕过配额、不放大权限——四条安全边界（身份 / 视图 / 同事务 / 留痕）中，身份与落点由 {@link
 * ChannelAdmission} 在模块侧判，视图由 sd 构造（{@link ChannelAdmission#redactedBrief}），留痕落进 revision 行的 {@code
 * initiator}（{@code channelId:actor}）。
 *
 * <p>★ **新增渠道不改领域代码**：只要实现 {@link DecisionChannel} 并在装配处加一行。
 */
abstract class AbstractCoreDecisionChannel implements DecisionChannel {

  private final CoreSimos core;
  private final Supplier<Set<ActorId>> actors;
  private final String initiator;

  AbstractCoreDecisionChannel(CoreSimos core, Supplier<Set<ActorId>> actors, String initiator) {
    this.core = core;
    this.actors = actors;
    this.initiator = initiator;
  }

  @Override
  public final Set<ActorId> representableActors() {
    return actors.get();
  }

  @Override
  public final void submit(ActorId actor, DecisionRequest request) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(request, "request");
    ChannelAdmission.requireRepresentable(representableActors(), actor);
    ChannelAdmission.requireLandingPoint(request.commandType());

    String commandId = UUID.randomUUID().toString();
    String initiatorOfChain = initiator + ":" + actor.value();
    List<CommandEnvelope> batch = new ArrayList<>(2);
    batch.add(
        new CommandEnvelope(
            commandId,
            commandId,
            initiatorOfChain,
            request.branch(),
            request.expectedRevision(),
            request.commandType(),
            request.payloadJson()));
    // ★★ 2026-10-12 批次 3b′：出令的**执行原文**条目与令**同批**落（同一条 revision）。它必须是一条独立命令——
    //   一条命令 = 一个命名空间（CommandBus.namespaceOf 取类型前缀），而令住 ga、INFO 覆盖层仍住 sd。
    //   载荷由 DirectiveIntentInfo 从同一份出令载荷派生（地址/键/来源引用的唯一拼写点在那里）；派生不出就不追加，
    //   让主命令自己给具名拒因（不在这里改判据）。
    if (IssueDirectiveHandler.TYPE.equals(request.commandType())) {
      DirectiveIntentInfo.putInfoPayloadJson(request.payloadJson())
          .ifPresent(
              putInfoPayload ->
                  batch.add(
                      new CommandEnvelope(
                          UUID.randomUUID().toString(),
                          commandId,
                          initiatorOfChain,
                          request.branch(),
                          request.expectedRevision(),
                          PutInfoHandler.TYPE,
                          putInfoPayload)));
    }
    if (batch.size() == 1) {
      CommandResult result = core.submit(batch.get(0));
      if (result instanceof CommandResult.Rejected rejected) {
        throw new IllegalArgumentException("决策被拒: " + rejected.reason());
      }
      if (result instanceof CommandResult.Conflict conflict) {
        throw new IllegalStateException("决策冲突，当前 head: " + conflict.current().revision().value());
      }
      return;
    }
    BatchResult result = core.submitBatch(batch);
    if (result instanceof BatchResult.Rejected rejected) {
      throw new IllegalArgumentException("决策被拒: " + rejectionReason(rejected.outcomes()));
    }
    if (result instanceof BatchResult.Conflict conflict) {
      throw new IllegalStateException("决策冲突，当前 head: " + conflict.current().revision().value());
    }
  }

  /** 整批被拒时取**主命令**（批内第 0 条）的拒因——与单命令提交的拒因逐字同源。 */
  private static String rejectionReason(List<CommandOutcome> outcomes) {
    for (CommandOutcome outcome : outcomes) {
      if (outcome.result()
          instanceof io.mosire.simos.core.command.CommandResult.Rejected rejected) {
        return rejected.reason();
      }
    }
    return "整批未提交（无逐条拒因）";
  }
}
