package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolClass;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.tools.ToolClassAudit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>工具三分类的**真装配**锚点</b>（2026-10-15 批 3a 的测试面；设计书 {@code
 * docs/superpowers/plans/2026-10-15-tool-three-categories.md} §2.1 / §3.1 / §3.2 / §6 不变量 C1–C3）。
 *
 * <p>★ <b>为什么必须有这一条</b>：批 3a 的三格计数（140 / 17 / 37）是用**临时 println 探针**读出来的，探针已逐字删除 ⇒
 * <b>最终版上读不到计数</b>，且当时**没有任何测试守着它**。本类把那三个数变成可执行的回归锚点，形制照 {@code ShellAssemblySmokeTest}
 * 的两条桶锚点（<b>常量 + hasSize + "增删一条都要显式改本行并说明为什么"</b>）。
 *
 * <p>★★ <b>自洽硬证据</b>（这就是"计数对"的理由，而不是"跑出来是多少就是多少"）：
 *
 * <pre>
 * GM 桶     = 类1 133 + 共享 46 = 179 = ShellAssemblySmokeTest.GM_BUCKET_SIZE
 * 决策人桶  = 类2  24 + 类3 37 =  61 = ShellAssemblySmokeTest.DECISION_BUCKET_SIZE
 * </pre>
 *
 * <p>★★ <b>2026-10-15 用户裁定「甲」后的新口径</b>（本类的前四个常量随之改）：{@code RaiseUnit} · {@code LevyRegion} · {@code
 * GovRecruit} · {@code GovRetireStaff} · {@code SocialHouseholdMembers} · {@code
 * GovSelectExaminees} · {@code GovDispatchTeam} 这 <b>7 条类1</b> 当年的 <b>DM
 * 标签是漏打的</b>（它们本来就是决策人的核心动作：造兵 / 征税 / 招吏 / 辞退 / 选人 / 派队 / 家户成员直调）⇒ 补 {@code
 * roles={DM,GM}}；它们<b>本来就是 {@code Ask}</b> ⇒ 自动成类2 {@code DM_APPROVAL}。⇒ 7 条同时离开类1（{@code 140 →
 * 133}）、进入类2（{@code 17 → 24}）与共享（{@code 39 → 46}）； 类3 与两桶并集、GM 桶<b>一格未动</b>（{@code 37 / 194 /
 * 179}）。★ GM 桶与并集的"不变"不是巧合而是算术自证：GM 桶 = 类1 + 共享 （{@code 133 + 46 = 179 = 140 + 39}），并集 =
 * 三格之和（{@code 133 + 24 + 37 = 194 = 140 + 17 + 37}）。
 *
 * <p>★ <b>本类怎么读分区</b>：走**真装配**（{@link Shell#start(ShellConfig)}）拿两桶，再调 {@link
 * ToolClassAudit#requireClasses} 取 {@code Partition} —— 与 {@code Shell:1398}
 * 装配期那次调用**同一份入参**（{@code toolsFor(GM)} == {@code gmToolFace} = {@code listTools() +
 * stopProtocolGmTools}）。
 *
 * <p>★ <b>本类不碰生产代码</b>：只读 {@code ToolClassAudit} 的公开返回面 + 扫它的源码（C3）。
 */
class ToolClassAuditAssemblyTest {

  // ────────────────────────── ★★ 三格条数锚点（增删一条都要显式改本行并说明为什么） ──────────────────────────

  /**
   * ★★ <b>类1 {@code GM_ONLY} = 133</b>（2026-10-15 用户裁定「甲」后真装配实测；= 129 条内部 {@code {GM}} 真工具 + 4 条外来
   * {@code {GM}}：{@code doc_void} / {@code mount_workspace} / {@code unmount_workspace} / {@code
   * list_workspaces}）。
   *
   * <p>★ <b>它不是"按文件 grep 数出来的"</b>：控制方原稿的 137/15/32 是文件级 {@code git grep} 的**系统性低估**
   * （看不见逐条表声明角色的外来工具、看不见具名常量写的 {@code roles()}、还把自己数成一条工具）—— 本类钉的是**真装配的分区**，理由见类注的自洽硬证据。
   *
   * <p>★★ <b>谁要改这个数</b>：工具面增删一条、或某条工具的 {@code roles()}/{@code gate()} 改了类归属 ⇒ 这里当场红。
   * 那时应当<b>显式</b>改本行并在提交说明里写清"哪一条、为什么换格"，<b>不是</b>把断言删掉/放宽。
   *
   * <p>★★ <b>本次变化（{@code 133 → 131}）来自批 L ②「两对外交工具合并」</b>（设计书 §2.1 裁定 N2「乙 + 合并甲」）：原来 <b>GM
   * 面独占</b>的两条（{@code simos.sd.record-diplomatic-event} / {@code
   * simos.sd.set-diplomatic-relation}）与 <b>DM 面</b>那两条（{@code gov.RecordDiplomaticEvent} / {@code
   * gov.SetDiplomaticRelation}）**合并成一条**、{@code roles={DM,GM}} ⇒ 这两条 GM-only 不再存在 ⇒ 类1 减 2。★
   * 实测读数是「Expected size: 133 but was: 131」；★ 与 {@link #SHARED_SIZE} 的联立是 131 + 48 = 179（GM
   * 桶**不动**，见 {@link #GM_BUCKET_SIZE}）。
   */
  private static final int GM_ONLY_SIZE = 131;

  /**
   * ★★ <b>类2 {@code DM_APPROVAL} = 24</b>（具名清单见 {@link #DM_APPROVAL_NAMES}）。★ 17 → 24 的全部增量 =
   * 裁定「甲」的那 7 条。
   */
  private static final int DM_APPROVAL_SIZE = 24;

  /** ★★ <b>类3 {@code DM_FREE} = 37</b>（★ 裁定「甲」<b>未动</b>本格：那 7 条本来就是 {@code Ask} ⇒ 落类2，不落类3）。 */
  private static final int DM_FREE_SIZE = 37;

  /**
   * ★ <b>共享 = 46</b>：既在 GM 桶、又在决策人桶的工具（内部 38 + 外来 8，{@code roles()} 含 {@code {GM, DM}} 两者）。
   *
   * <p>★ 裁定「甲」把 7 条从「只在 GM 桶」搬进「两桶共享」⇒ {@code 39 → 46}。★ 它与 {@link #GM_ONLY_SIZE} 的算术是
   * <b>双向钉死</b>的：GM 桶 = 类1 + 共享（{@code 133 + 46 = 179}）——只改一个数就会在下面的自洽断言里红。
   *
   * <p>★★ <b>本次变化（{@code 46 → 48}）来自批 L ②「两对外交工具合并」</b>：两条合并出来的工具 {@code roles={DM,GM}} ⇒ 它们**既在 GM
   * 桶、又含 DM** ⇒ 共享 +2。★ 与被减 2 的 {@link #GM_ONLY_SIZE} 联立后 GM 桶**仍是 179**（{@code 131 + 48 = 179}）——
   * 这正是"两个桶锚点不动、只有格在搬家"的算术。
   */
  private static final int SHARED_SIZE = 48;

  /**
   * ★ <b>两桶并集 = 192 = 131 + 24 + 37</b>（分区不重不漏的算术自证）。★★ 批 L ②：{@code 194 → 192}（合并把两条 GM-only 与两条
   * DM-only 折成两条、并集净 −2）。
   */
  private static final int TOOL_UNION_SIZE = 192;

  /**
   * ★ 与 {@code ShellAssemblySmokeTest.GM_BUCKET_SIZE} 逐字相同（那边是 {@code private} ⇒ 这里逐字重复而不是反射取）。 ★
   * 两处**同时**红才说明"分区搬了家"：本类下面的断言把"桶条数"与"三格算术"钉在一起。★ 裁定「甲」<b>未动</b>本数。
   */
  private static final int GM_BUCKET_SIZE = 179;

  /**
   * ★ 与 {@code ShellAssemblySmokeTest.DECISION_BUCKET_SIZE} 逐字相同。★ 裁定「甲」：{@code 54 → 61}（= 类2 24 +
   * 类3 37）。
   */
  private static final int DECISION_BUCKET_SIZE = 61;

  // ────────────────────────── ★★ 类2 具名清单（A2/A3） ──────────────────────────

  /**
   * ★★ <b>类2 {@code DM_APPROVAL} 的**全部** 24 条工具名</b>（2026-10-15 用户裁定「甲」后真装配实测，逐条核过）。
   *
   * <p>★ 组成（设计书 §2.1 + §4 裁定甲）：{@code ga.IssueDirective} · {@code ga.SubmitVerdict} · 9 条 {@code
   * simos.gov.*} 类2世界写 · 3 条外来 doc 写（{@code doc_append} / {@code doc_create} / {@code doc_edit}）·
   * {@code gov.SetDiplomaticRelation} · {@code gov.RecordDiplomaticEvent} · {@code simos.gov.pay} ·
   * ★★ <b>裁定「甲」补进来的 7 条</b>（原 7 条类1：{@code simos.unit.raiseUnit} / {@code simos.unit.levyRegion} /
   * {@code simos.gov.recruit} / {@code simos.gov.retireStaff} / {@code simos.gov.selectExaminees} /
   * {@code simos.gov.dispatchTeam} / {@code simos.social.household.members}）。
   *
   * <p>★★ <b>最后三条是"四格收敛成三格"的关键结论</b>（{@link #PURE_DM_APPROVAL_NAMES}）：它们是纯 {@code {DM}} + {@code
   * Ask}（DM 能用、但要问人）⇒ 按 §3.1 的口径**自动**落进类2，不需要为它们单列第四格、也不需要改它们的任何声明。
   *
   * <p>★★ <b>只数条数会漏掉"成员替换"</b>（本仓为这种假绿付过学费）⇒ 本清单是**具名**的，逐条相等断言。
   */
  private static final List<String> DM_APPROVAL_NAMES =
      List.of(
          "ga.IssueDirective",
          "ga.SubmitVerdict",
          "simos.gov.setEstablishment",
          "simos.gov.setBudgetPolicy",
          "simos.gov.assignPosts",
          "simos.gov.expandHousehold",
          "simos.gov.openPostsToMarket",
          "simos.gov.defineCurrency",
          "simos.gov.renameCurrency",
          "simos.gov.issueMoney",
          "simos.gov.setFxRate",
          // ★★ 2026-10-15 用户裁定「甲」的 7 条（漏打 DM 标签的类1 ⇒ 补 {DM,GM} ⇒ 本来就是 Ask ⇒ 自动落类2）。
          //   ★ 逐条即真装配读数（`classTwoRosterIsExactlyTheNamedTools` 的失败输出里那 7 条"unexpected elements"），
          //     不是照抄设计书的清单：造兵 / 征税 / 招吏 / 辞退 / 选人 / 派队 / 家户成员直调。
          "simos.unit.raiseUnit",
          "simos.gov.recruit",
          "simos.gov.retireStaff",
          "simos.social.household.members",
          "simos.unit.levyRegion",
          "simos.gov.selectExaminees",
          "simos.gov.dispatchTeam",
          "doc_append",
          "doc_create",
          "doc_edit",
          "simos.gov.setDiplomaticRelation",
          "simos.gov.recordDiplomaticEvent",
          "simos.gov.pay");

  /**
   * ★ A3 点名的**纯 {@code {DM}} + {@code Ask}**（四格收敛成三格的证据；它们必须落类2、且不在类3）。
   *
   * <p>★★ <b>批 L ② 显式收窄（三条 → 一条）</b>：{@code simos.gov.recordDiplomaticEvent} / {@code
   * simos.gov.setDiplomaticRelation} 原本与 {@code simos.gov.pay} 同为"纯 {@code {DM}} + {@code Ask}"的三条；
   * 合并之后那两条**拿到了 GM 那一半**（{@code roles={DM,GM}}，设计书 §2.1 裁定 N2）⇒ 它们**不再是**"纯 {@code {DM}}"。 ★
   * 这不是放宽：本清单只负责"纯 {@code {DM}} ⇒ 落类2"这一维，而**合并后那两条仍在类2**这一事实由新增的 {@link #MERGED_DIPLOMATIC_NAMES}
   * 断言逐条钉住（{@code roles()} 必须**恰好** {@code {DM,GM}}、{@code gate} 必须 {@code Ask}）。
   */
  private static final List<String> PURE_DM_APPROVAL_NAMES = List.of("simos.gov.pay");

  /**
   * ★★ <b>批 L ② 合并出来的两条（设计书 §2.1 裁定 N2「乙 + 合并甲」）</b>：两条命令各留**一条**工具、{@code roles={DM,GM}}、 {@code
   * gate=Ask(SENSITIVE)} ⇒ 落类2。
   *
   * <p>★ 它钉的是合并的**直接可执行后果**：① 旧 GM 面工具名（{@code simos.sd.*}）与旧 DM 面工具名（{@code gov.*}）都不得再出现； ②
   * 新名**恰好**在类2；③ {@code roles()} 恰好 {@code {DM,GM}}（多一个/少一个都在这里红）；④ {@code gate()} 实得档是 {@code
   * Ask}（"DM/GM 混合就是要交给 GM 审核"）。
   */
  private static final List<String> MERGED_DIPLOMATIC_NAMES =
      List.of("simos.gov.recordDiplomaticEvent", "simos.gov.setDiplomaticRelation");

  /**
   * ★★ <b>探针上下文</b>：独立重建的、与设计书 §3.2 同形的探针（{@code arguments()} = 空 {@code Map}）。
   *
   * <p>★ <b>为什么这里要"重建"而不是引用生产的那个</b>：A1 的判据是"分类 ⇔ 真 {@code gate()}"，若本测试复用生产侧的判类函数，
   * 那条断言就退化成"生产等于它自己"。⇒ 判类逻辑（{@link #classifyIndependently}）与探针都在本类里**独立重写**；两者只用
   * <b>公开契约</b>（{@code roles()} / {@code gate(ToolContext)}）对话。
   */
  private static final ToolContext PROBE =
      new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), Map.of());

  @TempDir Path tempDir;

  // ────────────────────────── A1：分类 ⇔ 真 gate()（本批核心不变量） ──────────────────────────

  /**
   * ★★ <b>A1：两桶并集里的**每一条**工具，独立重判的类必须与 {@code Partition} 的归属逐条相同</b>。
   *
   * <p>★ <b>为什么这是本批的核心</b>：三分类的口径是"由 {@code (roles, gate)} **纯派生**"，不许有第二处声明。若审计的判类 与真 {@code
   * gate()} 分叉（例如它自己查了张表、或把 {@code Ask} 判成 ALLOW），行为与分类就**各说各话**且无症状。本用例 用**独立重写的判类函数**（{@link
   * #classifyIndependently}：{@code roles()} + 真 {@code gate()}）覆盖全部 194 条。
   *
   * <p>★ <b>三条"不重不漏"的腿</b>（缺一条这条判据就是假的）：
   *
   * <ol>
   *   <li>并集非空（防"空 == 空"恒真）；
   *   <li>三份清单的**名字集合**恰好等于两桶并集（不多、不少、不重）；
   *   <li>逐条：独立重判的类 == 它所在的那一格。
   * </ol>
   */
  @Test
  void everyToolInTheTwoBucketsIsClassifiedExactlyAsItsRolesAndRealGateSay() {
    try (Shell shell = startShell()) {
      List<AgentTool> gmFace = shell.toolsFor(SimosToolSource.Role.GM);
      List<AgentTool> decisionFace = shell.toolsFor(SimosToolSource.Role.DECISION_AGENT);
      ToolClassAudit.Partition partition = ToolClassAudit.requireClasses(gmFace, decisionFace);

      Map<String, AgentTool> union = unionOf(gmFace, decisionFace);
      assertThat(union).as("★ A1 前提：两桶并集非空（否则下面的逐条比对是「空 == 空」）").isNotEmpty();

      Map<String, ToolClass> classifiedByAudit = new LinkedHashMap<>();
      for (ToolClass toolClass : ToolClass.values()) {
        for (AgentTool tool : partition.toolsOf(toolClass)) {
          ToolClass previous = classifiedByAudit.put(tool.name(), toolClass);
          assertThat(previous).as("★★ A1：%s 同时出现在两格里 —— 分区必须互斥", tool.name()).isNull();
        }
      }
      assertThat(classifiedByAudit.keySet())
          .as("★★ A1：三份清单必须恰好覆盖两桶并集（漏一条 = 有工具没落进任何一格且无人发现）")
          .containsExactlyInAnyOrderElementsOf(union.keySet());

      for (Map.Entry<String, AgentTool> entry : union.entrySet()) {
        ToolClass expected = classifyIndependently(entry.getValue());
        assertThat(classifiedByAudit.get(entry.getKey()))
            .as("★★ A1：%s 的分类必须来自**真 gate()**（独立重判 = %s）—— 分叉即「第二处声明」", entry.getKey(), expected)
            .isEqualTo(expected);
      }
    }
  }

  // ────────────────────────── 三格计数锚点 + 与两道既有桶锚点自洽（C1） ──────────────────────────

  /**
   * ★★ <b>三格计数锚点（{@link #GM_ONLY_SIZE} / {@link #DM_APPROVAL_SIZE} / {@link #DM_FREE_SIZE}）+
   * 与两道既有桶锚点 逐位自洽</b>。
   *
   * <p>★ <b>为什么"自洽"比"三个数"更值钱</b>：任何一条工具换格，三个数会动但**桶条数不一定动** ——
   * 反过来，只钉桶条数会漏掉"格内搬家"。两个方向一起钉，才说明分区是**同一份装配面**的派生，而不是另立一套数。
   */
  @Test
  void theThreeClassCountsAnchorToTheTwoExistingBucketAnchors() {
    try (Shell shell = startShell()) {
      List<AgentTool> gmFace = shell.toolsFor(SimosToolSource.Role.GM);
      List<AgentTool> decisionFace = shell.toolsFor(SimosToolSource.Role.DECISION_AGENT);
      ToolClassAudit.Partition partition = ToolClassAudit.requireClasses(gmFace, decisionFace);

      // ① 三格条数锚点（增删一条都要显式改本行并说明为什么）
      assertThat(partition.toolsOf(ToolClass.GM_ONLY))
          .as("★★ 类1 GM_ONLY 条数锚点（增删一条都要显式改本行并说明为什么）")
          .hasSize(GM_ONLY_SIZE);
      assertThat(partition.toolsOf(ToolClass.DM_APPROVAL))
          .as("★★ 类2 DM_APPROVAL 条数锚点（增删一条都要显式改本行并说明为什么）")
          .hasSize(DM_APPROVAL_SIZE);
      assertThat(partition.toolsOf(ToolClass.DM_FREE))
          .as("★★ 类3 DM_FREE 条数锚点（增删一条都要显式改本行并说明为什么）")
          .hasSize(DM_FREE_SIZE);

      // ② 与两道既有桶锚点自洽（两个数在 ShellAssemblySmokeTest 是同名常量；那边 private ⇒ 这里逐字重复）
      assertThat(gmFace)
          .as("★ GM 桶条数 = ShellAssemblySmokeTest.GM_BUCKET_SIZE")
          .hasSize(GM_BUCKET_SIZE);
      assertThat(decisionFace)
          .as("★ 决策人桶条数 = ShellAssemblySmokeTest.DECISION_BUCKET_SIZE")
          .hasSize(DECISION_BUCKET_SIZE);

      int shared = 0;
      for (AgentTool tool : gmFace) {
        if (rolesOf(tool).contains(ToolRole.DM)) {
          shared++;
        }
      }
      assertThat(shared)
          .as("★ 共享（既在 GM 桶又在决策人桶，= roles() 含 {GM, DM} 两者）条数锚点")
          .isEqualTo(SHARED_SIZE);
      assertThat(partition.toolsOf(ToolClass.GM_ONLY).size() + shared)
          .as("★★ 自洽：GM 桶 = 类1 + 共享（%d + %d = %d）", GM_ONLY_SIZE, SHARED_SIZE, GM_BUCKET_SIZE)
          .isEqualTo(gmFace.size());
      assertThat(
              partition.toolsOf(ToolClass.DM_APPROVAL).size()
                  + partition.toolsOf(ToolClass.DM_FREE).size())
          .as(
              "★★ 自洽：决策人桶 = 类2 + 类3（%d + %d = %d）",
              DM_APPROVAL_SIZE, DM_FREE_SIZE, DECISION_BUCKET_SIZE)
          .isEqualTo(decisionFace.size());
      assertThat(GM_ONLY_SIZE + DM_APPROVAL_SIZE + DM_FREE_SIZE)
          .as("★ 并集算术：类1 + 类2 + 类3 = 两桶并集条数")
          .isEqualTo(TOOL_UNION_SIZE);
      assertThat(unionOf(gmFace, decisionFace))
          .as("★ 并集条数锚点（两桶共享的工具只算一条）")
          .hasSize(TOOL_UNION_SIZE);

      // ③ 格与 roles() 的关系（类1 是 {GM}；类2/类3 都含 DM）—— 判类只看"属于谁 × 要不要问人"
      for (AgentTool tool : partition.toolsOf(ToolClass.GM_ONLY)) {
        assertThat(rolesOf(tool))
            .as("★ 类1 的 %s 必须**恰好**是 {GM}（多一个 DM 就该被算成「DM 能用」）", tool.name())
            .containsExactly(ToolRole.GM);
      }
      for (ToolClass toolClass : List.of(ToolClass.DM_APPROVAL, ToolClass.DM_FREE)) {
        for (AgentTool tool : partition.toolsOf(toolClass)) {
          assertThat(rolesOf(tool))
              .as("★ %s 的 %s 必须含 DM（不含 ⇒ 它根本不该在决策人可达面上被判出类）", toolClass, tool.name())
              .contains(ToolRole.DM);
        }
      }
    }
  }

  // ────────────────────────── A2 + A3：类2 具名 24 条 ──────────────────────────

  /**
   * ★★ <b>A2：类2 的名单**恰好**是 {@link #DM_APPROVAL_NAMES} 那 24 条</b>（逐条相等，不是"至少含这 24 条"）。
   *
   * <p>★ <b>方法名为什么不带数字</b>：它原叫 {@code ...TheSeventeenNamedTools}，而 2026-10-15 裁定「甲」把类2 从 17 条推到 24
   * 条 —— 名字里的数字与断言里的数字**只能有一个真相**；数字留在 {@link #DM_APPROVAL_SIZE} 与清单里，名字只描述判据。
   *
   * <p>★★ <b>A3：{@link #PURE_DM_APPROVAL_NAMES} 那三条纯 {@code {DM}} + {@code Ask} 必须在类2 里</b> ——
   * 它们不是"顺带在里面"，而是 §3.1「{@code {DM}} 与 {@code {DM,GM}} 在分类这一维等价」这条口径的**判别力落点**： 谁把口径改成"只有 {@code
   * {DM,GM}} 才算 DM 面"，这三条就会掉出去，这里当场红。
   */
  @Test
  void classTwoRosterIsExactlyTheNamedTools() {
    try (Shell shell = startShell()) {
      ToolClassAudit.Partition partition =
          ToolClassAudit.requireClasses(
              shell.toolsFor(SimosToolSource.Role.GM),
              shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));

      Set<String> classTwo = namesOf(partition.toolsOf(ToolClass.DM_APPROVAL));
      assertThat(classTwo)
          .as("★★ A2：类2 的名单必须**恰好**是这 24 条（逐条具名；只数条数会漏掉「成员替换」）")
          .containsExactlyInAnyOrderElementsOf(DM_APPROVAL_NAMES);
      assertThat(partition.toolsOf(ToolClass.DM_APPROVAL))
          .as("★ A2：类2 条数锚点")
          .hasSize(DM_APPROVAL_SIZE);
      assertThat(classTwo).as("★ A2：类2 无重名").hasSize(DM_APPROVAL_NAMES.size());
      assertThat(DM_APPROVAL_NAMES)
          .as("★ 清单自洽：类2 清单**恰好** 24 条（常量写重了/漏了 ⇒ 这里红）")
          .doesNotHaveDuplicates()
          .hasSize(DM_APPROVAL_SIZE);

      // ★★ A3：纯 {DM} + Ask ⇒ 必须在类2，且**不在**类3（四格收敛成三格）
      Map<String, AgentTool> classTwoTools = byName(partition.toolsOf(ToolClass.DM_APPROVAL));
      Set<String> classThree = namesOf(partition.toolsOf(ToolClass.DM_FREE));
      for (String name : PURE_DM_APPROVAL_NAMES) {
        assertThat(classTwo)
            .as("★★ A3：%s 是纯 {DM} + Ask ⇒ 按 §3.1 自动落类2（四格收敛成三格的关键结论）", name)
            .contains(name);
        assertThat(classThree).as("★ A3：%s 既然落类2，就**不许**同时出现在类3", name).doesNotContain(name);
        assertThat(rolesOf(classTwoTools.get(name)))
            .as("★★ A3：%s 的 roles() 必须是**纯 {DM}**（它没有 GM 那一半 —— 这正是「四格」里多出来的那一格）", name)
            .containsExactly(ToolRole.DM);
        assertThat(classTwoTools.get(name).gate(PROBE))
            .as("★ A3：%s 的 gate() 实得档必须是 Ask（要问人 ⇒ 类2 而不是类3）", name)
            .isInstanceOf(ToolGate.Ask.class);
      }

      // ★★ A3-合并：批 L ② 那两条（GM 面 + DM 面统一成一条）必须**恰好**在类2、roles 恰好 {DM,GM}、gate 实得 Ask。
      for (String name : MERGED_DIPLOMATIC_NAMES) {
        assertThat(classTwo)
            .as("★★ A3-合并：%s 是「两对面合并成一条」的产物 ⇒ 必须落类2（{DM,GM} + Ask）", name)
            .contains(name);
        assertThat(classThree).as("★ A3-合并：%s 落类2 ⇒ 不许同时出现在类3", name).doesNotContain(name);
        assertThat(rolesOf(classTwoTools.get(name)))
            .as("★★ A3-合并：%s 的 roles() 必须**恰好** {DM,GM}（合并后两个面都能用，多一个/少一个都在这里红）", name)
            .containsExactlyInAnyOrder(ToolRole.DM, ToolRole.GM);
        assertThat(classTwoTools.get(name).gate(PROBE))
            .as("★★ A3-合并：%s 的 gate() 实得档必须是 Ask（DM/GM 混合 ⇒ 交给 GM 审核）", name)
            .isInstanceOf(ToolGate.Ask.class);
      }
      // ★ 反向断言（"旧名一律不留"）**不写在这里**：它按 L4 集中于 {@link LegacyToolNameRetirementTest}
      //   —— 那里对**两个桶的并集**逐条钉 19 条旧名，并且**扫 {@code src/test} 源码文本**（门控用例的死角根治）。
      //   ★ 为什么不在本类里再写一份：本类的源码扫描面（{@code theAuditSourceCarriesNoToolNamesAndNoClassCounts}）
      //     只扫生产侧；而测试源码扫描器要求"旧名字面量只允许出现在**一个**持有反向断言数据的文件里" ——
      //     在这里再写一份旧名字面量会把那条不变量打破（实测：扫描器当场报出本文件）。
    }
  }

  // ────────────────────────── A6：Partition 真不可变（执行证据） ──────────────────────────

  /**
   * ★★ <b>A6：真装配拿到的 {@code Partition}，三份清单**真的**改不动</b>（{@code add} / {@code clear} ⇒ {@link
   * UnsupportedOperationException}）。
   *
   * <p>★ <b>为什么必须"执行证据"而不是看类型</b>：批 3a 的 {@code EI_EXPOSE_REP} 修复就是这条 —— 静态类型 {@code
   * List<AgentTool>} 不阻止返回一个可变 {@code ArrayList}，而后续批次（3b 的 {@code ProposalCatalog}）会把它当 **规格**读。★
   * 本用例在**真装配的实例**上打（合成夹具那侧在 {@code ToolClassAuditTest} 里另有一条）。
   */
  @Test
  void theRealPartitionListsRejectMutation() {
    try (Shell shell = startShell()) {
      ToolClassAudit.Partition partition =
          ToolClassAudit.requireClasses(
              shell.toolsFor(SimosToolSource.Role.GM),
              shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));

      for (ToolClass toolClass : ToolClass.values()) {
        List<AgentTool> tools = partition.toolsOf(toolClass);
        assertThat(tools).as("★ 前提：%s 那一格非空", toolClass).isNotEmpty();
        AgentTool first = tools.get(0);
        assertThatThrownBy(() -> tools.add(first))
            .as("★★ A6：%s 的清单必须拒绝 add（真装配实例上打，不是看类型）", toolClass)
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(tools::clear)
            .as("★★ A6：%s 的清单必须拒绝 clear", toolClass)
            .isInstanceOf(UnsupportedOperationException.class);
      }
    }
  }

  // ────────────────────────── A7 / C3：审计里没有第二处清单（源码扫描） ──────────────────────────

  /**
   * ★★ <b>A7 / C3：扫 {@code ToolClassAudit.java} 的源码 —— <b>0 处</b>工具名字面量、<b>0 处</b>条数字面量</b>。
   *
   * <p>★ <b>形制照 {@code DecisionPacketToolGateScanTest}</b>：按**相对模块目录**定位源码（surefire 工作目录 = {@code
   * simos-app/}）、**先断言扫描面存在**（"扫到 0 条 ⇒ 伪装成通过"是本仓踩过的陷阱）、并做**扫描器自证**（抽到的字面量必须非空、 且含已知的故障文案片段）。
   *
   * <p>★ <b>工具名单从哪来</b>：不写死 —— 从**真装配**的两桶并集取（194 条），逐条断言"这个名字在审计源码里 0 次出现"。
   * 这样"审计里有没有第二处清单"这条判据**永远不会因为工具改名而失效**（改名后它自动跟着新名字扫）。
   */
  @Test
  void theAuditSourceCarriesNoToolNamesAndNoClassCounts() throws IOException {
    Path auditSourceFile = auditSourceFile();
    assertThat(Files.isRegularFile(auditSourceFile))
        .as("★ 扫描根必须存在（否则下面的「0 处命中」会伪装成通过）：%s", auditSourceFile.toAbsolutePath())
        .isTrue();
    String source = Files.readString(auditSourceFile);

    List<String> literals = stringLiteralsOf(source);
    assertThat(literals).as("★ 扫描器自证之一：真的抽到了字符串字面量（抽到 0 条 ⇒ 下面的判据是空转）").isNotEmpty();
    assertThat(literals)
        .as("★ 扫描器自证之二：抽到了已知的故障文案片段（证明抽取器读的是这个文件、切的是字面量）")
        .anyMatch(literal -> literal.contains("工具三分类装配故障"));

    try (Shell shell = startShell()) {
      Map<String, AgentTool> union =
          unionOf(
              shell.toolsFor(SimosToolSource.Role.GM),
              shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
      assertThat(union).as("★ 扫描器自证之三：真装配并集非空（否则「没有任何工具名出现」是空 == 空）").hasSize(TOOL_UNION_SIZE);
      for (String toolName : union.keySet()) {
        assertThat(source)
            .as("★★ A7/C3：审计源码里出现了工具名 %s —— 分类只能从 roles()×gate() 派生，**不许**有第二处清单", toolName)
            .doesNotContain(toolName);
      }
    }

    // ★ 条数字面量：三格计数与两道桶锚点一个都不许写进审计实现（写了就是"第二处会漂移的声明"）
    for (int count :
        new int[] {
          GM_ONLY_SIZE,
          DM_APPROVAL_SIZE,
          DM_FREE_SIZE,
          SHARED_SIZE,
          GM_BUCKET_SIZE,
          DECISION_BUCKET_SIZE,
          TOOL_UNION_SIZE
        }) {
      assertThat(containsStandaloneNumber(source, count))
          .as("★★ A7/C3：审计源码里出现了条数 %d —— 条数只能从装配面**数出来**，不许是字面量", count)
          .isFalse();
    }
  }

  // ────────────────────────── 小件 ──────────────────────────

  /** ★ 真装配（端口全 0 ⇒ OS 随机分配，绝不占 5711/5715/5713；store 用 JUnit 临时目录）。 */
  private Shell startShell() {
    return Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  /**
   * ★★ <b>独立重写的判类函数</b>（只用公开契约 {@code roles()} / {@code gate(ToolContext)}）—— A1 的判据就是它与 {@code
   * Partition} 的归属逐条相等。★ 判据与设计书 §3.1 一字对应：{@code {GM}} ⇒ 类1（不调 {@code gate}）；含 DM 时看 {@code gate}
   * 的**实得档**：{@code Ask} ⇒ 类2、{@code Allow} ⇒ 类3、其余（含 {@code Block}）⇒ 判不出（本测试里直接红）。
   */
  private static ToolClass classifyIndependently(AgentTool tool) {
    Set<ToolRole> roles = rolesOf(tool);
    if (roles.size() == 1 && roles.contains(ToolRole.GM)) {
      return ToolClass.GM_ONLY;
    }
    assertThat(roles)
        .as("★ A1：%s 的 roles() 既不是 {GM} 也不含 DM ⇒ 真装配里不该存在（判不出类）", tool.name())
        .contains(ToolRole.DM);
    ToolGate gate = tool.gate(PROBE);
    if (gate instanceof ToolGate.Ask) {
      return ToolClass.DM_APPROVAL;
    }
    if (gate instanceof ToolGate.Allow) {
      return ToolClass.DM_FREE;
    }
    throw new AssertionError(
        "★ A1："
            + tool.name()
            + " 的 gate() 实得档 = "
            + gate
            + " 既不是 Ask 也不是 ALLOW ⇒ 判不出类（真装配里应当已经被装配期审计拦住）");
  }

  /** 两桶并集（按名去重，保序；共享的工具只算一条）。 */
  private static Map<String, AgentTool> unionOf(
      List<AgentTool> gmFace, List<AgentTool> decisionFace) {
    Map<String, AgentTool> union = new LinkedHashMap<>();
    for (AgentTool tool : gmFace) {
      union.putIfAbsent(tool.name(), tool);
    }
    for (AgentTool tool : decisionFace) {
      union.putIfAbsent(tool.name(), tool);
    }
    return union;
  }

  /** 一份清单的工具名（{@link LinkedHashSet} ⇒ 可读且能查重）。 */
  private static Set<String> namesOf(List<AgentTool> tools) {
    Set<String> names = new LinkedHashSet<>();
    for (AgentTool tool : tools) {
      names.add(tool.name());
    }
    return names;
  }

  /** 一份清单按名索引。 */
  private static Map<String, AgentTool> byName(List<AgentTool> tools) {
    Map<String, AgentTool> byName = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      byName.put(tool.name(), tool);
    }
    return byName;
  }

  /** {@code roles()} 的读口（本测试只面对真工具面：每条都必须是 {@link SimosTool}）。 */
  private static Set<ToolRole> rolesOf(AgentTool tool) {
    assertThat(tool)
        .as("★ 真装配面里的 %s 必须实现 SimosTool（否则它过不了装配期的角色审计）", tool.name())
        .isInstanceOf(SimosTool.class);
    return ((SimosTool) tool).roles();
  }

  /** {@code simos-ga} 里三分类审计的源码（surefire 工作目录 = {@code simos-app/}）。 */
  private static Path auditSourceFile() {
    return Paths.get(
        "..",
        "simos-ga",
        "src",
        "main",
        "java",
        "io",
        "mosire",
        "simos",
        "ga",
        "tools",
        "ToolClassAudit.java");
  }

  /** 抽源码里的字符串字面量（{@code "..."}，含转义处理）。 */
  private static List<String> stringLiteralsOf(String source) {
    List<String> literals = new ArrayList<>();
    Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(source);
    while (matcher.find()) {
      literals.add(matcher.group(1));
    }
    return literals;
  }

  /** 源码里是否出现**独立的**这个数字（{@code 140} 命中，但 {@code 1400} / {@code 2140} 不算）。 */
  private static boolean containsStandaloneNumber(String source, int number) {
    return Pattern.compile("(?<!\\d)" + number + "(?!\\d)").matcher(source).find();
  }
}
