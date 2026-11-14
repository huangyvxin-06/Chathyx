package com.hyx.mcmy;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hyx.mcmy.agent.ChatApi;
import com.hyx.mcmy.service.AgentService;

import org.json.JSONObject;

public class RegisterActivity extends AppCompatActivity {

    private EditText etUsername;
    private EditText etPassword;
    private EditText etConfirm;
    private SessionManager session;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etConfirm = findViewById(R.id.etConfirm);
        Button btnRegister = findViewById(R.id.btnRegister);

        session = new SessionManager(this);

        btnRegister.setOnClickListener(v -> doRegister());

        TextView tvGoLogin = findViewById(R.id.tvGoLogin);
        tvGoLogin.setOnClickListener(v -> finish());
    }

    private void doRegister() {
        if (busy) {
            return;
        }
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString();
        String confirm = etConfirm.getText().toString();

        if (username.length() < 2 || username.length() > 20) {
            Toast.makeText(this, "用户名长度需为 2-20 个字符", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!username.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]+")) {
            Toast.makeText(this, "用户名只能包含字母、数字、下划线和中文",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "密码长度至少 6 位", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!password.equals(confirm)) {
            Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        new Thread(() -> {
            try {
                JSONObject resp = ChatApi.register(username, password);
                runOnUiThread(() -> {
                    busy = false;
                    if (!resp.optBoolean("ok")) {
                        Toast.makeText(this, resp.optString("error", "注册失败"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 注册成功自动登录
                    session.save(username);
                    Intent svc = new Intent(this, AgentService.class);
                    stopService(svc);
                    startService(svc);

                    Intent intent = new Intent(this, ChatActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK |
                            Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}
