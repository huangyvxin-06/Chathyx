package com.hyx.mcmy;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.hyx.mcmy.service.AgentService;

/**
 * 启动页：自动拉起聊天连接服务，并按登录状态路由到登录页或聊天主页。
 */
public class MainActivity extends AppCompatActivity {

    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);
        session = new SessionManager(this);

        // 自动启动聊天连接服务，重复调用安全（服务内部会跳过已启动的客户端）
        Intent serviceIntent = new Intent(this, AgentService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        routeBySession();
    }

    private void routeBySession() {
        Intent next = session.isLoggedIn()
                ? new Intent(this, ChatActivity.class)
                : new Intent(this, LoginActivity.class);
        startActivity(next);
        finish();
    }
}
