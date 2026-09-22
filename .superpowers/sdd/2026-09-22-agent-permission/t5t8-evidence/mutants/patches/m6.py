# m6（T8）：决策人桶放回一条 unit 域窄写（两处同源里的注册面那一处）
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java'
s=open(p).read()
old="""    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
  }"""
new="""    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
    built.add(new UnitRenameTool(core, initiator, mapId));
  }"""
assert old in s
open(p,'w').write(s.replace(old,new,1))
