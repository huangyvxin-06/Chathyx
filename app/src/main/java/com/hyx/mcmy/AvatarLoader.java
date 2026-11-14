package com.hyx.mcmy;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.Base64;

import com.hyx.mcmy.agent.ChatApi;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 用户头像加载器：按版本号在本地缓存好友头像，版本不变时不重复下载。
 * ensure 为同步网络调用，须在子线程执行。
 */
public class AvatarLoader {

    private static final String PREF = "mc_avatar_ver";

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "avatars");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File file(Context ctx, String username) {
        return new File(dir(ctx), username + ".jpg");
    }

    /** 确保本地缓存为指定版本；返回是否有可用头像（须在子线程调用） */
    public static boolean ensure(Context ctx, String username, long version) {
        Context app = ctx.getApplicationContext();
        try {
            SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            File f = file(app, username);
            if (version <= 0) {
                return false;
            }
            if (sp.getLong(username, 0) == version && f.exists()) {
                return true;
            }
            JSONObject resp = ChatApi.getAvatar(app, username);
            if (resp.optBoolean("hasAvatar")) {
                byte[] bytes = Base64.decode(resp.getString("avatar"), Base64.DEFAULT);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(bytes);
                }
                sp.edit().putLong(username,
                        resp.optLong("avatarVersion", version)).apply();
                return f.exists();
            }
            return false;
        } catch (Exception e) {
            // 拉取失败时若本地有旧文件则兜底使用
            return file(app, username).exists();
        }
    }

    /** 读取本地缓存并解码为圆形位图，无缓存返回 null */
    public static Bitmap loadCircle(Context ctx, String username) {
        File f = file(ctx.getApplicationContext(), username);
        if (!f.exists()) return null;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        return bmp == null ? null : circle(bmp);
    }

    static Bitmap circle(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        RectF rect = new RectF(0, 0, src.getWidth(), src.getHeight());
        canvas.drawRoundRect(rect, src.getWidth() / 2f, src.getHeight() / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }
}
