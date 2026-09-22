# m4（T6）：决策人白名单放回一条写工具（unit.RenameUnit）
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/access/DecisionCallerFactory.java'
s=open(p).read()
old="          IssueDirectiveTool.NAME,\n          SubmitVerdictTool.NAME);"
new="          IssueDirectiveTool.NAME,\n          SubmitVerdictTool.NAME,\n          io.mosire.simos.app.tools.write.UnitRenameTool.NAME);"
assert old in s
open(p,'w').write(s.replace(old,new,1))
