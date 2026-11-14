package com.hyx.mcmy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 消息通知工具：创建通知渠道并发送新消息通知。
 * 同一好友的通知使用相同 id，连续来消息时更新而不是堆叠多条。
 */
public class NotificationHelper {

    public static final String CHANNEL_ID = "chathyx_msg";
    private static final String CHANNEL_NAME = "消息通知";

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("收到新聊天消息时提醒");
        channel.enableVibration(true);
        channel.enableLights(true);
        NotificationManager nm = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    /** 发送一条新消息通知，点击直接进入对应聊天窗口。 */
    public static void showMessage(Context context, String peer, String content) {
        ensureChannel(context);

        Intent intent = new Intent(context, ChatRoomActivity.class);
        intent.putExtra(ChatRoomActivity.EXTRA_PEER_NAME, peer);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(
                context, peer.hashCode(), intent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(peer)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            // 用 peer 名稳定 id，同一会话覆盖更新
            nm.notify(peer.hashCode(), builder.build());
        }
    }

    /** 取消指定好友的通知（进入会话时调用）。 */
    public static void cancelForPeer(Context context, String peer) {
        NotificationManager nm = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(peer.hashCode());
        }
    }
}
