# -*- coding: utf-8 -*-
"""
Chathyx - 数据库连接与初始化（MySQL，基于 pymysql）

连接参数全部从环境变量读取，db.py 本身不包含任何真实密码。
程序启动时会自动读取同目录 .env 文件（见 load_env），也可直接用系统环境变量覆盖：
  CHATHYX_DB_HOST  默认 127.0.0.1
  CHATHYX_DB_PORT  默认 3306
  CHATHYX_DB_USER  默认 root
  CHATHYX_DB_NAME  默认 chathyx
  CHATHYX_DB_PASSWORD  未配置时为占位 CHANGE_ME（连接会失败）

启动示例（PowerShell）：
  python chat_server.py        # 自动读取 .env
"""

import os

import pymysql
from pymysql.constants import CLIENT


def load_env():
    """
    读取同目录 .env 文件并注入环境变量（不覆盖系统中已存在的变量）。
    仅用标准库实现，无需 python-dotenv。
    """
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if not os.path.isfile(env_path):
        return
    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key and key not in os.environ:
                os.environ[key] = value


# 导入本模块即自动加载 .env（须在读取下面的 DB_* 常量之前执行）
load_env()

DB_HOST = os.environ.get("CHATHYX_DB_HOST", "127.0.0.1")
DB_PORT = int(os.environ.get("CHATHYX_DB_PORT", "3306"))
DB_USER = os.environ.get("CHATHYX_DB_USER", "root")
DB_NAME = os.environ.get("CHATHYX_DB_NAME", "chathyx")
# 优先取环境变量/.env；都没有时用占位（此时连接会失败）
DB_PASSWORD = os.environ.get("CHATHYX_DB_PASSWORD", "CHANGE_ME")


def get_conn():
    """获取一个指向业务库的连接（关闭自动提交，由调用方 commit/rollback）。"""
    return pymysql.connect(
        host=DB_HOST,
        port=DB_PORT,
        user=DB_USER,
        password=DB_PASSWORD,
        database=DB_NAME,
        charset="utf8mb4",
        autocommit=False,
        ssl_disabled=True,
    )


def init_db():
    """创建数据库与全部表。读取同目录 schema.sql，一次性执行多语句。"""
    schema_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               "schema.sql")
    with open(schema_path, "r", encoding="utf-8") as f:
        ddl = f.read()
    conn = pymysql.connect(
        host=DB_HOST,
        port=DB_PORT,
        user=DB_USER,
        password=DB_PASSWORD,
        charset="utf8mb4",
        client_flag=CLIENT.MULTI_STATEMENTS,
        ssl_disabled=True,
    )
    try:
        with conn.cursor() as cur:
            cur.execute(ddl)
            while cur.nextset():
                pass
        conn.commit()
    finally:
        conn.close()


def run_migrations():
    """对已存在的库执行幂等结构迁移。"""
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            # password_hash 由 CHAR(64) 扩容到 VARCHAR(255)，容纳 PBKDF2 哈希串
            cur.execute(
                "ALTER TABLE users "
                "MODIFY COLUMN password_hash VARCHAR(255) NOT NULL")
        conn.commit()
    finally:
        conn.close()
