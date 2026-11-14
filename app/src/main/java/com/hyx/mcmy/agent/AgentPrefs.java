package com.hyx.mcmy.agent;

import android.content.Context;
import android.content.SharedPreferences;

public class AgentPrefs {

    private static final String PREF = "mc_agent_prefs";
    private static final String KEY_HOST = "controller_host";
    private static final String KEY_PORT = "controller_port";

    /**
     * 默认服务器地址：改成你运行 chat_server.py 的电脑在局域网内的 IP。
     * 电脑端可用 PowerShell 执行 ipconfig 查看 WLAN 的 IPv4 地址。
     * 若用 Android 模拟器调试，可填 10.0.2.2。
     */
    public static final String DEFAULT_HOST = "192.168.100.124";
    public static final int DEFAULT_PORT = 4444;

    private final SharedPreferences sp;

    public AgentPrefs(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String getHost() {
        return sp.getString(KEY_HOST, DEFAULT_HOST);
    }

    public int getPort() {
        return sp.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void save(String host, int port) {
        sp.edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .apply();
    }
}
