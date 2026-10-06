package io.mosire.simos.social.provisioning;

import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;

/**
 * ★★ <b>provisioning 包的具名拒绝出口</b>（AGENTS §一.9 的日志纪律：具名拒绝必须有日志）。
 *
 * <p>包内所有"坏数据当场抛"的路径都从这里出去：先按 ERROR 记一条带原因的事件 （{@code event=SOCIAL_PROVISIONING_REJECTED}），再把同一句话包成
 * {@link IllegalArgumentException} 抛出。 之所以抽成一个包内单点，是为了让拒绝的日志级别与异常类型**只有一处拼写**——散在各构造器里就迟早会有
 * 一条"抛了但没记"或"记的级别不一致"。
 *
 * <p>★ 日志纪律：只记稳定 id / 字段名 / 原因档，不记任何密钥或载荷明文；日志失败不影响构造/查询（SLF4J 自身 的异常不会反向污染调用栈）。
 */
final class ProvisioningReject {

  private ProvisioningReject() {}

  /**
   * 记 ERROR 并返回待抛的具名异常。
   *
   * <p>调用形态固定为 {@code throw ProvisioningReject.reject("...")}：异常对象由这里创建，避免各调用点 自己拼异常类型与日志级别。
   *
   * @param message 具名原因（同一句话既进日志也进异常消息）
   */
  static IllegalArgumentException reject(String message) {
    EventLog.channel(SocialLog.provisioning())
        .error(
            LogEvent.of(
                "SOCIAL_PROVISIONING_REJECTED",
                SocialLogSource.SOCIAL_PROVISIONING,
                "reason",
                message));
    return new IllegalArgumentException(message);
  }
}
