package io.mosire.simos.app.sd.channel;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.channel.ActorId;
import io.mosire.simos.sd.channel.ChannelAdmission;
import io.mosire.simos.sd.channel.DecisionChannel;
import io.mosire.simos.sd.channel.DecisionRequest;
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
    // ★★ 2026-10-12 批次 3b-2：**一条命令**。批次 3b-1 的"出令执行原文随同批第二条 ga.PutInfo 落"（+ 配套载荷
    //   生产者 DirectiveIntentInfo）已整条删除——info 覆盖层搬进 ga 后，ga.IssueDirective 自己写令 + 执行原文
    //   （同一条 revision、不可分割）。本类回到单命令提交的原形。
    CommandResult result =
        core.submit(
            new CommandEnvelope(
                commandId,
                commandId,
                initiatorOfChain,
                request.branch(),
                request.expectedRevision(),
                request.commandType(),
                request.payloadJson()));
    if (result instanceof CommandResult.Rejected rejected) {
      throw new IllegalArgumentException("决策被拒: " + rejected.reason());
    }
    if (result instanceof CommandResult.Conflict conflict) {
      throw new IllegalStateException("决策冲突，当前 head: " + conflict.current().revision().value());
    }
  }
}
