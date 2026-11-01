# JointSwitchFixture

供 `proxy-switch` 场景使用的 Bungee 测试插件。控制台命令 `jointswitch <player> <server>` 调用原生 `ProxiedPlayer.connect(ServerInfo, Callback<Boolean>)`，拒绝玩家调用。

接口参考：[ProxiedPlayer](https://github.com/SpigotMC/BungeeCord/blob/master/api/src/main/java/net/md_5/bungee/api/connection/ProxiedPlayer.java)、[ConsoleCommandSender](https://github.com/SpigotMC/BungeeCord/blob/master/proxy/src/main/java/net/md_5/bungee/command/ConsoleCommandSender.java)。

验证路径标记为 `fixtureNativeAPI`，不验证上游 `/server` 命令或 Waterfall 的 `cmd_server` 模块。插件与 NovaChat 生产代码独立。

仅在 Bungee 的 `proxy-switch` 场景启动代理前安装。使用 [版本锁文件](../../../versions.lock.ps1) 指定的已下载 Waterfall fat jar；installer 校验 SHA-256，通过所给 JDK 的 `bin/javac` 和 `bin/jar` 编译打包，不下载依赖。

```powershell
. ./test/versions.lock.ps1
. ./test/joint/fixtures/bungee-switch/installer.ps1
$waterfall = Get-LockedServer -Name 'waterfall'
$fixtureJar = Install-JointBungeeSwitchFixture `
  -ServerJar (Join-Path $DistDir $waterfall.File) `
  -JdkHome $JdkHome -ServerDirectory $proxyDirectory
```

产物为 `<ServerDirectory>/plugins/JointSwitchFixture.jar`，元数据见 [bungee.yml](bungee.yml)，实现见 [JointSwitchFixture.java](src/com/nova/joint/fixture/JointSwitchFixture.java)。加载完成后输出 INFO marker：`JointSwitchFixture ready`。

主线程对 [proxy-switch 场景](../../scenarios/proxy-switch.ps1) 传入 `-SwitchMode callback`。`SwitchPlayer(username, server)` 回调向代理 stdin 写入 `jointswitch <username> <server>`，flush 后等待本次命令之后的新 INFO marker：`JointSwitchFixture switched <username> -> <server>`。只有原生 connect 的回调成功才输出此 marker。

切服失败输出 SEVERE marker：`JointSwitchFixture error <username> -> <server>: <reason>`。参数错误以 `JointSwitchFixture error:` 开头。控制台写入成功或旧日志中的同名 marker 都不能确认本次切服。

收到本次成功 marker 后，再从目标子服发送 tellraw 探针。真实判定仍须满足目标玩家的 tellraw 接收记录、往返切服和跨子服隔离断言；成功 marker 只用于驱动下一步。
