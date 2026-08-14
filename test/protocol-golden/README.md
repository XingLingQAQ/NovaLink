# NovaProtocol v2 跨语言黄金字节（golden bytes）样本集

由 Java 权威实现生成（`GoldenFileGenerator`，模块 `NovaChat:common` 测试源码）。
每个样本包含：`<name>.bin`（完整帧：`Length(VarInt) | PacketID(1B) | RequestID(UUID 16B) | Payload`）
与 `<name>.json`（包类型、RequestID、全部字段期望值、帧十六进制）。

重新生成：设置环境变量 `NOVALINK_GOLDEN_GENERATE=true` 后运行
`.\gradlew.bat :NovaChat:common:test --tests "*GoldenFileGenerator*" --no-daemon`。

字段说明：
- `legacyWire=true`：wire 上缺省可选尾部字段（如 Handshake v1 无 serverVersion、
  ChatMessage 无 placeholders 计数）。各语言解码必须成功，但 re-encode 会补写
  规范尾部字段，因此不做字节比对（`reencodeExact=false`）。
- `knownDrift`：某语言的已知行为漂移（该语言测试跳过字节比对并报告）。

| # | 样本 | 包类型 | PacketID | RequestID | reencodeExact | 已知漂移语言 | 说明 |
|---|------|--------|----------|-----------|---------------|--------------|------|
| 1 | `handshake_v2_full` | HandshakePacket | 0x01 | `00000000-0000-0000-0000-00000000002a` | true | - | 协议 v2 完整握手：含尾部 serverVersion 字段 |
| 2 | `handshake_v1_no_serverversion` | HandshakePacket | 0x01 | `00000000-0000-0000-0000-00000000002b` | false | - | 遗留 v1 握手：wire 上缺省尾部 serverVersion（中文 clientId），解码应得空串；re-encode 会补写空 serverVersion，故不做字节比对 |
| 3 | `handshake_response_success` | HandshakeResponsePacket | 0x02 | `00000000-0000-0000-0000-00000000002c` | true | - | 握手成功响应：空 errorCode + 中文/emoji message |
| 4 | `handshake_response_failure` | HandshakeResponsePacket | 0x02 | `00000000-0000-0000-0000-00000000002d` | true | - | 握手失败响应：NC-420（字段顺序 success|errorCode|message） |
| 5 | `chat_message_full` | ChatMessagePacket | 0x03 | `00000000-0000-0000-0000-00000000002e` | true | - | 聊天消息：中文+emoji 内容，placeholders 非空（1 项，保证跨语言 map 序确定） |
| 6 | `chat_message_legacy_no_placeholders` | ChatMessagePacket | 0x03 | `00000000-0000-0000-0000-00000000002f` | false | - | 遗留聊天消息：payload 在 content 后结束（无 placeholders 计数），空字符串边界；re-encode 会补写 varint 0，不做字节比对 |
| 7 | `channel_action_create_extra` | ChannelActionPacket | 0x04 | `00000000-0000-0000-0000-000000000030` | true | - | 创建频道：中文 channelId + 密码 + 非空 extra（1 项） |
| 8 | `channel_action_join_empty_extra` | ChannelActionPacket | 0x04 | `00000000-0000-0000-0000-000000000031` | true | - | 加入频道：空密码 + 显式写出的空 extra map（varint 0） |
| 9 | `channel_action_response_ok_extra` | ChannelActionResponsePacket | 0x05 | `00000000-0000-0000-0000-000000000032` | true | - | 频道操作成功响应：中文 message + 非空 extra（1 项） |
| 10 | `channel_action_response_error` | ChannelActionResponsePacket | 0x05 | `00000000-0000-0000-0000-000000000033` | true | - | 频道操作失败响应：NC-403 + emoji message + 空 extra |
| 11 | `config_sync_typical` | ConfigSyncPacket | 0x06 | `00000000-0000-0000-0000-000000000034` | true | - | 配置同步：嵌套 JSON 字符串（含引号转义与中文/emoji）+ 毫秒时间戳 |
| 12 | `config_sync_empty_string` | ConfigSyncPacket | 0x06 | `00000000-0000-0000-0000-000000000035` | true | - | 配置同步边界：configJson 为空字符串、timestamp=0——校验各语言不得把空串规范化为 {} |
| 13 | `keep_alive_typical` | KeepAlivePacket | 0x07 | `00000000-0000-0000-0000-000000000036` | true | - | 心跳：典型毫秒时间戳 |
| 14 | `keep_alive_max_long` | KeepAlivePacket | 0x07 | `00000000-0000-0000-0000-000000000037` | true | - | 心跳边界：timestamp = Long.MAX_VALUE（0x7FFFFFFFFFFFFFFF） |
| 15 | `title_typical` | TitlePacket | 0x09 | `00000000-0000-0000-0000-000000000038` | true | - | Title：颜色符号 § + 中文标题 + emoji 副标题 + 默认时序 |
| 16 | `title_boundary` | TitlePacket | 0x09 | `00000000-0000-0000-0000-000000000039` | true | - | Title 边界：空 channelId/subtitle、零时序、全零 senderId UUID |
| 17 | `admin_action_auth` | AdminActionPacket | 0x0B | `00000000-0000-0000-0000-00000000003a` | true | - | 管理员认证：AUTH + SHA-256 哈希 + 空 target + 空 extra |
| 18 | `admin_action_spy_extra` | AdminActionPacket | 0x0B | `00000000-0000-0000-0000-00000000003b` | true | - | 管理员监听：SPY_START + 中文 target + 非空 extra（1 项） |
| 19 | `admin_action_response_ok` | AdminActionResponsePacket | 0x0C | `00000000-0000-0000-0000-00000000003c` | true | - | 管理操作成功响应（字段顺序 action|success|errorCode|message） |
| 20 | `admin_action_response_fail` | AdminActionResponsePacket | 0x0C | `00000000-0000-0000-0000-00000000003d` | true | - | 管理操作失败响应：NC-500 + 中文 message |
| 21 | `item_display_typical` | ItemDisplayPacket | 0x10 | `00000000-0000-0000-0000-00000000003e` | true | - | 物品展示：嵌套 JSON 字符串 + 毫秒时间戳 |
| 22 | `item_display_boundary` | ItemDisplayPacket | 0x10 | `00000000-0000-0000-0000-00000000003f` | true | - | 物品展示边界：全空字符串、全零 UUID、负 long（-1 → 8 个 0xFF 字节） |
| 23 | `mention_typical` | MentionPacket | 0x12 | `00000000-0000-0000-0000-000000000040` | true | - | @提及通知：双 UUID + 中文/emoji 预览 + 毫秒时间戳 |
| 24 | `private_message_typical` | PrivateMessagePacket | 0x14 | `00000000-0000-0000-0000-000000000041` | true | - | 私聊消息 S→C 完整形态：后端已填 targetId 与服务器时间戳 |
| 25 | `private_message_cjk_nil_target` | PrivateMessagePacket | 0x14 | `00000000-0000-0000-0000-000000000042` | true | - | 私聊消息 C→S 边界：全零 targetId（后端按名解析）+ 中文名/emoji 内容 |
