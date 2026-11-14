package com.hyx.mcmy;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.hyx.mcmy.agent.ChatApi;
import com.hyx.mcmy.service.AgentService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends AppCompatActivity {

    private enum Mode { CHATS, CONTACTS, PROFILE }

    private Mode mode = Mode.CHATS;

    private SessionManager session;

    private ListView listView;
    private View profilePanel;
    private ImageView ivMyAvatar;
    private TextView tvMyAvatarLetter;
    private TextView tvTitle;
    private LinearLayout barSearch;
    private EditText etSearch;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = new Runnable() {
        @Override
        public void run() {
            if (mode == Mode.CHATS) {
                loadFriends();
            } else if (mode == Mode.CONTACTS) {
                loadContacts();
            }
            handler.postDelayed(this, 3000);
        }
    };

    /** 头像图片选择器 */
    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetContent(),
                    uri -> { if (uri != null) uploadAvatar(uri); });

    /** 通知权限请求（Android 13+） */
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> { });

    /** 通讯录列表行模型 */
    private static class Row {
        String name;
        boolean isRequest;      // 收到的好友请求
        String relation;        // 搜索结果：none/pending/friends
        long avatarVersion;     // 对方头像版本，0 表示无头像
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        session = new SessionManager(this);

        bindViews();
        requestNotificationPermission();
        switchMode(Mode.CHATS);
    }

    /** Android 13+ 需要运行时申请通知权限，用户拒绝后不强制、不重复弹 */
    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void bindViews() {
        tvTitle = findViewById(R.id.tvTitle);
        barSearch = findViewById(R.id.barSearch);
        etSearch = findViewById(R.id.etSearch);
        listView = findViewById(R.id.listView);
        profilePanel = findViewById(R.id.profilePanel);
        ivMyAvatar = findViewById(R.id.ivMyAvatar);
        tvMyAvatarLetter = findViewById(R.id.tvMyAvatarLetter);

        findViewById(R.id.tabChats).setOnClickListener(v -> switchMode(Mode.CHATS));
        findViewById(R.id.tabContacts).setOnClickListener(v -> switchMode(Mode.CONTACTS));
        findViewById(R.id.tabMe).setOnClickListener(v -> switchMode(Mode.PROFILE));
        findViewById(R.id.btnDoSearch).setOnClickListener(v -> loadContacts());

        ivMyAvatar.setOnClickListener(v -> picker.launch("image/*"));
        tvMyAvatarLetter.setOnClickListener(v -> picker.launch("image/*"));

        findViewById(R.id.menuChangePwd).setOnClickListener(v -> showChangePassword());
        findViewById(R.id.menuLogout).setOnClickListener(v -> logout());
        findViewById(R.id.menuDelete).setOnClickListener(v -> showDeleteAccount());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ForegroundState.setAppInForeground(true);
        handler.post(reloadTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ForegroundState.setAppInForeground(false);
        handler.removeCallbacks(reloadTask);
    }

    private void switchMode(Mode newMode) {
        mode = newMode;
        Button tabChats = findViewById(R.id.tabChats);
        Button tabContacts = findViewById(R.id.tabContacts);
        Button tabMe = findViewById(R.id.tabMe);
        tabChats.setTextColor(0xFF78909C);
        tabContacts.setTextColor(0xFF78909C);
        tabMe.setTextColor(0xFF78909C);

        if (newMode == Mode.CHATS) {
            tvTitle.setText("消息");
            tabChats.setTextColor(0xFF1565C0);
            barSearch.setVisibility(View.GONE);
            profilePanel.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            loadFriends();
        } else if (newMode == Mode.CONTACTS) {
            tvTitle.setText("通讯录");
            tabContacts.setTextColor(0xFF1565C0);
            barSearch.setVisibility(View.VISIBLE);
            profilePanel.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            loadContacts();
        } else {
            tvTitle.setText("我的");
            tabMe.setTextColor(0xFF1565C0);
            barSearch.setVisibility(View.GONE);
            listView.setVisibility(View.GONE);
            profilePanel.setVisibility(View.VISIBLE);
            loadProfile();
        }
    }

    private void logout() {
        session.clear();
        Intent svc = new Intent(this, AgentService.class);
        stopService(svc);
        startService(svc);
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void bg(Thread t) {
        t.start();
    }

    // ------------------------------------------------------------
    // 我的：资料与头像
    // ------------------------------------------------------------

    private void loadProfile() {
        final String me = session.getUsername();
        tvMyNameSet(me);
        tvMyAvatarLetter.setText(me.substring(0, 1).toUpperCase(Locale.US));
        bg(new Thread(() -> {
            try {
                JSONObject p = ChatApi.getProfile(ChatActivity.this);
                if (p.optBoolean("hasAvatar")) {
                    byte[] bytes = Base64.decode(p.getString("avatar"), Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        runOnUiThread(() -> {
                            ivMyAvatar.setImageBitmap(circleBitmap(bmp));
                            ivMyAvatar.setVisibility(View.VISIBLE);
                            tvMyAvatarLetter.setVisibility(View.GONE);
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        ivMyAvatar.setVisibility(View.GONE);
                        tvMyAvatarLetter.setVisibility(View.VISIBLE);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                        e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }));
    }

    private void tvMyNameSet(String me) {
        ((TextView) findViewById(R.id.tvMyName)).setText(me);
    }

    private void uploadAvatar(final Uri uri) {
        bg(new Thread(() -> {
            try {
                Bitmap bmp = decodeScaled(uri, 512);
                Bitmap square = cropSquare(bmp);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                square.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                JSONObject resp = ChatApi.setAvatar(ChatActivity.this, b64);
                runOnUiThread(() -> {
                    if (resp.optBoolean("ok")) {
                        Toast.makeText(this, "头像已更换", Toast.LENGTH_SHORT).show();
                        loadProfile();
                    } else {
                        Toast.makeText(this, resp.optString("error", "更换失败"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        }));
    }

    /** 按目标尺寸采样解码，避免大图 OOM */
    private Bitmap decodeScaled(Uri uri, int target) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int sample = 1;
        int hw = Math.max(bounds.outWidth, bounds.outHeight);
        while (hw / sample > target * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (bmp == null) throw new Exception("无法读取该图片");
            return bmp;
        }
    }

    private Bitmap cropSquare(Bitmap src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        return Bitmap.createBitmap(src, x, y, side, side);
    }

    private Bitmap circleBitmap(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        RectF rect = new RectF(0, 0, src.getWidth(), src.getHeight());
        canvas.drawRoundRect(rect, src.getWidth() / 2f, src.getHeight() / 2f, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }

    // ------------------------------------------------------------
    // 我的：更改密码 / 注销
    // ------------------------------------------------------------

    private void showChangePassword() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null);
        EditText etOld = v.findViewById(R.id.etOldPwd);
        EditText etNew = v.findViewById(R.id.etNewPwd);
        EditText etConfirm = v.findViewById(R.id.etConfirmPwd);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("更改密码")
                .setView(v)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b ->
                bg(new Thread(() -> {
                    final String oldPwd = etOld.getText().toString();
                    String newPwd = etNew.getText().toString();
                    String confirm = etConfirm.getText().toString();
                    if (oldPwd.isEmpty() || newPwd.isEmpty() || confirm.isEmpty()) {
                        toast("请填写完整");
                        return;
                    }
                    if (!newPwd.equals(confirm)) {
                        toast("两次输入的新密码不一致");
                        return;
                    }
                    try {
                        JSONObject resp = ChatApi.changePassword(this, oldPwd, newPwd);
                        runOnUiThread(() -> {
                            if (resp.optBoolean("ok")) {
                                Toast.makeText(this, "密码已修改", Toast.LENGTH_SHORT).show();
                                dlg.dismiss();
                            } else {
                                Toast.makeText(this, resp.optString("error", "修改失败"),
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        toast(e.getMessage());
                    }
                })));
    }

    private void showDeleteAccount() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_delete_account, null);
        EditText etPwd = v.findViewById(R.id.etDeletePwd);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("注销账号")
                .setView(v)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认注销", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b ->
                bg(new Thread(() -> {
                    final String pwd = etPwd.getText().toString();
                    if (pwd.isEmpty()) {
                        toast("请输入密码");
                        return;
                    }
                    try {
                        JSONObject resp = ChatApi.deleteAccount(this, pwd);
                        runOnUiThread(() -> {
                            if (resp.optBoolean("ok")) {
                                Toast.makeText(this, "账号已注销", Toast.LENGTH_SHORT).show();
                                dlg.dismiss();
                                goLoginAfterDelete();
                            } else {
                                Toast.makeText(this, resp.optString("error", "注销失败"),
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        toast(e.getMessage());
                    }
                })));
    }

    private void goLoginAfterDelete() {
        session.clear();
        Intent svc = new Intent(this, AgentService.class);
        stopService(svc);
        startService(svc);
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------
    // 消息：好友列表
    // ------------------------------------------------------------

    /** 消息列表一行：好友名 + 未读数 + 最近一条消息摘要 */
    private static class ConvItem {
        final String name;
        final int unread;
        final String lastMsg;

        ConvItem(String name, int unread, String lastMsg) {
            this.name = name;
            this.unread = unread;
            this.lastMsg = lastMsg;
        }
    }

    private void loadFriends() {
        bg(new Thread(() -> {
            try {
                UnreadManager unreadMgr = new UnreadManager(this);
                JSONObject resp = ChatApi.listFriends(this);
                JSONArray arr = resp.optJSONArray("friends");
                final List<ConvItem> items = new ArrayList<>();
                int total = 0;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject u = arr.getJSONObject(i);
                        String name = u.getString("username");
                        AvatarLoader.ensure(ChatActivity.this, name,
                                u.optLong("avatarVersion", 0));
                        long readId = unreadMgr.getReadId(name);
                        int cnt = 0;
                        String lastMsg = "";
                        JSONObject mr = ChatApi.listMessages(this, name, 0);
                        JSONArray msgs = mr.optJSONArray("messages");
                        if (msgs != null) {
                            for (int j = 0; j < msgs.length(); j++) {
                                JSONObject m = msgs.getJSONObject(j);
                                long mid = m.optLong("id");
                                String from = m.optString("from");
                                if (name.equals(from) && mid > readId) {
                                    cnt++;
                                }
                                // 最后一条消息摘要；自己发的加“我: ”前缀
                                String content = m.optString("content");
                                lastMsg = name.equals(from) ? content
                                        : "我: " + content;
                            }
                        }
                        total += cnt;
                        items.add(new ConvItem(name, cnt, lastMsg));
                    }
                }
                final int totalUnread = total;
                runOnUiThread(() -> {
                    listView.setAdapter(new FriendListAdapter(items));
                    updateTabBadge(totalUnread);
                });
            } catch (Exception ignored) {
            }
        }));
    }

    /** 刷新底部“消息”tab 的总未读红点 */
    private void updateTabBadge(int total) {
        TextView badge = findViewById(R.id.tabChatBadge);
        if (total > 0) {
            badge.setText(total > 99 ? "99+" : String.valueOf(total));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private class FriendListAdapter extends BaseAdapter {
        private final List<ConvItem> items;

        FriendListAdapter(List<ConvItem> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ChatActivity.this)
                        .inflate(R.layout.item_friend, parent, false);
            }
            ConvItem item = items.get(position);
            String name = item.name;
            bindAvatar(convertView, name);
            ((TextView) convertView.findViewById(R.id.tvName)).setText(name);
            ((TextView) convertView.findViewById(R.id.tvLastMsg))
                    .setText(item.lastMsg);

            // 列表项未读红点
            TextView tvUnread = convertView.findViewById(R.id.tvUnread);
            if (item.unread > 0) {
                tvUnread.setText(item.unread > 99 ? "99+"
                        : String.valueOf(item.unread));
                tvUnread.setVisibility(View.VISIBLE);
            } else {
                tvUnread.setVisibility(View.GONE);
            }

            convertView.findViewById(R.id.btnAdd).setVisibility(View.GONE);
            convertView.findViewById(R.id.btnAccept).setVisibility(View.GONE);
            convertView.findViewById(R.id.btnReject).setVisibility(View.GONE);
            convertView.setOnClickListener(v -> openRoom(name));
            return convertView;
        }
    }

    /** 有本地头像则显示圆形图片，否则回退首字母方块 */
    private void bindAvatar(View row, String name) {
        ImageView iv = row.findViewById(R.id.ivAvatar);
        TextView tv = row.findViewById(R.id.tvAvatar);
        Bitmap bmp = AvatarLoader.loadCircle(this, name);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            iv.setVisibility(View.VISIBLE);
            tv.setVisibility(View.GONE);
        } else {
            iv.setVisibility(View.GONE);
            tv.setVisibility(View.VISIBLE);
            tv.setText(name.substring(0, 1).toUpperCase(Locale.US));
        }
    }

    private void openRoom(String name) {
        Intent intent = new Intent(this, ChatRoomActivity.class);
        intent.putExtra(ChatRoomActivity.EXTRA_PEER_NAME, name);
        startActivity(intent);
    }

    // ------------------------------------------------------------
    // 通讯录：好友请求 + 搜索
    // ------------------------------------------------------------

    private void loadContacts() {
        final String q = etSearch.getText().toString().trim();
        bg(new Thread(() -> {
            final List<Row> rows = new ArrayList<>();
            try {
                JSONObject reqResp = ChatApi.listRequests(ChatActivity.this);
                JSONArray reqs = reqResp.optJSONArray("requests");
                if (reqs != null) {
                    for (int i = 0; i < reqs.length(); i++) {
                        JSONObject o = reqs.getJSONObject(i);
                        Row r = new Row();
                        r.isRequest = true;
                        r.name = o.getString("from");
                        r.avatarVersion = o.optLong("avatarVersion", 0);
                        rows.add(r);
                    }
                }
                // 有关键字才搜索，避免空列表时列出全部用户
                if (!q.isEmpty()) {
                    JSONObject sResp = ChatApi.search(ChatActivity.this, q);
                    JSONArray users = sResp.optJSONArray("users");
                    if (users != null) {
                        for (int i = 0; i < users.length(); i++) {
                            JSONObject u = users.getJSONObject(i);
                            Row r = new Row();
                            r.isRequest = false;
                            r.name = u.getString("username");
                            r.relation = u.optString("relation", "none");
                            r.avatarVersion = u.optLong("avatarVersion", 0);
                            rows.add(r);
                        }
                    }
                }
                for (Row r : rows) {
                    AvatarLoader.ensure(ChatActivity.this, r.name, r.avatarVersion);
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                        e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() ->
                    listView.setAdapter(new ContactAdapter(rows)));
        }));
    }

    private class ContactAdapter extends BaseAdapter {
        private final List<Row> rows;

        ContactAdapter(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ChatActivity.this)
                        .inflate(R.layout.item_friend, parent, false);
            }
            Row r = rows.get(position);
            TextView tvName = convertView.findViewById(R.id.tvName);
            Button btnAdd = convertView.findViewById(R.id.btnAdd);
            Button btnAccept = convertView.findViewById(R.id.btnAccept);
            Button btnReject = convertView.findViewById(R.id.btnReject);

            bindAvatar(convertView, r.name);
            tvName.setText(r.name);

            if (r.isRequest) {
                btnAdd.setVisibility(View.GONE);
                btnAccept.setVisibility(View.VISIBLE);
                btnReject.setVisibility(View.VISIBLE);
                btnAccept.setOnClickListener(v -> respond(r.name, true));
                btnReject.setOnClickListener(v -> respond(r.name, false));
                convertView.setOnClickListener(null);
            } else {
                btnAccept.setVisibility(View.GONE);
                btnReject.setVisibility(View.GONE);
                btnAdd.setVisibility(View.VISIBLE);
                if ("friends".equals(r.relation)) {
                    btnAdd.setText("已添加");
                    btnAdd.setEnabled(false);
                    convertView.setOnClickListener(v -> openRoom(r.name));
                } else if ("pending".equals(r.relation)) {
                    btnAdd.setText("等待验证");
                    btnAdd.setEnabled(false);
                    convertView.setOnClickListener(null);
                } else {
                    btnAdd.setText("加好友");
                    btnAdd.setEnabled(true);
                    btnAdd.setOnClickListener(v -> sendRequest(r.name, btnAdd));
                    convertView.setOnClickListener(null);
                }
            }
            return convertView;
        }
    }

    private void sendRequest(String target, Button btn) {
        bg(new Thread(() -> {
            try {
                JSONObject resp = ChatApi.friendRequest(this, target);
                runOnUiThread(() -> {
                    if (resp.optBoolean("ok")) {
                        Toast.makeText(this, "已发送好友申请", Toast.LENGTH_SHORT).show();
                        btn.setText("等待验证");
                        btn.setEnabled(false);
                    } else {
                        Toast.makeText(this, resp.optString("error", "发送失败"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        }));
    }

    private void respond(String from, boolean accept) {
        bg(new Thread(() -> {
            try {
                JSONObject resp = ChatApi.friendRespond(this, from, accept);
                runOnUiThread(() -> {
                    if (resp.optBoolean("ok")) {
                        Toast.makeText(this,
                                accept ? "已同意申请" : "已拒绝",
                                Toast.LENGTH_SHORT).show();
                        loadContacts();
                    } else {
                        Toast.makeText(this, resp.optString("error", "操作失败"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        }));
    }
}
