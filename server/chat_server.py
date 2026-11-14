# -*- coding: utf-8 -*-
"""
Chathyx - 纯多人聊天服务器（MySQL 存储）

工作方式：
  1. 监听 TCP 4444，等待 Android 客户端连接（可同时接入多个客户端）
  2. 客户端连接后直接在同一连接上发送聊天业务帧（JSON），服务器处理后原样回复
  3. 用户 / 好友申请 / 好友关系 / 消息全部存入 MySQL，密码仅保存 SHA-256 哈希
  4. 头像图片仍以文件形式保存在 data/avatars/，数据库只存文件名

数据库连接参数见 db.py（通过环境变量配置，代码中不含真实密码）。
仅用于自己或获授权的学习用途。
"""

import base64
import json
import os
import socket
import ssl
import struct
import threading
import time

import db
import security

# ---------------- 配置 ----------------
SERVER_HOST = "0.0.0.0"   # 监听所有网卡，局域网内手机可连
SERVER_PORT = 4444
# TLS 证书（自签，客户端做证书锁定）
CERT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "certs")
CERT_FILE = os.path.join(CERT_DIR, "cert.pem")
KEY_FILE = os.path.join(CERT_DIR, "key.pem")
# -------------------------------------

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
AVATAR_DIR = os.path.join(DATA_DIR, "avatars")


# ============================================================
# 帧协议（与 Android 端一致）：4 字节大端长度 + payload
# ============================================================

def recv_exact(conn, size):
    buf = b""
    while len(buf) < size:
        chunk = conn.recv(size - len(buf))
        if not chunk:
            raise ConnectionError("连接已断开")
        buf += chunk
    return buf


MAX_FRAME = 10 * 1024 * 1024   # 单帧最大 10MB，防止超大包耗尽内存


def read_frame(conn):
    header = recv_exact(conn, 4)
    (length,) = struct.unpack(">I", header)
    if length > MAX_FRAME:
        raise IOError("帧长度异常: %d" % length)
    return recv_exact(conn, length)


def write_json(conn, obj):
    payload = json.dumps(obj).encode("utf-8")
    conn.sendall(struct.pack(">I", len(payload)) + payload)


# ============================================================
# 聊天业务：用户 / 好友请求 / 好友关系 / 消息（MySQL，密码 SHA-256）
# ============================================================

class ChatService:
    def __init__(self):
        self.lock = threading.Lock()

    # ---------- 注册 / 登录 ----------
    def register(self, username, password):
        username = (username or "").strip()
        if not self._valid_name(username):
            return {"ok": False, "error": "用户名只能包含字母、数字、下划线和中文，长度2-20"}
        weak = security.validate_password_strength(password)
        if weak:
            return {"ok": False, "error": weak}
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT 1 FROM users WHERE username=%s", (username,))
                if cur.fetchone():
                    return {"ok": False, "error": "用户名已被注册"}
                cur.execute(
                    "INSERT INTO users(username, password_hash, created_at) "
                    "VALUES (%s, %s, %s)",
                    (username, security.hash_password(password), time.time()))
            conn.commit()
            return {"ok": True, "username": username}
        finally:
            conn.close()

    def login(self, username, password):
        username = (username or "").strip()
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT password_hash FROM users WHERE username=%s",
                            (username,))
                row = cur.fetchone()
                stored = row[0] if row else None
                if stored is None or not security.verify_password(password, stored):
                    return {"ok": False, "error": "用户名或密码错误"}
                # 旧哈希在登录成功后平滑升级为 PBKDF2
                if security.needs_rehash(stored):
                    cur.execute(
                        "UPDATE users SET password_hash=%s WHERE username=%s",
                        (security.hash_password(password), username))
                cur.execute("UPDATE users SET last_login=%s WHERE username=%s",
                            (time.time(), username))
            conn.commit()
            return {"ok": True, "username": username}
        finally:
            conn.close()

    @staticmethod
    def _valid_name(name):
        if len(name) < 2 or len(name) > 20:
            return False
        import re
        return re.fullmatch(r"[a-zA-Z0-9_\u4e00-\u9fa5]+", name) is not None

    # ---------- 好友关系辅助 ----------
    def _are_friends(self, a, b):
        x, y = sorted([a, b])
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT 1 FROM friends WHERE user_a=%s AND user_b=%s",
                            (x, y))
                return cur.fetchone() is not None
        finally:
            conn.close()

    def _pending_between(self, a, b):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id FROM friend_requests WHERE status='pending' AND "
                    "((from_user=%s AND to_user=%s) OR (from_user=%s AND to_user=%s))",
                    (a, b, b, a))
                return cur.fetchone() is not None
        finally:
            conn.close()

    # ---------- 搜索 ----------
    def search(self, keyword, me):
        kw = (keyword or "").strip().lower()
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT username FROM users")
                names = [r[0] for r in cur.fetchall()]
        finally:
            conn.close()
        result = []
        for name in names:
            if name == me:
                continue
            if kw and kw not in name.lower():
                continue
            if self._are_friends(me, name):
                rel = "friends"
            elif self._pending_between(me, name):
                rel = "pending"
            else:
                rel = "none"
            result.append({"username": name, "relation": rel,
                           "avatarVersion": self._avatar_version(name)})
        return {"ok": True, "users": result[:50]}

    # ---------- 好友请求 ----------
    def friend_request(self, me, target):
        target = (target or "").strip()
        if target == me:
            return {"ok": False, "error": "不能添加自己"}
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT 1 FROM users WHERE username=%s", (target,))
                if not cur.fetchone():
                    return {"ok": False, "error": "用户不存在"}
            if self._are_friends(me, target):
                return {"ok": False, "error": "已经是好友"}
            if self._pending_between(me, target):
                return {"ok": False, "error": "已发送过好友申请，等待对方确认"}
            with conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO friend_requests(from_user, to_user, status, created_at) "
                    "VALUES (%s, %s, 'pending', %s)",
                    (me, target, time.time()))
            conn.commit()
            return {"ok": True}
        finally:
            conn.close()

    def list_incoming(self, me):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT from_user, created_at FROM friend_requests "
                    "WHERE to_user=%s AND status='pending'", (me,))
                rows = cur.fetchall()
        finally:
            conn.close()
        items = [{"from": f, "createdAt": ts,
                  "avatarVersion": self._avatar_version(f)} for f, ts in rows]
        return {"ok": True, "requests": items}

    def friend_respond(self, me, from_user, accept):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id FROM friend_requests "
                    "WHERE to_user=%s AND from_user=%s AND status='pending' FOR UPDATE",
                    (me, from_user))
                if not cur.fetchone():
                    return {"ok": False, "error": "申请不存在或已处理"}
                if accept:
                    cur.execute(
                        "UPDATE friend_requests SET status='accepted' "
                        "WHERE to_user=%s AND from_user=%s AND status='pending'",
                        (me, from_user))
                    x, y = sorted([me, from_user])
                    cur.execute(
                        "INSERT IGNORE INTO friends(user_a, user_b, created_at) "
                        "VALUES (%s, %s, %s)", (x, y, time.time()))
                else:
                    cur.execute(
                        "UPDATE friend_requests SET status='rejected' "
                        "WHERE to_user=%s AND from_user=%s AND status='pending'",
                        (me, from_user))
            conn.commit()
            return {"ok": True, "accepted": bool(accept)}
        finally:
            conn.close()

    def list_friends(self, me):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT user_a, user_b FROM friends "
                    "WHERE user_a=%s OR user_b=%s", (me, me))
                rows = cur.fetchall()
        finally:
            conn.close()
        names = []
        for a, b in rows:
            names.append(b if a == me else a)
        items = [{"username": n, "avatarVersion": self._avatar_version(n)}
                 for n in sorted(names)]
        return {"ok": True, "friends": items}

    # ---------- 消息 ----------
    def send_message(self, me, to, content):
        content = (content or "")
        if not content.strip():
            return {"ok": False, "error": "消息不能为空"}
        if not self._are_friends(me, to):
            return {"ok": False, "error": "还不是好友，无法发送消息"}
        now = time.time()
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO messages(from_user, to_user, content, created_at) "
                    "VALUES (%s, %s, %s, %s)", (me, to, content, now))
                msg_id = cur.lastrowid
            conn.commit()
            msg = {"id": msg_id, "from": me, "to": to,
                   "content": content, "createdAt": now}
            return {"ok": True, "message": msg}
        finally:
            conn.close()

    def list_messages(self, me, peer, since_id=0):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id, from_user, to_user, content, created_at FROM messages "
                    "WHERE id > %s AND ((from_user=%s AND to_user=%s) "
                    "OR (from_user=%s AND to_user=%s))",
                    (since_id, me, peer, peer, me))
                rows = cur.fetchall()
        finally:
            conn.close()
        items = [{"id": i, "from": f, "to": t, "content": c, "createdAt": ts}
                 for i, f, t, c, ts in rows]
        return {"ok": True, "messages": items}

    # ---------- 个人资料 / 头像 ----------
    def _avatar_name(self, username, cur):
        cur.execute("SELECT avatar FROM users WHERE username=%s", (username,))
        row = cur.fetchone()
        return row[0] if row else None

    def _avatar_version(self, username):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                name = self._avatar_name(username, cur)
        finally:
            conn.close()
        if not name:
            return 0
        p = os.path.join(AVATAR_DIR, name)
        return int(os.path.getmtime(p)) if os.path.isfile(p) else 0

    def get_avatar(self, target):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                name = self._avatar_name(target, cur)
        finally:
            conn.close()
        ver = self._avatar_version(target)
        if not name or ver == 0:
            return {"ok": True, "hasAvatar": False, "avatar": "", "avatarVersion": 0}
        with open(os.path.join(AVATAR_DIR, name), "rb") as f:
            b64 = base64.b64encode(f.read()).decode("ascii")
        return {"ok": True, "hasAvatar": True, "avatar": b64,
                "avatarVersion": ver}

    def get_profile(self, me):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT created_at, avatar FROM users WHERE username=%s", (me,))
                row = cur.fetchone()
        finally:
            conn.close()
        if row is None:
            return {"ok": False, "error": "账号不存在"}
        created_at, avatar_name = row
        avatar_b64 = ""
        if avatar_name:
            p = os.path.join(AVATAR_DIR, avatar_name)
            if os.path.isfile(p):
                with open(p, "rb") as f:
                    avatar_b64 = base64.b64encode(f.read()).decode("ascii")
        return {
            "ok": True,
            "username": me,
            "createdAt": created_at,
            "hasAvatar": bool(avatar_b64),
            "avatar": avatar_b64,
        }

    def set_avatar(self, me, data_b64):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT avatar FROM users WHERE username=%s", (me,))
                row = cur.fetchone()
                if row is None:
                    return {"ok": False, "error": "账号不存在"}
                old = row[0]
            try:
                raw = base64.b64decode(data_b64 or "")
            except Exception:
                return {"ok": False, "error": "头像数据无效"}
            if not raw or len(raw) > 8 * 1024 * 1024:
                return {"ok": False, "error": "头像需小于 8MB"}
            os.makedirs(AVATAR_DIR, exist_ok=True)
            if old:
                try:
                    os.remove(os.path.join(AVATAR_DIR, old))
                except OSError:
                    pass
            avatar_name = "%s.jpg" % me
            with open(os.path.join(AVATAR_DIR, avatar_name), "wb") as f:
                f.write(raw)
            with conn.cursor() as cur:
                cur.execute("UPDATE users SET avatar=%s WHERE username=%s",
                            (avatar_name, me))
            conn.commit()
            return {"ok": True}
        finally:
            conn.close()

    # ---------- 修改密码 ----------
    def change_password(self, me, old_password, new_password):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT password_hash FROM users WHERE username=%s",
                            (me,))
                row = cur.fetchone()
                if row is None:
                    return {"ok": False, "error": "账号不存在"}
                stored = row[0]
                if not security.verify_password(old_password, stored):
                    return {"ok": False, "error": "原密码不正确"}
                weak = security.validate_password_strength(new_password)
                if weak:
                    return {"ok": False, "error": weak}
                if security.verify_password(new_password, stored):
                    return {"ok": False, "error": "新密码不能与原密码相同"}
                cur.execute("UPDATE users SET password_hash=%s WHERE username=%s",
                            (security.hash_password(new_password), me))
            conn.commit()
            return {"ok": True}
        finally:
            conn.close()

    # ---------- 注销账号 ----------
    def delete_account(self, me, password):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT password_hash, avatar FROM users WHERE username=%s",
                            (me,))
                row = cur.fetchone()
                if row is None:
                    return {"ok": False, "error": "账号不存在"}
                if not security.verify_password(password, row[0]):
                    return {"ok": False, "error": "密码不正确，无法注销"}
                avatar_name = row[1]
                # 外键 ON DELETE CASCADE 会自动清理申请 / 好友 / 消息
                cur.execute("DELETE FROM users WHERE username=%s", (me,))
            conn.commit()
            if avatar_name:
                try:
                    os.remove(os.path.join(AVATAR_DIR, avatar_name))
                except OSError:
                    pass
            return {"ok": True}
        finally:
            conn.close()


CHAT = ChatService()


# ============================================================
# 业务分发
# ============================================================

def dispatch_action(frame):
    action = frame.get("action")
    me = frame.get("self")
    if action == "register":
        return CHAT.register(frame.get("username"), frame.get("password"))
    if action == "login":
        return CHAT.login(frame.get("username"), frame.get("password"))
    if action == "search":
        return CHAT.search(frame.get("keyword"), me)
    if action == "friend_req":
        return CHAT.friend_request(me, frame.get("username"))
    if action == "list_req":
        return CHAT.list_incoming(me)
    if action == "friend_respond":
        return CHAT.friend_respond(me, frame.get("from"), frame.get("accept"))
    if action == "list_friends":
        return CHAT.list_friends(me)
    if action == "send_msg":
        return CHAT.send_message(me, frame.get("to"), frame.get("content"))
    if action == "list_msgs":
        return CHAT.list_messages(me, frame.get("peer"),
                                  frame.get("sinceId", 0))
    if action == "get_profile":
        return CHAT.get_profile(me)
    if action == "get_avatar":
        return CHAT.get_avatar(frame.get("username"))
    if action == "set_avatar":
        return CHAT.set_avatar(me, frame.get("avatar"))
    if action == "change_password":
        return CHAT.change_password(me, frame.get("oldPassword"),
                                    frame.get("newPassword"))
    if action == "delete_account":
        return CHAT.delete_account(me, frame.get("password"))
    return {"ok": False, "error": "未知指令"}


# ============================================================
# 每个客户端连接一个线程：连接后直接收发聊天业务帧
# ============================================================

def client_thread(conn, addr):
    print("[+] 客户端接入: %s:%d" % addr)
    try:
        while True:
            frame = json.loads(read_frame(conn).decode("utf-8"))
            # 心跳：客户端/服务器均可发 ping，对端回 pong
            if frame.get("type") == "ping":
                write_json(conn, {"type": "pong"})
                continue
            if "action" not in frame:
                continue
            rid = frame.get("id")
            try:
                with CHAT.lock:
                    resp = dispatch_action(frame)
            except Exception as e:
                resp = {"ok": False, "error": str(e)}
            resp["id"] = rid
            write_json(conn, resp)
    except Exception as exc:
        print("[-] 客户端 %s 断开: %s" % (addr[0], exc))
    finally:
        try:
            conn.close()
        except Exception:
            pass


def build_ssl_context():
    """构建仅用于服务器的 TLS 上下文：加载自签证书，最低 TLS 1.2。"""
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(certfile=CERT_FILE, keyfile=KEY_FILE)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    # 仅启用前向保密的 AEAD 套件，禁用弱套件/空加密
    ctx.set_ciphers(
        "ECDHE-ECDSA-AES256-GCM-SHA384:"
        "ECDHE-RSA-AES256-GCM-SHA384:"
        "ECDHE-ECDSA-AES128-GCM-SHA256:"
        "ECDHE-RSA-AES128-GCM-SHA256")
    return ctx


def main():
    # 启动前确保数据库、表存在，并执行结构迁移
    db.init_db()
    db.run_migrations()
    os.makedirs(DATA_DIR, exist_ok=True)

    tls_ctx = build_ssl_context()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((SERVER_HOST, SERVER_PORT))
    srv.listen(16)
    print("[*] Chathyx 聊天服务器已启动(TLS): %s:%d" % (SERVER_HOST, SERVER_PORT))
    print("    数据库: %s@%s:%s/%s" % (db.DB_USER, db.DB_HOST,
                                      db.DB_PORT, db.DB_NAME))
    print("    最低 TLS 1.2，Ctrl+C 退出")
    try:
        while True:
            raw, addr = srv.accept()
            try:
                conn = tls_ctx.wrap_socket(raw, server_side=True)
            except ssl.SSLError as e:
                print("[-] TLS 握手失败 %s: %s" % (addr[0], e))
                try:
                    raw.close()
                except OSError:
                    pass
                continue
            threading.Thread(target=client_thread,
                             args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\n[*] 已退出")


if __name__ == "__main__":
    main()
