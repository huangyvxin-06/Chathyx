package com.hyx.mcmy;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hyx.mcmy.agent.ChatApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatRoomActivity extends AppCompatActivity {

    public static final String EXTRA_PEER_NAME = "peer_name";

    private String peer;
    private String me;

    private ListView lvMessages;
    private EditText etMessage;
    private MessageAdapter adapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            loadMessages();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_room);

        SessionManager session = new SessionManager(this);
        me = session.getUsername();
        peer = getIntent().getStringExtra(EXTRA_PEER_NAME);

        ((TextView) findViewById(R.id.tvFriendName)).setText(peer);
        ((TextView) findViewById(R.id.tvPeerAvatarLetter))
                .setText(peer.substring(0, 1).toUpperCase(Locale.US));
        loadPeerAvatar();

        lvMessages = findViewById(R.id.lvMessages);
        etMessage = findViewById(R.id.etMessage);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSend).setOnClickListener(v -> sendMessage());

        adapter = new MessageAdapter();
        lvMessages.setAdapter(adapter);
        loadMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ForegroundState.setAppInForeground(true);
        ForegroundState.setActivePeer(peer);
        // 进入会话后取消该好友的通知
        NotificationHelper.cancelForPeer(this, peer);
        handler.post(pollTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ForegroundState.setAppInForeground(false);
        ForegroundState.setActivePeer(null);
        handler.removeCallbacks(pollTask);
    }

    private void sendMessage() {
        final String content = etMessage.getText().toString().trim();
        if (content.isEmpty()) {
            return;
        }
        etMessage.setText("");
        new Thread(() -> {
            try {
                JSONObject resp = ChatApi.sendMessage(this, peer, content);
                if (!resp.optBoolean("ok")) {
                    runOnUiThread(() -> etMessage.setText(content));
                }
                loadMessages();
            } catch (Exception e) {
                runOnUiThread(() -> etMessage.setText(content));
            }
        }).start();
    }

    private void loadPeerAvatar() {
        new Thread(() -> {
            boolean has = false;
            Bitmap bmp = null;
            try {
                JSONObject av = ChatApi.getAvatar(this, peer);
                if (av.optBoolean("hasAvatar")) {
                    byte[] bytes = Base64.decode(
                            av.optString("avatar"), Base64.DEFAULT);
                    Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (raw != null) {
                        bmp = AvatarLoader.circle(raw);
                        has = true;
                    }
                }
            } catch (Exception ignored) {
            }
            final boolean ok = has;
            final Bitmap result = bmp;
            runOnUiThread(() -> {
                ImageView iv = findViewById(R.id.ivPeerAvatar);
                TextView tv = findViewById(R.id.tvPeerAvatarLetter);
                if (ok) {
                    iv.setImageBitmap(result);
                    iv.setVisibility(View.VISIBLE);
                    tv.setVisibility(View.GONE);
                } else {
                    iv.setVisibility(View.GONE);
                    tv.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void loadMessages() {
        new Thread(() -> {
            try {
                JSONObject resp = ChatApi.listMessages(this, peer, 0);
                JSONArray arr = resp.optJSONArray("messages");
                final List<JSONObject> list = new ArrayList<>();
                long maxId = 0L;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.getJSONObject(i);
                        list.add(m);
                        if (m.optLong("id") > maxId) {
                            maxId = m.optLong("id");
                        }
                    }
                }
                // 正在查看该会话：已读位置推进到最新，未读清零
                new UnreadManager(this).markRead(peer, maxId);
                runOnUiThread(() -> {
                    adapter.setMessages(list);
                    if (adapter.getCount() > 0) {
                        lvMessages.setSelection(adapter.getCount() - 1);
                    }
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private class MessageAdapter extends BaseAdapter {

        private List<JSONObject> messages = new ArrayList<>();

        void setMessages(List<JSONObject> messages) {
            this.messages = messages;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return messages.size();
        }

        @Override
        public Object getItem(int position) {
            return messages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return messages.get(position).optLong("id", position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ChatRoomActivity.this)
                        .inflate(R.layout.item_message, parent, false);
            }
            LinearLayout row = convertView.findViewById(R.id.msgRow);
            TextView tvBubble = convertView.findViewById(R.id.tvBubble);
            TextView tvTime = convertView.findViewById(R.id.tvTime);

            JSONObject m = messages.get(position);
            boolean mine = me.equals(m.optString("from"));

            tvBubble.setText(m.optString("content"));
            tvBubble.setBackgroundResource(mine
                    ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
            tvBubble.setTextColor(mine ? 0xFFFFFFFF : 0xFF263238);

            tvTime.setText(new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    .format(new Date((long) m.optDouble("createdAt", 0) * 1000)));

            row.setGravity(mine ? android.view.Gravity.END : android.view.Gravity.START);
            return convertView;
        }
    }
}
