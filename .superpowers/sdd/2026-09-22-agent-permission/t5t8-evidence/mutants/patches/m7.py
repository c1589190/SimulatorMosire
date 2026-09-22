# m7（T7）：决策渠道清单少一条（Http 掉队）——渠道数护栏的反方向
p='/home/cna/SimulatorMosire/simos-app/src/main/java/io/mosire/simos/app/Shell.java'
s=open(p).read()
old="""            new CliDecisionChannel(coreSimos, representableActors),
            new HttpDecisionChannel(coreSimos, representableActors));"""
new="""            new CliDecisionChannel(coreSimos, representableActors));"""
assert old in s
s=s.replace(old,new,1)
# Checkstyle 会因为 import 变成未使用而在 surefire 之前拦下（那样这一轮就是 VOID，不是 KILLED）——把 import 一起去掉
s=s.replace("import io.mosire.simos.app.sd.channel.HttpDecisionChannel;\n","")
open(p,'w').write(s)
