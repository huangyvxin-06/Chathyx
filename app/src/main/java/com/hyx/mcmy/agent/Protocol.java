package com.hyx.mcmy.agent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 长度前缀帧协议：
 * 帧 = 4 字节大端长度 + payload
 * JSON 帧 payload 为 UTF-8 文本；二进制帧 payload 为原始字节
 */
public final class Protocol {

    private Protocol() {
    }

    public static byte[] readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 200 * 1024 * 1024) {
            throw new IOException("非法帧长度: " + len);
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }

    public static JSONObject readJsonFrame(DataInputStream in) throws IOException, JSONException {
        byte[] data = readFrame(in);
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        byte[] header = new byte[4];
        int len = payload.length;
        header[0] = (byte) (len >> 24);
        header[1] = (byte) (len >> 16);
        header[2] = (byte) (len >> 8);
        header[3] = (byte) len;
        synchronized (out) {
            out.write(header);
            out.write(payload);
            out.flush();
        }
    }

    public static void writeJson(OutputStream out, JSONObject json) throws IOException {
        writeFrame(out, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBinary(OutputStream out, byte[] data) throws IOException {
        writeFrame(out, data);
    }
}
