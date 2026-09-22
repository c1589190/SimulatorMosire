# m3（T6）：决策人的 resourceScopes 被 unlimited() 覆盖 —— 范围函数白算
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/access/DecisionCallerFactory.java'
s=open(p).read()
old="            .resourceScopes(gmAccessLimit == null ? computed : computed.narrowTo(gmAccessLimit))"
new="            .resourceScopes(ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, io.mosire.agentlib.permission.ResourceScope.unlimited()))"
assert old in s
s=s.replace(old,new,1)
if 'import io.mosire.simos.app.tools.ToolSupport;' not in s:
    s=s.replace('import io.mosire.simos.sd.model.DecisionMaker;','import io.mosire.simos.app.tools.ToolSupport;\nimport io.mosire.simos.sd.model.DecisionMaker;',1)
open(p,'w').write(s)
