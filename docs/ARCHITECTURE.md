# 架构与通信协议

## 整体架构

```
 Android 客户端 A ─┐
 Android 客户端 B ─┼── TLS 长连接（帧协议）──> chat_server.py（多线程）
 Android 客户端 C ─┘                              │
                                                  └── MySQL（库 chathyx，四表）
```

- 服务器在 `0.0.0.0:4444` 监听，每接受一个 TCP 连接先用 TLS 握手，再开启独立线程处理。
- 客户端通过 `SSLSocket` 建立连接，并做证书锁定，只信任内置证书。
- 业务数据通过 `ChatService` 统一管理，并用一把锁保证多线程下的读写安全。
- 所有 SQL 均为参数化查询，杜绝 SQL 注入。

## 传输安全（TLS）

- 服务器 `ssl.SSLContext` 最低 TLS 1.2，仅启用前向保密的 AEAD（GCM）套件，禁用弱套件与空加密。
- 实测协商为 **TLS 1.3 / AES-256-GCM**。
- 证书由 `gen_cert.py` 生成（RSA 2048，有效期 10 年）。
- 客户端 `TlsTrust` 把内置证书放入独立 KeyStore，只信任该证书，系统其他 CA 无法冒充服务器（防中间人）。
- 明文连接在握手阶段即被关闭。

## 帧协议

TLS 连接上的每一帧由两部分组成：

```
┌──────────────────┬─────────────────────────┐
│  4 字节大端长度   │  payload（长度字节）      │
│  (uint32, >I)    │  JSON 文本（UTF-8）       │
└──────────────────┴─────────────────────────┘
```

- 单帧上限 **10 MB**，超过即判定为异常帧并断开。
- 帧的读写在 TLS 提供的密文流上进行，应用层无需自行加密。

## 请求与响应模型

客户端请求是带 `action` 字段的 JSON 对象，例如发送消息：

```json
{
  "id": 12,
  "action": "send_msg",
  "self": "alice",
  "to": "bob",
  "content": "你好"
}
```

服务器响应复用同一个 `id`，并带 `ok` 字段：

```json
{ "id": 12, "ok": true, "message": { "...": "..." } }
```

失败时：

```json
{ "id": 12, "ok": false, "error": "还不是好友，无法发送消息" }
```

客户端通过 `id` 将响应与本地等待盒（pending box）匹配，实现同步请求-响应语义。

## 心跳

- 任一端发送 `{"type": "ping"}`，对端回复 `{"type": "pong"}`。
- 用于检测连接存活、维持 NAT 映射。

## Action 一览

| action | 说明 | 关键字段 |
| --- | --- | --- |
| `register` | 注册 | username, password |
| `login` | 登录 | username, password |
| `search` | 搜索用户 | keyword, self |
| `friend_req` | 发送好友申请 | self, username |
| `list_req` | 收到的好友申请 | self |
| `friend_respond` | 响应好友申请 | self, from, accept |
| `list_friends` | 好友列表 | self |
| `send_msg` | 发送消息 | self, to, content |
| `list_msgs` | 拉取历史消息 | self, peer, sinceId |
| `get_profile` | 获取个人资料 | self |
| `get_avatar` | 获取头像 | username |
| `set_avatar` | 上传头像 | self, avatar(base64) |
| `change_password` | 修改密码 | self, oldPassword, newPassword |
| `delete_account` | 注销账号 | self, password |

## 数据存储（MySQL）

库 `chathyx`，字符集 utf8mb4，共四张表，外键全部 `ON DELETE CASCADE`：

| 表 | 内容 |
| --- | --- |
| `users` | 用户（PBKDF2 密码哈希、头像名、创建/登录时间） |
| `friend_requests` | 好友申请（pending / accepted / rejected） |
| `friends` | 好友关系对（约定 user_a < user_b） |
| `messages` | 全部消息（一对一） |

删除某用户时，InnoDB 在同一事务内级联清理其申请、好友与消息，无孤儿数据。头像图片仍以文件形式存于 `server/data/avatars/`，数据库只存文件名。

## 密码安全

- `security.py` 使用 PBKDF2-HMAC-SHA256：随机 16 字节盐、20 万次迭代。
- 存储格式：`pbkdf2_sha256$迭代次数$盐(base64)$哈希(base64)`。
- 校验使用 `hmac.compare_digest`，防时序侧信道。
- 兼容旧版 64 位纯 SHA-256，老用户登录成功后自动平滑升级为 PBKDF2。

## 未读与通知

- `UnreadManager`：按会话记录已读到的最大消息 id（SharedPreferences `mc_read_id`），peer 发来且 id 更大的消息计为未读。
- `ChatActivity`：列表项与底部“消息”tab 显示未读红点，进入会话由 `ChatRoomActivity` 标记已读、红点清零。
- `NotificationHelper` + `ForegroundState`：后台轮询发现新消息时发系统通知，正在查看该会话时抑制。

## Android 端模块职责

| 类 | 职责 |
| --- | --- |
| `agent/AgentClient` | TLS 连接、自动重连、请求-响应、心跳 |
| `agent/TlsTrust` | 证书锁定，构建只信任内置证书的 SSLContext |
| `agent/Protocol` | 帧读写（长度前缀编解码） |
| `agent/ChatApi` | 各业务动作的静态封装 |
| `agent/AgentPrefs` | 服务器地址与端口配置 |
| `service/AgentService` | 前台服务、连接保活、后台轮询通知 |
| `UnreadManager` | 会话已读位置与未读统计 |
| `NotificationHelper` | 通知渠道、发通知与取消 |
| `ForegroundState` | 前后台与当前会话状态 |
| `SessionManager` | 本地登录态（SharedPreferences） |
| `AvatarLoader` | 头像本地缓存与圆形位图 |
