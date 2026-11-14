package com.hyx.mcmy.agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import javax.net.ssl.SSLSocket;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天连接客户端：主动连接服务器，自动重连，收发聊天业务帧（纯 JSON）。
 */
public class AgentClient {

    private static final String TAG = "AgentClient";
    private static final int CONNECT_TIMEOUT_MS = 8000;

    private final Context context;
    private final String host;
    private final int port;

    private volatile boolean stopped = false;
    private Socket socket;
    private DataInputStream input;
    private OutputStream output;

    /** 写帧串行锁（读取线程与业务请求线程都会写） */
    private final Object writeLock = new Object();
    /** 业务请求 id -> 等待盒 */
    private final Map<Long, Box> pending = new HashMap<>();
    private final AtomicLong bizSeq = new AtomicLong(0);

    private static class Box {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject result;
    }

    /** 当前是否已连接 */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * 发送聊天业务请求并同步等待响应（须在子线程调用）。
     * 调用方在 req 中放 action 等字段，无需设置 id。
     */
    public JSONObject request(JSONObject req) throws Exception {
        if (!isConnected()) {
            throw new Exception("网络错误，请稍后重试");
        }
        long id = bizSeq.incrementAndGet();
        req.put("id", id);
        Box box = new Box();
        synchronized (pending) {
            pending.put(id, box);
        }
        try {
            synchronized (writeLock) {
                Protocol.writeJson(output, req);
            }
            if (!box.latch.await(30, TimeUnit.SECONDS)) {
                throw new Exception("网络错误，请求超时");
            }
            if (box.result == null) {
                throw new Exception("网络错误，连接已断开");
            }
            return box.result;
        } finally {
            synchronized (pending) {
                pending.remove(id);
            }
        }
    }

    /** 断开/重连时唤醒所有等待中的业务请求 */
    private void failAllPending() {
        synchronized (pending) {
            for (Box b : pending.values()) {
                b.latch.countDown();
            }
            pending.clear();
        }
    }

    public AgentClient(Context context, String host, int port) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.port = port;
    }

    public void stop() {
        stopped = true;
        closeQuietly();
    }

    public void runLoop() {
        while (!stopped) {
            try {
                connectAndServe();
            } catch (Exception e) {
                Log.w(TAG, "连接中断: " + e.getMessage());
            }
            closeQuietly();
            if (!stopped) {
                sleep(5000);
            }
        }
    }

    private void connectAndServe() throws Exception {
        Log.i(TAG, "TLS 连接聊天服务器 " + host + ":" + port);
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        plain.setTcpNoDelay(true);
        // 在已建立的 TCP 连接上叠加 TLS（证书锁定），并显式完成握手
        SSLSocket ssl = (SSLSocket) TlsTrust.factory(context)
                .createSocket(plain, host, port, true);
        ssl.setUseClientMode(true);
        ssl.setTcpNoDelay(true);
        ssl.startHandshake();
        socket = ssl;
        input = new DataInputStream(socket.getInputStream());
        output = socket.getOutputStream();

        while (!stopped) {
            JSONObject msg = Protocol.readJsonFrame(input);
            if (msg.has("type") && "ping".equals(msg.optString("type"))) {
                JSONObject pong = new JSONObject();
                pong.put("type", "pong");
                synchronized (writeLock) {
                    Protocol.writeJson(output, pong);
                }
                continue;
            }
            long rid = msg.optLong("id", -1);
            Box box;
            synchronized (pending) {
                box = pending.get(rid);
            }
            if (box != null && msg.has("ok")) {
                // 业务请求的响应
                box.result = msg;
                box.latch.countDown();
            }
        }
    }

    private void closeQuietly() {
        failAllPending();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
        input = null;
        output = null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
