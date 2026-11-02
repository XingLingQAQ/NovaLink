# Bungee 切服驱动

`proxy-switch` 默认使用 `player-command`：安装官方 `cmd_server` 模块，由玩家执行 `/server`。历史 `callback` 路径通过本目录的 `JointSwitchFixture` 调用原生 connect API。命令驱动失败时直接失败，不回退 API。

PaperMC [Waterfall 1.21 build 615 元数据](https://fill.papermc.io/v3/projects/waterfall/versions/1.21/builds/615)提供同次发布的代理和模块。旧下载器仍使用已返回 410 的 v2 API，测试启动前需要显式安装模块。[版本锁](../../../versions.lock.ps1)记录官方地址和 SHA256：

| 制品 | SHA256 | 归档版本 |
| --- | --- | --- |
| `waterfall-1.21-615.jar` | `5EDA8BFD0691E5088701F87020C68964299586DF0FABA289A634122D282D598C` | `git:Waterfall-Bootstrap:26.1-R0.1-SNAPSHOT:4bc2b02:615` |
| `cmd_server-1.21-615.jar` | `D1C68F7C0469E61C83939A37ABAE08F8AD231FC5BB4C7F455065AF42F7950710` | `git:cmd_server:26.1-R0.1-SNAPSHOT:4bc2b02:615` |

文件名中的 `1.21` 是官方构建分组；归档内部的 Maven 版本为 `26.1-R0.1-SNAPSHOT`。模块和代理的 git/build 尾号一致。安装器同时校验哈希、代理 manifest 和模块 `plugin.yml`，复制原始模块，不编译替代 `/server` 实现。

主线程在代理启动前调用：

```powershell
. ./test/versions.lock.ps1
. ./test/joint/fixtures/bungee-switch/installer.ps1
if (-not (Invoke-FetchServer 'waterfall-cmd-server')) {
  throw 'Pinned upstream cmd_server download failed; player-command has no API fallback.'
}
$modulePin = Get-LockedServer -Name 'waterfall-cmd-server'
$moduleJar = Install-JointBungeeServerCommand `
  -ServerJar $jarPath `
  -ModuleJar (Join-Path $DistDir $modulePin.File) `
  -ServerDirectory $dir
```

`Invoke-FetchServer` 是 `run-joint.ps1` 的现有下载函数。独立下载可用 `powershell -NoProfile -File test/bin/fetch-server.ps1 -Name waterfall-cmd-server -DistDir <cache>`，使用固定版本模式。无需修改下载脚本。

安装器返回 `<ServerDirectory>/modules/cmd_server.jar`，并写入以下 `modules.yml`。代理启动时发现模块的版本已匹配，无需调用旧下载接口。请使用新的场景目录；安装器会拒绝已有的其他模块配置。

```yaml
version: 2
modules:
  - jenkins://cmd_server
```

命令驱动选择 `-SwitchMode player-command`，summary 使用 `proxySwitchDriver = 'player-command'`。此路径不安装 API fixture，不传入 `SwitchPlayer`。Bungee 配置须授予测试玩家 `bungeecord.command.server`，下游名称须匹配 `PrimaryServer`、`SecondaryServer`。当下游为 `restricted: true` 时，还需相应的 `bungeecord.server.<name>` 权限；现有测试配置为 `restricted: false`。`disabled_commands` 不得包含 `server`。

必需 ID 使用 `Get-ProxySwitchRequiredAssertionIds -RoundIndex $RoundIndex -SwitchMode player-command` 提取。原有 23 条断言保持不变，另加：

- `round<N>/proxy-switch/player-command-right`
- `round<N>/proxy-switch/player-command-move`
- `round<N>/proxy-switch/player-command-return`

每条新断言都要求：玩家在对应 checkpoint 后恰好发送一次目标 `/server` 命令，在 30 秒内收到目标子服独有的探针，且 bot 的必需确认断言通过。直接调用 `Assert-JointProxySwitchResults` 时须传 `-SwitchMode player-command -PrimaryServer <name> -SecondaryServer <name>`。切服完成仍以玩家收到的目标探针为依据；往返投递、成员列表和 SERVER 频道隔离保持原有门槛。

API 驱动保留原调用：

```powershell
$fixtureJar = Install-JointBungeeSwitchFixture `
  -ServerJar $jarPath -JdkHome $jdkPath -ServerDirectory $dir
```

它生成 `<ServerDirectory>/plugins/JointSwitchFixture.jar`，注册仅允许控制台调用的 `jointswitch <player> <server>`。主线程显式选择 `-SwitchMode callback`，summary 标记为 `fixtureNativeAPI`。`SwitchPlayer(username, server)` 回调写入代理 stdin，并等待本次命令后新出现的 `JointSwitchFixture switched <username> -> <server>`；失败输出 `JointSwitchFixture error`。API marker 只驱动下一步，结果仍须满足目标探针、往返投递和隔离断言。

`Get-ProxySwitchRequiredAssertionIds` 和 `Assert-JointProxySwitchResults` 省略 `SwitchMode` 时保留原来的 23 条 API／移动证据，用于旧记录重放。它们不产生新的命令证据 ID，不能作为 `/server` 验收。`Invoke-JointProxySwitchScenarios` 会显式传入实际选择的模式。

最小离线验证命令：

```powershell
powershell -NoProfile -File test/joint/fixtures/bungee-switch/verify-player-command.ps1 `
  -ServerJar .e2e-artifacts/dist/waterfall-1.21-615.jar `
  -ModuleJar <cache>/cmd_server-1.21-615.jar
```

该命令检查真实模块归档、安装前提、PowerShell 语法和计划／结果契约。合成接收记录只验证断言逻辑，不代表真实切服。尚未运行新的真实玩家 `/server`、往返聊天或隔离 E2E；这些边界仍需主线程集成后验收。
