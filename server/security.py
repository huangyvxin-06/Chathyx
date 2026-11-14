# -*- coding: utf-8 -*-
"""
Chathyx 安全模块（仅标准库）：

1. 密码哈希：PBKDF2-HMAC-SHA256，每用户随机盐，20 万次迭代，
   存储格式  pbkdf2_sha256$迭代次数$盐(base64)$哈希(base64)
   并兼容校验旧的纯 SHA-256 哈希（老用户可正常登录，登录后可平滑升级）。
2. 校验使用 hmac.compare_digest，避免时序侧信道。
3. 密码强度统一校验。
"""

import base64
import hashlib
import hmac
import os
import re

PBKDF2_ALGO = "pbkdf2_sha256"
ITERATIONS = 200000
SALT_BYTES = 16


# ---------------- 密码哈希 ----------------

def hash_password(password):
    """生成 PBKDF2 加盐哈希字符串。"""
    salt = os.urandom(SALT_BYTES)
    dk = hashlib.pbkdf2_hmac("sha256", (password or "").encode("utf-8"),
                             salt, ITERATIONS)
    return "%s$%d$%s$%s" % (
        PBKDF2_ALGO, ITERATIONS,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(dk).decode("ascii"))


def verify_password(password, stored):
    """校验密码是否匹配存储值；支持新 PBKDF2 与旧纯 SHA-256。"""
    stored = stored or ""
    if stored.startswith(PBKDF2_ALGO + "$"):
        try:
            _, iter_s, salt_b64, hash_b64 = stored.split("$")
            salt = base64.b64decode(salt_b64)
            dk = hashlib.pbkdf2_hmac("sha256",
                                     (password or "").encode("utf-8"),
                                     salt, int(iter_s))
            return hmac.compare_digest(
                base64.b64encode(dk).decode("ascii"), hash_b64)
        except (ValueError, TypeError):
            return False
    # 兼容旧版：64 位十六进制 SHA-256
    if len(stored) == 64 and re.fullmatch(r"[0-9a-f]{64}", stored):
        calc = hashlib.sha256((password or "").encode("utf-8")).hexdigest()
        return hmac.compare_digest(calc, stored)
    return False


def needs_rehash(stored):
    """旧哈希（非 PBKDF2）需要在登录后平滑升级。"""
    return not (stored or "").startswith(PBKDF2_ALGO + "$")


# ---------------- 密码强度 ----------------

def validate_password_strength(password):
    """返回错误提示字符串；通过时返回 None。"""
    pwd = password or ""
    if len(pwd) < 8 or len(pwd) > 64:
        return "密码长度需为 8-64 位"
    if re.search(r"\s", pwd):
        return "密码不能包含空格"
    if not re.search(r"[A-Za-z]", pwd):
        return "密码必须包含字母"
    if not re.search(r"[0-9]", pwd):
        return "密码必须包含数字"
    return None
