-- ============================================================
-- Chathyx 数据库结构（MySQL 8.x，字符集 utf8mb4）
-- 手动初始化：mysql -u root -p < schema.sql
-- 也可直接启动 chat_server.py，程序会自动执行本建表逻辑。
-- ============================================================

CREATE DATABASE IF NOT EXISTS chathyx
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE chathyx;

-- 用户表：密码仅存 PBKDF2 加盐哈希（含旧 SHA-256 兼容），不存明文
CREATE TABLE IF NOT EXISTS users (
    username      VARCHAR(32)  NOT NULL PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    avatar        VARCHAR(64)  NULL,
    created_at    DOUBLE       NOT NULL,
    last_login    DOUBLE       NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 好友申请表
CREATE TABLE IF NOT EXISTS friend_requests (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    from_user  VARCHAR(32) NOT NULL,
    to_user    VARCHAR(32) NOT NULL,
    status     ENUM('pending','accepted','rejected') NOT NULL DEFAULT 'pending',
    created_at DOUBLE NOT NULL,
    INDEX idx_to_status (to_user, status),
    INDEX idx_from_status (from_user, status),
    CONSTRAINT fk_req_from FOREIGN KEY (from_user)
        REFERENCES users(username) ON DELETE CASCADE,
    CONSTRAINT fk_req_to FOREIGN KEY (to_user)
        REFERENCES users(username) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 好友关系表：约定 user_a < user_b，保证每对只存一行
CREATE TABLE IF NOT EXISTS friends (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    user_a     VARCHAR(32) NOT NULL,
    user_b     VARCHAR(32) NOT NULL,
    created_at DOUBLE NOT NULL,
    UNIQUE KEY uq_pair (user_a, user_b),
    CONSTRAINT fk_friend_a FOREIGN KEY (user_a)
        REFERENCES users(username) ON DELETE CASCADE,
    CONSTRAINT fk_friend_b FOREIGN KEY (user_b)
        REFERENCES users(username) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 一对一消息表
CREATE TABLE IF NOT EXISTS messages (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    from_user  VARCHAR(32) NOT NULL,
    to_user    VARCHAR(32) NOT NULL,
    content    TEXT NOT NULL,
    created_at DOUBLE NOT NULL,
    INDEX idx_pair (from_user, to_user),
    INDEX idx_to (to_user),
    CONSTRAINT fk_msg_from FOREIGN KEY (from_user)
        REFERENCES users(username) ON DELETE CASCADE,
    CONSTRAINT fk_msg_to FOREIGN KEY (to_user)
        REFERENCES users(username) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
