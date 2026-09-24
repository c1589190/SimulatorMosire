package io.mosire.simos.app.llm;

import io.mosire.agentlib.llm.LlmClient;
import java.util.Objects;

/**
 * 一条 provider 的**客户端 + 能力**（P4，2026-09-24）：同一份路由配置的两个投影，**必须一起取**。
 *
 * <p>★★ **为什么捆成一个值、而不是两处各查一次**：决策人链路要同时知道"往哪发"（{@link LlmClient}）与"发不发图"（ {@code
 * vision}）。两份各查一次（一处建客户端、另一处读能力）就会漂移：改了路由的 {@code capabilities.vision} 而漏改另一处， 症状是"图附着发出去、供应商
 * 400"，或者反过来"有视觉能力的模型一直收不到图"——**两种都不会报装配错**。 同一个值里带出来，就不存在"只改了一处"的形态。
 *
 * <p>★ {@code vision} 的缺省是 **false**（AgentLib 的 {@code ModelCapabilities.defaults()} 全关）：路由没写
 * {@code capabilities.vision=true} 就是没有视觉能力。这个方向的失效是安全的——不附图，模型仍拿到工具结果的**文本摘要** （`simos.map.render`
 * 的字符图回落见 {@code format=text}），而不是发一张它读不懂的图。
 *
 * @param client 该 provider 的 AgentLib 客户端（已带工件解析器：消息里的图片分片能在发送那一刻取到字节）
 * @param vision 该 provider 的模型有没有视觉能力（路由配置 {@code llm.routes.<name>.capabilities.vision}）
 */
public record ProviderLlm(LlmClient client, boolean vision) {

  public ProviderLlm {
    Objects.requireNonNull(client, "client");
  }
}
