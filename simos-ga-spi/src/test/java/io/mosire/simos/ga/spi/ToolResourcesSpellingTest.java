package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourcePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 A（{@code simos-ga-spi} 测试面）：{@code ToolResources} 是"命名空间 / 资源路径 / 声明策略"的唯一拼写点 ——
 * 断言<b>实际值</b>与<b>路径形状</b></b>。
 *
 * <h2>它守的是哪种坏法</h2>
 *
 * <p>{@code ToolResources} 的类注把理由写死了：这些字符串是<b>权限围栏的输入</b>（{@code ResourceScope} 的段边界前缀匹配）， 拼错一段（多一条
 * {@code /}、少一个段、把 {@code _} 写成 {@code /}、命名空间写成旧名 {@code sd}）<b>不会抛任何异常</b> ——只会让"该看见的看不见 /
 * 不该看见的看得见"。⇒ 唯一能守它的形态就是把<b>实际取值</b>钉成断言。
 *
 * <p>★ <b>第二条坏法是"第二处会漂的声明"</b>：{@code MAP_READ} / {@code ALL_READ} / {@code ALL_WRITE} / {@code
 * PEER_WRITE} 是"命名空间 → 策略"的第二处声明，与命名空间常量、与调用点的资源断言都可能各自走样（且无症状）⇒ 本类逐条断言 策略表（尤其 {@code actor} 那一维的
 * {@code DENY} 与 {@code peer} 那一维的 {@code DENY}，见 §判据 B 的用例）。
 *
 * <p>★★ <b>负向（判据 A 明写的那一条）</b>：{@code ga} 域 2026-10-14 由 {@code sd} 改名 ⇒ 本类同时钉住 "实际值是 {@code ga}" 与
 * "<b>旧名 {@code sd} 一律不产出</b>"。
 */
class ToolResourcesSpellingTest {

  /** 全部命名空间常量（顺序 = {@code ToolResources} 的声明序）。 */
  private static final List<String> NAMESPACES =
      List.of(
          ToolResources.MAP_NAMESPACE,
          ToolResources.SOCIAL_NAMESPACE,
          ToolResources.UNIT_NAMESPACE,
          ToolResources.GA_NAMESPACE,
          ToolResources.ECONOMY_NAMESPACE,
          ToolResources.ACTOR_NAMESPACE,
          ToolResources.ARMY_NAMESPACE,
          ToolResources.PEER_NAMESPACE);

  /**
   * ★ <b>判据 A：八个命名空间的<b>实际值</b>逐字钉死，且两两不同</b>。
   *
   * <p>守的坏法：命名空间是<b>权限表态的键</b>（GM 组按命名空间逐条表态，见 {@code Shell#gmPermissionSet}）。把两个常量写成同一个串
   * （复制粘贴）或改掉一个串，两侧（工具声明 vs 调用者可达面）会落在<b>不同的键</b>上 ⇒ 该通的通不了、该拒的可能通，<b>没有任何一层报错</b>。
   */
  @Test
  void namespaceConstantsCarryTheirExactSpellingsAndArePairwiseDistinct() {
    assertThat(ToolResources.MAP_NAMESPACE).isEqualTo("map");
    assertThat(ToolResources.SOCIAL_NAMESPACE).isEqualTo("social");
    assertThat(ToolResources.UNIT_NAMESPACE).isEqualTo("unit");
    assertThat(ToolResources.GA_NAMESPACE).isEqualTo("ga");
    assertThat(ToolResources.ECONOMY_NAMESPACE).isEqualTo("economy");
    assertThat(ToolResources.ACTOR_NAMESPACE).isEqualTo("actor");
    assertThat(ToolResources.ARMY_NAMESPACE).isEqualTo("army");
    assertThat(ToolResources.PEER_NAMESPACE).isEqualTo("peer");
    assertThat(NAMESPACES).as("★★ 两个命名空间常量撞成同一个串 = 两侧表态落在不同键上，而没有任何一层报错").doesNotHaveDuplicates();
  }

  /**
   * ★★ <b>判据 A 的负向：{@code GA_NAMESPACE} 是 {@code "ga"}，旧名 {@code "sd"} 一律不产出</b> （2026-10-14 批次 F
   * 的用户裁定：「但凡和被取缔的 sd 模块有关的就改」）。
   *
   * <p>守的坏法：改名只做了一半（声明改了、某个助手还在产 {@code sd:…}）⇒ 权限围栏按新名表态、资源按旧名断言 = <b>GM 面静默回落成"未表态"分支</b>（{@code
   * ResourceAuthorizer} 对未声明命名空间一律拒），表现为"这些工具突然全都够不着"， 而错误现场只是一条平平无奇的拒。
   */
  @Test
  void theGaNamespaceIsGaAndTheRetiredSdNameProducesNothing() {
    assertThat(ToolResources.GA_NAMESPACE)
        .as("★★ 判据 A：ga 域的命名空间是 ga（与地址族 ga: 同名、一处一致）")
        .isEqualTo("ga")
        .isNotEqualTo("sd");
    assertThat(NAMESPACES).as("★★ 判据 A 负向：任何一个命名空间常量都不得再是旧名 sd").doesNotContain("sd");
  }

  /**
   * ★★ <b>判据 A：{@code GM_ID} 是 {@code "gm"}——全仓唯一拼写点，且它<b>不是</b>命名空间</b>。
   *
   * <p>守的坏法（两条）：① 它不是决策人 id（GM 不参与决策包/裁决）⇒ 把它混进命名空间表会让"按命名空间表态"的循环多出一个 永远够不着的键；② 它同时出现在信件地址段、信件的
   * {@code tags}、{@code peer:gm} 前缀表三处（全部引用本常量）⇒ 改一个字节 会让"GM 收件人"这一段地址与判据对不上。
   */
  @Test
  void theGmIdIsTheExactLiteralGmAndItIsNotANamespace() {
    assertThat(ToolResources.GM_ID)
        .as("★★ GM 的稳定身份 id = \"gm\"（信件地址段 / tags / peer:gm 三处同源）")
        .isEqualTo("gm");
    assertThat(NAMESPACES)
        .as("★ GM_ID 不是命名空间：它是地址段与 tags 的字符串，不是资源命名空间")
        .doesNotContain(ToolResources.GM_ID);
    assertThat(ToolResources.DEFAULT_BRANCH).as("★ 缺省分支 = main（GUI 同款）").isEqualTo("main");
  }

  /**
   * ★★ <b>判据 A：路径形状逐字钉死（段边界 + 分隔符 + 无子路径）</b>，并对旧名做负向。
   *
   * <p>守的坏法：{@code ResourceScope} 是<b>段边界</b>前缀匹配 ⇒
   *
   * <ul>
   *   <li>坐标分隔符写成 {@code /}（{@code Map1/hex/1/2}）把格切成两段 ⇒ 前缀 {@code Map1/hex/1} 会<b>顺带</b>放行 {@code
   *       1/2} 之类的邻格；写成 {@code ,} 则所有逐格围栏全部对不上；
   *   <li>{@code peer} / {@code unit} 资源多带一段命名空间（{@code peer:peer:alice}）⇒ 前缀表里的 {@code alice}
   *       永远匹配不上；
   *   <li>命名空间前缀写成旧名 {@code sd:…} ⇒ 见上一条用例。
   * </ul>
   */
  @Test
  void thePathShapesAreExactSegmentBoundaryStrings() {
    assertThat(ToolResources.resourceRegion("Map1", "701").fullId())
        .as("区域：map:<mapId>/region/<regionId>")
        .isEqualTo("map:Map1/region/701");
    assertThat(ToolResources.resourceHex("Map1", 1, 2).fullId())
        .as("单格：map:<mapId>/hex/<q>_<r>（坐标分隔符是下划线）")
        .isEqualTo("map:Map1/hex/1_2");
    assertThat(ToolResources.resourceHex("Map1", 1, 2).path())
        .as("★★ 坐标分隔符必须是下划线：写成 `1/2` 会被段边界匹配切成两段 ⇒ 逐格围栏静默失效")
        .isEqualTo("Map1/hex/1_2")
        .doesNotContain("1/2");
    assertThat(ToolResources.resourceHex("Map1", -3, -4).fullId())
        .as("★ 负坐标原样带（真档的 hex 大量落在负格）")
        .isEqualTo("map:Map1/hex/-3_-4");
    assertThat(ToolResources.resourceUnit("u-1").fullId())
        .as("单位：unit:<unitId>（无子路径——单位本身就是资源）")
        .isEqualTo("unit:u-1");
    assertThat(ToolResources.resourceSocial(3, 4).fullId())
        .as("人口：social:<q>_<r>（不带 mapId——人口按格取，地图只有一张）")
        .isEqualTo("social:3_4");
    assertThat(ToolResources.resourceSocial(3, 4).path())
        .as("★ 人口路径里不得再带 mapId（多一段 = 与范围函数派生的那组格对不上）")
        .isEqualTo("3_4");
    assertThat(ToolResources.resourceGa("nation", "n-7").fullId())
        .as("ga：ga:<kind>/<id>")
        .isEqualTo("ga:nation/n-7");
    assertThat(ToolResources.resourcePeer("alice").fullId())
        .as("peer：peer:<决策人id>")
        .isEqualTo("peer:alice");
    assertThat(ToolResources.resourcePeer("alice").path())
        .as("★★ peer 无子路径：路径里再带一次命名空间 ⇒ fullId 变成 peer:peer:alice，前缀表永远匹配不上")
        .isEqualTo("alice");
  }

  /**
   * ★ <b>判据 A：六个资源助手产出的 id，其命名空间分量必须<b>就等于</b>那几个常量（不是各写一遍字面量）</b>。
   *
   * <p>守的坏法：{@code ResourceId.of("ga", …)} 这种"顺手写的字面量"在文件里看起来一模一样，改名时它不会跟着动 ⇒ 常量与调用点各自成立、互不相等。
   */
  @Test
  void everyProducedResourceIdCarriesOneOfTheNamespaceConstants() {
    List<ResourceId> produced =
        List.of(
            ToolResources.resourceRegion("Map1", "701"),
            ToolResources.resourceHex("Map1", 1, 2),
            ToolResources.resourceHex("Map1", -3, -4),
            ToolResources.resourceUnit("u-1"),
            ToolResources.resourceSocial(3, 4),
            ToolResources.resourceGa("nation", "n-7"),
            ToolResources.resourcePeer("alice"));
    assertThat(produced)
        .allSatisfy(
            id ->
                assertThat(NAMESPACES)
                    .as("★★ %s 的命名空间分量必须是命名空间常量之一（手写字面量 = 改名时不会跟着动）", id.fullId())
                    .contains(id.namespace()));
    assertThat(produced)
        .extracting(ResourceId::fullId)
        .as("★★ 判据 A 负向：旧名 sd 一律不产出")
        .allSatisfy(fullId -> assertThat(fullId).doesNotStartWith("sd:"));
  }

  /**
   * ★ <b>判据 A：{@code allWriteResources} 的三个粗断言逐字</b>（{@code simos.command.submit} / {@code
   * simos.advance} 与窄写 基类的缺省声明都吃它）。
   *
   * <p>守的坏法：这三个串是"通用写"的<b>唯一</b>表态面——少一条命名空间 ⇒ 那一维的写工具在受限调用者身上变成"够不着" （或反过来：把 {@code *} 换成具体 id ⇒
   * 粗断言不再粗，{@code requireAllWrite} 的语义整体改变）。
   */
  @Test
  void theWriteAssertionNamesTheThreeNamespacesWithTheBareMapId() {
    assertThat(ToolResources.allWriteResources("Map1"))
        .extracting(ResourceId::fullId)
        .as("通用写断言 = map:<mapId> + unit:* + social:*（三条缺一不可）")
        .containsExactlyInAnyOrder("map:Map1", "unit:*", "social:*");
  }

  /**
   * ★★ <b>判据 A + B：四张声明清单的"命名空间 → 策略"表逐条钉死（第二处声明的守门）</b>。
   *
   * <p>守的坏法（每条都实测过，注释写在 {@code ToolResources} 的常量注里）：
   *
   * <ul>
   *   <li>{@code ALL_READ} 里的 {@code actor} 一维若<b>不声明</b> ⇒ {@code ResourceAuthorizer} 对 {@code
   *       actor:…} 一律判否 ⇒ 工具里那条 actor 分支<b>恒为假</b>（实测：只加判定分支、不加声明，{@code simos.state.resolve} 对
   *       {@code actor:Map1} 仍是空候选）；
   *   <li>若照 map/social/unit 抄成 {@code READ_ONLY} ⇒ <b>静默放宽</b>（未表态的调用者整片放行 actor 面，且声明式前置闸
   *       会永远通过）；⇒ 必须是 {@code DENY}；
   *   <li>{@code PEER_WRITE} 若取 {@code UNRESTRICTED} ⇒ <b>未授权 DM 也能给任何人发信</b>（I2 整条失效，2026-10-14
   *       实测的 fail-open 陷阱）。
   * </ul>
   */
  @Test
  void theDeclaredManifestsMatchTheNamespaceConstantsAndTheFailClosedPolicies() {
    assertThat(ToolResources.MAP_READ.byNamespace())
        .containsExactly(entry(ToolResources.MAP_NAMESPACE, ResourcePolicy.READ_ONLY));
    assertThat(ToolResources.UNIT_READ.byNamespace())
        .containsExactly(entry(ToolResources.UNIT_NAMESPACE, ResourcePolicy.READ_ONLY));
    assertThat(ToolResources.SOCIAL_READ.byNamespace())
        .containsExactly(entry(ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY));

    assertThat(ToolResources.ALL_READ.namespaces())
        .as("★★ 读声明必须含 actor 一维（缺它 ⇒ actor 分支恒为假，且症状只是「空候选」）")
        .containsExactlyInAnyOrder(
            ToolResources.MAP_NAMESPACE,
            ToolResources.SOCIAL_NAMESPACE,
            ToolResources.UNIT_NAMESPACE,
            ToolResources.ACTOR_NAMESPACE);
    assertThat(ToolResources.ALL_READ.policyFor(ToolResources.MAP_NAMESPACE))
        .isEqualTo(ResourcePolicy.READ_ONLY);
    assertThat(ToolResources.ALL_READ.policyFor(ToolResources.SOCIAL_NAMESPACE))
        .isEqualTo(ResourcePolicy.READ_ONLY);
    assertThat(ToolResources.ALL_READ.policyFor(ToolResources.UNIT_NAMESPACE))
        .isEqualTo(ResourcePolicy.READ_ONLY);
    assertThat(ToolResources.ALL_READ.policyFor(ToolResources.ACTOR_NAMESPACE))
        .as("★★ actor 一维必须是 DENY：抄成 READ_ONLY 就是对未表态的调用者整片放行（静默放宽）")
        .isEqualTo(ResourcePolicy.DENY);

    assertThat(ToolResources.ALL_WRITE.byNamespace())
        .as("写声明 = 三命名空间 UNRESTRICTED（spec §7.1）")
        .containsOnly(
            entry(ToolResources.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED),
            entry(ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED),
            entry(ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED));

    assertThat(ToolResources.PEER_WRITE.byNamespace())
        .as("★★ peer 声明的策略必须是 DENY：取 UNRESTRICTED ⇒ 未授权的 DM 也能给任何人发信（fail-open 陷阱）")
        .containsExactly(entry(ToolResources.PEER_NAMESPACE, ResourcePolicy.DENY));
  }
}
