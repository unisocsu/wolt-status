package com.woltwatch;

import android.app.Application;
import android.content.Context;

public class App extends Application {
    private static Context ctx;

    @Override public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
    }

    static Context context() { return ctx; }
}
