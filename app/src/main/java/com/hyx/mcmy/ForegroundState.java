package com.hyx.mcmy;

/**
 * 全局前台状态：记录用户当前正在查看哪个聊天对象的会话。
 * AgentService 后台轮询到新消息时，若该 peer 正打开在前台则不弹通知，避免重复打扰。
 */
public class ForegroundState {

    private static volatile String activePeer = null;
    private static volatile boolean appInForeground = false;

    public static void setAppInForeground(boolean value) {
        appInForeground = value;
        if (!value) {
            activePeer = null;
        }
    }

    public static boolean isAppInForeground() {
        return appInForeground;
    }

    public static void setActivePeer(String peer) {
        activePeer = peer;
    }

    public static String getActivePeer() {
        return activePeer;
    }

    /** 指定会话是否正被用户查看 */
    public static boolean isPeerOpen(String peer) {
        return appInForeground && peer != null && peer.equals(activePeer);
    }
}
