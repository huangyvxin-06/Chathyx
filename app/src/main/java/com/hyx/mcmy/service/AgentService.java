package com.hyx.mcmy.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.hyx.mcmy.ForegroundState;
import com.hyx.mcmy.NotificationHelper;
import com.hyx.mcmy.SessionManager;
import com.hyx.mcmy.agent.AgentClient;
import com.hyx.mcmy.agent.AgentPrefs;
import com.hyx.mcmy.agent.ChatApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AgentService extends Service {

    private static final String CHANNEL_ID = "chathyx_conn";
    private static final int NOTIFICATION_ID = 1002;
    private static final String LAST_PREF = "mc_msg_lastid";
    private static final int POLL_INTERVAL_SEC = 3;

    private AgentClient client;
    private Thread worker;
    private ScheduledExecutorService poller;

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private static volatile AgentClient activeClient;

    /** 供 UI 拿到当前连接的客户端（可能为 null） */
    public static AgentClient getClient() {
        return activeClient;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        NotificationHelper.ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (client == null) {
            AgentPrefs prefs = new AgentPrefs(this);
            client = new AgentClient(this, prefs.getHost(), prefs.getPort());
            activeClient = client;
            worker = new Thread(client::runLoop, "chathyx-conn");
            worker.start();
        }
        if (poller == null) {
            poller = Executors.newSingleThreadScheduledExecutor();
            // 延迟 2 秒启动，之后固定间隔轮询新消息
            poller.scheduleAtFixedRate(this::safeCheckMessages,
                    2, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
        }
        acquireLocks();
        return START_STICKY;
    }

    // ------------------------------------------------------------
    // 后台消息轮询 + 通知
    // ------------------------------------------------------------

    private void safeCheckMessages() {
        try {
            checkMessages();
        } catch (Exception ignored) {
        }
    }

    private void checkMessages() throws Exception {
        String me = new SessionManager(this).getUsername();
        if (me.isEmpty()) {
            return;
        }
        JSONObject resp = ChatApi.listFriends(this);
        JSONArray friends = resp.optJSONArray("friends");
        if (friends == null) {
            return;
        }
        for (int i = 0; i < friends.length(); i++) {
            String peer = friends.getJSONObject(i).getString("username");
            checkPeer(peer);
        }
    }

    private void checkPeer(String peer) {
        try {
            SharedPreferences sp = getSharedPreferences(LAST_PREF, Context.MODE_PRIVATE);
            // -1 表示该会话从未初始化过
            long lastId = sp.getLong(peer, -1L);
            long sinceId = lastId < 0 ? 0L : lastId;

            JSONObject resp = ChatApi.listMessages(this, peer, sinceId);
            JSONArray arr = resp.optJSONArray("messages");
            if (arr == null || arr.length() == 0) {
                return;
            }

            long maxId = sinceId;
            boolean hasIncoming = false;
            String latestContent = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                long id = m.optLong("id");
                if (id > maxId) {
                    maxId = id;
                }
                if (peer.equals(m.optString("from"))) {
                    hasIncoming = true;
                    latestContent = m.optString("content");
                }
            }
            sp.edit().putLong(peer, maxId).apply();

            // 首次初始化只同步游标，不为历史消息弹通知
            if (lastId < 0) {
                return;
            }
            // 当前正打开该会话时不通知
            if (hasIncoming && !ForegroundState.isPeerOpen(peer)) {
                NotificationHelper.showMessage(this, peer, latestContent);
            }
        } catch (Exception ignored) {
        }
    }

    /** 后台/灭屏时保持 WiFi 高性能与 CPU 运行，避免长连接被系统省电中断 */
    private void acquireLocks() {
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "chathyx:wifi");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "chathyx:conn");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            // 给 1 小时兜底超时，防止异常情况下泄漏；正常由 release 释放
            wakeLock.acquire(60 * 60 * 1000L);
        }
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        if (client != null) {
            client.stop();
            client = null;
        }
        activeClient = null;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Chathyx")
                .setContentText("聊天连接维持中")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "聊天连接", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }
}
