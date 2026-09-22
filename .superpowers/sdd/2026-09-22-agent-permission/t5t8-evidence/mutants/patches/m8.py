# m8（T6）：范围不再"每次现算"（按决策人 id 缓存）——世界变了范围不变
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/access/DecisionCallerFactory.java'
s=open(p).read()
old="    ResourceScopeMap computed = scopeFunctions.scopesFor(dm, state, mapId);"
new="    ResourceScopeMap computed =\n        CACHE.computeIfAbsent(dm.id().value(), k -> scopeFunctions.scopesFor(dm, state, mapId));"
assert old in s
s=s.replace(old,new,1)
anchor="  private final DecisionScopeFunctions scopeFunctions;"
s=s.replace(anchor,"  private static final java.util.concurrent.ConcurrentHashMap<String, ResourceScopeMap> CACHE =\n      new java.util.concurrent.ConcurrentHashMap<>();\n\n"+anchor,1)
open(p,'w').write(s)
