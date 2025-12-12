# Implementation Plan

## 第一部分：构建系统迁移 (Maven → Gradle)

- [x] 0. 迁移现有 Maven 项目到 Gradle




  - [x] 0.1 创建根 build.gradle 和 settings.gradle


    - 配置 Gradle 版本和插件
    - 配置统一的依赖管理
    - _Requirements: 26.1, 26.2_
  - [x] 0.2 迁移 novachat-common 模块


    - 创建 build.gradle，转换 pom.xml 配置
    - 配置 JUnit 5 和 jqwik 测试框架
    - _Requirements: 26.1_
  - [x] 0.3 迁移 novalink-core 模块


    - 创建 build.gradle，转换 pom.xml 配置
    - 配置 shadowJar 打包
    - _Requirements: 26.1_
  - [x] 0.4 迁移 novachat-bukkit 模块


    - 创建 build.gradle，转换 pom.xml 配置
    - _Requirements: 26.1_
  - [x] 0.5 迁移 novachat-velocity 模块


    - 创建 build.gradle，转换 pom.xml 配置
    - _Requirements: 26.1_
  - [x] 0.6 迁移 novachat-bungee 模块


    - 创建 build.gradle，转换 pom.xml 配置
    - _Requirements: 26.1_
  - [x] 0.7 迁移 novachat-nukkit 模块


    - 创建 build.gradle，转换 pom.xml 配置
    - _Requirements: 26.1_
  - [x] 0.8 验证所有迁移的项目构建成功


    - 运行 gradle build 验证
    - 验证测试通过
    - _Requirements: 26.1_

- [x] 1. Checkpoint - 确保所有 Gradle 迁移完成





  - Ensure all tests pass, ask the user if questions arise.

---

## 第二部分：Java Edition Mod 支持 (Architectury)

- [x] 2. 设置 Architectury 项目结构





  - [x] 2.1 创建 novachat-mod 根项目，配置 Gradle 构建系统


    - 创建 build.gradle、settings.gradle、gradle.properties
    - 配置 Architectury 插件和 Loom
    - _Requirements: 1.1, 1.2_
  - [x] 2.2 创建 common 模块基础结构


    - 创建 common/build.gradle
    - 创建包结构 com.nova.chat.mod
    - _Requirements: 1.4_
  - [x] 2.3 创建平台特定模块骨架


    - 创建 fabric、neoforge、quilt、forge 子模块
    - 配置各模块的 build.gradle
    - _Requirements: 1.2, 1.5_

- [x] 3. 实现 Common 模块核心功能




  - [x] 3.1 实现 NetworkClient 接口和 Netty 客户端

    - 复用 novachat-common 协议实现
    - 实现连接、断开、重连逻辑
    - _Requirements: 2.3, 3.3, 4.3, 5.3_


  - [x] 2.2 实现 ChatHandler 和消息处理

    - 实现聊天消息拦截和转发


    - 实现消息格式化和渲染
    - _Requirements: 2.4, 3.4, 4.3, 5.4_


  - [x] 2.3 实现 ConfigManager 配置系统



    - 实现 YAML 配置加载和保存
    - 实现默认配置生成
    - _Requirements: 6.1-6.5_
  - [x] 2.4 编写属性测试：配置解析往返

    - **Property 12: Mod Configuration Parsing Round-Trip**
    - **Validates: Requirements 6.1**
  - [x] 2.5 实现 CommandManager 命令系统

    - 实现命令注册和分发
    - 实现所有子命令
    - _Requirements: 7.1-7.5_


  - [x] 2.6 实现 Platform 抽象接口

    - 定义平台无关的 API
    - _Requirements: 1.4_

- [x] 3. Checkpoint - 确保所有测试通过




  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. 实现 Fabric 平台适配




  - [x] 4.1 实现 FabricPlatform 适配器


    - 使用 ServerMessageEvents 注册聊天监听
    - 使用 Text API 渲染消息
    - _Requirements: 2.2, 2.4, 2.5_

  - [x] 4.2 创建 fabric.mod.json 元数据

    - 声明依赖和入口点
    - _Requirements: 2.6_

  - [x] 4.3 实现 Fabric 命令注册

    - 使用 CommandRegistrationCallback
    - _Requirements: 7.1-7.4_

- [x] 5. 实现 NeoForge 平台适配





  - [x] 5.1 实现 NeoForgePlatform 适配器


    - 使用 NeoForge 事件总线
    - 使用 ServerChatEvent 拦截消息
    - _Requirements: 3.2, 3.3_

  - [x] 5.2 创建 mods.toml 元数据

    - 声明依赖和版本
    - _Requirements: 3.5_

  - [x] 5.3 实现 NeoForge 命令注册

    - 使用 RegisterCommandsEvent
    - _Requirements: 7.1-7.4_

- [x] 6. 实现 Quilt 平台适配









  - [x] 6.1 实现 QuiltPlatform 适配器


    - 兼容 Quilted Fabric API
    - _Requirements: 4.2, 4.3_

  - [x] 6.2 创建 quilt.mod.json 元数据

    - _Requirements: 4.4_

- [x] 7. 实现 Forge 平台适配




  - [x] 7.1 实现 ForgePlatform 适配器


    - 使用 MinecraftForge.EVENT_BUS
    - _Requirements: 5.2, 5.3_

  - [x] 7.2 创建 mods.toml 元数据

    - _Requirements: 5.5_

- [x] 8. Checkpoint - 确保所有测试通过




  - Ensure all tests pass, ask the user if questions arise.

---

## 第二部分：Bedrock Edition 扩展


### PocketMine-MP 插件

- [x] 9. 设置 PocketMine-MP 项目结构






  - [x] 9.1 创建 novachat-pmmp 项目

    - 创建 composer.json、plugin.yml
    - 配置 PHP 8.1+ 依赖
    - _Requirements: 8.1, 8.2, 8.6_

  - [x] 9.2 创建基础目录结构

    - 创建 src/NovaChat 包结构
    - _Requirements: 8.1_

- [x] 10. 实现 PMMP 协议层


  - [x] 10.1 实现 VarInt 编解码器




    - _Requirements: 9.1_


  - [x] 10.2 实现 PacketBuffer 和核心数据包

    - 实现 Handshake、ChatMessage、ChannelAction、KeepAlive
    - _Requirements: 9.2_
  - [x] 10.3 编写属性测试：VarInt 往返


    - **Property 1: VarInt Encoding Round-Trip (Cross-Language)**
    - **Validates: Requirements 9.1**
  - [x] 10.4 编写属性测试：数据包序列化往返



    - **Property 2: Packet Serialization Round-Trip (Cross-Language)**
    - **Validates: Requirements 9.2**

- [x] 11. 实现 PMMP 网络客户端




  - [x] 11.1 实现异步 TCP 客户端

    - 使用 libasyncsocket 或 pmmpthread
    - _Requirements: 8.5_


  - [x] 11.2 实现重连机制
    - 指数退避重连

    - _Requirements: 9.4_
  - [x] 11.3 实现心跳机制


    - 每 15 秒发送心跳包
    - _Requirements: 9.5_

- [x] 12. 实现 PMMP 聊天和命令





  - [x] 12.1 实现 ChatHandler


    - 使用 PlayerChatEvent 拦截消息
    - _Requirements: 8.4_

  - [x] 12.2 实现消息渲染

    - 使用 TextFormat 类
    - _Requirements: 8.7_

  - [x] 12.3 实现命令系统

    - 注册 /novachat 命令
    - _Requirements: 8.1_

- [x] 13. Checkpoint - 确保所有测试通过




  - Ensure all tests pass, ask the user if questions arise.

### Endstone 插件

- [x] 14. 设置 Endstone 项目结构





  - [x] 14.1 创建 novachat-endstone 项目

    - 创建 pyproject.toml、plugin.toml
    - 配置 Python 3.10+ 依赖
    - _Requirements: 10.1, 10.2, 10.6_

  - [x] 14.2 创建基础目录结构

    - 创建 novachat_endstone 包结构
    - _Requirements: 10.1_

- [x] 15. 实现 Endstone 协议层




  - [x] 15.1 实现 VarInt 编解码器


    - 使用 struct 模块
    - _Requirements: 11.1, 11.3_

  - [x] 15.2 实现核心数据包
    - _Requirements: 11.2_

  - [x] 15.3 编写属性测试：VarInt 往返

    - **Property 1: VarInt Encoding Round-Trip (Cross-Language)**
    - **Validates: Requirements 11.1**

- [x] 16. 实现 Endstone 网络客户端




  - [x] 16.1 实现 asyncio TCP 客户端


    - _Requirements: 10.5_
  - [x] 16.2 实现重连和心跳机制


    - _Requirements: 11.4, 11.5_

- [x] 17. 实现 Endstone 聊天和命令




  - [x] 17.1 实现聊天拦截器


    - _Requirements: 10.4_
  - [x] 17.2 实现命令系统


    - _Requirements: 10.1_

### PowerNukkitX 插件

- [x] 18. 设置 PowerNukkitX 项目结构




  - [x] 18.1 创建 novachat-pnx Gradle 模块（独立项目）


    - 创建 build.gradle，配置 Java 17+
    - 添加 PowerNukkitX API 依赖
    - 添加 novachat-common 依赖
    - _Requirements: 28.1, 28.2, 28.5_
  - [x] 18.2 创建 plugin.yml 和默认配置

    - _Requirements: 28.6, 29.3, 29.5_
  - [x] 18.3 配置 Gradle 构建脚本


    - 配置 shadowJar 打包
    - _Requirements: 26.1_

- [x] 19. 实现 PowerNukkitX 核心功能

  - [x] 19.1 实现 NovaChatPNX 主插件类



    - 初始化配置、网络客户端、事件监听
    - _Requirements: 28.3_
  - [x] 19.2 实现 NetworkClient 和 ClientChannelHandler


    - 复用 novachat-common 协议实现
    - _Requirements: 28.3, 28.5_
  - [x] 19.3 实现 ChatInterceptor


    - 使用 PlayerChatEvent 拦截消息
    - _Requirements: 28.4_
  - [x] 19.4 实现 MessageFormatter



    - 使用 TextFormat 渲染颜色代码
    - _Requirements: 28.7_

- [x] 20. 实现 PowerNukkitX 命令和表单






  - [x] 20.1 实现 NovaChatCommand 和子命令

    - 实现 help、join、leave、toggle、reload、debug
    - _Requirements: 29.1, 29.2_

  - [x] 20.2 实现 ChannelFormManager

    - 使用 PowerNukkitX 表单 UI 系统
    - _Requirements: 28.8_

  - [x] 20.3 实现 NovaChatConfig 配置管理

    - _Requirements: 29.3, 29.4, 29.5_

  - [x] 20.4 实现 WorldMonitor 世界监控

    - 实现自动频道切换
    - _Requirements: 29.6_

- [x] 21. Checkpoint - 确保所有测试通过






  - Ensure all tests pass, ask the user if questions arise.

---

## 第三部分：Go 版本后端 (NovaLink-Go)


- [x] 22. 设置 NovaLink-Go 项目结构





  - [x] 22.1 创建 novalink-go 项目


    - 初始化 Go Modules
    - 创建 cmd/novalink/main.go
    - _Requirements: 12.1, 12.4_

  - [x] 22.2 创建包结构

    - 创建 pkg/protocol、pkg/network、pkg/channel 等
    - _Requirements: 12.1_

- [x] 23. 实现 NovaLink-Go 协议层




  - [x] 23.1 实现 VarInt 编解码器


    - _Requirements: 13.2_
  - [x] 23.2 实现 PacketBuffer 和核心数据包


    - _Requirements: 13.3_
  - [x] 23.3 编写属性测试：VarInt 往返


    - **Property 1: VarInt Encoding Round-Trip (Cross-Language)**
    - **Validates: Requirements 13.2**
  - [x] 23.4 编写属性测试：数据包序列化往返


    - **Property 2: Packet Serialization Round-Trip (Cross-Language)**
    - **Validates: Requirements 13.3**
  - [x] 23.5 编写属性测试：Go-Java 协议兼容性


    - **Property 4: Go-Java Protocol Compatibility**
    - **Validates: Requirements 19.1-19.5**

- [x] 24. 实现 NovaLink-Go 网络层





  - [x] 24.1 实现 TCP 服务器


    - 使用 goroutine 处理连接
    - _Requirements: 13.1_
  - [x] 24.2 实现 ClientConnection 管理


    - 使用 channel 进行协程通信
    - _Requirements: 13.4, 13.5_
  - [x] 24.3 实现 PacketHandler 分发


    - _Requirements: 13.1_

- [x] 25. 实现 NovaLink-Go 频道系统




  - [x] 25.1 实现 ChannelManager


    - 实现 GLOBAL、SERVER、PRIVATE 作用域
    - _Requirements: 14.1_

  - [x] 25.2 实现 MessageRouter

    - _Requirements: 14.2_

  - [x] 25.3 编写属性测试：消息路由作用域隔离

    - **Property 5: Go Message Routing Scope Isolation**
    - **Validates: Requirements 14.1-14.5**

  - [x] 25.4 实现 WorldFilter

    - _Requirements: 14.3_


  - [x] 25.5 实现 PrivateChannelManager

    - _Requirements: 14.4_
  - [x] 25.6 实现 TemplateManager

    - _Requirements: 14.5_

- [x] 26. Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

- [x] 27. 实现 NovaLink-Go 认证系统



  - [x] 27.1 实现 AuthManager


    - SHA-256 密码哈希验证
    - _Requirements: 15.1_


  - [x] 27.2 编写属性测试：认证哈希一致性
    - **Property 6: Go Authentication Hash Consistency**
    - **Validates: Requirements 15.1**
  - [x] 27.3 实现 PermissionManager


    - 四级权限体系
    - _Requirements: 15.2_

  - [x] 27.4 编写属性测试：权限层级执行

    - **Property 7: Go Permission Hierarchy Enforcement**
    - **Validates: Requirements 15.2**
  - [x] 27.5 实现 IpBanManager


    - _Requirements: 15.3_

  - [x] 27.6 编写属性测试：IP 封禁机制

    - **Property 8: Go IP Ban After Consecutive Failures**
    - **Validates: Requirements 15.3**
  - [x] 27.7 实现 JwtService


    - _Requirements: 15.5_

  - [x] 27.8 编写属性测试：JWT 令牌往返


    - **Property 9: Go JWT Token Round-Trip**
    - **Validates: Requirements 15.5**

- [x] 28. 实现 NovaLink-Go 数据持久化





  - [x] 28.1 实现 DatabaseProvider 接口


    - _Requirements: 16.1-16.3_
  - [x] 28.2 实现 MySQLProvider


    - _Requirements: 16.1_

  - [x] 28.3 实现 RedisProvider

    - _Requirements: 16.2_

  - [x] 28.4 实现 MemoryProvider

    - _Requirements: 16.3_

  - [x] 28.5 实现 PlayerStateManager

    - _Requirements: 16.4_

  - [x] 28.6 编写属性测试：玩家状态持久化往返

    - **Property 10: Go Player State Persistence Round-Trip**
    - **Validates: Requirements 16.1-16.5**

- [x] 29. 实现 NovaLink-Go 管理功能





  - [x] 29.1 实现 MuteManager


    - _Requirements: 17.1_

  - [x] 29.2 编写属性测试：禁言时长执行

    - **Property 11: Go Mute Duration Enforcement**
    - **Validates: Requirements 17.1**


  - [x] 29.3 实现 AnnouncementManager

    - _Requirements: 17.2_
  - [x] 29.4 实现 TitleManager

    - _Requirements: 17.3_

  - [x] 29.5 实现 KickManager

    - _Requirements: 17.4_

  - [x] 29.6 实现 SensitiveWordFilter

    - _Requirements: 17.5_

  - [x] 29.7 实现 InvitationManager

    - _Requirements: 17.6_

- [x] 30. 实现 NovaLink-Go WebSocket 网关



  - [x] 30.1 实现 WebSocketServer


    - _Requirements: 18.1_


  - [x] 30.2 实现 REST API 端点

    - _Requirements: 18.4_

  - [x] 30.3 实现 WebhookManager

    - _Requirements: 18.5_

- [x] 31. 实现 NovaLink-Go 配置加载







  - [x] 31.1 实现 ConfigLoader




    - 使用与 Java 版本相同的 YAML 格式
    - _Requirements: 12.3, 19.1_

- [x] 32. Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

---

## 第四部分：测试覆盖增强


- [x] 33. NovaLink 核心模块单元测试





  - [x] 33.1 为 ChannelManager 编写单元测试


    - 覆盖所有公共方法
    - _Requirements: 20.1, 20.5_

  - [x] 33.2 为 AuthManager 编写单元测试

    - _Requirements: 20.2, 20.5_

  - [x] 33.3 为 MuteManager 编写单元测试

    - _Requirements: 20.3, 20.5_
  - [x] 33.4 为 AnnouncementManager 编写单元测试


    - _Requirements: 20.4, 20.5_

- [x] 34. NovaChat-Common 协议测试




  - [x] 34.1 为 PacketBuffer 编写完整读写测试


    - 覆盖所有数据类型
    - _Requirements: 21.1_

  - [x] 34.2 为每种 Packet 类型编写序列化测试

    - _Requirements: 21.2_

  - [x] 34.3 为 VarInt 编写边界值测试

    - 测试 0、127、128、16383、16384、最大值
    - _Requirements: 21.3_



  - [x] 34.4 验证字节序正确性
    - _Requirements: 21.4_
  - [x] 34.5 编写属性测试：字节序一致性

    - **Property 3: Byte Order Consistency**
    - **Validates: Requirements 9.3**

- [x] 35. 属性测试扩展





  - [x] 35.1 为 CronSchedule 编写属性测试


    - **Property 13: Cron Schedule Correctness**
    - **Validates: Requirements 22.1**
  - [x] 35.2 为 WebhookManager 编写属性测试


    - **Property 14: Webhook Event Distribution**
    - **Validates: Requirements 22.2**
  - [x] 35.3 为 JwtService 编写属性测试


    - **Property 15: JWT Service Consistency**
    - **Validates: Requirements 22.3**

- [x] 36. Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

- [x] 37. 插件与后端对接测试

  - [x] 37.1 编写 NovaChat-Bukkit 对接测试
    - _Requirements: 23.1_
  - [x] 37.2 编写 NovaChat-Velocity 对接测试
    - _Requirements: 23.2_
  - [x] 37.3 编写 NovaChat-BungeeCord 对接测试
    - _Requirements: 23.3_
  - [x] 37.4 编写 NovaChat-Nukkit 对接测试
    - _Requirements: 23.4_
  - [x] 37.5 编写 NovaChat-PNX 对接测试
    - _Requirements: 23.5_
  - [x] 37.6 验证握手认证流程
    - _Requirements: 23.6_
  - [x] 37.7 验证消息发送和接收流程
    - _Requirements: 23.7_
  - [x] 37.8 验证频道加入和离开流程
    - _Requirements: 23.8_
  - [x] 37.9 验证管理命令执行流程

    - _Requirements: 23.9_

- [x] 38. 集成测试框架





  - [x] 38.1 配置 Testcontainers


    - 管理 MySQL/Redis 依赖
    - _Requirements: 24.5_

  - [x] 38.2 实现嵌入式 NovaLink 服务器启动

    - _Requirements: 24.1_

  - [x] 38.3 实现多客户端模拟

    - _Requirements: 24.2_
  - [x] 38.4 验证消息路由端到端正确性


    - _Requirements: 24.3_

  - [x] 38.5 验证认证流程完整性

    - _Requirements: 24.4_

  - [x] 38.6 验证 Go 和 Java 后端行为一致性

    - _Requirements: 24.6_

- [x] 39. 现有功能完整性检查






  - [x] 39.1 验证所有已完成任务的实现

    - _Requirements: 25.1_
  - [x] 39.2 识别缺失功能


    - _Requirements: 25.2_
  - [x] 39.3 验证所有命令实现


    - _Requirements: 25.3_
  - [x] 39.4 验证所有事件处理器注册


    - _Requirements: 25.4_
  - [x] 39.5 验证配置文件解析完整性


    - _Requirements: 25.5_

---

## 第五部分：构建与发布

- [x] 41. 统一构建系统





  - [x] 41.1 验证所有 Gradle 项目配置


    - 验证 novalink-core、novachat-common、novachat-bukkit、novachat-velocity、novachat-bungee、novachat-nukkit 构建成功
    - 验证 novachat-mod 和 novachat-pnx 构建成功
    - _Requirements: 26.1, 26.2_
  - [x] 41.2 配置 Go Modules


    - _Requirements: 26.3_
  - [x] 41.3 配置 Composer (PMMP)


    - _Requirements: 26.4_
  - [x] 41.4 配置 Poetry (Endstone)


    - _Requirements: 26.5_
  - [x] 41.5 创建一键构建脚本


    - 支持 Gradle（所有 Java 项目）
    - 支持 Go（novalink-go）
    - 支持 PHP（novachat-pmmp）
    - 支持 Python（novachat-endstone）
    - _Requirements: 26.6_

- [x] 41. 版本兼容性



  - [x] 41.1 更新 README 平台兼容性矩阵


    - _Requirements: 27.1_

  - [x] 41.2 在各模块配置中声明支持版本

    - _Requirements: 27.2_

  - [x] 41.3 实现版本不兼容错误提示

    - _Requirements: 27.3_

  - [x] 41.4 实现协议版本握手验证
    - _Requirements: 27.4_
  - [x] 41.5 确保 Go 和 Java 后端使用相同协议版本号



    - _Requirements: 27.5_

- [x] 42. Final Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

---

## 任务编号说明

由于添加了第 0 部分（构建系统迁移），所有后续任务编号已增加 1：
- 第 0 部分：构建系统迁移 (Maven → Gradle)
- 第 1 部分：Checkpoint
- 第 2-8 部分：Java Edition Mod 支持 (原第 1-7 部分)
- 第 9-21 部分：Bedrock Edition 扩展 (原第 8-20 部分)
- 第 22-32 部分：Go 版本后端 (原第 21-31 部分)
- 第 33-39 部分：测试覆盖增强 (原第 32-38 部分)
- 第 40-42 部分：构建与发布 (原第 39-41 部分)
