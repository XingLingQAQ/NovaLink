import { 
  Server, 
  Users, 
  MessageSquare, 
  Activity, 
  Hash, 
  Globe, 
  Lock, 
  Shield,
  AlertTriangle,
  CheckCircle,
  Info,
  UserX
} from 'lucide-react';

// 服务器连接状态
export const CONNECTED_SERVERS = [
  { id: 1, name: "Lobby-1", platform: "Bukkit", players: 45, status: "online", ping: 12, version: "1.20.4" },
  { id: 2, name: "Survival-1", platform: "Paper", players: 128, status: "online", ping: 8, version: "1.20.4" },
  { id: 3, name: "Creative-1", platform: "Bukkit", players: 23, status: "online", ping: 15, version: "1.20.4" },
  { id: 4, name: "Bedrock-1", platform: "LeviLamina", players: 67, status: "online", ping: 25, version: "1.21.0" },
  { id: 5, name: "Nukkit-1", platform: "Nukkit", players: 34, status: "offline", ping: 0, version: "1.0.0" },
  { id: 6, name: "Proxy-1", platform: "Velocity", players: 297, status: "online", ping: 5, version: "3.3.0" },
];

// 频道配置
export const CHANNELS = [
  { id: "global", name: "全服聊天", type: "GLOBAL", permission: "novachat.channel.global", format: "&8[&b{server}&8] &7{player}&f: {message}", icon: Globe, color: "blue" },
  { id: "local", name: "本地聊天", type: "LOCAL", permission: "novachat.channel.local", format: "&7[本地] {player}: {message}", icon: Hash, color: "gray" },
  { id: "staff", name: "管理频道", type: "PRIVATE", permission: "novachat.channel.staff", format: "&c[Staff] &f{player}: {message}", icon: Shield, color: "red" },
  { id: "vip", name: "VIP频道", type: "PRIVATE", permission: "novachat.channel.vip", format: "&6[VIP] &e{player}: {message}", icon: Lock, color: "yellow" },
];

// 在线玩家
export const ONLINE_PLAYERS = [
  { uuid: "550e8400-e29b-41d4-a716-446655440001", name: "Steve_Player", server: "Survival-1", channel: "global", platform: "Java", muted: false },
  { uuid: "550e8400-e29b-41d4-a716-446655440002", name: "Alex_Builder", server: "Creative-1", channel: "global", platform: "Java", muted: false },
  { uuid: "550e8400-e29b-41d4-a716-446655440003", name: "BedrockGamer", server: "Bedrock-1", channel: "global", platform: "Bedrock", muted: false },
  { uuid: "550e8400-e29b-41d4-a716-446655440004", name: "AdminUser", server: "Lobby-1", channel: "staff", platform: "Java", muted: false },
  { uuid: "550e8400-e29b-41d4-a716-446655440005", name: "ToxicPlayer", server: "Survival-1", channel: "global", platform: "Java", muted: true },
];

// 禁言列表
export const MUTED_PLAYERS = [
  { uuid: "550e8400-e29b-41d4-a716-446655440005", name: "ToxicPlayer", reason: "辱骂其他玩家", expireTime: "2025-12-10 15:30:00", operator: "AdminUser" },
  { uuid: "550e8400-e29b-41d4-a716-446655440010", name: "SpamBot", reason: "刷屏广告", expireTime: "永久", operator: "System" },
];

// 仪表盘统计
export const DASHBOARD_STATS = [
  { title: "在线服务器", value: "5/6", change: "1 离线", trend: "normal", icon: Server },
  { title: "在线玩家", value: "297", change: "+23", trend: "up", icon: Users },
  { title: "今日消息", value: "12,453", change: "+15.2%", trend: "up", icon: MessageSquare },
  { title: "系统负载", value: "34%", change: "正常", trend: "normal", icon: Activity },
];

// 实时聊天消息
export const CHAT_MESSAGES = [
  { id: 1, time: "14:32:15", server: "Survival-1", player: "Steve_Player", channel: "global", content: "有人要一起挖矿吗？", platform: "Java" },
  { id: 2, time: "14:32:18", server: "Creative-1", player: "Alex_Builder", channel: "global", content: "我在建一个城堡，快来看！", platform: "Java" },
  { id: 3, time: "14:32:22", server: "Bedrock-1", player: "BedrockGamer", channel: "global", content: "基岩版玩家报到~", platform: "Bedrock" },
  { id: 4, time: "14:32:25", server: "Lobby-1", player: "AdminUser", channel: "staff", content: "[Staff] 注意监控Survival-1的聊天", platform: "Java" },
  { id: 5, time: "14:32:30", server: "Survival-1", player: "NewPlayer123", channel: "global", content: "大家好，我是新人！", platform: "Java" },
];

// 系统通知
export const SYSTEM_NOTIFICATIONS = [
  { id: 1, title: "服务器连接", desc: "Nukkit-1 断开连接", time: "2 分钟前", icon: AlertTriangle, type: "warning", read: false },
  { id: 2, title: "配置更新", desc: "频道配置已热重载", time: "10 分钟前", icon: CheckCircle, type: "success", read: false },
  { id: 3, title: "玩家禁言", desc: "ToxicPlayer 被禁言 24 小时", time: "1 小时前", icon: UserX, type: "info", read: true },
  { id: 4, title: "系统信息", desc: "NovaLink v1.0.0 运行正常", time: "启动时", icon: Info, type: "info", read: true },
];

// 敏感词列表
export const SENSITIVE_WORDS = [
  "广告词1", "广告词2", "脏话1", "脏话2"
];

// 公告配置
export const ANNOUNCEMENTS = [
  { id: 1, content: "欢迎来到服务器！输入 /help 查看帮助", interval: 300, enabled: true },
  { id: 2, content: "加入我们的QQ群：123456789", interval: 600, enabled: true },
  { id: 3, content: "VIP玩家享受专属频道和称号！", interval: 900, enabled: false },
];
