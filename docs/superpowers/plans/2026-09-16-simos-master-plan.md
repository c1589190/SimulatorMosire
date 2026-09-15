# SimulatorMosire 实现计划（总计划 · M0 可执行分解）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SimulatorMosire 立起可构建、有硬边界、门禁可跑的 Maven 多模块骨架，并把 AgentLibMosire 从过时 JAR 中解救出来。

**Architecture:** 五模块（`simos-util` / `simos-map` / `simos-social` / `simos-unit` / `simos-core`）自下而上单向依赖。模块边界**由 `maven-enforcer-plugin` 的 `bannedDependencies` 在构建期强制**，不靠约定。计划本身只管 M0；M1 起的模块内部设计由各自 spec 决定（见 §二）。

**Tech Stack:** Java 21、Maven 3.8+、Jackson 2.22.2、SLF4J 2.0.19、JUnit 5（`junit-jupiter` 6.1.3）、AssertJ 3.27.7、`maven-enforcer-plugin` 3.6.3、Spotless 3.10.2（googleJavaFormat）、Checkstyle 3.6.0、SpotBugs 4.10.4.1。

**Spec:** `docs/superpowers/specs/2026-09-16-simos-master-design.md`

---

## Global Constraints

以下每条对本计划的**每个**任务生效，不再逐任务重复：

| # | 约束 | 出处 |
|---|---|---|
| G1 | Java **21**；`maven.compiler.release=21`；enforcer 要求 Maven `[3.8,)` | spec §10.1 |
| G2 | groupId `io.mosire`；父 POM `io.mosire:simos-parent:0.1.0-SNAPSHOT` | spec §10.1 |
| G3 | 模块 artifactId：`simos-util` / `simos-map` / `simos-social` / `simos-unit` / `simos-core` | spec §10.1 |
| G4 | 包名前缀：`io.mosire.simos.util` / `.map` / `.social` / `.unit` / `.core` | spec §10.1 |
| G5 | **不继承** `io.mosire:mosire-parent`；依赖版本显式声明在本 POM 内 | spec §10.3 |
| G6 | `simos-util` 只依赖 Jackson databind + SLF4J API。**不依赖** `agentlib-mosire`、不依赖任何 simos 模块、**不碰文件系统** | spec §3.1、§10.2 |
| G7 | `simos-map` **永不** import `simos-social` / `simos-unit` / `agentlib-mosire` | spec §3.1 |
| G8 | `simos-social` 与 `simos-unit` **互不依赖**；二者可依赖 `simos-util` + `simos-map` | spec §3.1 |
| G9 | 只有 `simos-core` 可依赖 `agentlib-mosire`、MCP SDK、`sqlite-jdbc`、日志实现 | spec §10.2 |
| G10 | 五条铁律写进 `README.md` 第一行（spec §2 原文） | spec §2 |
| G11 | 提交前扫暂存 diff；**绝不 `git add -A`**；**不擅自推送** | 项目纪律 |
| G12 | 迭代只跑相关单条用例（`-Dtest=<类名>`）；完整门禁 `mvn verify` 在关账时跑 | 项目纪律 D29 |
| G13 | **门禁自证**：任何"护栏"（enforcer 规则、Spotless、测试不变量）必须用一个**故意违规**的用例证明它真的会响——这是 spec §1.3 从 GSimulator 的 L1 事故提炼出的原则 | spec §1.3、§11 M1 判据 |

---

## 〇 本版范围与粒度裁决

### 0.1 本版覆盖

| 工作 | 粒度 | 为什么是这个粒度 |
|---|---|---|
| **M0 构建骨架** | **可执行分解**（5 个任务，逐步骤） | spec §10.1~§10.3 已把坐标、模块名、依赖、门禁版本、AgentLib 处置全部钉死，无需再做设计裁决 |
| **M1~M6** | **路线图**（非可执行步骤） | spec §十三 明确把各模块**内部设计**（`MapChangeSet` 字段清单、`Region` 如何统一三概念、`TemporalSeries` 插值语义、时间线 DAG 存储 schema…）留给各自后续 spec。**现在给它们写 bite-sized 步骤等于编造未经裁决的设计** |

### 0.2 为什么 M1 不在这份计划里

`writing-plans` 技能规定：spec 覆盖多个独立子系统时，应拆成"每个子系统一份计划，每份能独立产出可工作、可测试的软件"。M1（UtilSimos 八大件）本身就能独立产出一个"八大件全绿 + 往返不变式框架"的可测库，且是 spec 点名的**两个成败点之一**——它值得单独一份计划。

**M1 的详细计划是下一份文档**，前置条件是把 spec §十三 中"UtilSimos 待决事项"（八大件完整签名、Address 转义规则、Resolver 注册与优先级、TemporalSeries 插值/事件语义、Facet 协议）裁决掉。裁决方式可以是用户直接拍板，也可以在 M1 计划里作为显式决策列出供审。

### 0.3 里程碑顺序（spec §11）

```
M0 骨架 ──→ M1 UtilSimos ──→ M2 MapSimos ──┬──→ M3 Social+Unit ──→ M4 Core内核 ──→ M5 Core外壳
                                           └──→ M6 GSimap 导入器（并行）
```

M6 只依赖 M2，可与 M3/M4 并行。其余严格串行。

---

## 一 M0：构建骨架（可执行）

### Task 1: 固定 AgentLibMosire 版本并重建本地 JAR

**为什么排第一**：这是**硬阻塞项**。2026-09-16 实测 `~/.m2/repository/io/mosire/agentlib-mosire/0.1.0-SNAPSHOT/agentlib-mosire-0.1.0-SNAPSHOT.jar` 是 2026-09-10 的旧构建，只有 **49 个类**；而 `AgentLibMosire/target/classes` 有 **118 个类**（源码 91 个 `.java`，含嵌套类共 118 个 `.class`）。spec §10.5 列出的能力（`ToolCallAuthorizer`、`ResourceAuthorizer`、`Digest`、`ApprovalCoordinator`、`AskKind`）**在旧 JAR 里全部缺失**。不先修这个，M0 Task 5 的验收测试根本编译不过。

**Files:**
- Modify: `/home/cna/ProjectMosire/pom.xml:9`（`<version>0.1.0-SNAPSHOT</version>` → `0.2.0`）
- Modify: `/home/cna/ProjectMosire/AgentLibMosire/pom.xml:10`（`<parent>` 块内的 version）
- Modify: `/home/cna/ProjectMosire/BrainMosire/pom.xml:10`（同上）
- Modify: `/home/cna/ProjectMosire/MainMosire/pom.xml:10`（同上）
- Modify: `/home/cna/ProjectMosire/BashPluginMosire/pom.xml:10`（同上）
- Modify: `/home/cna/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/Version.java`（`VERSION` 常量）
- Modify: `/home/cna/ProjectMosire/BrainMosire/src/main/java/io/mosire/brain/Version.java`（`VERSION` 常量）
- Modify: `/home/cna/ProjectMosire/MainMosire/src/main/java/io/mosire/main/Version.java`（`VERSION` 常量）
- Modify: `/home/cna/ProjectMosire/BashPluginMosire/src/test/java/io/mosire/bash/BashPluginLoadPathTest.java:57`（`PLUGIN_VERSION` 常量）
- Modify: `/home/cna/ProjectMosire/MainMosire/src/test/java/io/mosire/main/app/AppPluginsWiringTest.java:43`（`PLUGIN_VERSION` 常量）

**Interfaces:**
- Consumes: 无（本任务是全计划的前置）
- Produces: 本地仓库中的 `io.mosire:agentlib-mosire:0.2.0` JAR，含 **≥118** 个 `.class`。Task 5 与 `simos-core/pom.xml` 依赖此坐标与版本号

**⚠️ 影响面**：本任务修改的是**另一个仓库** `~/ProjectMosire`，它当前有在途工作（其 `开发计划.md` 记 工作束三、工作束七 均"进行中"）。改动是机械的 10 处字符串替换，但需在 ProjectMosire 侧单独提交。

- [ ] **Step 1: 记录基线（改动前）**

```bash
cd /home/cna/ProjectMosire
echo "引用 0.1.0-SNAPSHOT 的文件数（期望 10）:"
grep -rl "0\.1\.0-SNAPSHOT" . --exclude-dir=.git --exclude-dir=target 2>/dev/null | wc -l
```

Expected: `10`

**⚠️ `--exclude-dir=.git` 不可省**。不加的话 `grep -r` 会遍历 `.git/`，而 `.git/logs/HEAD` 里存着历史提交信息——若任何一条提到该字符串，下一步的 `sed` 就会**改写 git 元数据**。下面的数量守卫同时兜住这个风险。

- [ ] **Step 2: 替换版本字符串（带数量守卫）**

```bash
cd /home/cna/ProjectMosire
FILES=$(grep -rl "0\.1\.0-SNAPSHOT" . --exclude-dir=.git --exclude-dir=target 2>/dev/null)
N=$(echo "$FILES" | wc -l)
echo "待改文件数: $N"
[ "$N" -eq 10 ] || { echo "❌ 文件数与预期(10)不符，中止以免误改"; echo "$FILES"; exit 1; }
echo "$FILES"
echo "$FILES" | xargs sed -i 's/0\.1\.0-SNAPSHOT/0.2.0/g'
```

Expected: 打印 10 个文件路径后替换

- [ ] **Step 3: 验证无残留**

```bash
cd /home/cna/ProjectMosire
echo "残留 0.1.0-SNAPSHOT（期望 0）:"
grep -rl "0\.1\.0-SNAPSHOT" . --exclude-dir=.git --exclude-dir=target 2>/dev/null | wc -l
echo "新版本出现次数（期望 10）:"
grep -rl "0\.2\.0" . --exclude-dir=.git --exclude-dir=target 2>/dev/null | wc -l
```

Expected: 第一行 `0`，第二行 `10`

- [ ] **Step 4: 证明 ProjectMosire 自身没被改坏**

```bash
cd /home/cna/ProjectMosire
./mvnw -q -Dspotbugs.skip=true verify
```

Expected: `BUILD SUCCESS`。加 `-Dspotbugs.skip=true` 是因为 SpotBugs 在本机最慢模块上会跑数分钟（其 POM 注释记载 1.6GB 内存机器上曾超 10 分钟）。

- [ ] **Step 5: 重建并安装 AgentLibMosire（解除阻塞）**

```bash
cd /home/cna/ProjectMosire
./mvnw -q -Dspotbugs.skip=true -pl AgentLibMosire -am install
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: 验证新 JAR 内容（关键断言）**

```bash
J=/home/cna/.m2/repository/io/mosire/agentlib-mosire/0.2.0/agentlib-mosire-0.2.0.jar
echo "JAR: $J"
ls -l "$J"
echo "类数（期望 >= 118，旧 JAR 为 49）:"
unzip -l "$J" | grep -c '\.class$'
echo "关键类抽查:"
for c in ToolCallAuthorizer ResourceAuthorizer ResourceScopeMap Digest ApprovalCoordinator AskKind OpenAICompatibleLlmClient SqliteEventStore; do
  printf "  %-28s " "$c"
  unzip -l "$J" | grep -q "/$c\.class" && echo "✓" || echo "✗ 缺失"
done
```

Expected: 类数 `>= 118`；八个关键类**全部 `✓`**

- [ ] **Step 7: 提交（在 ProjectMosire 仓库内）**

```bash
cd /home/cna/ProjectMosire
git add pom.xml AgentLibMosire/pom.xml BrainMosire/pom.xml MainMosire/pom.xml BashPluginMosire/pom.xml \
  AgentLibMosire/src/main/java/io/mosire/agentlib/Version.java \
  BrainMosire/src/main/java/io/mosire/brain/Version.java \
  MainMosire/src/main/java/io/mosire/main/Version.java \
  BashPluginMosire/src/test/java/io/mosire/bash/BashPluginLoadPathTest.java \
  MainMosire/src/test/java/io/mosire/main/app/AppPluginsWiringTest.java
git diff --cached --stat
git commit -m "chore: 版本由 0.1.0-SNAPSHOT 升为固定版本 0.2.0

SimulatorMosire 需依赖一个不随 SNAPSHOT 漂移的 agentlib-mosire。
本仓 4 个模块均继承父 POM 版本，一并更新（含 3 个 Version.java 常量
与 2 处测试常量）。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

**不推送**（G11）。

---

### Task 2: Maven 骨架、父 POM 与五模块

**Files:**
- Create: `pom.xml`
- Create: `simos-util/pom.xml`
- Create: `simos-map/pom.xml`
- Create: `simos-social/pom.xml`
- Create: `simos-unit/pom.xml`
- Create: `simos-core/pom.xml`
- Create: `simos-util/src/main/java/io/mosire/simos/util/package-info.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/package-info.java`
- Create: `simos-social/src/main/java/io/mosire/simos/social/package-info.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/package-info.java`
- Create: `simos-core/src/main/java/io/mosire/simos/core/package-info.java`
- Create: `README.md`
- Create: `.gitignore`
- Create: `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`（自 ProjectMosire 复制）

**Interfaces:**
- Consumes: Task 1 产出的 `io.mosire:agentlib-mosire:0.2.0`（仅 `simos-core` 用）
- Produces: 五个可构建的模块，坐标 `io.mosire:simos-{util,map,social,unit,core}:0.1.0-SNAPSHOT`；父 POM 中 `simos-parent` 的 `<properties>` 与 `<dependencyManagement>` 供 Task 3/4/5 追加

- [ ] **Step 1: 复制 Maven wrapper（复用已验证可用的一份）**

```bash
cd /home/cna/SimulatorMosire
cp /home/cna/ProjectMosire/mvnw /home/cna/ProjectMosire/mvnw.cmd .
mkdir -p .mvn/wrapper
cp /home/cna/ProjectMosire/.mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
chmod +x mvnw
./mvnw -v
```

Expected: 打印 Maven 版本（应 ≥ 3.8）

- [ ] **Step 2: 写父 POM**

创建 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <!-- 不继承 io.mosire:mosire-parent：避免连带继承其 dependencyManagement
       与插件配置（spec §10.3 G5）。依赖版本一律在本 POM 显式声明。 -->
  <groupId>io.mosire</groupId>
  <artifactId>simos-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>SimulatorMosire</name>
  <description>多模块模拟引擎：UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos</description>

  <modules>
    <module>simos-util</module>
    <module>simos-map</module>
    <module>simos-social</module>
    <module>simos-unit</module>
    <module>simos-core</module>
  </modules>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <jackson.version>2.22.2</jackson.version>
    <slf4j.version>2.0.19</slf4j.version>
    <sqlite-jdbc.version>3.53.4.0</sqlite-jdbc.version>
    <log4j.version>2.26.1</log4j.version>
    <mcp.sdk.version>2.0.1</mcp.sdk.version>
    <junit.version>6.1.3</junit.version>
    <assertj.version>3.27.7</assertj.version>

    <!-- 与 ~/ProjectMosire 的固定版本保持一致（Task 1）。改这里之前先改那边并重新 install。 -->
    <agentlib-mosire.version>0.2.0</agentlib-mosire.version>

    <maven-compiler-plugin.version>3.16.0</maven-compiler-plugin.version>
    <maven-surefire-plugin.version>3.6.0</maven-surefire-plugin.version>
    <maven-enforcer-plugin.version>3.6.3</maven-enforcer-plugin.version>
    <spotless.version>3.10.2</spotless.version>
    <maven-checkstyle-plugin.version>3.6.0</maven-checkstyle-plugin.version>
    <spotbugs-maven-plugin.version>4.10.4.1</spotbugs-maven-plugin.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- 本仓五模块：版本集中在此，模块 POM 不再重复写 -->
      <dependency>
        <groupId>io.mosire</groupId><artifactId>simos-util</artifactId><version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.mosire</groupId><artifactId>simos-map</artifactId><version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.mosire</groupId><artifactId>simos-social</artifactId><version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.mosire</groupId><artifactId>simos-unit</artifactId><version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.mosire</groupId><artifactId>simos-core</artifactId><version>${project.version}</version>
      </dependency>

      <!-- 外部依赖 -->
      <dependency>
        <groupId>com.fasterxml.jackson</groupId><artifactId>jackson-bom</artifactId>
        <version>${jackson.version}</version><type>pom</type><scope>import</scope>
      </dependency>
      <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-bom</artifactId>
        <version>${mcp.sdk.version}</version><type>pom</type><scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
      </dependency>
      <dependency>
        <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>${slf4j.version}</version>
      </dependency>
      <dependency>
        <groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><version>${sqlite-jdbc.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId><version>${log4j.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.logging.log4j</groupId><artifactId>log4j-slf4j2-impl</artifactId><version>${log4j.version}</version>
      </dependency>
      <dependency>
        <groupId>io.mosire</groupId><artifactId>agentlib-mosire</artifactId>
        <version>${agentlib-mosire.version}</version>
      </dependency>
      <dependency>
        <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
        <version>${junit.version}</version><scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId><artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version><scope>test</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>${maven-compiler-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>${maven-surefire-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-enforcer-plugin</artifactId>
          <version>${maven-enforcer-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>com.diffplug.spotless</groupId>
          <artifactId>spotless-maven-plugin</artifactId>
          <version>${spotless.version}</version>
          <configuration>
            <java>
              <googleJavaFormat/>
            </java>
          </configuration>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-checkstyle-plugin</artifactId>
          <version>${maven-checkstyle-plugin.version}</version>
          <configuration>
            <configLocation>${maven.multiModuleProjectDirectory}/config/checkstyle.xml</configLocation>
            <consoleOutput>true</consoleOutput>
            <failOnViolation>true</failOnViolation>
          </configuration>
        </plugin>
        <plugin>
          <groupId>com.github.spotbugs</groupId>
          <artifactId>spotbugs-maven-plugin</artifactId>
          <version>${spotbugs-maven-plugin.version}</version>
          <configuration>
            <effort>More</effort>
            <threshold>Low</threshold>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
    <plugins>
      <!-- 门禁的 execution 接线在 Task 4 完成；此处先只锚定 Java/Maven 版本 -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <executions>
          <execution>
            <id>enforce-java-and-maven</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion><version>[21,)</version></requireJavaVersion>
                <requireMavenVersion><version>[3.8,)</version></requireMavenVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 写五个模块 POM**

`simos-util/pom.xml`（其余四个照此模板改 artifactId / name / description / dependencies）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.mosire</groupId>
    <artifactId>simos-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>simos-util</artifactId>
  <name>UtilSimos</name>
  <description>零领域依赖地基：地址/身份/时间/版本原语、快照与变更集协议、Info 外挂属性、时态序列、Resolver SPI。不依赖 AgentLibMosire，不碰文件系统。</description>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId><artifactId>assertj-core</artifactId>
    </dependency>
  </dependencies>
</project>
```

各模块 `<dependencies>` 的差异（G6~G9）：

| 模块 | artifactId | 依赖 |
|---|---|---|
| UtilSimos | `simos-util` | `jackson-databind`、`slf4j-api`、`junit-jupiter`、`assertj-core` |
| MapSimos | `simos-map` | `simos-util`、`jackson-databind`、`junit-jupiter`、`assertj-core` |
| SocialSimos | `simos-social` | `simos-util`、`simos-map`、`jackson-databind`、`junit-jupiter`、`assertj-core` |
| UnitSimos | `simos-unit` | `simos-util`、`simos-map`、`jackson-databind`、`junit-jupiter`、`assertj-core` |
| CoreSimos | `simos-core` | `simos-util`、`simos-map`、`simos-social`、`simos-unit`、`agentlib-mosire`、`junit-jupiter`、`assertj-core` |

对应 `<name>` / `<description>`：

- `simos-map` / `MapSimos` / `六边形网格地图：地形、区域、连通性、地形生成与寻址实现。不做任何存储——序列化上收到 CoreSimos。`
- `simos-social` / `SocialSimos` / `hex 社会属性（当前仅人口）：带时间戳的时态序列与分段增长率积分。不完善的测试模块。`
- `simos-unit` / `UnitSimos` / `军事单位：编制树、装备与人数、位置继承、基于通行成本的移动与寻路。`
- `simos-core` / `CoreSimos` / `集成点：时间线 DAG、两阶段时间推进、Command Bus、存储、可观测性、AgentBinding、GUI 与 MCP。`

**M0 的 `simos-core` 只引到 `agentlib-mosire` 为止**（Task 5 Step 3 才加）。`sqlite-jdbc`、`log4j-slf4j2-impl`、MCP SDK 构件分别到 M4（存储/可观测性）与 M5（MCP 服务）真正需要时再加——M0 加了也没有代码用它。**不要**为了"先配好"提前引入。

- [ ] **Step 4: 写五个模块的 `package-info.java`（模块契约，不是占位符）**

`simos-util/src/main/java/io/mosire/simos/util/package-info.java`：

```java
/**
 * UtilSimos —— 零领域依赖的地基。
 *
 * <p>本包只提供原语，不理解任何领域概念：地址与身份的表示与解析、模拟时间与数据版本的正交坐标、
 * 快照与变更集的协议接口、外挂式 Info 属性系统、时态序列，以及供各领域模块注册的 Resolver SPI。
 *
 * <p><b>硬约束</b>：不依赖 AgentLibMosire，不依赖任何其它 simos 模块，不碰文件系统。
 */
package io.mosire.simos.util;
```

其余四个同款，正文替换为：

- `io.mosire.simos.map`：
```java
/**
 * MapSimos —— 六边形网格地图。
 *
 * <p>地形类型、区域、连通性、地形生成与编辑，以及 {@code map:} 命名空间的地址解析实现。
 *
 * <p><b>硬约束</b>：不做任何存储（序列化/反序列化上收到 CoreSimos）；永远不知道
 * SocialSimos / UnitSimos 存在——跨模块可见性走 Util 的 Facet 扩展查询。
 */
package io.mosire.simos.map;
```

- `io.mosire.simos.social`：
```java
/**
 * SocialSimos —— 社会属性（当前仅人口）。
 *
 * <p>为单个 hex 挂带时间戳的人口与人口增长率，按分段增长率从当前时间戳积分到目标时间戳。
 *
 * <p><b>硬约束</b>：不依赖 UnitSimos。本模块是不完善的测试模块——社会存在不可能这么简单，
 * 先搭架子。
 */
package io.mosire.simos.social;
```

- `io.mosire.simos.unit`：
```java
/**
 * UnitSimos —— 军事单位。
 *
 * <p>严格树形编制、装备与人数、位置继承（自身优先，无则向父取）、基于通行成本的最短路径
 * 与按时间戳推进的移动 materialize。
 *
 * <p><b>硬约束</b>：不依赖 SocialSimos。
 */
package io.mosire.simos.unit;
```

- `io.mosire.simos.core`：
```java
/**
 * CoreSimos —— 唯一知晓全部模块的集成点。
 *
 * <p>时间线 DAG、两阶段时间推进、Command Bus、存储、可观测性、AgentBinding、GUI 与 MCP。
 *
 * <p><b>硬约束</b>：只负责组合与调度，不重新实现任何领域逻辑。GUI / MCP / Agent / 玩家
 * 全部走同一个 Command 入口。
 */
package io.mosire.simos.core;
```

- [ ] **Step 5: 写 README（五条铁律置顶，G10）**

创建 `README.md`：

```markdown
# SimulatorMosire

> **铁律**
> 1. 所有查询最终解析为**稳定实体**。地址是定位方式，ID 是身份。
> 2. 所有修改最终表示为 `Command → ChangeSet → Revision`。不存在绕过该路径的写入口。
> 3. 所有领域模块**只拥有自己的数据**。MapSimos 永远不知道 SocialSimos / UnitSimos 存在。
> 4. Core 只负责**组合与调度**，不重新实现领域逻辑。
> 5. 变更集**从完整状态类型派生**，且有往返不变式测试守卫：
>    `apply(changeSet, base)` 必须逐字段重建出 target。

模拟引擎。五模块自下而上单向依赖：

    UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos

设计文档：`docs/superpowers/specs/2026-09-16-simos-master-design.md`
实现计划：`docs/superpowers/plans/2026-09-16-simos-master-plan.md`

## 构建

    ./mvnw verify

门禁 = Spotless + Checkstyle + SpotBugs + Surefire。
注意 `mvn test` **不跑** SpotBugs，关账前须单独跑 `spotbugs:check`。

迭代时只跑相关单条用例：

    ./mvnw -q -Dtest=<类名> test
```

- [ ] **Step 6: 写 `.gitignore`**

```gitignore
target/
*.class
.idea/
*.iml
.vscode/
```

- [ ] **Step 7: 构建（本计划第一个绿灯）**

```bash
cd /home/cna/SimulatorMosire
./mvnw -q clean compile
```

Expected: `BUILD SUCCESS`，五个模块全部 `SUCCESS`

- [ ] **Step 8: 验证五模块都被 reactor 认到**

```bash
cd /home/cna/SimulatorMosire
./mvnw -q validate 2>&1 | tail -5
./mvnw help:evaluate -Dexpression=project.modules -q -DforceStdout 2>/dev/null | head -20
```

Expected: 模块列表含全部五个

- [ ] **Step 9: 提交**

```bash
cd /home/cna/SimulatorMosire
git add pom.xml .gitignore README.md mvnw mvnw.cmd .mvn \
  simos-util/pom.xml simos-map/pom.xml simos-social/pom.xml simos-unit/pom.xml simos-core/pom.xml \
  simos-util/src simos-map/src simos-social/src simos-unit/src simos-core/src
git diff --cached --stat
git commit -m "build: 建立 Maven 多模块骨架与五条铁律

父 POM simos-parent + 五模块（simos-util/map/social/unit/core），
Java 21，不继承 mosire-parent。五条铁律写进 README 首行。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 用 enforcer 把模块边界钉成构建期硬约束

**Files:**
- Modify: `simos-util/pom.xml`、`simos-map/pom.xml`、`simos-social/pom.xml`、`simos-unit/pom.xml`、`simos-core/pom.xml`

**Interfaces:**
- Consumes: Task 2 建立的模块坐标
- Produces: 构建期边界门禁。Task 5 及后续所有模块都在此保护下

**设计要点**：spec §3.1 的边界不能靠约定。GSimulator 的 L1 事故（`MapDiff` 手工维护导致四字段漂移、静默丢数据）正说明**没有护栏的约定一定会烂**。`bannedDependencies` 让"越界"变成构建失败。

- [ ] **Step 1: 给 `simos-util` 加边界规则**

在 `simos-util/pom.xml` 的 `</dependencies>` 之后加：

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <executions>
          <execution>
            <id>enforce-module-boundaries</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <bannedDependencies>
                  <excludes>
                    <exclude>io.mosire:agentlib-mosire</exclude>
                    <exclude>io.mosire:simos-map</exclude>
                    <exclude>io.mosire:simos-social</exclude>
                    <exclude>io.mosire:simos-unit</exclude>
                    <exclude>io.mosire:simos-core</exclude>
                  </excludes>
                  <message>UtilSimos 是零依赖地基：不得依赖 AgentLibMosire 或任何 simos 模块（spec §3.1 / G6）</message>
                </bannedDependencies>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

- [ ] **Step 2: 给其余四个模块加同类规则**

`simos-map/pom.xml` 的 `<excludes>`：

```xml
<exclude>io.mosire:simos-social</exclude>
<exclude>io.mosire:simos-unit</exclude>
<exclude>io.mosire:simos-core</exclude>
<exclude>io.mosire:agentlib-mosire</exclude>
```

`<message>`：`MapSimos 永远不知道 SocialSimos / UnitSimos 存在（spec §3.1 / G7）`

`simos-social/pom.xml`：

```xml
<exclude>io.mosire:simos-unit</exclude>
<exclude>io.mosire:simos-core</exclude>
<exclude>io.mosire:agentlib-mosire</exclude>
```

`<message>`：`SocialSimos 与 UnitSimos 互不依赖（spec §3.1 / G8）`

`simos-unit/pom.xml`：

```xml
<exclude>io.mosire:simos-social</exclude>
<exclude>io.mosire:simos-core</exclude>
<exclude>io.mosire:agentlib-mosire</exclude>
```

`<message>`：`UnitSimos 与 SocialSimos 互不依赖（spec §3.1 / G8）`

`simos-core/pom.xml`：**不加** `bannedDependencies`——它是集成点，按设计依赖全部模块（G9）。

- [ ] **Step 3: 证明护栏在正常情况下静默**

```bash
cd /home/cna/SimulatorMosire
./mvnw -q clean validate
```

Expected: `BUILD SUCCESS`（无越界，规则不响）

- [ ] **Step 4: ★ 故意违规——证明护栏真的会响（G13）**

临时把 `simos-social` 加进 `simos-map/pom.xml` 的 `<dependencies>`：

```xml
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>simos-social</artifactId>
</dependency>
```

```bash
cd /home/cna/SimulatorMosire
./mvnw validate 2>&1 | tail -20
```

Expected: **`BUILD FAILURE`**，且输出含 `MapSimos 永远不知道 SocialSimos / UnitSimos 存在`

**若这里 BUILD SUCCESS，说明护栏是假的**——停下来查出原因（常见：`<phase>` 未绑到 validate 之前的阶段、或规则写在了 `pluginManagement` 里没被任何模块执行），修好再继续。这一步是整个 Task 的核心价值；没有它，前面的规则只是装饰。

- [ ] **Step 5: 移除故意违规**

从 `simos-map/pom.xml` 删掉刚加的 `<dependency>`。

```bash
cd /home/cna/SimulatorMosire
./mvnw -q clean validate
grep -c "simos-social" simos-map/pom.xml
```

Expected: `BUILD SUCCESS`；`grep` 计数 `0`

- [ ] **Step 6: 提交**

```bash
cd /home/cna/SimulatorMosire
git add simos-util/pom.xml simos-map/pom.xml simos-social/pom.xml simos-unit/pom.xml
git diff --cached --stat
git commit -m "build: 模块边界由 enforcer bannedDependencies 在构建期强制

MapSimos 不得依赖 Social/Unit 或 AgentLibMosire，Social 与 Unit 互不
依赖，UtilSimos 零依赖。已用故意违规用例证伪过护栏确实会响。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: 接上门禁三件套

**Files:**
- Create: `config/checkstyle.xml`（自 ProjectMosire 复制）
- Modify: `pom.xml`

**Interfaces:**
- Consumes: Task 2 的父 POM `pluginManagement`（版本与配置已在其中锚定）
- Produces: `./mvnw verify` = Spotless → Checkstyle → SpotBugs + Surefire 的完整门禁

- [ ] **Step 1: 复制 checkstyle 规则集**

```bash
cd /home/cna/SimulatorMosire
mkdir -p config
cp /home/cna/ProjectMosire/config/checkstyle.xml config/
head -20 config/checkstyle.xml
```

Expected: 打印出规则集头部，确认是有效 XML

- [ ] **Step 2: 在父 POM 的 `<plugins>` 中接上三个 execution**

在 `pom.xml` 的 `<plugins>` 里、`maven-enforcer-plugin` 之后追加：

```xml
      <plugin>
        <groupId>com.diffplug.spotless</groupId>
        <artifactId>spotless-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>spotless-check</id>
            <goals><goal>check</goal></goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-checkstyle-plugin</artifactId>
        <executions>
          <execution>
            <id>checkstyle-check</id>
            <phase>validate</phase>
            <goals><goal>check</goal></goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>com.github.spotbugs</groupId>
        <artifactId>spotbugs-maven-plugin</artifactId>
        <configuration>
          <fork>true</fork>
          <maxHeap>768</maxHeap>
          <timeout>1800000</timeout>
        </configuration>
        <executions>
          <execution>
            <id>spotbugs-check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
          </execution>
        </executions>
      </plugin>
```

`fork`/`maxHeap`/`timeout` 三项自 `~/ProjectMosire/pom.xml:263-267` 照搬——其注释记载本机内存下 `effort=More` 的分析进程会被换页拖慢、撞插件默认 60s 超时。

- [ ] **Step 3: 跑完整门禁**

```bash
cd /home/cna/SimulatorMosire
./mvnw clean verify 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: 证明三个插件真的执行了（不是静默跳过）**

```bash
cd /home/cna/SimulatorMosire
./mvnw clean verify 2>&1 | grep -E "spotless|checkstyle|spotbugs" | head -20
```

Expected: 输出中出现 `spotless-maven-plugin:...:check`、`maven-checkstyle-plugin:...:check`、`spotbugs-maven-plugin:...:check` **三条**。缺哪条就说明哪个门禁没接上。

- [ ] **Step 5: ★ 故意违规——证明 Spotless 真的会拦（G13）**

在一个源文件里制造格式违规（googleJavaFormat 会拒绝的行）：

```bash
cd /home/cna/SimulatorMosire
cat >> simos-util/src/main/java/io/mosire/simos/util/package-info.java <<'EOF'

class SpotlessCanary {   int    x=1; }
EOF
./mvnw spotless:check 2>&1 | tail -15
```

Expected: **`BUILD FAILURE`**，输出指出 `package-info.java` 未通过格式校验

- [ ] **Step 6: 还原**

```bash
cd /home/cna/SimulatorMosire
git checkout simos-util/src/main/java/io/mosire/simos/util/package-info.java
./mvnw -q spotless:check && echo "OK: 门禁恢复静默"
```

Expected: `OK: 门禁恢复静默`

- [ ] **Step 7: 提交**

```bash
cd /home/cna/SimulatorMosire
git add config/checkstyle.xml pom.xml
git diff --cached --stat
git commit -m "build: 接上门禁三件套 Spotless + Checkstyle + SpotBugs

配置照搬 ProjectMosire（googleJavaFormat、effort=More、fork/768m/30min
超时）。已用故意格式违规证明 Spotless 确实会拦。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: simos-core 接入 agentlib-mosire，并用测试钉死"JAR 不许再退回过时构建"

**这是 M0 的验收判据**（spec §11）：`mvn verify` 通过；`simos-core` 能 import 到 `ToolCallAuthorizer` / `ResourceAuthorizer`（证明 118 类可用）。

**Files:**
- Modify: `simos-core/pom.xml`
- Create: `simos-core/src/test/resources/log4j2-test.xml`（静默测试期日志，可选）
- Create: `simos-core/src/test/java/io/mosire/simos/core/AgentLibAvailabilityTest.java`

**Interfaces:**
- Consumes: Task 1 装好的 `io.mosire:agentlib-mosire:0.2.0`
- Produces: 一个**长期不变量测试**——任何人把 `~/.m2` 退回旧构建、或改了版本号却没重新 `install`，这个测试立刻红

- [ ] **Step 1: 写失败测试**

创建 `simos-core/src/test/java/io/mosire/simos/core/AgentLibAvailabilityTest.java`：

```java
package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M0 验收：证明本地仓库里的 agentlib-mosire 是完整构建，而不是那个只有 49 个类的过时 JAR。
 *
 * <p>背景：2026-09-16 实测 {@code ~/.m2} 里的 0.1.0-SNAPSHOT 是 2026-09-10 的旧构建，只有 49 个类；
 * 同一时刻 {@code AgentLibMosire/target/classes} 有 118 个类。spec §10.5 依赖的能力
 * （ToolCallAuthorizer / ResourceAuthorizer / Digest / ApprovalCoordinator / AskKind）
 * 在旧 JAR 里全部缺失。这个测试就是防止那种状态悄悄回来。
 */
class AgentLibAvailabilityTest {

  /** agentlib-mosire 0.2.0 的类文件数；旧过时构建为 49。用 >= 以免新增类时误报。 */
  private static final int MIN_EXPECTED_CLASSES = 118;

  @ParameterizedTest
  @ValueSource(
      strings = {
        // 旧 JAR 缺失、但 spec §10.5 明确要复用的能力
        "io.mosire.agentlib.tool.ToolCallAuthorizer", // 唯一调用入口
        "io.mosire.agentlib.tool.Digest", // 参数摘要（spec §8.1）
        "io.mosire.agentlib.permission.ResourceAuthorizer", // 地址级权限
        "io.mosire.agentlib.permission.ResourceScope",
        "io.mosire.agentlib.permission.ResourceScopeMap",
        "io.mosire.agentlib.approval.ApprovalCoordinator", // 审批流
        "io.mosire.agentlib.approval.AskKind",
        "io.mosire.agentlib.plugin.HostServices", // 插件宿主服务
        "io.mosire.agentlib.plugin.PluginToolSource",
        "io.mosire.agentlib.llm.OpenAICompatibleLlmClient", // LLM 客户端
        "io.mosire.agentlib.llm.LlmRouteLoader",
        "io.mosire.agentlib.event.SqliteEventStore", // 事件持久化
        "io.mosire.agentlib.config.FileConfigStore",
      })
  void agentLibApiIsLoadable(String fqn) throws Exception {
    assertThat(Class.forName(fqn)).as("agentlib-mosire 应提供 %s", fqn).isNotNull();
  }

  @Test
  void agentLibJarIsNotTheStaleBuild() throws Exception {
    URL location = ToolCallAuthorizer.class.getProtectionDomain().getCodeSource().getLocation();
    Path jarPath = Paths.get(location.toURI());

    assertThat(Files.isRegularFile(jarPath))
        .as("应从 JAR 运行（Maven 构建）；实际位置 = %s", location)
        .isTrue();

    try (JarFile jar = new JarFile(jarPath.toFile())) {
      long classCount = jar.stream().filter(e -> e.getName().endsWith(".class")).count();
      assertThat(classCount)
          .as(
              "agentlib-mosire JAR (%s) 只有 %d 个类，低于期望的 %d——"
                  + "极可能是 ~/.m2 里躺着一个过时构建。"
                  + "修复：cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install",
              jarPath, classCount, MIN_EXPECTED_CLASSES)
          .isGreaterThanOrEqualTo(MIN_EXPECTED_CLASSES);
    }
  }
}
```

- [ ] **Step 2: 跑测试，确认它因为"依赖没接上"而失败**

```bash
cd /home/cna/SimulatorMosire
./mvnw -q -pl simos-core -am -Dtest=AgentLibAvailabilityTest test 2>&1 | tail -25
```

Expected: **编译失败**，报 `程序包 io.mosire.agentlib.tool 不存在`（`simos-core/pom.xml` 还没加依赖）

- [ ] **Step 3: 给 `simos-core/pom.xml` 加依赖**

在 `<dependencies>` 中加：

```xml
    <dependency>
      <groupId>io.mosire</groupId>
      <artifactId>agentlib-mosire</artifactId>
    </dependency>
```

（版本由父 POM `dependencyManagement` 提供，此处不写。）

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd /home/cna/SimulatorMosire
./mvnw -q -pl simos-core -am -Dtest=AgentLibAvailabilityTest test
```

Expected: `BUILD SUCCESS`，14 个用例全绿（13 个参数化 + 1 个 JAR 检查）

- [ ] **Step 5: ★ 故意退回旧 JAR——证明这个测试真的会响（G13）**

```bash
cd /home/cna/SimulatorMosire
# 临时把 0.2.0 换成旧的过时 0.1.0-SNAPSHOT 做一次实验
sed -i 's|<agentlib-mosire.version>0.2.0</agentlib-mosire.version>|<agentlib-mosire.version>0.1.0-SNAPSHOT</agentlib-mosire.version>|' pom.xml
./mvnw -pl simos-core -am -Dtest=AgentLibAvailabilityTest test 2>&1 | tail -20
```

Expected: **`BUILD FAILURE`**，报 `程序包 io.mosire.agentlib.tool 不存在`——旧 JAR 里确实没有这些类

- [ ] **Step 6: 还原版本**

```bash
cd /home/cna/SimulatorMosire
git checkout pom.xml
grep -n "agentlib-mosire.version" pom.xml
```

Expected: `<agentlib-mosire.version>0.2.0</agentlib-mosire.version>`

- [ ] **Step 7: 跑 M0 的完整验收判据**

```bash
cd /home/cna/SimulatorMosire
./mvnw clean verify 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
cd /home/cna/SimulatorMosire
git add simos-core/pom.xml simos-core/src/test
git diff --cached --stat
git commit -m "test: simos-core 接入 agentlib-mosire 并钉死类可用性（M0 验收）

AgentLibAvailabilityTest 断言 13 个 spec §10.5 要复用的类可加载，
并直接读 JAR 断言类数 >= 118——旧过时构建（49 类）会让它立刻红。
已用故意降级到 0.1.0-SNAPSHOT 证伪过该测试确实会响。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 二 M1~M6 路线图（**非可执行步骤**）

> 本节的每一项**不是**可以照着敲的步骤，而是里程碑级的交付物与判据。它们各自需要一份独立的详细计划，**前置条件是把该模块的待决设计裁决掉**（见每项的"待决"一行）。原因见 §0.1：spec §十三 有意把模块内部设计留给后续 spec。

### M1：UtilSimos 八大件（**成败点**）

| 项 | 内容 |
|---|---|
| **交付物** | `Address`/`AddressSegment`、`SubjectId`、`SimosTimestamp`、`RevisionId`/`BranchId`/`StateRef`、`Snapshot`、`ChangeSet`、`Command`、`SimulationState` 八个原语；`InfoSystem`；`TemporalSeries`；`Resolver` SPI；**往返不变式测试框架** |
| **判据** | 八大件各有单测；往返不变式框架有一个**故意漂移字段**的失败用例，证明护栏真的会响（spec §11） |
| **待决**（spec §十三） | 八大件完整方法签名；Address 转义与边界规则；Resolver 注册与优先级；TemporalSeries 的插值/事件语义；Facet 查询协议 |
| **依赖** | M0 |

### M2：MapSimos

| 项 | 内容 |
|---|---|
| **交付物** | 六边形网格（axial、**单一方向常量表**、距离）、`TerraType`、`Region`（闭环边界）、统一连通性系统（稳定 ID）、`GameMap`、地形生成（从头生成 + 框选随机化 + 自动河流）、`MapChangeSet`、`map:` 寻址实现 |
| **判据** | GSimulator 的 L1~L9 **逐条**有对应用例；框选随机化与自动河流各有验收（spec §11） |
| **待决** | 六边形数据结构最终形态；`Region` 如何统一 GSimulator 的三个 region 概念；连通性稳定 ID 生成规则；生成算法参数面；`MapChangeSet` 字段清单 |
| **依赖** | M1 |

**M2 必须修正的 GSimulator 缺陷**（spec §5.1，逐条源码核实过）：
L1 子节点写 `edges` 静默丢失 / L2 双份连通性存储 / L3 方向数组错位（`TerrainGeometry.DIRS` 与 `MapService.HEX_DIRS` 在索引 1-4 指向不同方向）/ L4 三个 region 概念 / L5 Province 归属 O(区域数×hex数) / L6 坐标表述不一致 / L7 无海拔无种子落盘 / L8 12 参数构造复制 12 次 / L9 地形词表分裂。

### M3：SocialSimos + UnitSimos

| 项 | 内容 |
|---|---|
| **交付物** | Social：hex 人口 + 增长率，TemporalSeries，分段积分。Unit：编制树、装备、位置继承、Movement Points 移动与 A* |
| **判据** | 人口分段积分与手算种子数对得上；单位移动按 spec §5.3 的例子**逐值**验算（`40 - 12.5 = 27.5`；`27.5 - 32.5 = -5` → `currentHex=[1,2]`, `nextHex=[1,3]`, `remaining=5 MP`）（spec §11） |
| **待决** | 增长率分段边界语义（时间戳落在段边界算哪段）；人口 cache 策略；编制树修改操作面；移动 materialize 的精度与舍入；A* 启发函数 |
| **依赖** | M2 |

### M4：CoreSimos 内核（**成败点**）

| 项 | 内容 |
|---|---|
| **交付物** | 时间线 DAG（分支、按时间戳推进）、两阶段时间推进（Prepare→Propose→Resolve→Validate→Commit→Post-commit）、Command Bus（含乐观并发）、存储（日志 SQLite / 快照 JSON）、可观测性 |
| **判据** | 时间线能分岔；`correlationId` 能一条命令从入口追到落盘；CONFLICT 有**真实并发**用例（spec §11） |
| **待决** | 时间线 DAG 存储 schema；Checkpoint 周期；Command 类型清单 |
| **依赖** | M3 |

**关键约束**：A* 寻路与实际移动**必须用同一个 `movementCost()` 函数**（spec §5.3），否则会出现"算法说 A 最快、执行发现 B 更快"。`correlationId` 须贯穿 `Command → Proposal → ChangeSet → Revision`（spec §8）。

### M5：CoreSimos 外壳

| 项 | 内容 |
|---|---|
| **交付物** | AgentBinding（决策人绑定）、AgentLibMosire 集成（`AgentTool` 包装 + `ToolCallAuthorizer` 桥接）、5711 主 GUI、5715 MCP |
| **判据** | Agent 与玩家改同一状态**走同一 Command 路径**；MCP 能达到任何**合法**状态（spec §11） |
| **待决** | GUI 具体形态；MCP 工具清单；`Operation` 枚举是否扩展（`CONTROL`/`OBSERVE`/`OWN`）；是否需要工具级硬上限（cap 语义） |
| **依赖** | M4 |

**复用 AgentLibMosire 的四项注意**（spec §10.5，调查发现）：`Operation` 只有 READ/WRITE；`ResourcePolicy` 是**默认值不是上限**（调用方显式声明会覆盖工具声明，没有"绝不允许"的硬保证）；`ResourceScope` 只做词法段比较；`ResourceScope.ofDirs`/`ofDir`/`allowsDir`/`dirList` 是 **fs 专用**，做游戏地址要用 `ResourceScope.of(String...)`。

**决策人绑定**（spec §5.5）：哪些类型允许绑决策人**由各模块自己声明**，Core **不写** `if (object instanceof Region && type.equals("Nation"))`。`MapSimos` 注册「`Region` 且 `type=Nation` 允许绑定」，`UnitSimos` 注册「全部 Unit 允许」，Core 只问 `canAttachAgent(subject)?`。

### M6：GSimap 导入器（独立 CLI，可与 M3/M4 并行）

| 项 | 内容 |
|---|---|
| **交付物** | 把 GSimulator 的 `*_map.json` 转成 simos 数据集的独立 CLI |
| **判据** | 旧 `*_map.json` 能转成 simos 数据集（spec §11） |
| **已知输入形态** | 顶层键 `[gridSize, hexOrientation, hexes, terrainBlocks, provinces, cities, rivers, roads, terrainTypes, compressedRegions, pathwayGroups, edges]`；hex 键形如 `-3_-1`；`hexOrientation: false` |
| **依赖** | M2 |

---

## 三 明确不做 / 开口项

| 项 | 处置 |
|---|---|
| Social 的复杂社会参数（经济、政治、外交） | **登记在案**，本轮只做人口，明确标注为"不完善的测试模块" |
| Unit 的支援/配属关系 | 本轮只做严格编制树；未来 `UnitRelation` 只预留概念 |
| Unit 移动的 `LOCK_ROUTE` / `REPLAN_EVERY_STEP` | 只预留枚举，第一版用 `NEED_REPLAN` |
| `Operation` 枚举扩展 | 待 M5 裁决 |
| 工具级硬上限（cap 语义） | 待 M5 裁决 |
| 审批 UI | `AgentLibMosire` **没有** HTTP 渠道实现，M5 需自写 `ApprovalChannel` |
| 流式 LLM 输出 | `LlmClient.chat` 是同步一请求一响应（契约级约束），打字机效果需改造 |
| Embedding / RAG | `AgentLibMosire` 只有 `NoopEmbeddingProvider`，无生产实现 |
| 发布流水线 | 当前无 `distributionManagement` |

---

## 四 纪律（沿用，逐条有效）

- **绝不 `git add -A`**；提交前先扫 `git diff --cached`；**不擅自推送**（G11）
- **迭代只跑相关单条用例**（`-Dtest=<类名>`），别动辄全量测试（G12）
- **门禁**：`./mvnw verify` = Spotless + Checkstyle + SpotBugs + Surefire；`mvn test` **不跑** SpotBugs，关账前须单独跑 `spotbugs:check`
- **护栏必须自证**（G13）：任何 enforcer 规则、格式门禁、测试不变量，都要有一个故意违规的用例证明它真的会响
- **密钥纪律**：值绝不进日志/异常/事件/argv/env/stdio；读配置只打印路径 + 长度

---

## 五 自审记录

**Spec 覆盖检查**（逐个 spec 章节 → 落在哪）：

| spec 章节 | 落到 |
|---|---|
| §2 五条铁律 | Task 2 Step 5（README）+ Global Constraints G10 |
| §3.1 依赖硬约束 | Task 3（enforcer 强制）+ G6~G9 |
| §4 UtilSimos 八大件 | M1 路线图（详细计划待写） |
| §5.1~§5.4 各模块职责与不做清单 | M2~M5 路线图"交付物/待决" |
| §5.5 AgentBinding | M5 路线图 |
| §6 两阶段时间推进 | M4 路线图 + 判据 |
| §7 乐观并发 | M4 路线图 + 判据（CONFLICT 真实并发用例） |
| §8 可观测性 | M4 路线图 + 判据（`correlationId` 全链可追） |
| §9 存储分层 | M4 路线图 |
| §10.1~§10.2 坐标与依赖 | Task 2 + Global Constraints G2~G9 |
| §10.3 AgentLib 接入（含硬阻塞） | **Task 1**（阻塞项）+ Task 5（判据） |
| §10.4 服务拓扑 | M5 路线图 |
| §10.5 可复用能力与四项注意 | M5 路线图 |
| §11 里程碑与判据 | §二 路线图逐条引用 |
| §12 开口项 | §三 |
| §13 各模块待决 | §二 每项的"待决"一行 |

**占位符扫描**：无 TBD / TODO / "适当配置" / "类似 Task N"。所有可执行步骤都带真实命令、真实 XML、真实 Java。

**类型一致性**：`agentlib-mosire` 版本号在 Task 1（`0.2.0`）、Task 2 父 POM（`<agentlib-mosire.version>0.2.0`）、Task 5 测试断言（`>= 118`）三处一致；模块 artifactId 在 `pom.xml`、五个模块 POM、Task 3 的 enforcer `<exclude>` 中拼写一致。

**已知风险**：

1. **Task 1 修改另一个仓库**（`~/ProjectMosire`，有在途工作）。改动机械（10 处常量），但需在那边单独提交。若用户希望暂缓升版本，可只做 Step 5~6（重新 `install`），把版本固定推迟——但那会让本计划 Task 5 的 `agentlib-mosire.version` 需写 `0.1.0-SNAPSHOT`。
2. **`simos-core` 的 MCP / sqlite / 日志实现依赖推迟到 M4/M5**（已定，非开口项）——M0 加进来也没有代码用。真到 M5 时需先用 `mvn dependency:tree` 确认 `mcp-bom` 提供的 artifactId 再写死。
3. **M1~M6 是路线图不是步骤**，直接照做会卡在未裁决的设计上。每份独立计划的前置条件已在该项"待决"一行列明。
