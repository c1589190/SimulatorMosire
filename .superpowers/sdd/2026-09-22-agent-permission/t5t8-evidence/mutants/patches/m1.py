# m1（T5）：GM 组整体退回 unrestricted —— 资源维再次"不表态"（空图）
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/Shell.java'
s=open(p).read()
old="    return ToolContext.of(AccessToken.DEFAULT, gmPermissionSet(), AgentIdentity.external());"
new="    return ToolContext.of(\n        AccessToken.DEFAULT,\n        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),\n        AgentIdentity.external());"
assert old in s
open(p,'w').write(s.replace(old,new,1))
