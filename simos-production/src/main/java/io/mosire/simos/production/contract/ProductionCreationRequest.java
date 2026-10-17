package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.Objects;

/**
 * ★★ <b>② 执行：新建实例请求</b>（D-032 约束设计书 §4.3「'按需进入生产/创建产业实例'：economy 在分配阶段
 * 提出<b>创建请求</b>（要采用哪个方案、在哪一格、由谁经营），production 在自身状态里创建实例并返回」）。
 *
 * <p>★★ <b>"按需新建定义"与"按需新建实例"是两件事</b>（§4.1 / 边界裁决 §3）：
 *
 * <ul>
 *   <li><b>选用已有方案</b> ⇒ <b>只建实例</b>，实例只持方案<b>稳定 ID</b>（{@link #plan()}）， <b>不复制</b>方案定义；
 *   <li><b>按需新建定义</b>走既有命令面（{@code production.UpsertIndustry}）；
 *   <li>本类型只做后者以外的那件事：把一个<b>已存在</b>的方案在某格、由某个经营者启用成一个实例。
 * </ul>
 *
 * <p>★★ <b>方案可跨地点共享，实例携带地点</b>（§4.1；复核裁决 §1）：地点自 A 批起是<b>实例自己的 显式组件</b>（{@link #location()}），不再从方案
 * id 的串里反解。⇒ 同一方案可以在多格各起一个实例， 每个实例各带自己的地点，且它们引用的是<b>同一个</b>方案稳定 id（方案基础值一改，两地实例同批按新有效 定义计算）。
 *
 * @param plan 要采用的方案稳定 ID；不得为 null（方案不存在 ⇒ 执行的具名拒绝，不创建）
 * @param unit 新实例的稳定身份；不得为 null（由调用方按 {@link ProductionUnitId#idOf} 或既有拼写点生成）
 * @param location ★ <b>新实例的地点</b>（A 批新增）；不得为 null
 * @param operator 新实例的经营者；不得为 null
 * @param modeKey 生产方式键；不得为空白
 */
public record ProductionCreationRequest(
    IndustryId plan,
    ProductionUnitId unit,
    ProductionLocation location,
    ActorRef operator,
    String modeKey) {

  // ★★ B11（DEF-T5-4）：施工期暂留的"不带地点"4 参构造器已**收口删除**。依据三条：
  //     ① 它自己的注释就写着"B 批删除"（本仓纪律：施工期暂留的旧重载必须在正式批收口 —— §13.3 第 2 条 /
  //        §13.7 第 4 条对"runtime=null 旧构造器"给的是同一条口径）；
  //     ② 语义：它把 location 传 null ⇒ 唯一的命运就是被 compact 构造器立刻拒绝（"实例必须有地点"），
  //        即**没有任何一条会成功的调用路径**；留着它只会让"地点无从落定"在编译期不可见；
  //     ③ 现场：全仓（main + 所有测试）**0 个调用点**（核验命令：
  //        `grep -rn "new ProductionCreationRequest(" --include=*.java . | grep -v /target/`）。
  //   ★ 它同时是 SpotBugs NP_NULL_PARAM_DEREF_NONVIRTUAL 的唯一来源（:44 把 null 字面量传给"非空参数"的
  //     规范构造器）；**不是**误报，而是"这条重载本就不该存在"的正确指认 —— 故处置是删除，不是压警告。
  //   ★ 删除面只在本文件：不碰任何调用点（本就没有），也不碰 src/test/**。

  public ProductionCreationRequest {
    Objects.requireNonNull(plan, "ProductionCreationRequest.plan 不得为 null");
    Objects.requireNonNull(unit, "ProductionCreationRequest.unit 不得为 null");
    Objects.requireNonNull(location, "ProductionCreationRequest.location 不得为 null（实例必须有地点）");
    Objects.requireNonNull(operator, "ProductionCreationRequest.operator 不得为 null");
    if (modeKey == null || modeKey.isBlank()) {
      throw new IllegalArgumentException("ProductionCreationRequest.modeKey 不得为空白");
    }
  }
}
