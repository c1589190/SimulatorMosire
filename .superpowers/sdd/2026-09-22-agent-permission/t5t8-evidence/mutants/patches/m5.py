# m5（T7）：决策人端口又监听上（把第二个 AgentToMcpServer 起回来）
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/Shell.java'
s=open(p).read()
old="""      mcpUp = true;
    } finally {"""
new="""      AgentToMcpServer decisionAgain =
          AgentToMcpServer.startHttp(
              config.bindAddress(),
              5717, // T4 时代的决策人口缺省
              config.mcpPath(),
              toolRegistry,
              "simos-shell-decision",
              MCP_SERVER_VERSION,
              gmCaller(),
              toolAuthorizer);
      Objects.requireNonNull(decisionAgain);
      mcpUp = true;
    } finally {"""
assert old in s
s=s.replace(old,new,1)
open(p,'w').write(s)
