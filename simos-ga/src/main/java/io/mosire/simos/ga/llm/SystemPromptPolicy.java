package io.mosire.simos.ga.llm;

import java.util.Objects;

/**
 * **单空格 system 硬约束**（用户 2026-10-17，逐字：「我这边的要求是，系统上下文应该直接传一个空格，因为多种证据表面过长的系统上下文会导致模型性能
 * 衰减，加上现在正在测试，所以应当硬要求只传一个空格」）。
 *
 * <p>★ 执行要求：本项目发往 LLM 的 system 消息内容**只能是一个 ASCII 空格**（U+0020）。身份、规则、简报、schema、命令白名单等**全部**走 user
 * 消息（既有顺序不变）。「长系统提示导致性能衰减」是用户给的实验背景，本类不把它扩写成适用于所有模型的通用结论。
 *
 * <p>★ <b>唯一生产来源</b>：本常量是两条生产路径（决策人运行流 / 裁决器）与裁决请求边界校验的**共同来源**；别处不得再写 {@code " "} 字面量。
 * 空串、多空格、换行、任意长文本一律**响亮失败**——且拒绝消息**不回显提示词内容**（那里面可能有脱敏简报）。
 *
 * <p>★ 与批 R 的宿主内部 {@code ToolContext} / {@code AgentPermissionSet} 无关：本类只管"发给模型的 system 内容"。
 */
public final class SystemPromptPolicy {

  /** 唯一合法值：一个 ASCII 空格（U+0020）。 */
  public static final String SINGLE_SPACE = " ";

  private SystemPromptPolicy() {}

  /**
   * 请求边界校验：只接受 {@link #SINGLE_SPACE}。
   *
   * @param systemPrompt 待校验的 system 内容（可为 null）
   * @param where 拒绝消息里的落点（例如「裁决请求」；调用方不得把提示词内容拼进来）
   * @return 合法时返回 {@link #SINGLE_SPACE}
   * @throws IllegalArgumentException 非单空格；消息只带"落点 + 非法值形状"，**不回显内容**
   */
  public static String requireSingleSpace(String systemPrompt, String where) {
    Objects.requireNonNull(where, "where");
    if (SINGLE_SPACE.equals(systemPrompt)) {
      return SINGLE_SPACE;
    }
    String shape;
    if (systemPrompt == null) {
      shape = "null";
    } else if (systemPrompt.isEmpty()) {
      shape = "空串";
    } else if (systemPrompt.length() == 1) {
      shape = "单个非空格字符";
    } else {
      shape = "长度 " + systemPrompt.length() + " 的文本";
    }
    throw new IllegalArgumentException(
        where + "：system 消息内容只允许单个 ASCII 空格（U+0020），收到 " + shape + "（内容不回显）");
  }
}
