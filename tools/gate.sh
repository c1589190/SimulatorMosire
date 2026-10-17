#!/usr/bin/env bash
# =============================================================================
# tools/gate.sh —— 本仓**验收门禁**：把「只写在文档里的规矩」变成「跑起来就过不去」的拦截点
# =============================================================================
#
# 【这个脚本强制哪几条规则 / 每条为什么】
#
#  R1 ★★ 前端门禁与打包不许被 skip 掉（「没有机制强制」的第一号事故）
#     为什么：实测 —— 为绕过 1 条旧断言而加 `-Dexec.skip=true`，于是**整道前端门禁停摆**
#     （`simos-app/pom.xml:147-161` 的 `frontend-unit-tests` 就是 exec-maven-plugin 的 `exec` 目标；
#     `exec.skip` 一设，插件照常「执行」但整条不跑）。后果：`simos-app/src/main/resources/webui/api.js:535`
#     引用一个已删除的函数 ⇒ `window.SimosaApi` **整个不发布**（GUI 全坏），**活了 5 个提交**没人发现。
#     拦截点（四条，缺一不可）：
#       ① `full` **不接受任何参数** —— 任何 `-D…`/`-q` 一律当场拒，连「想去掉点什么」的入口都没有；
#       ② `focused` / `guards` 拒**削弱门禁的开关**：凡 `-D` 属性名里带 `skip` 的（`-DskipTests` /
#          `-Dmaven.test.skip` / `-Dexec.skip` / `-Dcheckstyle.skip` / `-Dspotbugs.skip` /
#          `-Dspotless.check.skip` / `-Denforcer.skip` …）以及 `-Dmaven.test.failure.ignore` /
#          `-DfailIfNoTests=false` / `-Dsurefire.failIfNoSpecifiedTests=false` 一律拒
#          （只有显式 `=false`/`=0` 的「反向 skip」才放行）；并拒 `-q`（本仓纪律：不加 `-q`，rc 取 `$?`）；
#          ★ 例外且只有两处，都是门禁**自己内部**补的、且都有更严的补偿断言（见下）：
#            `guards` 与 `focused`（仅在带 `-Dtest=` 时）补 `-Dsurefire.failIfNoSpecifiedTests=false` ——
#            因为上游模块里没有点名的测试，surefire 会以 "No tests matching pattern …" 直接判红；
#            补偿 = 跑完断言**报告真的存在且 tests>0**（`guards` 逐类、`focused` 按新报告数），
#            拼错表达式照样红 ⇒ 不是放松，是「不拿红掩盖」。调用方自己传这个开关仍被拒。
#       ③ `MAVEN_ARGS` / `MAVEN_OPTS` / `JAVA_TOOL_OPTIONS` / `_JAVA_OPTIONS` / `.mvn/maven.config`
#          里**藏**skip 开关同样被拒 —— 否则上面的参数白名单可以整条绕过去（系统属性会进 Maven 的 JVM）；
#       ④ 跑完**正向验证据**（不靠黑名单）：构建日志里必须有 `[frontend-gate] OK tests=`（前端门禁真跑了）、
#          必须有 `shade:…:shade (shade-executable) @ simos-app`（真的打了包）、shaded jar 必须存在、
#          全仓 surefire 报告合计 `tests>0 且 failures=errors=0`（`clean` 之后报告全是本次新写的
#          ⇒ 任何 skip 都表现为「报告为零」，**假绿无路可走**）。
#
#  R2 ★★ 每批必须有一条「会真的构造组合根」的检查
#     为什么：架构调整轮**从没跑过 `clean verify`**（只跑 `-DskipTests package spotbugs:check`）⇒
#     没有任何机制构造真 `Shell` ⇒ `SimosSdReportsTool.roles()` 写成 `{GM}` 而它注册在 DM 桶 ⇒
#     `Shell` 构造函数当场抛 ⇒ **GUI 5 个提交起不来**（`simos-app` 445 E，其中 378 同此一因）。
#     拦截点：① `guards` 子命令跑 7 条护栏（`ShellAssemblySmokeTest` 真的调 `Shell.start(...)` 构造组合根 +
#              `ArchitectureGuardsTest` + `Ga/Gov/ArmyRoundTripTest`）；`full` 里也跑（verify 覆盖 test）；
#              ② `guards`/`full` 跑完**逐条断言每个护栏类真的执行了**：读
#              `<module>/target/surefire-reports/TEST-<fqcn>.xml`，要求 `tests>0 且 failures=errors=0`
#              —— `-Dtest=` 拼错、类被改名、测试被 skip，都在这里红，而不是「零测试通过」式的假绿；
#              ③ 护栏源文件必须还在（`<module>/src/test/java/<fqcn 路径>.java`）—— 删掉/改名 = 门禁当场红，
#              不许「清单过期了但没人发现」。
#
#  R3 ★★ `-pl <module>` 验的可能是 `~/.m2` 里的旧 jar，不是工作树
#     为什么：实测（规划 §34.3）—— 把 mutant 放进 `simos-ga/src/main` 之后再跑 `-pl simos-app -Dtest=…`
#     **是绿的**：Maven 从本地仓库解析上游构件，工作树里未 `install` 的改动**根本不在被验的字节码里**。
#     拦截点：① `focused` **默认加 `-am`**（上游在同一个 reactor 里、从工作树构建）；
#              ② 要去掉 `-am` 必须显式传 `--i-know-upstream-may-be-stale`，输出打醒目警告并把「你验的是旧
#                 字节码」写清楚（把隐性坑变成显式签字）；
#              ③ 透传参数里不许出现 `-pl`/`--projects`（防止偷换模块、把 `-am` 架在别的模块上）。
#
#  R4 ★ `git add -A` 会把**在飞 agent 的文件**扫进提交
#     为什么：实测三次（`79f9bb0e` / `68f56d24` / `c5b0c203`）——`git add -A` 把别的 agent 正在写的文件
#     一起提交了（多 agent 共用一棵工作树，AGENTS §一.2/§一.3）。
#     拦截点：① `add` 只接受**逐条点名的路径**：`-A`/`--all`/`-a`/`.`/`./`/`--ignore-removal`/任何 `-` 开头
#                的选项/通配符（`*?[`）/pathspec magic（`:` 开头）/仓外路径/等于仓根的路径 **一律当场拒**；
#              ② 目录参数允许，但会**列出它里面将被扫进去的全部条目**（看得见才能负责）；
#              ③ 加完回显 `git status --porcelain` + `git diff --cached --name-status` + **本次 add 新入列
#                 的清单**（`comm` 前后差集），并对「最近 2 分钟被改过」的文件打警告（可能是在飞中间态）。
#
#  R5 ★ 跑 Maven 前不查服务 ⇒ 运行中的 jar 被就地重写（静态资源全 500）
#     为什么：AGENTS §二 —— `package` 会重写 shade 产物；运行中 JVM 手里 jar 的 inode 被就地重写后，
#     `/` 与 `/styles.css` 全 500（进程还活着、`/api/*` 还 200，最难查的一族）。
#     ★ 作用域（用户 2026-10-23 更正：「那是隔壁项目，编译门禁只是用于同一个项目文件！」）：
#       **只看落在本仓的进程** —— 判定 = 命令行含本仓路径 **或** cwd 在本仓之下；隔壁仓库的 Maven/服务不算。
#     拦截点：① `pre` 自检；② `full`（含 package/shade）**起跑前**查服务：有 ⇒ 当场拒；
#              ③ `focused` 透传的目标里含 `package|verify|install|deploy|shade:shade` 时同样拒
#                 （`test`/`compile` 不碰 jar ⇒ 只警告，符合 AGENTS §二「服务在跑时验代码用 test」）。
#     ★ 分级（口径要精确，不做「见进程就拦」的假阳性）：
#       · **硬风险**（判红）：命令行/cwd 指着**本仓** `simos-app/target/` 的进程 —— `clean verify` 会重写它；
#       · **仅报告**（不拦）：经 `tools/run-shaded.sh` 起的 `/tmp/simos-shaded-*.jar` 快照进程（快照独占，
#         这正是 AGENTS §二给出的修法）、以及其他 checkout / worktree 里的实例（不吃本仓 target）。
#
#  既有约定（本脚本一并遵守，不新增）：Maven 一律走 `tools/mvn-lock.sh`（本仓串行化锁，AGENTS §一.1，
#  **不绕过**）；不加 `-q`，rc 取 `$?`（管道取 `PIPESTATUS[0]`），失败日志原样透出；零新依赖、不联网、
#  不写仓内文件（日志落 /tmp）。**rc 约定**：`2` = 门禁拦截（用法/规则被违反，构建根本没起跑）；
#  `1` = 被检查的东西红了（检测到服务 / 构建失败 / 证据不足）。
#
# 【子命令】
#   tools/gate.sh pre                          跑前自检（本仓服务 / .mvn-lock.d / 工作树脏度）
#   tools/gate.sh full                         ★ 完整验收：`clean verify`（零参数、零 skip）+ `-DskipTests validate`
#   tools/gate.sh focused <module> [args…]     ★ 聚焦跑；默认 `-am`（上游来自工作树）；去 `-am` 要显式签字
#   tools/gate.sh guards                       ★ 7 条关键护栏（含真构造组合根）+ 逐条断言「真的跑了」
#   tools/gate.sh add <path> [path…]           ★ 白名单式 `git add` + 回显即将提交的清单
#   tools/gate.sh selftest                     本脚本自己的红/绿对照（不需要 Maven，秒级）
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/tools/mvn-lock.sh"
SELF="$ROOT/tools/gate.sh"
LOCK_DIR="$ROOT/.mvn-lock.d"

# 7 条护栏：<module> <全限定类名>。★ 与源码一一对应，改名/删除 ⇒ 门禁红（见 claim_guard_sources）。
# ★★ D-031 Z6（2026-10-16）：新增 simos-production / simos-culture 两条往返守卫；护栏数量文案按
#    GUARDS_TABLE 实际条数（7）同步，不改任何命令运行逻辑。
GUARDS_TABLE='simos-core io.mosire.simos.core.ArchitectureGuardsTest
simos-ga io.mosire.simos.ga.change.GaRoundTripTest
simos-gov io.mosire.simos.gov.change.GovRoundTripTest
simos-army io.mosire.simos.army.change.ArmyRoundTripTest
simos-app io.mosire.simos.app.ShellAssemblySmokeTest
simos-production io.mosire.simos.production.change.ProductionRoundTripTest
simos-culture io.mosire.simos.culture.change.CultureRoundTripTest'

# 疑似「本仓 app 服务」的命令行特征（只认本仓独有的入口名，不认泛化的 java）。
SERVICE_PAT='io\.mosire\.simos\.app\.ShellMain|simos-app-[^ ]*-shaded\.jar|simos-shaded-[^ ]*\.jar'
# 本仓 Maven / 测试 JVM 的命令行特征。
MAVEN_PAT='classworlds\.launcher\.Launcher|surefirebooter|maven\.wrapper'

# ---------------------------------------------------------------------------
# 输出小工具
# ---------------------------------------------------------------------------
hr() { printf -- '------------------------------------------------------------------------\n'; }
log() { printf '[gate] %s\n' "$*"; }
ok() { printf '[gate] ✓ %s\n' "$*"; }
warn() { printf '[gate] ! %s\n' "$*" >&2; }
bad() { printf '[gate] ✗ %s\n' "$*" >&2; }

# 门禁拦截：报「哪条规则 + 为什么 + 正确用法」，rc=2（= 构建根本没起跑）。
# $1 规则号  $2 被违反的事  $3 为什么  $4 正确用法
reject() {
  {
    printf '\n[gate] ✗ 门禁拒绝（规则 %s）：%s\n' "$1" "$2"
    printf '[gate]   为什么：%s\n' "$3"
    printf '[gate]   正确用法：%s\n' "$4"
  } >&2
  exit 2
}

# ---------------------------------------------------------------------------
# 进程扫描：只认「落本仓」的进程（用户 2026-10-23 更正：隔壁项目不算）
# ---------------------------------------------------------------------------
# 输出：pid<TAB>cwd<TAB>cmdline   —— $1 = cmdline 的 ERE（pgrep -f 口径）
repo_procs() {
  local pat="$1" pid cmd cwd hit
  for pid in $(pgrep -f -- "$pat" 2>/dev/null || true); do
    [ "$pid" = "$$" ] && continue
    [ "$pid" = "$PPID" ] && continue
    cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    [ -n "$cmd" ] || continue
    # 谁的命令行里提到 gate.sh，谁就是本门禁自己的包装进程（harness 的 bash -c 也是），不是被查对象。
    case "$cmd" in *'tools/gate.sh'*) continue;; esac
    cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    hit=0
    case "$cmd" in *"$ROOT"*) hit=1;; esac
    case "$cwd" in "$ROOT"|"$ROOT"/*) hit=1;; esac
    [ "$hit" = 1 ] || continue
    printf '%s\t%s\t%s\n' "$pid" "${cwd:-?}" "$cmd"
  done
  return 0   # ★ 列表函数恒 rc=0：否则最后一行"不匹配"会让 rc=1，在 pipefail 下把整条管道判负（实测踩到）
}

# 与 repo_procs 同，但**不**要求可归因到本仓路径（仅用于本仓独有文件名，如 run-shaded.sh 的 /tmp 快照）。
any_procs() {
  local pat="$1" pid cmd cwd
  for pid in $(pgrep -f -- "$pat" 2>/dev/null || true); do
    [ "$pid" = "$$" ] && continue
    [ "$pid" = "$PPID" ] && continue
    cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    [ -n "$cmd" ] || continue
    case "$cmd" in *'tools/gate.sh'*) continue;; esac
    cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    printf '%s\t%s\t%s\n' "$pid" "${cwd:-?}" "$cmd"
  done
  return 0
}

# 硬风险：命令行/cwd 指着本仓 simos-app/target/（clean verify 会就地重写它）。
is_at_risk_cmd() { # $1=cmd $2=cwd
  case "$1" in *"$ROOT/simos-app/target/"*) return 0;; esac
  case "$2" in
    "$ROOT"|"$ROOT"/*) case "$1" in *'simos-app/target/'*) return 0;; esac;;
  esac
  return 1
}

service_procs_at_risk() {
  local pid cwd cmd
  while IFS=$'\t' read -r pid cwd cmd; do
    [ -n "${pid:-}" ] || continue
    is_at_risk_cmd "$cmd" "$cwd" && printf '%s\t%s\t%s\n' "$pid" "$cwd" "$cmd"
  done < <(repo_procs "$SERVICE_PAT")
  return 0
}

service_procs_other() {
  local pid cwd cmd
  while IFS=$'\t' read -r pid cwd cmd; do
    [ -n "${pid:-}" ] || continue
    is_at_risk_cmd "$cmd" "$cwd" || printf '%s\t%s\t%s\n' "$pid" "$cwd" "$cmd"
  done < <( { repo_procs "$SERVICE_PAT"; any_procs 'simos-shaded-[^ ]*\.jar'; } | sort -u)
  return 0
}

maven_procs() { repo_procs "$MAVEN_PAT"; }

port_probe() { # 仅提示：本仓默认 5711(GUI) / 5717(MCP)；端口占用可能来自别的项目，不判红。
  local p code
  for p in 5711 5717; do
    code="$(curl -s -m 1 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$p/" 2>/dev/null || true)"
    case "$code" in ''|000) ;; *) printf '%s\t%s\n' "$p" "$code";; esac
  done
}

print_proc_table() { # stdin: pid<TAB>cwd<TAB>cmd
  local pid cwd cmd
  while IFS=$'\t' read -r pid cwd cmd; do
    [ -n "${pid:-}" ] || continue
    printf '        pid=%s  cwd=%s\n          %s\n' "$pid" "$cwd" "$cmd"
  done
  return 0
}

print_service_report() { # $1 = 0 ⇒ 有硬风险服务时 rc=1；1 ⇒ 只报不判
  local strict="$1" at risk rc=0
  at="$(service_procs_at_risk)"
  risk="$(service_procs_other)"
  if [ -n "$at" ]; then
    if [ "$strict" = 0 ]; then
      bad "规则 R5：本仓有**直接吃 simos-app/target/** 的服务在跑（clean/package 会就地重写它的 jar）"
    else
      warn "规则 R5（仅提示，不拦）：本仓有**直接吃 simos-app/target/** 的服务在跑"
    fi
    printf '%s\n' "$at" | print_proc_table >&2
    {
      log "  为什么：AGENTS §二 —— 运行中的 JVM 手里 jar 的 inode 被就地重写后，/ 与 /styles.css 全 500"
      log "           （进程还活着、/api/* 还 200）——最难查的一族故障。"
      log "  正确用法：① 用 tools/run-shaded.sh <jar> 起（快照独占，免受重写）；② 或先停掉该进程；"
      log "           ③ 只想验代码不打包 ⇒ tools/gate.sh guards（test 阶段不碰 jar）。"
    } >&2
    rc=1
  else
    ok "本仓没有直接吃 simos-app/target/ 的服务进程"
  fi
  if [ -n "$risk" ]; then
    warn "另有本仓服务进程**不吃本仓 target**（不拦，仅报；快照启动 / 其他 checkout 都属此类）："
    printf '%s\n' "$risk" | print_proc_table >&2
  fi
  [ "$strict" = 0 ] || rc=0
  return "$rc"
}

require_no_at_risk_service() { # $1 = 上下文文案；有硬风险服务 ⇒ rc=1（拒跑）
  print_service_report 0 || {
    bad "拒绝执行：$1"
    exit 1
  }
}

# ---------------------------------------------------------------------------
# 参数校验（「有牙」的地方）
# ---------------------------------------------------------------------------
# 单个 -D 参数是否「削弱门禁」？0 = 是（拒）。
is_weakening_prop() {
  local arg="$1" name value lname
  case "$arg" in -D*) name="${arg#-D}";; *) return 1;; esac
  value=""
  case "$name" in *=*) value="${name#*=}"; name="${name%%=*}";; esac
  lname="${name,,}"
  # 这两个属性**只有 false/0 才是削弱**（它们的 true 才是安全默认）
  case "$lname" in
    surefire.failifnospecifiedtests|failifnotests)
      case "$value" in false|FALSE|0) return 0;; *) return 1;; esac;;
  esac
  # 其余属性：显式 false/0 = 没削弱（如 -DskipTests=false）
  case "$value" in false|FALSE|False|0) return 1;; esac
  case "$lname" in
    *skip*) return 0;;
    maven.test.failure.ignore|test.failure.ignore) return 0;;
  esac
  return 1
}

reject_weakening() { # $1 = 上下文；其余 = 参数
  local ctx="$1"; shift
  local a
  for a in "$@"; do
    is_weakening_prop "$a" && reject "R1" \
      "$ctx 的透传参数削弱门禁：$a" \
      "规则写在文档里、没人拦，就是没有规则：本仓实测 -Dexec.skip=true 停掉整道前端门禁 ⇒ api.js 引用已删函数 ⇒ window.SimosaApi 整个不发布（GUI 全坏）活了 5 个提交。" \
      "去掉该开关；确实只想快速编译就明说要用哪个阶段（tools/gate.sh focused <module> compile），不要用 skip 假装全绿。"
    case "$a" in
      -q|--quiet) reject "R1" \
        "$ctx 不接受 -q/--quiet" \
        "本仓纪律：不加 -q，rc 取 \$?；-q 会把「哪条测试跑了 / 哪些插件跑了」一起吞掉，正是本门禁要留证据的东西。" \
        "去掉 -q；输出会很长，但本脚本会把完整日志落到 /tmp 并打印路径。"
        ;;
    esac
  done
}

parse_zero_args() { # $1 = 子命令名；其余 = 参数（必须为空）
  local name="$1"; shift
  (($# == 0)) || reject "用法" \
    "$name 不接受任何参数（收到：$*）" \
    "$name 的语义就是「这条路径上没有可调的旋钮」：一旦允许透传 -D…，第一个被传进来的就会是 skip 开关（本仓已发生过）。" \
    "tools/gate.sh $name"
}

# 环境里藏着的 skip 开关（MAVEN_ARGS/MAVEN_OPTS/JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/.mvn/maven.config）
check_env_not_weakening() {
  local var val a line
  for var in MAVEN_ARGS MAVEN_OPTS JAVA_TOOL_OPTIONS _JAVA_OPTIONS; do
    val="${!var:-}"
    [ -n "$val" ] || continue
    for a in $val; do
      is_weakening_prop "$a" && reject "R1" \
        "环境变量 $var 里藏着削弱门禁的开关：$a" \
        "这些变量会进 Maven 的 JVM（系统属性对插件可见）⇒ 参数白名单可以被整条绕过去。" \
        "清掉 $var 里的该开关再跑（要加速就用 focused + 明确的阶段，不要 skip 门禁）。"
    done
  done
  if [ -f "$ROOT/.mvn/maven.config" ]; then
    while IFS= read -r line; do
      for a in $line; do
        is_weakening_prop "$a" && reject "R1" \
          ".mvn/maven.config 里藏着削弱门禁的开关：$a" \
          "maven.config 是无参命令行之外唯一能给每次构建偷偷加参数的地方。" \
          "从 .mvn/maven.config 里删掉该行。"
      done
    done < "$ROOT/.mvn/maven.config"
  fi
}

reactor_modules() {
  sed -n '/<modules>/,/<\/modules>/p' "$ROOT/pom.xml" | grep -o '<module>[^<]*' | sed 's/<module>//'
}

declare -a FOCUSED_ARGS=()
FOCUSED_MODULE=""
FOCUSED_AM=1
FOCUSED_ACK=0

parse_focused_args() {
  (($# >= 1)) || reject "R3" "focused 必须点名模块" \
    "没有模块名就无从保证「上游来自工作树」（-am 要挂在具体模块上）。" \
    "tools/gate.sh focused <module> [maven args…]；可选模块：$(reactor_modules | tr '\n' ' ')"
  FOCUSED_MODULE="$1"; shift
  grep -qx -- "$FOCUSED_MODULE" <(reactor_modules) || reject "R3" \
    "模块不在 reactor 里：${FOCUSED_MODULE:-<空>}" \
    "不在 reactor 的模块跑不了 -pl，拼错模块名只会得到 Maven 的报错，或者（更坏）验到别的东西。" \
    "可选模块：$(reactor_modules | tr '\n' ' ')"
  local a want_am=1 am_explicit=0 ack=0
  FOCUSED_ARGS=()
  for a in "$@"; do
    case "$a" in
      --i-know-upstream-may-be-stale) want_am=0; ack=1; continue;;
      -am|--also-make) am_explicit=1; FOCUSED_ARGS+=("$a"); continue;;
      -pl|--projects) reject "R3" \
        "透传参数里不许出现 $a（模块由子命令位置参数给定）" \
        "允许 -pl 就能把 -am 架到别的模块上：验证目标被偷换，而输出看起来一模一样。" \
        "把模块写在子命令位置参数上：tools/gate.sh focused ${FOCUSED_MODULE} …";;
    esac
    FOCUSED_ARGS+=("$a")
  done
  reject_weakening "focused" "${FOCUSED_ARGS[@]+"${FOCUSED_ARGS[@]}"}"
  if [ "$am_explicit" = 1 ] && [ "$ack" = 1 ]; then
    reject "R3" "同时给了 -am 与 --i-know-upstream-may-be-stale（自相矛盾）" \
      "一个说「上游从工作树建」，一个说「我知道上游可能是旧的」，两者不可能同时成立。" \
      "二选一：要 -am 就别签字；要签字就别写 -am。"
  fi
  FOCUSED_AM=1
  [ "$want_am" = 1 ] || FOCUSED_AM=0
  FOCUSED_ACK="$ack"
}

# 目标里是否含「会重写 jar」的阶段
focused_touches_jar() {
  local a
  for a in "${FOCUSED_ARGS[@]+"${FOCUSED_ARGS[@]}"}"; do
    case "$a" in
      package|verify|install|deploy|shade:shade|shade:*|*':shade') return 0;;
    esac
  done
  return 1
}

focused_has_test_expr() {
  local a
  for a in "${FOCUSED_ARGS[@]+"${FOCUSED_ARGS[@]}"}"; do
    case "$a" in -Dtest=*) return 0;; esac
  done
  return 1
}

focused_has_prop() { # $1 = 属性名；透传参数里是否已显式给了它
  local a want="$1"
  for a in "${FOCUSED_ARGS[@]+"${FOCUSED_ARGS[@]}"}"; do
    case "$a" in -D"$want"=*|-D"$want") return 0;; esac
  done
  return 1
}

focused_runs_tests() {
  local a
  for a in "${FOCUSED_ARGS[@]+"${FOCUSED_ARGS[@]}"}"; do
    case "$a" in
      test|verify|package|install|deploy|integration-test|*':test') return 0;;
    esac
  done
  return 1
}

declare -a ADD_PATHS=()

parse_add_args() {
  (($# >= 1)) || reject "R4" "add 至少要逐条点名一个路径" \
    "git add 不带路径 = 变相 -A。" \
    "tools/gate.sh add <path> [path…]（一行一个路径，逐个点名）"
  local p abs
  ADD_PATHS=()
  for p in "$@"; do
    case "$p" in
      -A|--all|-a|--ignore-removal|--no-ignore-removal|-u|--update|--intent-to-add|-N|.|./|.//|..|../*|*/..|*/../*)
        reject "R4" "拒绝该路径/选项：$p" \
          "本仓实测三次（79f9bb0e / 68f56d24 / c5b0c203）：git add -A 把别的 agent 正在写的文件一起提交了 —— 多 agent 共用一棵工作树时，「全部」永远是错的。" \
          "逐条点名你要提交的文件：tools/gate.sh add <file1> <file2> …"
        ;;
    esac
    case "$p" in
      -*) reject "R4" "不接受任何以 - 开头的参数：$p" \
        "git 的选项（-A/-u/-f/--ignore-removal…）都会扩大或改变入列范围，白名单只放行裸路径。" \
        "写成裸路径；要提交删除的文件，直接点名那个已跟踪的路径（git add <path> 一样记录删除）。";;
    esac
    case "$p" in
      *'*'*|*'?'*|*'['*) reject "R4" "不接受通配符：$p" \
        "通配符扫的是「此刻恰好匹配到的文件」，包含别的 agent 刚写出来的在飞文件 —— 和 -A 是同一类错。" \
        "先把匹配结果列出来看一遍，再把要提交的路径逐条写全。";;
    esac
    case "$p" in
      :*) reject "R4" "不接受 pathspec magic：$p" \
        ":(glob)/:(exclude) 之类可以隐式扩大或排除范围，白名单不解析它。" \
        "用裸路径。";;
    esac
    # ★ 相对路径一律按**仓根**解析（与 `git -C "$ROOT"` 的口径一致）：这样从任何 cwd 调用
    #   `tools/gate.sh add src/Foo.java` 指的都是本仓的那个文件，而不是"当前目录恰好也在本仓"。
    case "$p" in
      /*) abs="$(realpath -m -- "$p" 2>/dev/null || true)";;
      *) abs="$(realpath -m -- "$ROOT/$p" 2>/dev/null || true)";;
    esac
    if [ -z "$abs" ]; then
      reject "R4" "路径无法解析：$p" "解析不了就无法确认它在本仓之内。" "用仓内相对路径或本仓内的绝对路径。"
    fi
    case "$abs" in
      "$ROOT") reject "R4" "该路径等价于仓根（$p ⇒ $abs）：这就是变相的 -A" \
        "仓根入列 = 整棵树入列。" "逐条点名文件。";;
      "$ROOT"/*) ;;
      *) reject "R4" "路径在本仓之外：$p ⇒ $abs" \
        "本仓门禁只对本仓负责；仓外路径不可能出现在本仓提交里（写错多半是拼错了）。" \
        "用本仓内的路径（仓根：$ROOT）。";;
    esac
    if [ ! -e "$abs" ] && ! git -C "$ROOT" ls-files --error-unmatch -- "$p" >/dev/null 2>&1; then
      reject "R4" "路径不存在、也不是已跟踪文件：$p" \
        "拼错的路径在 git add 里多半表现为「什么都没加」，然后你以为提交了。" \
        "核对路径拼写（ls $p）。"
    fi
    ADD_PATHS+=("$p")
  done
}

# ---------------------------------------------------------------------------
# 证据解析
# ---------------------------------------------------------------------------
# 汇总给定 surefire 报告：输出 "tests errors failures reports"
surefire_sum() {
  if (($# == 0)); then
    printf '0 0 0 0\n'
    return 0
  fi
  awk '
    /<testsuite / {
      if (match($0, /tests="[0-9]+"/))    { t += substr($0, RSTART+7,  RLENGTH-8)  }
      if (match($0, /errors="[0-9]+"/))   { e += substr($0, RSTART+8,  RLENGTH-9)  }
      if (match($0, /failures="[0-9]+"/)) { f += substr($0, RSTART+10, RLENGTH-11) }
      n++
    }
    END { printf "%d %d %d %d\n", t, e, f, n }
  ' "$@"
}

repo_surefire_reports() {
  find "$ROOT"/simos-*/target/surefire-reports -maxdepth 1 -name 'TEST-*.xml' 2>/dev/null
}

guard_class_names() {
  printf '%s\n' "$GUARDS_TABLE" | awk '{print $2}' | sed 's/.*\.//' | paste -sd, -
}

# 正向断言：每个护栏类的报告必须存在、tests>0、0 红。
assert_guard_reports() {
  local kind="$1" mod fqcn f counts t e fl rc=0
  while read -r mod fqcn; do
    [ -n "${mod:-}" ] || continue
    f="$ROOT/$mod/target/surefire-reports/TEST-$fqcn.xml"
    if [ ! -f "$f" ]; then
      bad "[$kind] 护栏 $fqcn（$mod）**没有 surefire 报告** ⇒ 它根本没跑（拼错 -Dtest / 被 skip / 类被改名）"
      rc=1
      continue
    fi
    counts="$(surefire_sum "$f")"
    read -r t e fl _ <<< "$counts"
    if [ "${t:-0}" -gt 0 ] && [ "${e:-1}" = 0 ] && [ "${fl:-1}" = 0 ]; then
      ok "[$kind] 护栏 $fqcn（$mod）：tests=$t failures=0 errors=0"
    else
      bad "[$kind] 护栏 $fqcn（$mod）：tests=$t errors=$e failures=$fl"
      rc=1
    fi
  done <<< "$GUARDS_TABLE"
  return "$rc"
}

# 护栏源文件必须还在（删除/改名 ⇒ 门禁红，不许「清单过期」）
claim_guard_sources() {
  local kind="$1" mod fqcn p rc=0
  while read -r mod fqcn; do
    [ -n "${mod:-}" ] || continue
    p="$ROOT/$mod/src/test/java/$(printf '%s' "$fqcn" | tr '.' '/').java"
    if [ -f "$p" ]; then
      ok "[$kind] 护栏源码在位：${p#$ROOT/}"
    else
      bad "[$kind] 护栏源码不见了：${p#$ROOT/}（改名/删除 ⇒ 门禁清单已过期，必须显式改 GUARDS_TABLE 并说明为什么）"
      rc=1
    fi
  done <<< "$GUARDS_TABLE"
  return "$rc"
}

# 构建日志里必须出现的「真的执行了」的证据（正向断言，不靠黑名单）
assert_log_evidence() {
  local kind="$1" log="$2" pat rc=0
  local -a pats=(
    '--- spotless:.*:check \(spotless-check\) @ '
    '--- checkstyle:.*:check \(checkstyle-check\) @ '
    '--- spotbugs:.*:check \(spotbugs-check\) @ simos-app '
    '--- exec:.*:exec \(frontend-unit-tests\) @ simos-app '
    '--- shade:.*:shade \(shade-executable\) @ simos-app '
    '--- enforcer:.*:enforce \(enforce-module-boundaries\) @ simos-util '
  )
  for pat in "${pats[@]}"; do
    if grep -qE -- "$pat" "$log"; then
      ok "[$kind] 插件真的跑了：$pat"
    else
      bad "[$kind] 构建日志里缺这条执行证据：$pat  ⇒ 对应门禁被跳过/根本没执行"
      rc=1
    fi
  done
  if grep -q '\[frontend-gate\] OK tests=' "$log"; then
    ok "[$kind] 前端门禁真的跑了：$(grep -o '\[frontend-gate\] OK tests=[0-9]* pass=[0-9]* fail=[0-9]*' "$log" | head -1)"
  else
    bad "[$kind] 构建日志里没有 [frontend-gate] OK … ⇒ 前端门禁没跑（-Dexec.skip 一类）"
    rc=1
  fi
  local jar
  jar="$(ls -1 "$ROOT"/simos-app/target/simos-app-*-shaded.jar 2>/dev/null | head -1)"
  if [ -n "$jar" ]; then
    ok "[$kind] shaded jar 真的打了：${jar#$ROOT/}（$(stat -c%s "$jar") 字节）"
  else
    bad "[$kind] simos-app/target 下没有 *-shaded.jar ⇒ shade 没跑"
    rc=1
  fi
  return "$rc"
}

# 全仓 surefire 合计必须 tests>0 且 0 红（clean 之后报告全是本次写的 ⇒ 任何 skip 都表现为「零报告」）
assert_surefire_totals() {
  local kind="$1" t e fl n f
  local -a files=()
  while IFS= read -r f; do
    [ -n "$f" ] && files+=("$f")
  done < <(repo_surefire_reports)
  read -r t e fl n <<< "$(surefire_sum "${files[@]+"${files[@]}"}")"
  if [ "${t:-0}" -gt 0 ] && [ "${e:-1}" = 0 ] && [ "${fl:-1}" = 0 ]; then
    ok "[$kind] 全仓 surefire 合计：tests=$t failures=0 errors=0（报告 $n 个）"
    return 0
  fi
  bad "[$kind] 全仓 surefire 合计：tests=${t:-0} errors=${e:-?} failures=${fl:-?}（报告 ${n:-0} 个）"
  bad "[$kind]   ⇒ tests=0 说明测试根本没跑（skip）；有 errors/failures 说明门禁没绿"
  return 1
}

# ---------------------------------------------------------------------------
# Maven 执行（一律走 mvn-lock.sh；不加 -q；rc 取 PIPESTATUS[0]）
# ---------------------------------------------------------------------------
run_mvn_logged() { # $1 = 日志路径；其余 = 传给 mvn-lock.sh 的参数
  local logf="$1"; shift
  log "运行：tools/mvn-lock.sh $*   （完整日志：$logf）"
  "$LOCK" "$@" 2>&1 | tee "$logf"
  return "${PIPESTATUS[0]}"
}

lock_state() {
  if [ ! -d "$LOCK_DIR" ]; then
    echo free
    return
  fi
  local holder
  holder="$(cat "$LOCK_DIR/pid" 2>/dev/null || echo '')"
  if [ -n "$holder" ] && kill -0 "$holder" 2>/dev/null; then
    echo "held:$holder"
  else
    echo "stale:$holder"
  fi
}

# ---------------------------------------------------------------------------
# 子命令：pre
# ---------------------------------------------------------------------------
cmd_pre() {
  parse_zero_args pre "$@"
  hr
  log "跑前自检（pre）—— 仓根：$ROOT"
  log "HEAD：$(git -C "$ROOT" log --oneline -1 2>/dev/null)  分支：$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null)"
  hr
  log "① 本仓服务（只在命令行含本仓路径 或 cwd 在本仓之下时算数 —— 隔壁项目不参与）"
  local svc_rc=0
  print_service_report 0 || svc_rc=1
  local ports
  ports="$(port_probe)"
  if [ -n "$ports" ]; then
    while IFS=$'\t' read -r p code; do
      [ -n "${p:-}" ] || continue
      warn "端口 $p 有 HTTP 响应（$code）—— 仅提示：可能来自别的项目（用户 2026-10-23：隔壁项目不算本仓），本门禁不据此判红"
    done <<< "$ports"
  fi
  hr
  log "② Maven / 锁"
  local mp mpn ls
  mp="$(maven_procs)"
  ls="$(lock_state)"
  if [ -n "$mp" ]; then
    mpn="$(printf '%s\n' "$mp" | wc -l)"
    warn "本仓有 $mpn 个 Maven/测试 JVM 在跑（mvn-lock.sh 会让你排队等锁；AGENTS §一.1 的正解是「等」）："
    printf '%s\n' "$mp" | print_proc_table >&2
  else
    ok "本仓没有 Maven/测试 JVM 在跑"
  fi
  case "$ls" in
    free) ok ".mvn-lock.d 不存在（锁空闲）";;
    held:*) warn ".mvn-lock.d 存在且持有者 pid=${ls#held:} 仍活着 ⇒ 另一个 Maven 正在跑";;
    stale:*) warn ".mvn-lock.d 是**陈旧锁**（持有者 pid=${ls#stale:} 已不在）；mvn-lock.sh 下次运行会自动清理，无需手工干预";;
  esac
  hr
  log "③ 工作树"
  local dirty dn
  dirty="$(git -C "$ROOT" status --porcelain)"
  if [ -z "$dirty" ]; then
    ok "工作树干净"
  else
    dn="$(printf '%s\n' "$dirty" | wc -l)"
    warn "工作树脏：$dn 条（多 agent 共用一棵树时属正常；提交前用 tools/gate.sh add 逐条点名）"
    printf '%s\n' "$dirty" | head -40 | sed 's/^/        /' >&2
    [ "$dn" -gt 40 ] && warn "  …（截断，完整看 git status --porcelain）"
  fi
  local recent
  recent="$(find "$ROOT"/simos-*/src -type f -newermt '-5 minutes' 2>/dev/null | head -20)"
  if [ -n "$recent" ]; then
    warn "最近 5 分钟被改过的源文件（可能是别的 agent 的在飞中间态，别接手、别扫进提交 —— AGENTS §一.2）："
    printf '%s\n' "$recent" | sed "s|$ROOT/|        |" >&2
  else
    ok "最近 5 分钟没有源文件被改动（没有明显的在飞中间态）"
  fi
  hr
  if [ "$svc_rc" = 0 ]; then
    ok "pre 结论：PASS —— 可以跑 tools/gate.sh full / guards / focused"
    return 0
  fi
  bad "pre 结论：FAIL —— 先按上面 R5 的「正确用法」处理，再跑 full（full 自己也会再拦一次）"
  return 1
}

# ---------------------------------------------------------------------------
# 子命令：full
# ---------------------------------------------------------------------------
parse_full_args() { # 纯校验，供 cmd_full 与 selftest 共用（校验不过 ⇒ exit 2，绝不会起 Maven）
  # 先报"削弱门禁"（R1 的信息量最大），再报"不接受任何参数"
  reject_weakening "full" "$@"
  parse_zero_args full "$@"
  check_env_not_weakening
}

cmd_full() {
  parse_full_args "$@"
  hr
  log "★ full = 完整验收（这是**唯一可信的「全绿」**）"
  log "  ① tools/mvn-lock.sh clean verify —— 不带任何 skip：test + 前端门禁 + spotless + checkstyle + spotbugs + shade"
  log "  ② tools/mvn-lock.sh -DskipTests validate —— enforcer 模块边界 + checkstyle（validate 不跑测试）"
  log "  为什么：本仓两次栽在「规则写在文档里、没有任何机制强制」上 —— -Dexec.skip 停掉整道前端门禁（GUI 全坏活 5 个提交）、"
  log "          架构轮只跑 -DskipTests package spotbugs:check ⇒ 没有机制构造真 Shell ⇒ 组合根装配抛错、GUI 5 个提交起不来。"
  hr
  require_no_at_risk_service "full 会跑 clean verify（package/shade 会就地重写 simos-app/target 下的产物）"

  local log1 log2 rc1 rc2 t0 t1 rc=0
  t0="$(date +%s)"
  log1="$(mktemp /tmp/simos-gate-full-verify-XXXXXX.log)"
  log2="$(mktemp /tmp/simos-gate-full-validate-XXXXXX.log)"

  log "步骤 ①/2：clean verify"
  run_mvn_logged "$log1" clean verify
  rc1=$?
  if [ "$rc1" != 0 ]; then
    bad "clean verify 失败：rc=$rc1（日志 $log1）"
    bad "  末尾 40 行："
    tail -40 "$log1" >&2
    exit 1
  fi
  ok "clean verify rc=0"

  log "步骤 ②/2：-DskipTests validate（这里的 -DskipTests 是门禁自己的固定动作：validate 阶段本来就不跑测试，不是绕过）"
  run_mvn_logged "$log2" -DskipTests validate
  rc2=$?
  if [ "$rc2" != 0 ]; then
    bad "-DskipTests validate 失败：rc=$rc2（日志 $log2）"
    tail -40 "$log2" >&2
    exit 1
  fi
  ok "-DskipTests validate rc=0"

  hr
  log "验收证据（正向断言：不看你说了什么，看构建里发生了什么）"
  assert_log_evidence "full" "$log1" || rc=1
  assert_surefire_totals "full" || rc=1
  assert_guard_reports "full" || rc=1
  grep -q 'BUILD SUCCESS' "$log1" && ok "[full] verify 日志含 BUILD SUCCESS" || { bad "[full] verify 日志不含 BUILD SUCCESS"; rc=1; }
  grep -q 'BUILD SUCCESS' "$log2" && ok "[full] validate 日志含 BUILD SUCCESS" || { bad "[full] validate 日志不含 BUILD SUCCESS"; rc=1; }

  t1="$(date +%s)"
  hr
  if [ "$rc" != 0 ]; then
    bad "full 结论：FAIL —— 构建 rc=0，但验收证据不足（见上）；日志：$log1"
    return 1
  fi
  ok "full 结论：PASS —— clean verify rc=0 + validate rc=0 + 证据齐（耗时 $((t1 - t0))s）"
  log "日志留存：$log1 、$log2"
  return 0
}

# ---------------------------------------------------------------------------
# 子命令：guards
# ---------------------------------------------------------------------------
parse_guards_args() {
  reject_weakening "guards" "$@"
  parse_zero_args guards "$@"
  check_env_not_weakening
}

cmd_guards() {
  parse_guards_args "$@"
  hr
  log "★ guards = 7 条关键护栏（含**真的构造组合根**的那一条）"
  log "  为什么：架构轮只跑 -DskipTests package spotbugs:check ⇒ 没有任何机制构造真 Shell ⇒"
  log "          SimosSdReportsTool.roles()={GM} 却注册在 DM 桶 ⇒ Shell 构造当场抛 ⇒ GUI 5 个提交起不来。"
  log "  口径：-pl simos-app -am ⇒ 组合根与它的**全部上游都从工作树**构建（不是 ~/.m2 的旧 jar）"
  hr
  claim_guard_sources "guards" || { bad "护栏清单与源码不一致 ⇒ 门禁自身已腐烂，拒绝执行"; exit 1; }
  print_service_report 1 || true

  local logf rc vrc=0
  logf="$(mktemp /tmp/simos-gate-guards-XXXXXX.log)"
  log "运行：tools/mvn-lock.sh -pl simos-app -am -Dtest=<7 条护栏> -Dsurefire.failIfNoSpecifiedTests=false test"
  log "  ★ 那个 failIfNoSpecifiedTests=false 不是放松：上游模块里没有这 7 个类，surefire 会因「没找到点名的测试」报错；"
  log "    放行它之后，本脚本**逐类断言** 7 个报告真的存在且 tests>0 —— 比 surefire 自己的检查更严。"
  run_mvn_logged "$logf" -pl simos-app -am \
    -Dtest="$(guard_class_names)" \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
  rc=$?
  if [ "$rc" != 0 ]; then
    bad "guards Maven 运行失败：rc=$rc（日志 $logf）"
    tail -40 "$logf" >&2
    exit 1
  fi
  hr
  assert_guard_reports "guards" || vrc=1
  grep -q '\[frontend-gate\] OK tests=' "$logf" \
    && ok "[guards] 顺带确认前端门禁也跑了（simos-app 的 test 阶段带着它）" \
    || warn "[guards] 未见前端门禁标记——full 会单独断言它"
  grep -qE -- '--- surefire:.*:test \(default-test\) @ simos-app ' "$logf" \
    && ok "[guards] surefire 真的在 simos-app 跑过" \
    || { bad "[guards] simos-app 的 surefire 没跑"; vrc=1; }
  hr
  if [ "$vrc" != 0 ]; then
    bad "guards 结论：FAIL（见上）；日志：$logf"
    return 1
  fi
  ok "guards 结论：PASS —— 7 条护栏逐条真的跑了且 0 红；日志：$logf"
  return 0
}

# ---------------------------------------------------------------------------
# 子命令：focused
# ---------------------------------------------------------------------------
cmd_focused() {
  parse_focused_args "$@"
  check_env_not_weakening
  hr
  log "★ focused ${FOCUSED_MODULE} —— 聚焦跑"
  local -a mvn_args=(-pl "$FOCUSED_MODULE")
  if [ "$FOCUSED_AM" = 1 ]; then
    mvn_args+=(-am)
    log "  R3：已加 -am ⇒ 上游在同一个 reactor 里从**工作树**构建（不是 ~/.m2 里的旧 jar）"
  else
    {
      warn "★★ 你显式签字 --i-know-upstream-may-be-stale：本次**没有** -am"
      warn "   上游模块将从 ~/.m2 解析已安装的构件 —— 工作树里未 install 的上游改动**不在被验的字节码里**"
      warn "   （规划 §34.3 实测：mutant 放进 simos-ga/src/main，-pl simos-app -Dtest=… 仍然是绿的）"
      warn "   ⇒ 本次结果**不能**当作「这个模块没问题」的证据。"
    }
  fi
  mvn_args+=("${FOCUSED_ARGS[@]+"${FOCUSED_ARGS[@]}"}")
  if [ "${#FOCUSED_ARGS[@]}" = 0 ]; then
    mvn_args+=(test)
    log "  未给阶段 ⇒ 默认追加 test（不打包、不动 jar）"
  fi
  # ★ 实测踩到的坑：`-Dtest=<表达式>` + `-am` 时，上游模块里没有点名的测试，
  #   surefire 会以 `No tests matching pattern "…" were executed!` 直接判红（不是"跳过"）。
  #   这里自动补 false —— **牙齿由跑完的"新报告数 > 0"断言提供**（比 surefire 自己的检查更严）：
  #   表达式拼错 / 测试被 skip ⇒ 新报告 0 个 ⇒ 本门禁照样判红。用红掩盖不是更安全。
  if focused_has_test_expr && ! focused_has_prop 'surefire.failIfNoSpecifiedTests'; then
    mvn_args+=(-Dsurefire.failIfNoSpecifiedTests=false)
    log "  ★ 检测到 -Dtest=<表达式> + -am ⇒ 自动补 -Dsurefire.failIfNoSpecifiedTests=false（否则上游模块会因"
    log "    「没找到点名的测试」而红）。这不是放松：跑完会断言 $FOCUSED_MODULE 里**真的有**测试跑了（新报告数 > 0）。"
  fi
  if focused_touches_jar; then
    require_no_at_risk_service "focused 的目标里含 package/verify/install/deploy/shade（会就地重写 simos-app/target 的产物）"
  else
    print_service_report 1 || true
  fi
  local logf t0 t1 rc n stamp
  logf="$(mktemp /tmp/simos-gate-focused-XXXXXX.log)"
  stamp="$(mktemp /tmp/simos-gate-focused-stamp-XXXXXX)"
  t0="$(date +%s)"
  run_mvn_logged "$logf" "${mvn_args[@]}"
  rc=$?
  t1="$(date +%s)"
  if [ "$rc" != 0 ]; then
    rm -f "$stamp"
    bad "focused 失败：rc=$rc（日志 $logf）"
    tail -40 "$logf" >&2
    exit 1
  fi
  ok "focused rc=0（耗时 $((t1 - t0))s）"
  # 假绿拦截：带了 -Dtest=<表达式> 却没跑出任何报告 ⇒ 点名了一条根本不存在的测试
  if focused_has_test_expr && focused_runs_tests; then
    n="$(find "$ROOT/$FOCUSED_MODULE/target/surefire-reports" -maxdepth 1 -name 'TEST-*.xml' -newer "$stamp" 2>/dev/null | wc -l)"
    if [ "$n" -gt 0 ]; then
      ok "你点名的测试真的跑了（$FOCUSED_MODULE 本次新写报告 $n 个）"
    else
      rm -f "$stamp"
      bad "你带了 -Dtest=<表达式>，但 $FOCUSED_MODULE 里**没有任何测试真的跑**（本次新报告 0 个）"
      bad "  ⇒ 这是「零测试通过」式的假绿（surefire 在没匹配到时会静默跳过）：表达式拼错了，或被 skip 了。"
      bad "  日志：$logf"
      exit 1
    fi
  fi
  rm -f "$stamp"
  log "日志留存：$logf"
  return 0
}

# ---------------------------------------------------------------------------
# 子命令：add
# ---------------------------------------------------------------------------
cmd_add() {
  parse_add_args "$@"
  print_service_report 1 || true
  local b a newly p abs
  b="$(mktemp /tmp/simos-gate-idx-before-XXXXXX)"
  a="$(mktemp /tmp/simos-gate-idx-after-XXXXXX)"
  git -C "$ROOT" diff --cached --name-only > "$b"
  log "git add -- ${ADD_PATHS[*]}"
  if ! git -C "$ROOT" add -- "${ADD_PATHS[@]}"; then
    bad "git add 失败"
    rm -f "$b" "$a"
    exit 1
  fi
  git -C "$ROOT" diff --cached --name-only > "$a"
  hr
  log "本次 add **新入列**的文件（逐条核对：有没有别的 agent 的在飞文件？）："
  newly="$(comm -13 "$b" "$a")"
  if [ -z "$newly" ]; then
    log "  （无 —— 这些路径本来就已在索引里）"
  else
    printf '%s\n' "$newly" | sed 's/^/        + /'
    while IFS= read -r p; do
      [ -n "$p" ] || continue
      abs="$ROOT/$p"
      if [ -f "$abs" ] && [ -n "$(find "$abs" -newermt '-2 minutes' 2>/dev/null)" ]; then
        warn "刚被改过（2 分钟内）：$p —— 如果这是别的 agent 的在飞文件，把它撤出索引：git restore --staged -- $p"
      fi
    done <<< "$newly"
  fi
  hr
  log "即将提交的清单（git status --porcelain）："
  git -C "$ROOT" status --porcelain | sed 's/^/        /'
  hr
  log "已入索引（git diff --cached --name-status）："
  git -C "$ROOT" diff --cached --name-status | sed 's/^/        /'
  hr
  log "★ 提交前请自己读一遍上面两份清单；本脚本只保证「不是 -A 扫进来的」，扫进来的是不是你想要，只有你知道。"
  rm -f "$b" "$a"
  return 0
}

# ---------------------------------------------------------------------------
# 子命令：selftest（本脚本自己的红/绿对照，不需要 Maven）
# ---------------------------------------------------------------------------
cmd_selftest() {
  parse_zero_args selftest "$@"
  local pass=0 fail=0

  st_ok() { pass=$((pass + 1)); printf '[selftest] ✓ %s\n' "$*"; }
  st_bad() { fail=$((fail + 1)); printf '[selftest] ✗ %s\n' "$*" >&2; }

  # 期望被拒：在**命令替换的子 shell** 里跑纯校验函数 ⇒ 即使校验被改坏，也绝不会起 Maven
  expect_reject() { # $1=说明 $2=期望命中的规则号 $3=函数名 其余=参数
    local desc="$1" rule="$2" fn="$3"; shift 3
    local out rc
    out="$("$fn" "$@" 2>&1)"; rc=$?
    if [ "$rc" = 2 ] && printf '%s' "$out" | grep -q "规则 $rule"; then
      st_ok "被拒（规则 $rule）：$desc"
    else
      st_bad "**没被拒**：$desc（rc=$rc，期望 rc=2 + 规则 $rule）"
    fi
  }
  expect_accept() { # $1=说明 $2=函数名 其余=参数
    local desc="$1" fn="$2"; shift 2
    local out rc
    out="$("$fn" "$@" 2>&1)"; rc=$?
    if [ "$rc" = 0 ]; then st_ok "放行：$desc"; else st_bad "**被误拒**：$desc（rc=$rc）"; fi
  }
  expect_err_contains() { # $1=说明 $2=期望子串 $3=函数名 其余=参数
    local desc="$1" want="$2" fn="$3"; shift 3
    local out rc
    out="$("$fn" "$@" 2>&1)"; rc=$?
    if [ "$rc" = 2 ] && printf '%s' "$out" | grep -qF -- "$want"; then
      st_ok "被拒（$want）：$desc"
    else
      st_bad "**没被拒或理由不对**：$desc（rc=$rc，期望含「$want」）"
    fi
  }

  printf '[selftest] 一、参数校验的红/绿对照（纯函数，零 Maven）\n'
  expect_reject "full 带 -Dexec.skip=true" R1 parse_full_args -Dexec.skip=true
  expect_reject "full 带 -DskipTests" R1 parse_full_args -DskipTests
  expect_reject "full 带 -q" R1 parse_full_args -q
  expect_reject "full 带无关参数（不接受任何参数）" 用法 parse_full_args --whatever
  expect_reject "guards 带 -DskipTests" R1 parse_guards_args -DskipTests
  expect_reject "guards 带无关参数" 用法 parse_guards_args extra
  expect_reject "focused 少给模块" R3 parse_focused_args
  expect_reject "focused 模块名拼错" R3 parse_focused_args simos-nope test
  expect_reject "focused 同时 -am 与签字（自相矛盾）" R3 parse_focused_args simos-app -am --i-know-upstream-may-be-stale test
  expect_reject "focused 带 -Dexec.skip=true" R1 parse_focused_args simos-app -Dexec.skip=true test
  expect_reject "focused 带 -DskipTests" R1 parse_focused_args simos-app -DskipTests test
  expect_reject "focused 带 -Dmaven.test.skip=true" R1 parse_focused_args simos-app -Dmaven.test.skip=true test
  expect_reject "focused 带 -Dcheckstyle.skip=true" R1 parse_focused_args simos-app -Dcheckstyle.skip=true test
  expect_reject "focused 带 -Dspotbugs.skip=true" R1 parse_focused_args simos-app -Dspotbugs.skip=true test
  expect_reject "focused 带 -Dspotless.check.skip=true" R1 parse_focused_args simos-app -Dspotless.check.skip=true test
  expect_reject "focused 带 -Denforcer.skip=true" R1 parse_focused_args simos-app -Denforcer.skip=true test
  expect_reject "focused 带 -Dmaven.test.failure.ignore=true" R1 parse_focused_args simos-app -Dmaven.test.failure.ignore=true test
  expect_reject "focused 带 -DfailIfNoTests=false" R1 parse_focused_args simos-app -DfailIfNoTests=false test
  expect_reject "focused 带 -q" R1 parse_focused_args simos-app -q test
  expect_reject "focused 想偷换 -pl" R3 parse_focused_args simos-app -pl simos-map test
  expect_reject "focused 模块名不在 reactor" R3 parse_focused_args ../etc test
  expect_accept "focused 默认用法（自动 -am）" parse_focused_args simos-app -Dtest=ShellAssemblySmokeTest test
  expect_accept "focused 显式签字去 -am" parse_focused_args simos-app --i-know-upstream-may-be-stale test
  expect_accept "focused -DskipTests=false 是反向 skip（放行）" parse_focused_args simos-app -DskipTests=false test
  expect_reject "add -A" R4 parse_add_args -A
  expect_reject "add --all" R4 parse_add_args --all
  expect_reject "add -a" R4 parse_add_args -a
  expect_reject "add ." R4 parse_add_args .
  expect_reject "add ./" R4 parse_add_args ./
  expect_reject "add --ignore-removal" R4 parse_add_args --ignore-removal
  expect_reject "add 不带路径" R4 parse_add_args
  expect_reject "add 通配符" R4 parse_add_args 'tools/*.sh'
  expect_reject "add pathspec magic" R4 parse_add_args ':(glob)tools/**'
  expect_reject "add 仓外路径" R4 parse_add_args /etc/passwd
  expect_reject "add 仓根（变相 -A）" R4 parse_add_args tools/..
  expect_reject "add 不存在的路径" R4 parse_add_args tools/definitely-not-here.sh
  expect_accept "add 逐条点名（已存在的文件）" parse_add_args tools/gate.sh
  if ( parse_focused_args simos-app -Dtest=X test >/dev/null 2>&1 \
       && focused_has_test_expr && ! focused_has_prop 'surefire.failIfNoSpecifiedTests' ); then
    st_ok "-Dtest=<表达式> 被识别 ⇒ focused 会据此自动补 failIfNoSpecifiedTests=false（补偿断言见下）"
  else
    st_bad "-Dtest=<表达式> 识别失败（自动补开关的判据失效）"
  fi

  printf '\n[selftest] 二、进程识别口径（真起桩进程，真 pgrep）\n'
  local stub1 stub2 stub3 stub4 stub5 stub6
  (cd "$ROOT" && exec -a "java -jar $ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar" sleep 30) &
  stub1=$!
  (cd "$ROOT" && exec -a 'java -jar /tmp/simos-shaded-ABC123.jar --store /tmp/x' sleep 30) &
  stub2=$!
  (cd /tmp && exec -a 'java -jar /tmp/otherproj/target/other.jar com.other.Main' sleep 30) &
  stub3=$!
  (cd /tmp && exec -a 'java org.codehaus.plexus.classworlds.launcher.Launcher' sleep 30) &
  stub4=$!
  (cd "$ROOT" && exec -a 'java org.codehaus.plexus.classworlds.launcher.Launcher -f pom.xml' sleep 30) &
  stub5=$!
  sleep 1
  # ★ 先把清单收进变量再判（列表函数恒 rc=0；这里再用 grep 而不是 awk 管道，避免任何 pipefail 误判）
  local atl otherl mvl
  atl="$(service_procs_at_risk)"
  otherl="$(service_procs_other)"
  mvl="$(maven_procs)"
  has_pid() { printf '%s\n' "$1" | cut -f1 | grep -qx -- "$2"; }
  if has_pid "$atl" "$stub1"; then
    st_ok "服务识别：直接吃 simos-app/target 的进程 = **硬风险**"
  else
    st_bad "服务识别：硬风险进程没被识别"
  fi
  if has_pid "$otherl" "$stub2"; then
    st_ok "服务识别：/tmp/simos-shaded-*.jar 快照进程只报不拦"
  else
    st_bad "服务识别：快照进程没被识别"
  fi
  if has_pid "$atl" "$stub2"; then
    st_bad "服务识别：快照进程被误判为硬风险"
  else
    st_ok "服务识别：快照进程没被误判为硬风险"
  fi
  if has_pid "$atl" "$stub3" || has_pid "$otherl" "$stub3"; then
    st_bad "服务识别：隔壁项目的进程被误算成本仓服务"
  else
    st_ok "服务识别：隔壁项目的进程不算本仓（作用域口径）"
  fi
  if has_pid "$mvl" "$stub5"; then
    st_ok "Maven 识别：cwd 在本仓的 Maven 被识别"
  else
    st_bad "Maven 识别：本仓 Maven 没被识别"
  fi
  if has_pid "$mvl" "$stub4"; then
    st_bad "Maven 识别：隔壁项目的 Maven 被误算进本仓"
  else
    st_ok "Maven 识别：隔壁项目的 Maven 不算本仓（用户 2026-10-23 更正）"
  fi
  # 反向自证：把硬风险桩的路径改成 /tmp（等价于"别的 checkout"），它就必须**不再**被判硬风险
  (cd /tmp && exec -a 'java -jar /tmp/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar' sleep 30) &
  local stub6=$!
  sleep 0.5
  if has_pid "$(service_procs_at_risk)" "$stub6"; then
    st_bad "服务识别：只按文件名匹配就可能误判别的 checkout（口径退化）"
  else
    st_ok "服务识别：同名 jar 但不在本仓路径/cwd 下 ⇒ 不判硬风险（作用域真的在起作用）"
  fi
  kill "$stub1" "$stub2" "$stub3" "$stub4" "$stub5" "$stub6" 2>/dev/null || true

  printf '\n[selftest] 三、门禁自身的证据与清单\n'
  if claim_guard_sources "selftest" >/dev/null 2>&1; then
    st_ok "7 条护栏源码在位（GUARDS_TABLE 未腐烂）"
  else
    st_bad "护栏源码与 GUARDS_TABLE 不一致"
  fi
  if [ "$(guard_class_names)" = "ArchitectureGuardsTest,GaRoundTripTest,GovRoundTripTest,ArmyRoundTripTest,ShellAssemblySmokeTest" ]; then
    st_ok "护栏 -Dtest 表达式正确：$(guard_class_names)"
  else
    st_bad "护栏 -Dtest 表达式不对：$(guard_class_names)"
  fi
  local tmpd got
  tmpd="$(mktemp -d)"
  cat > "$tmpd/TEST-a.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="3.0.2" name="a" time="0.1" tests="7" errors="1" skipped="0" failures="2" flakes="0">
XML
  got="$(surefire_sum "$tmpd/TEST-a.xml")"
  if [ "$got" = "7 1 2 1" ]; then st_ok "surefire 报告解析（tests/errors/failures/报告数）"; else st_bad "surefire 解析不对：$got（期望 7 1 2 1）"; fi
  rm -rf "$tmpd"

  printf '\n[selftest] 四、add 端到端（隔离子仓，真 git 真 index）\n'
  local tmprepo
  tmprepo="$(mktemp -d /tmp/simos-gate-selftest-XXXXXX)/repo"
  mkdir -p "$tmprepo/tools" "$tmprepo/sub" "$tmprepo/.mvn"
  cp "$SELF" "$tmprepo/tools/gate.sh"
  cp "$LOCK" "$tmprepo/tools/mvn-lock.sh"
  chmod +x "$tmprepo/tools/gate.sh"
  ( cd "$tmprepo" && git init -q . )
  printf 'a\n' > "$tmprepo/keep.txt"
  printf 'b\n' > "$tmprepo/noise.txt"
  printf 'c\n' > "$tmprepo/sub/x.txt"
  if "$tmprepo/tools/gate.sh" add -A >/dev/null 2>&1; then st_bad "add -A 没被拒（隔离子仓）"; else st_ok "add -A 被拒（隔离子仓，真 git）"; fi
  if git -C "$tmprepo" status --porcelain | grep -q '^A '; then st_bad "add -A 之后索引里居然有东西"; else st_ok "add -A 之后索引干净（noise.txt 没被扫进去）"; fi
  if "$tmprepo/tools/gate.sh" add keep.txt >/dev/null 2>&1; then st_ok "add keep.txt 放行"; else st_bad "add keep.txt 被误拒"; fi
  if [ "$(git -C "$tmprepo" diff --cached --name-only)" = "keep.txt" ]; then st_ok "只入列 keep.txt（noise.txt 仍是未跟踪）"; else st_bad "入列清单不对：$(git -C "$tmprepo" diff --cached --name-only | tr '\n' ' ')"; fi
  if git -C "$tmprepo" status --porcelain | grep -q '^?? noise.txt'; then st_ok "noise.txt 未被扫进索引"; else st_bad "noise.txt 状态异常"; fi
  if "$tmprepo/tools/gate.sh" add sub >/dev/null 2>&1; then st_ok "add <目录> 放行（会列出里面全部条目供核对）"; else st_bad "add <目录> 被误拒"; fi
  if git -C "$tmprepo" diff --cached --name-only | grep -q '^sub/x.txt$'; then st_ok "目录参数逐条入列（sub/x.txt）"; else st_bad "目录参数没把内容加进来"; fi
  # .mvn/maven.config 与 MAVEN_OPTS 里藏 skip ⇒ full 被拒（隔离副本，隔离目录内）
  printf -- '-Dexec.skip=true\n' > "$tmprepo/.mvn/maven.config"
  expect_err_contains ".mvn/maven.config 里藏 skip 也被拒（白名单不可绕）" "规则 R1" "$tmprepo/tools/gate.sh" full
  rm -f "$tmprepo/.mvn/maven.config"
  expect_err_contains "MAVEN_OPTS 里藏 skip 也被拒" "规则 R1" env MAVEN_OPTS=-Dexec.skip=true "$tmprepo/tools/gate.sh" full
  rm -rf "$(dirname "$tmprepo")"

  hr
  printf '[selftest] 合计：pass=%d fail=%d\n' "$pass" "$fail"
  if [ "$fail" != 0 ]; then
    printf '[selftest] 结论：FAIL —— 门禁本身没牙（上面每条 ✗ 都是一条能被绕过的规则）\n' >&2
    return 1
  fi
  printf '[selftest] 结论：PASS —— 每条规则的拦截点都有自己的红/绿对照\n'
  return 0
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
usage() {
  sed -n '/^# 【子命令】/,/^# ====/p' "$SELF" | sed 's/^# \{0,1\}//' | sed '/^====*$/d'
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    pre) shift; cmd_pre "$@";;
    full) shift; cmd_full "$@";;
    focused) shift; cmd_focused "$@";;
    guards) shift; cmd_guards "$@";;
    add) shift; cmd_add "$@";;
    selftest) shift; cmd_selftest "$@";;
    ''|-h|--help|help) usage; exit 0;;
    *) usage >&2; exit 2;;
  esac
}

if [ ! -x "$LOCK" ]; then
  bad "缺 tools/mvn-lock.sh（本仓 Maven 串行化锁）—— 本门禁不绕过它，无法继续"
  exit 2
fi
main "$@"
