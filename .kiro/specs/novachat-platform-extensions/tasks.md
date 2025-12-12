# Implementation Plan - NovaChat Platform Extensions

## 第一部分：MultiPaper 插件支持

- [x] 1. 设置 MultiPaper 项目结构






  - [x] 1.1 创建 novachat-multipaper Gradle 模块


    - 创建 build.gradle，配置 Paper API 依赖
    - 添加 novachat-common 依赖
    - _Requirements: 1.1, 1.5_

  - [x] 1.2 创建 plugin.yml 和默认配置


    - _Requirements: 1.4_

  - [x] 1.3 配置 Gradle 构建脚本


    - 配置 shadowJar 打包
    - _Requirements: 1.1_

- [x] 2. 实现 MultiPaper 核心功能





  - [x] 2.1 实现 NovaChatMultiPaper 主插件类


    - 初始化配置、网络客户端、事件监听
    - _Requirements: 1.1, 1.3_


  - [x] 2.2 实现 MultiPaperAdapter 环境检测

    - 检测 MultiPaper 环境
    - 获取实例 ID
    - _Requirements: 1.2_


  - [x] 2.3 实现 NetworkClient 和 ClientChannelHandler

    - 复用 novachat-common 协议实现
    - _Requirements: 1.1, 1.5_


  - [x] 2.4 实现 ChatInterceptor

    - 使用 AsyncPlayerChatEvent 拦截消息
    - 处理 MultiPaper 跨实例玩家同步
    - _Requirements: 1.3, 1.4_

  - [x] 2.5 编写属性测试：MultiPaper 状态同步


    - **Property 18: MultiPaper State Synchronization**
    - **Validates: Requirements 1.3**


  - [x] 2.6 实现 MessageFormatter

    - 使用 Component API 渲染消息
    - _Requirements: 1.4_

  - [x] 2.7 实现 NovaChatCommand 和子命令


    - 实现 help、join、leave、toggle、reload、debug
    - _Requirements: 1.1_


  - [x] 2.8 实现 NovaChatConfig 配置管理

    - _Requirements: 1.1_

- [x] 3. Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

---

## 第二部分：Folia 插件支持

- [x] 4. 设置 Folia 项目结构






  - [x] 4.1 创建 novachat-folia Gradle 模块

    - 创建 build.gradle，配置 Paper API 依赖
    - 添加 novachat-common 依赖
    - _Requirements: 2.1, 2.5_


  - [x] 4.2 创建 plugin.yml 和默认配置

    - _Requirements: 2.4_


  - [x] 4.3 配置 Gradle 构建脚本

    - 配置 shadowJar 打包
    - _Requirements: 2.1_

- [x] 5. 实现 Folia 异步架构






  - [x] 5.1 实现 FoliaSchedulerAdapter


    - 使用 Folia 的异步任务调度器
    - 实现 runForPlayer、runAsync、runGlobal 方法
    - _Requirements: 2.2_


  - [x] 5.2 实现 AsyncNetworkClient

    - 使用 Folia 调度器处理网络操作
    - _Requirements: 2.2_



  - [x] 5.3 实现 AsyncChatInterceptor




    - 使用 AsyncPlayerChatEvent 拦截消息
    - 正确处理区域线程并发访问


    - _Requirements: 2.3, 2.4_

  - [x] 5.4 编写属性测试：Folia 线程安全




    - **Property 17: Folia Thread Safety**

    - **Validates: Requirements 2.3**


  - [x] 5.5 实现 AsyncMessageFormatter




    - 在正确的线程上渲染消息
    - _Requirements: 2.4_

- [x] 6. 实现 Folia 命令和配置






  - [x] 6.1 实现 NovaChatCommand 和子命令

    - 实现 help、join、leave、toggle、reload、debug
    - _Requirements: 2.1_


  - [x] 6.2 实现 NovaChatConfig 配置管理

    - _Requirements: 2.1_

- [x] 7. Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

---

## 第三部分：Sponge 插件支持

- [-] 8. 设置 Sponge 项目结构




  - [x] 8.1 创建 novachat-sponge Gradle 模块


    - 创建 build.gradle，配置 Sponge API 8.x 依赖
    - 添加 novachat-common 依赖
    - _Requirements: 3.1, 3.5_

  - [x] 8.2 创建 mcmod.info 和默认配置


    - _Requirements: 3.4_

  - [x] 8.3 配置 Gradle 构建脚本







    - 配置 shadowJar 打包
    - _Requirements: 3.1_

- [x] 9. 实现 Sponge 核心功能





  - [x] 9.1 实现 NovaChatSponge 主插件类


    - 初始化配置、网络客户端、事件监听
    - _Requirements: 3.1, 3.2_


  - [x] 9.2 实现 NetworkClient 和 ClientChannelHandler

    - 复用 novachat-common 协议实现
    - _Requirements: 3.1, 3.5_


  - [x] 9.3 实现 ChatListener

    - 使用 Sponge PlayerChatEvent 拦截消息
    - 处理 Sponge 权限系统
    - _Requirements: 3.3_



  - [x] 9.4 实现 MessageFormatter
    - 使用 Sponge Text API 渲染消息
    - _Requirements: 3.4_


  - [x] 9.5 实现 NovaChatCommand 和子命令

    - 实现 help、join、leave、toggle、reload、debug
    - _Requirements: 3.1_


  - [x] 9.6 实现 NovaChatConfig 配置管理

    - _Requirements: 3.1_

- [x] 10. Checkpoint - 确保所有测试通过




  - Ensure all tests pass, ask the user if questions arise.

---

## 第四部分：Mod 多版本支持

- [x] 11. 完善 Fabric Mod 多版本支持




  - [x] 11.1 实现 VersionDetector 和 VersionAdapter


    - 检测 Minecraft 版本
    - 返回版本特定的适配器
    - _Requirements: 4.1, 4.4_


  - [x] 11.2 为 Fabric 1.14-1.19 创建版本特定实现

    - 处理 API 差异
    - _Requirements: 4.1, 4.3_


  - [x] 11.3 为 Fabric 1.20-1.21 创建版本特定实现

    - 处理 API 差异
    - _Requirements: 4.1, 4.3_


  - [x] 11.4 配置 Gradle 多版本构建

    - 为每个版本生成独立 JAR
    - _Requirements: 4.2_


  - [x] 11.5 编写 Fabric 版本兼容性测试


    - **Property 16: Mod Version Detection Correctness**
    - **Validates: Requirements 4.4**

- [x] 12. 完善 NeoForge Mod 多版本支持





  - [x] 12.1 实现 VersionDetector 和 VersionAdapter


    - 检测 Minecraft 版本
    - 返回版本特定的适配器
    - _Requirements: 5.1, 5.4_

  - [x] 12.2 为 NeoForge 1.20.2-1.21 创建版本特定实现


    - 处理 NeoForge API 差异
    - _Requirements: 5.1, 5.3_

  - [x] 12.3 配置 Gradle 多版本构建


    - 为每个版本生成独立 JAR
    - _Requirements: 5.2_

  - [x] 12.4 编写 NeoForge 版本兼容性测试


    - **Property 16: Mod Version Detection Correctness**
    - **Validates: Requirements 5.4**

- [x] 13. 完善 Quilt Mod 多版本支持

  - [x] 13.1 实现 VersionDetector 和 VersionAdapter
    - 检测 Minecraft 版本
    - 返回版本特定的适配器
    - _Requirements: 6.1, 6.4_

  - [x] 13.2 为 Quilt 1.14-1.19 创建版本特定实现
    - 处理 Quilted Fabric API 差异
    - _Requirements: 6.1, 6.3_

  - [x] 13.3 为 Quilt 1.20-1.21 创建版本特定实现
    - 处理 Quilted Fabric API 差异
    - _Requirements: 6.1, 6.3_

  - [x] 13.4 配置 Gradle 多版本构建
    - 为每个版本生成独立 JAR
    - _Requirements: 6.2_

  - [x] 13.5 编写 Quilt 版本兼容性测试


    - **Property 16: Mod Version Detection Correctness**
    - **Validates: Requirements 6.4**

- [x] 14. 完善 Forge Mod 多版本支持






  - [x] 14.1 实现 VersionDetector 和 VersionAdapter


    - 检测 Minecraft 版本
    - 返回版本特定的适配器
    - _Requirements: 7.1, 7.4_


  - [x] 14.2 为 Forge 1.7-1.12 创建版本特定实现

    - 处理 Forge API 差异
    - _Requirements: 7.1, 7.3_


  - [x] 14.3 为 Forge 1.13-1.19 创建版本特定实现

    - 处理 Forge API 差异
    - _Requirements: 7.1, 7.3_

  - [x] 14.4 为 Forge 1.20-1.21 创建版本特定实现


    - 处理 Forge API 差异
    - _Requirements: 7.1, 7.3_


  - [x] 14.5 配置 Gradle 多版本构建

    - 为每个版本生成独立 JAR
    - _Requirements: 7.2_

  - [x] 14.6 编写 Forge 版本兼容性测试


    - **Property 16: Mod Version Detection Correctness**
    - **Validates: Requirements 7.4**

- [x] 15. Checkpoint - 确保所有测试通过




  - Ensure all tests pass, ask the user if questions arise.

---

## 第五部分：自定义插件系统

- [x] 16. 实现扩展 API 核心



  - [x] 16.1 创建 NovaChatExtension 接口


    - 定义 onEnable、onDisable、getMeta 方法
    - _Requirements: 8.1, 9.2_



  - [x] 16.2 创建 ExtensionMeta 数据类
    - 包含 id、name、version、author、description、dependencies
    - _Requirements: 8.4_


  - [x] 16.3 实现 ExtensionLoader

    - 扫描 extensions 目录
    - 加载 JAR 文件
    - 解析 extension.yml 元数据
    - _Requirements: 8.3, 9.1_



  - [x] 16.4 编写属性测试：扩展元数据解析往返
    - **Property 10: Extension Metadata Parsing Round-Trip**
    - **Validates: Requirements 8.4**

  - [x] 16.5 编写属性测试：扩展加载隔离



    - **Property 9: Extension Loading Isolation**
    - **Validates: Requirements 8.5**

- [x] 17. 实现扩展管理器





  - [x] 17.1 创建 ExtensionManager


    - 管理扩展生命周期
    - 处理依赖关系
    - _Requirements: 8.3, 8.5_

  - [x] 17.2 实现扩展事件系统


    - 允许扩展注册事件监听器
    - _Requirements: 8.2_

  - [x] 17.3 实现扩展命令注册


    - 允许扩展注册自定义命令
    - _Requirements: 8.2_

- [x] 18. 实现基岩版扩展支持






  - [x] 18.1 为 PMMP 实现 PHP 扩展加载器


    - 扫描 extensions 目录
    - 加载 PHP 扩展文件
    - _Requirements: 10.1_

  - [x] 18.2 为 Endstone 实现 Python 扩展加载器


    - 扫描 extensions 目录
    - 加载 Python 扩展模块
    - _Requirements: 10.2_

  - [x] 18.3 为 Nukkit/PNX 实现 Java 扩展加载器


    - 复用 Java 扩展加载器
    - _Requirements: 10.3_

- [x] 19. Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

---

## 第六部分：高级聊天功能 - @提及

- [x] 20. 实现提及解析器





  - [x] 20.1 创建 MentionParser 类


    - 实现 @玩家名 解析
    - 实现 @all 解析
    - _Requirements: 11.1, 11.4_


  - [x] 20.2 编写属性测试：提及解析一致性

    - **Property 1: Mention Parsing Consistency**
    - **Validates: Requirements 11.1**


  - [x] 20.3 编写属性测试：@all 扩展正确性

    - **Property 3: @all Expansion Correctness**
    - **Validates: Requirements 11.4**

- [x] 21. 实现提及通知系统






  - [x] 21.1 创建 MentionPacket 数据包


    - 实现序列化和反序列化
    - _Requirements: 20.1, 20.2_


  - [x] 21.2 编写属性测试：提及数据包序列化往返

    - **Property 14: Mention Packet Serialization Round-Trip**
    - **Validates: Requirements 20.1-20.2**


  - [x] 21.3 实现提及通知发送

    - 发送声音通知
    - 发送标题通知
    - _Requirements: 11.2_


  - [x] 21.4 实现提及权限检查

    - 检查 novachat.feature.mention 权限
    - 检查 novachat.feature.mention.all 权限
    - _Requirements: 11.5_


  - [x] 21.5 编写属性测试：提及权限执行

    - **Property 2: Mention Permission Enforcement**
    - **Validates: Requirements 11.5**

- [x] 22. 实现提及 Tab 补全







  - [x] 22.1 为 Bukkit 平台实现 Tab 补全

    - _Requirements: 11.3_


  - [x] 22.2 为其他平台实现 Tab 补全

    - _Requirements: 11.3_

- [x] 23. Checkpoint - 确保所有测试通过




  - Ensure all tests pass, ask the user if questions arise.

---

## 第七部分：高级聊天功能 - 物品展示

- [x] 24. 实现物品展示解析器






  - [x] 24.1 创建 ItemDisplayParser 类


    - 实现 [item] 和 [i] 标签解析
    - _Requirements: 12.1_


  - [x] 24.2 编写属性测试：物品展示标签解析

    - **Property 4: Item Display Tag Parsing**
    - **Validates: Requirements 12.1**

- [x] 25. 实现物品序列化








  - [x] 25.1 创建 ItemSerializer 类


    - 实现 ItemStack 到 JSON/NBT 序列化
    - 实现 JSON/NBT 到 ItemStack 反序列化
    - _Requirements: 12.2_


  - [x] 25.2 编写属性测试：物品序列化往返

    - **Property 5: Item Serialization Round-Trip**
    - **Validates: Requirements 12.2**

- [x] 26. 实现物品展示数据包






  - [x] 26.1 创建 ItemDisplayPacket 数据包


    - 实现序列化和反序列化
    - _Requirements: 19.1_

  - [x] 26.2 编写属性测试：展示数据包序列化往返


    - **Property 13: Display Packet Serialization Round-Trip**
    - **Validates: Requirements 19.1-19.4**

- [x] 27. 实现物品展示渲染







  - [x] 27.1 为 Java 版实现 HoverEvent 渲染

    - _Requirements: 12.3, 12.4_

  - [x] 27.2 为基岩版实现替代方案


    - _Requirements: 12.4_

  - [x] 27.3 实现物品展示权限检查


    - 检查 novachat.feature.item 权限
    - _Requirements: 12.5_

  - [x] 27.4 编写属性测试：展示权限执行


    - **Property 6: Display Permission Enforcement**
    - **Validates: Requirements 12.5, 13.5, 14.5**

- [x] 28. Checkpoint - 确保所有测试通过





  - Ensure all tests pass, ask the user if questions arise.

---

## 第八部分：高级聊天功能 - 背包/末影箱展示

- [-] 29. 实现背包展示解析器





  - [ ] 29.1 创建 InventoryDisplayParser 类

    - 实现 [inv] 和 [inventory] 标签解析
    - 实现 [ec] 和 [enderchest] 标签解析
    - _Requirements: 13.1, 14.1_

- [ ] 30. 实现背包快照系统

  - [ ] 30.1 创建 InventorySnapshot 类
    - 存储背包内容快照
    - 支持过期时间
    - _Requirements: 13.3, 14.3_

  - [ ] 30.2 创建 InventorySnapshotPacket 数据包
    - 实现序列化和反序列化
    - _Requirements: 19.2_

  - [ ] 30.3 实现快照存储管理
    - 使用唯一 ID 标识快照
    - 自动清理过期快照
    - _Requirements: 13.3, 14.3_

- [ ] 31. 实现背包预览界面

  - [ ] 31.1 为 Java 版实现只读背包界面
    - _Requirements: 13.2, 13.4_

  - [ ] 31.2 为基岩版实现表单预览
    - _Requirements: 14.2, 14.4_

  - [ ] 31.3 实现背包展示权限检查
    - 检查 novachat.feature.inventory 权限
    - 检查 novachat.feature.enderchest 权限
    - _Requirements: 13.5, 14.5_

- [ ] 32. Checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

---

## 第九部分：高级聊天功能 - 图片展示

- [ ] 33. 实现图片 URL 解析器

  - [ ] 33.1 创建 ImageDisplayParser 类
    - 实现图片 URL 检测
    - 支持 PNG、JPG、GIF、WebP 格式
    - _Requirements: 15.1, 15.2_

  - [ ] 33.2 编写属性测试：图片 URL 检测
    - **Property 7: Image URL Detection**
    - **Validates: Requirements 15.1, 15.2**

- [ ] 34. 实现图片白名单系统

  - [ ] 34.1 实现图床白名单配置
    - 从配置文件加载白名单
    - _Requirements: 15.4_

  - [ ] 34.2 实现白名单验证
    - 检查 URL 域名是否在白名单中
    - _Requirements: 15.4_

  - [ ] 34.3 编写属性测试：图片白名单执行
    - **Property 8: Image Whitelist Enforcement**
    - **Validates: Requirements 15.4**

- [ ] 35. 实现图片展示数据包

  - [ ] 35.1 创建 ImageDisplayPacket 数据包
    - 实现序列化和反序列化
    - _Requirements: 19.3_

- [ ] 36. 实现图片预览

  - [ ] 36.1 为 Java 版实现地图/告示牌图片预览
    - _Requirements: 15.3_

  - [ ] 36.2 为基岩版实现表单图片预览
    - _Requirements: 15.3_

  - [ ] 36.3 实现图片展示权限检查
    - 检查 novachat.feature.image 权限
    - _Requirements: 15.5_

- [ ] 37. Checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

---

## 第十部分：权限系统完善

- [ ] 38. 实现权限节点常量

  - [ ] 38.1 创建 PermissionNode 常量类
    - 定义所有命令权限节点
    - 定义所有功能权限节点
    - 定义频道权限生成方法
    - 定义格式权限生成方法
    - _Requirements: 16.1-16.5, 17.1-17.5_

  - [ ] 38.2 编写属性测试：权限节点一致性
    - **Property 11: Permission Node Consistency**
    - **Validates: Requirements 17.1-17.3**

- [ ] 39. 实现格式组系统

  - [ ] 39.1 创建 FormatGroup 数据类
    - 包含 permission、priority、template
    - _Requirements: 18.1, 18.2_

  - [ ] 39.2 实现格式组选择逻辑
    - 根据权限和优先级选择格式
    - _Requirements: 18.3_

  - [ ] 39.3 编写属性测试：格式组优先级
    - **Property 12: Format Group Priority**
    - **Validates: Requirements 18.3**

  - [ ] 39.4 实现格式配置加载
    - 从配置文件加载格式组
    - _Requirements: 18.4_

- [ ] 40. 更新各平台权限检查

  - [ ] 40.1 更新 Bukkit 平台权限检查
    - _Requirements: 16.5_

  - [ ] 40.2 更新 Mod 平台权限检查
    - _Requirements: 16.5_

  - [ ] 40.3 更新基岩版平台权限检查
    - _Requirements: 16.5_

- [ ] 41. Checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

---

## 第十一部分：协议扩展与跨平台一致性

- [ ] 42. 实现新数据包类型

  - [ ] 42.1 在 novachat-common 中添加新数据包
    - ItemDisplayPacket (0x10)
    - InventorySnapshotPacket (0x11)
    - MentionPacket (0x12)
    - ImageDisplayPacket (0x13)
    - _Requirements: 19.1-19.5, 20.1-20.5_

  - [ ] 42.2 更新 PacketRegistry
    - 注册新数据包类型
    - _Requirements: 19.1-19.5_

- [ ] 43. 实现跨平台协议一致性

  - [ ] 43.1 为 PMMP 实现新数据包
    - _Requirements: 21.1-21.4_

  - [ ] 43.2 为 Endstone 实现新数据包
    - _Requirements: 21.1-21.4_

  - [ ] 43.3 为 Go 后端实现新数据包
    - _Requirements: 21.1-21.4_

  - [ ] 43.4 编写属性测试：跨平台字节序一致性
    - **Property 15: Cross-Platform Byte Order Consistency**
    - **Validates: Requirements 21.1-21.3**

- [ ] 44. Checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

---

## 第十二部分：文档与集成测试

- [ ] 45. 更新文档

  - [ ] 45.1 更新 README 平台兼容性矩阵
    - 包含新增平台
    - 包含功能支持情况
    - _Requirements: 22.1, 22.2_

  - [ ] 45.2 编写各平台安装指南
    - MultiPaper 安装指南
    - Folia 安装指南
    - Sponge 安装指南
    - _Requirements: 22.3_

  - [ ] 45.3 编写扩展开发指南
    - Java 扩展开发
    - PHP 扩展开发
    - Python 扩展开发
    - _Requirements: 22.5_

  - [ ] 45.4 编写高级功能使用指南
    - @提及功能
    - 物品/背包/末影箱展示
    - 图片展示
    - _Requirements: 22.4_

- [ ] 46. 跨平台集成测试

  - [ ] 46.1 编写 MultiPaper 集成测试
    - 验证与 NovaLink 的完整通信流程
    - _Requirements: 1.1-1.5_

  - [ ] 46.2 编写 Folia 集成测试
    - 验证异步处理的正确性
    - _Requirements: 2.1-2.5_

  - [ ] 46.3 编写 Sponge 集成测试
    - 验证与 NovaLink 的完整通信流程
    - _Requirements: 3.1-3.5_

  - [ ] 46.4 编写高级功能集成测试
    - 验证 @提及跨服通知
    - 验证物品展示跨服传输
    - _Requirements: 11.1-15.5_

- [ ] 47. Final Checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

