# m2（T5）：四命名空间只表态 map 一个（其余三条又变成"不表态"）
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/Shell.java'
s=open(p).read()
old="""            ResourceScopeMap.of(
                Map.of(
                    ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.SOCIAL_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.UNIT_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.SD_NAMESPACE, ResourceScope.unlimited())))"""
new="""            ResourceScopeMap.of(
                Map.of(ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited())))"""
assert old in s
open(p,'w').write(s.replace(old,new,1))
