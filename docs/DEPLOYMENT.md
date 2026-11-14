# 部署与网络配置

## 零、环境准备（一次性）

1. 安装 **Python 3.8+** 与 **MySQL 8**（端口默认 3306，记住 root 密码）。
2. 安装 Python 依赖：

```bash
pip install pymysql cryptography
```

3. 在 `server/` 下准备 `.env`，至少配置数据库密码：

```
CHATHYX_DB_HOST=127.0.0.1
CHATHYX_DB_PORT=3306
CHATHYX_DB_USER=root
CHATHYX_DB_NAME=chathyx
CHATHYX_DB_PASSWORD=你的数据库密码
CHATHYX_ADMIN_PASSWORD=admin123
```

4. 生成 TLS 证书（首次部署）：

```bash
cd server
python gen_cert.py
```

## 一、局域网部署（默认，最简单）

手机和服务器电脑连同一个 WiFi（同一局域网）。

1. 启动服务器：`python chat_server.py`，监听 `0.0.0.0:4444`（TLS）。
2. 电脑执行 `ipconfig`，查看 WLAN 的 IPv4 地址，例如 `192.168.100.124`。
3. 填入 Android 端 `AgentPrefs.DEFAULT_HOST`，端口填 `4444`。
4. 把证书复制到 `res/raw/chathyx_cert` 后构建：

```bash
mkdir app/src/main/res/raw
copy /Y server/certs/cert.pem app/src/main/res/raw/chathyx_cert
gradlew.bat assembleDebug
```

5. 若开了防火墙，放行 4444 端口入站连接（首次启动弹窗时点“允许”即可）。

## 二、Android 模拟器

模拟器访问宿主机用固定地址 `10.0.2.2`：

```java
public static final String DEFAULT_HOST = "10.0.2.2";
public static final int DEFAULT_PORT = 4444;
```

## 三、跨网络 / 外网访问

手机不在同一局域网（如用移动流量）时，需要让手机能访问服务器。

> 由于客户端做证书锁定、不校验主机名，只要服务器证书不变，通过任意域名 / IP 连接都能完成 TLS 握手，穿透地址变化不影响证书信任。

### 方案 1：内网穿透（cpolar / ngrok 等）

```bash
cpolar tcp 4444
```

得到公网地址形如 `xxx.tcp.vip.cpolar.cn:12345`，把域名填入 `DEFAULT_HOST`、端口填入 `DEFAULT_PORT`，重新构建 APK。免费版地址每次会变，需同步修改。

### 方案 2：frp（有公网服务器，推荐）

- 云服务器部署 `frps`，本机部署 `frpc` 把远程端口转发到本地 4444。
- 手机连云服务器公网 IP 与远程端口，TLS 端到端加密在穿透通道内继续生效。

### 方案 3：Tailscale（组网简单，推荐）

- 服务器电脑和手机都装 Tailscale 并登录同一账号。
- 手机直接用电脑的 Tailscale IP 连接，无需公网 IP、无需开放端口。

## 四、直接部署到公网服务器

把 `server/` 放到云服务器，安装 MySQL 与依赖后运行：

```bash
nohup python3 chat_server.py > server.log 2>&1 &
```

手机连接云服务器公网 IP 与 4444，并在安全组 / 防火墙放行该端口。

> 安全提醒：TLS 已保证传输加密；暴露公网时建议再配合防火墙限制来源，并使用强密码。

## 五、修改服务器端口

在 `server/chat_server.py` 顶部修改 `SERVER_PORT`，同时把 Android 端 `AgentPrefs.DEFAULT_PORT` 改成相同端口，重新构建。

## 六、证书更新 / 换机注意

- 重新运行 `gen_cert.py` 会生成**新证书**，客户端 `res/raw/chathyx_cert` 必须同步替换，否则 TLS 握手失败。
- 想让现有 APK 继续可用，就保留原 `certs/` 不重新生成。
- 私钥 `certs/key.pem` 与 `.env` 属敏感文件，提交或分享前务必处理。

## 七、数据迁移（保留账号与聊天记录）

旧机器导出：

```bash
mysqldump -u root -p chathyx > chathyx.sql
```

新机器导入：

```bash
mysql -u root -p -e "CREATE DATABASE chathyx DEFAULT CHARSET utf8mb4;"
mysql -u root -p chathyx < chathyx.sql
```
