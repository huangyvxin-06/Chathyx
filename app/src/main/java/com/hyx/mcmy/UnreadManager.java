package com.hyx.mcmy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 未读管理：记录每个会话（peer）当前已读到的最大消息 id。
 * peer 发来的、id 大于该值的消息即为未读。进入聊天窗口时把已读位置推进到最新。
 */
public class UnreadManager {

    private static final String PREF = "mc_read_id";

    private final SharedPreferences sp;

    public UnreadManager(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public long getReadId(String peer) {
        return sp.getLong(peer, 0L);
    }

    /** 把指定会话标记已读到 id（只增不减） */
    public void markRead(String peer, long id) {
        if (id > getReadId(peer)) {
            sp.edit().putLong(peer, id).apply();
        }
    }
}
