# -*- coding: utf-8 -*-
"""
Chathyx - Web 管理后台（基于 Python 标准库 http.server）

功能：
  - 管理员登录 / 退出（基于 Cookie 的会话）
  - 用户列表（注册时间、最近登录、是否有头像）
  - 管理员重置任意用户密码（不需要该用户的旧密码）

管理员密码通过环境变量配置，代码中不含真实密码：
  CHATHYX_ADMIN_PASSWORD  默认 CHANGE_ME（占位，必须用环境变量覆盖）
  管理员用户名固定为 admin

启动（PowerShell）：
  $env:CHATHYX_DB_PASSWORD="数据库密码"
  $env:CHATHYX_ADMIN_PASSWORD="管理员密码"
  python admin_server.py
然后浏览器打开 http://127.0.0.1:8080
"""

import html
import os
import secrets
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

import db
import security

ADMIN_HOST = "0.0.0.0"
ADMIN_PORT = 8080
ADMIN_USER = "admin"
ADMIN_PASSWORD = os.environ.get("CHATHYX_ADMIN_PASSWORD", "CHANGE_ME")

# token -> 过期时间（简单的内存会话）
SESSIONS = {}
SESSION_TTL = 30 * 60


def page(title, body):
    return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
<style>
 *{box-sizing:border-box} body{margin:0;font-family:-apple-system,Segoe UI,Roboto,
 'Microsoft YaHei',sans-serif;background:#f2f5f9;color:#1f2d3d}
 .bar{background:#1565c0;color:#fff;padding:14px 22px;display:flex;
 justify-content:space-between;align-items:center}
 .bar a{color:#fff;text-decoration:none;margin-left:16px}
 .wrap{max-width:900px;margin:28px auto;padding:0 16px}
 .card{background:#fff;border-radius:12px;padding:22px;box-shadow:0 4px 14px rgba(0,0,0,.06)}
 table{width:100%%;border-collapse:collapse} th,td{text-align:left;padding:10px 8px;
 border-bottom:1px solid #eef1f5;font-size:14px}
 input{width:100%%;padding:11px 12px;margin:8px 0 16px;border:1px solid #d4dae2;
 border-radius:8px;font-size:15px}
 button{background:#1565c0;color:#fff;border:0;border-radius:8px;padding:11px 20px;
 font-size:15px;cursor:pointer}
 .muted{color:#7a8aa0;font-size:13px}.msg{padding:10px 14px;border-radius:8px;
 margin-bottom:14px}.ok{background:#e6f4ea;color:#1e7d3a}.err{background:#fdecea;color:#b3261e}
 a.btn{display:inline-block;background:#1565c0;color:#fff;text-decoration:none;
 padding:7px 14px;border-radius:7px;font-size:13px}
</style></head><body>%s</body></html>""" % (html.escape(title), body)


def fmt_ts(ts):
    if not ts:
        return "—"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ts))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    # ---------- 会话工具 ----------
    def _token(self):
        c = SimpleCookie(self.headers.get("Cookie", ""))
        if "sid" in c:
            return c["sid"].value
        return None

    def _is_login(self):
        tok = self._token()
        exp = SESSIONS.get(tok)
        if exp and exp > time.time():
            return True
        return False

    def _send(self, content, status=200, set_cookie=None, redirect=None):
        if redirect:
            self.send_response(302)
            self.send_header("Location", redirect)
            if set_cookie:
                self.send_header("Set-Cookie", set_cookie)
            self.end_headers()
            return
        data = content.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        if set_cookie:
            self.send_header("Set-Cookie", set_cookie)
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        return parse_qs(raw)

    # ---------- GET ----------
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/login":
            self._send(page("管理员登录", self._login_form()))
            return
        if not self._is_login():
            self._send(None, redirect="/login")
            return
        if path == "/logout":
            tok = self._token()
            SESSIONS.pop(tok, None)
            self._send(None, set_cookie="sid=; Max-Age=0", redirect="/login")
            return
        if path == "/reset":
            qs = parse_qs(self.path.split("?")[1]) if "?" in self.path else {}
            self._send(page("重置密码", self._reset_form(qs.get("u", [""])[0])))
            return
        self._send(page("用户管理", self._user_list()))

    # ---------- POST ----------
    def do_POST(self):
        path = self.path.split("?")[0]
        form = self._body()
        if path == "/login":
            u = form.get("username", [""])[0]
            p = form.get("password", [""])[0]
            if u == ADMIN_USER and p == ADMIN_PASSWORD:
                tok = secrets.token_hex(16)
                SESSIONS[tok] = time.time() + SESSION_TTL
                self._send(None, set_cookie="sid=%s; HttpOnly; Path=/" % tok,
                           redirect="/")
                return
            body = self._login_form(alert="用户名或密码错误")
            self._send(page("管理员登录", body), status=401)
            return
        if not self._is_login():
            self._send(None, redirect="/login")
            return
        if path == "/reset":
            self._do_reset(form)
            return
        self._send(None, redirect="/")

    # ---------- 页面片段 ----------
    def _bar(self):
        return '<div class="bar"><b>Chathyx 管理后台</b><span>' \
               '<a href="/">用户管理</a><a href="/logout">退出</a></span></div>'

    def _login_form(self, alert=None):
        msg = '<div class="msg err">%s</div>' % html.escape(alert) if alert else ""
        return '<div class="wrap"><div class="card"><h3>管理员登录</h3>%s' \
               '<form method="post" action="/login">' \
               '<label>用户名</label><input name="username" autofocus>' \
               '<label>密码</label><input name="password" type="password">' \
               '<button type="submit">登录</button></form></div></div>' % msg

    def _user_list(self):
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT username, created_at, last_login, avatar "
                            "FROM users ORDER BY created_at")
                rows = cur.fetchall()
        finally:
            conn.close()
        trs = ""
        for name, created, last, avatar in rows:
            trs += "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td>" \
                   '<td><a class="btn" href="/reset?u=%s">重置密码</a></td></tr>' % (
                       html.escape(name), fmt_ts(created), fmt_ts(last),
                       "有" if avatar else "—", html.escape(name))
        return self._bar() + ('<div class="wrap"><div class="card"><h3>用户列表'
                              '（共 %d 人）</h3><table><tr><th>用户名</th><th>注册时间'
                              '</th><th>最近登录</th><th>头像</th><th>操作</th></tr>'
                              "%s</table></div></div>") % (len(rows), trs)

    def _reset_form(self, username, alert=None):
        msg = '<div class="msg err">%s</div>' % html.escape(alert) if alert else ""
        return self._bar() + ('<div class="wrap"><div class="card"><h3>重置密码</h3>'
                              "%s<form method='post' action='/reset'>"
                              "<label>用户名</label><input name='username' value='%s' readonly>"
                              "<label>新密码（8-64 位，含字母和数字）</label>"
                              "<input name='new_password' type='password' autofocus>"
                              "<button type='submit'>确认重置</button> "
                              "<a href='/' class='btn' style='background:#7a8aa0'>返回</a>"
                              "</form></div></div>") % (msg, html.escape(username))

    def _do_reset(self, form):
        username = form.get("username", [""])[0]
        new_pw = form.get("new_password", [""])[0]
        weak = security.validate_password_strength(new_pw)
        if weak:
            self._send(page("重置密码",
                            self._reset_form(username, weak)))
            return
        conn = db.get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT 1 FROM users WHERE username=%s", (username,))
                if not cur.fetchone():
                    self._send(page("重置密码",
                                    self._reset_form(username, "用户不存在")))
                    return
                cur.execute("UPDATE users SET password_hash=%s WHERE username=%s",
                            (security.hash_password(new_pw), username))
            conn.commit()
        finally:
            conn.close()
        ok = self._bar() + ('<div class="wrap"><div class="card">'
                            '<div class="msg ok">已成功重置用户「%s」的密码</div>'
                            '<a class="btn" href="/">返回用户列表</a>'
                            "</div></div>") % html.escape(username)
        self._send(page("操作成功", ok))


def main():
    db.init_db()
    srv = ThreadingHTTPServer((ADMIN_HOST, ADMIN_PORT), Handler)
    print("[*] Chathyx 管理后台已启动: http://%s:%d" % (ADMIN_HOST, ADMIN_PORT))
    print("    管理员: %s，Ctrl+C 退出" % ADMIN_USER)
    srv.serve_forever()


if __name__ == "__main__":
    main()
