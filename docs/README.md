# NovaLink 项目文档

NovaLink 是面向多端 Minecraft 社区的聊天路由、频道治理与运营控制基础设施。本文档集以仓库中的实现与示例配置为依据，服务于初次接入、生产部署、日常运维、外部集成与代码贡献等不同场景。根目录 README 负责项目概览；这里负责解释“如何正确使用、运行、维护与扩展”。

> **文档边界。** 本文档描述当前仓库已经实现的行为。示例配置中被明确标注为未来参考的字段不会被表述为可直接启用的生产能力。部署前仍应在自己的目标平台与版本组合上完成验证。[1] [2]

## 从这里开始

| 你的目标 | 建议阅读路径 | 你将获得什么 |
| --- | --- | --- |
| 让第一个服务端接入网络 | [快速开始](guide/getting-started.md) → [配置指南](guide/configuration.md) | 后端构建、最小配置、客户端接入与连通性检查。 |
| 在生产环境部署 | [部署指南](guide/deployment.md) → [安全基线](operations/security.md) → [运行手册](operations/operations-runbook.md) | 端口、存储、反向代理、凭据、观测、回退与日常操作。 |
| 开发平台适配或后端能力 | [架构参考](reference/architecture.md) → [协议与客户端](reference/protocol-and-clients.md) → [开发与测试](development/development-and-testing.md) | 模块职责、依赖方向、协议生命周期、构建与验证层级。 |
| 对接管理控制面 | [管理 API](reference/admin-api.md) → [实时网关](reference/realtime-gateway.md) | REST 鉴权、已实现端点、WebSocket 会话、订阅和推送消息。 |
| 提交改进 | [贡献指南](development/contributing.md) → [文档维护](maintainers/documentation-maintenance.md) | PR 说明、验证记录、敏感信息边界与文档更新规则。 |

## 文档地图

### 使用与部署

| 页面 | 适用读者 | 核心内容 |
| --- | --- | --- |
| [快速开始](guide/getting-started.md) | 首次接入者 | 构建后端、复制最小配置、启动服务、接入第一个 NovaChat 客户端。 |
| [配置指南](guide/configuration.md) | 管理员、运维者 | 已解析 YAML 字段、频道模型、客户端凭据、功能开关与热重载。 |
| [部署指南](guide/deployment.md) | 运维者 | 运行拓扑、端口与网络边界、数据层、面板与反向代理。 |
| [安全基线](operations/security.md) | 管理员、安全负责人 | 密钥与认证、网络最小暴露、令牌、日志和部署前检查。 |
| [运行手册](operations/operations-runbook.md) | 值班与运营人员 | 控制台、管理面板、配置重载、治理操作与异常处置。 |

### 架构与集成

| 页面 | 适用读者 | 核心内容 |
| --- | --- | --- |
| [架构参考](reference/architecture.md) | 开发者、架构师 | 平台边缘、协议层、后端核心、管理控制面与依赖边界。 |
| [协议与客户端](reference/protocol-and-clients.md) | 平台适配开发者 | 握手、认证摘要、KeepAlive、断线重连、平台本地职责。 |
| [管理 API](reference/admin-api.md) | 集成开发者 | REST 认证模型、端点目录、操作风险与响应约定。 |
| [实时网关](reference/realtime-gateway.md) | 面板与自动化开发者 | `/ws` 鉴权、订阅、查询、推送消息和会话约束。 |

### 开发与维护

| 页面 | 适用读者 | 核心内容 |
| --- | --- | --- |
| [开发与测试](development/development-and-testing.md) | 开发者、CI 维护者 | Gradle 构建、单元/属性/集成验证、面板构建与真实 E2E。 |
| [贡献指南](development/contributing.md) | 贡献者、审阅者 | Issue、变更说明、平台影响、验证证据与安全卫生。 |
| [文档维护](maintainers/documentation-maintenance.md) | 维护者 | 事实来源、更新触发条件与发布前审阅。 |

## 事实来源与版本意识

技术文档的可靠性来自可复核性，而不是叙述长度。对于 NovaLink，后端启动、配置解析和管理接口以 `StarLink/core` 为准；平台接入与客户端运行时以 `NovaChat/*` 为准；真实环境验证以 `e2e/` 及其脚本为准。文档中的命令示例不替代你在目标 Minecraft、Loader、JDK 与平台代理组合上的实际测试。[3] [4]

如果发现文档与实现不一致，请优先提交可复现的 Issue 或 PR，并指出受影响的页面、代码路径、平台和验证结果。文档维护规则见[《文档维护》](maintainers/documentation-maintenance.md)。

## GitHub Wiki

GitHub Wiki 提供较短的角色化入口、常用操作和故障排查索引；本目录保留随代码版本化审阅的完整技术说明。两者的主题保持一致，但当二者出现差异时，应以当前分支的仓库文档、代码和示例配置为准。

## 参考资料

[1]: ../README.md "NovaLink 项目概览"
[2]: ../examples/novalink.yml "NovaLink 后端完整示例配置"
[3]: ../StarLink/core/src/main/java/com/nova/link/NovaLinkMain.java "NovaLink 后端启动与组件装配"
[4]: ../e2e/README.md "真实服务端 E2E 验证说明"
