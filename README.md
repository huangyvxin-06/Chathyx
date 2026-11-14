# Chathyx

> Chat with hyx —— 一个基于 Python + 原生 Android 的轻量级多人聊天应用。 可以直接在amdroid studio中运行构建

服务器端使用 Python 编写，数据全部存入 MySQL，通信全程 TLS 加密；Android 端使用原生 Java，支持多客户端同时在线，通过自定义的 TLS 帧协议通信。


## 安全机制

| 方面 | 实现 |
| --- | --- |
| 传输加密 | 全程 TLS（实测 TLS 1.3 / AES-256-GCM），明文连接被服务器拒绝 |
| 防中间人 | 客户端证书锁定，只信任内置证书，系统其他 CA 无法冒充 |
| 密码存储 | PBKDF2-HMAC-SHA256，每用户随机盐、20 万次迭代，抗彩虹表 |
| 密码强度 | 8–64 位、含字母和数字、无空格 |
| 时序攻击 | 密码与哈希比较使用 `hmac.compare_digest` |
| SQL 注入 | 全部参数化查询，用户输入不拼接进 SQL |
| XSS | Web 后台所有动态输出经 `html.escape` 转义；Cookie 加 `HttpOnly` |

## 技术栈

| 端 | 技术 |
| --- | --- |
| 服务器 | Python 3，依赖 `pymysql`（MySQL 驱动）、`cryptography`（生成证书） |
| 存储 | MySQL 8（`users` / `friend_requests` / `friends` / `messages` 四表，外键级联） |
| Android | 原生 Java、AndroidX、Material Components |
| 通信 | TLS 长连接，帧 = 4 字节大端长度 + JSON payload |

## 目录结构

```
Chathyx/
├── app/                    # Android 应用模块（Android Studio 工程）
│   └── src/main/
│       ├── java/com/hyx/mcmy/
│       │   ├── agent/      # AgentClient、TlsTrust、Protocol、ChatApi、AgentPrefs
│       │   ├── service/    # 前台保活服务
│       │   └── *.java      # 界面、通知、未读、会话管理等
│       └── res/            # 布局、图标、主题等资源
├── server/
│   ├── chat_server.py      # 聊天服务器（TLS + MySQL）
│   ├── admin_server.py     # Web 管理后台（8080）
│   ├── db.py               # 连接配置、自动读 .env、建表迁移
│   ├── security.py         # PBKDF2 哈希、密码强度校验
│   ├── gen_cert.py         # 生成自签 TLS 证书
│   ├── schema.sql          # 建库建表 DDL
│   └── .env                # 环境变量（敏感，需自行配置）
├── docs/                   # 架构与部署文档
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat   # Gradle Wrapper
```

## 快速开始

### 一、准备环境

- 安装 **android studio**(我用的2026版)、**Python 3.8+**(我用的3.14.3)、**MySQL 8**（端口默认 3306，记住 root 密码）。
- 安装 Python 依赖：

```bash
pip install pymysql cryptography
```

### 二、配置 .env

在 `server/` 下创建 `.env`（可参考下面的键），把数据库密码改成本机 MySQL 的 root 密码：

```
CHATHYX_DB_HOST=127.0.0.1
CHATHYX_DB_PORT=3306
CHATHYX_DB_USER=root
CHATHYX_DB_NAME=chathyx
CHATHYX_DB_PASSWORD=你的数据库密码
CHATHYX_ADMIN_PASSWORD=admin123
```

> `.env` 含敏感信息，对外分享或提交代码前请改为占位值。

### 三、生成 TLS 证书

```bash
cd server
python gen_cert.py
```

会在 `server/certs/` 下生成 `cert.pem`（证书）与 `key.pem`（私钥）。

### 四、启动聊天服务器

```bash
python chat_server.py
```
会自动创建数据库和表

### 五、启动 Web 管理后台（可选）

```bash
python admin_server.py
```

浏览器打开 `http://127.0.0.1:8080`，用户名 `admin`，密码为 `.env` 中修改的 `CHATHYX_ADMIN_PASSWORD`。

### 六、配置并构建 Android 客户端

服务器地址配置在：

```
app/src/main/java/com/hyx/mcmy/agent/AgentPrefs.java
```

如果不想使用局域网在同一网段下玩，可以使用cpolar 进行测试，cpolar tcp 4444,生成类似于tcp://1.tcp.cpolar.cn:23456
可以将192.168.100.124修改为1.tcp.cpolar.cn,4444端口修改为23456

```java
public static final String DEFAULT_HOST = "192.168.100.124"; // 服务器电脑 IP
public static final int DEFAULT_PORT = 4444;
```

由于客户端做了证书锁定，还需把服务器证书复制到 `res/raw/chathyx_cert`：

```bash
mkdir app/src/main/res/raw
copy /Y server/certs/cert.pem app/src/main/res/raw/chathyx_cert
```

然后构建：

```bash
通过android studio 直接进行构建
```

APK 输出于 `app/build/outputs/apk/debug/app-debug.apk`。



## License

本项目采用 [MIT License](LICENSE)，是利用ai加手搓完成，可自由用于学习与个人项目。
