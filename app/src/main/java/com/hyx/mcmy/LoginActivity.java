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

public class LoginActivity extends AppCompatActivity {

    private EditText etUsername;
    private EditText etPassword;
    private SessionManager session;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        Button btnLogin = findViewById(R.id.btnLogin);
        TextView tvGoRegister = findViewById(R.id.tvGoRegister);

        session = new SessionManager(this);

        btnLogin.setOnClickListener(v -> doLogin());
        tvGoRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    private void restartAgent() {
        Intent s = new Intent(this, AgentService.class);
        stopService(s);
        startService(s);
    }

    private void doLogin() {
        if (busy) {
            return;
        }
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        new Thread(() -> {
            try {
                JSONObject resp = ChatApi.login(username, password);
                runOnUiThread(() -> {
                    busy = false;
                    if (!resp.optBoolean("ok")) {
                        Toast.makeText(this, resp.optString("error", "登录失败"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    session.save(username);
                    restartAgent();
                    startActivity(new Intent(this, ChatActivity.class));
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
