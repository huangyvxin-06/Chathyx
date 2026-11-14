package com.hyx.mcmy;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREF = "mc_session";
    private static final String KEY_USERNAME = "username";

    private final SharedPreferences sp;

    public SessionManager(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public void save(String username) {
        sp.edit().putString(KEY_USERNAME, username).apply();
    }

    public boolean isLoggedIn() {
        String name = sp.getString(KEY_USERNAME, "");
        return name != null && !name.isEmpty();
    }

    public String getUsername() {
        return sp.getString(KEY_USERNAME, "");
    }

    public void clear() {
        sp.edit().clear().apply();
    }
}
