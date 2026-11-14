package com.hyx.mcmy.agent;

import android.content.Context;

import com.hyx.mcmy.SessionManager;
import com.hyx.mcmy.service.AgentService;

import org.json.JSONObject;

/**
 * 聊天业务 API：封装通过反向 Socket 发往电脑端控制器的请求。
 * 所有方法均为同步网络调用，必须在子线程中调用。
 */
public class ChatApi {

    private static AgentClient client() throws Exception {
        AgentClient c = AgentService.getClient();
        if (c == null) {
            throw new Exception("网络错误，请稍后重试");
        }
        return c;
    }

    private static JSONObject base(Context ctx, String action) throws Exception {
        JSONObject j = new JSONObject();
        j.put("action", action);
        j.put("self", new SessionManager(ctx).getUsername());
        return j;
    }

    // ---------- 认证（无 self） ----------
    public static JSONObject register(String username, String password) throws Exception {
        JSONObject j = new JSONObject();
        j.put("action", "register");
        j.put("username", username);
        j.put("password", password);
        return client().request(j);
    }

    public static JSONObject login(String username, String password) throws Exception {
        JSONObject j = new JSONObject();
        j.put("action", "login");
        j.put("username", username);
        j.put("password", password);
        return client().request(j);
    }

    // ---------- 搜索 / 好友 ----------
    public static JSONObject search(Context ctx, String keyword) throws Exception {
        JSONObject j = base(ctx, "search");
        j.put("keyword", keyword);
        return client().request(j);
    }

    public static JSONObject friendRequest(Context ctx, String target) throws Exception {
        JSONObject j = base(ctx, "friend_req");
        j.put("username", target);
        return client().request(j);
    }

    public static JSONObject listRequests(Context ctx) throws Exception {
        return client().request(base(ctx, "list_req"));
    }

    public static JSONObject friendRespond(Context ctx, String from, boolean accept)
            throws Exception {
        JSONObject j = base(ctx, "friend_respond");
        j.put("from", from);
        j.put("accept", accept);
        return client().request(j);
    }

    public static JSONObject listFriends(Context ctx) throws Exception {
        return client().request(base(ctx, "list_friends"));
    }

    // ---------- 消息 ----------
    public static JSONObject sendMessage(Context ctx, String to, String content)
            throws Exception {
        JSONObject j = base(ctx, "send_msg");
        j.put("to", to);
        j.put("content", content);
        return client().request(j);
    }

    public static JSONObject listMessages(Context ctx, String peer, long sinceId)
            throws Exception {
        JSONObject j = base(ctx, "list_msgs");
        j.put("peer", peer);
        j.put("sinceId", sinceId);
        return client().request(j);
    }

    // ---------- 个人资料 / 头像 ----------
    public static JSONObject getProfile(Context ctx) throws Exception {
        return client().request(base(ctx, "get_profile"));
    }

    public static JSONObject getAvatar(Context ctx, String username) throws Exception {
        JSONObject j = base(ctx, "get_avatar");
        j.put("username", username);
        return client().request(j);
    }

    public static JSONObject setAvatar(Context ctx, String avatarBase64)
            throws Exception {
        JSONObject j = base(ctx, "set_avatar");
        j.put("avatar", avatarBase64);
        return client().request(j);
    }

    // ---------- 修改密码 / 注销 ----------
    public static JSONObject changePassword(Context ctx, String oldPwd, String newPwd)
            throws Exception {
        JSONObject j = base(ctx, "change_password");
        j.put("oldPassword", oldPwd);
        j.put("newPassword", newPwd);
        return client().request(j);
    }

    public static JSONObject deleteAccount(Context ctx, String password)
            throws Exception {
        JSONObject j = base(ctx, "delete_account");
        j.put("password", password);
        return client().request(j);
    }
}
