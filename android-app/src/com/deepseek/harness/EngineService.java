package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 前台保活服务：引擎（node 服务器）运行期间常驻通知栏，
 * 让系统把本应用标记为高优先级进程，挂后台/锁屏不被杀掉，
 * AI 后台任务（对话、工具调用）可持续执行。
 *
 * 生命周期：
 *   - startEngine() 时由 MainActivity 拉起（startForegroundService / startService）
 *   - 用户主动「退出」时由 MainActivity 停止（stopService）
 *   - 按 Home 挂后台不停止（这正是保活的目的）
 *
 * v1.18.8 起，本服务还负责拉起【悬浮球状态推送器】（见 ensureOverlayWatcher）。
 */
public class EngineService extends Service {
    private static final String TAG = "DSH-EngineService";
    private static final String CHANNEL_ID = "dsh_engine";
    private static final int NOTIF_ID = 1;

    /** 与 MainActivity 共用同一份偏好（engine_port 存在这里）。 */
    private static final String PREFS = "dsh_prefs";
    private static final String KEY_ENGINE_PORT = "engine_port";

    /** 悬浮球状态推送器的脚本名（从 res/raw 落到 files 下再执行）。 */
    private static final String WATCHER_SCRIPT = "overlay-watch.js";

    /** 推送器进程。static：Service 重建时不重复拉起。 */
    private static Process overlayWatchProc;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification("DeepSeek Harness 正在运行", "AI 引擎保活中，后台任务持续执行"));
        ensureOverlayWatcher();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次收到启动/重启意图都刷新通知（系统杀进程后 START_STICKY 重建也会走到这里）
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification("DeepSeek Harness 正在运行", "AI 引擎保活中，后台任务持续执行"));
        // 保活服务被系统重建时，顺手确认状态推送器还在
        ensureOverlayWatcher();
        // 定时任务自动执行：闹钟到点后带 scheduledTask extra 启动本服务，后台执行任务
        if (intent != null) {
            String task = intent.getStringExtra("scheduledTask");
            if (task != null && !task.isEmpty()) {
                final String fTask = task;
                new Thread(new Runnable() {
                    @Override public void run() {
                        ScheduleExecutor.execute(EngineService.this, fTask);
                    }
                }, "scheduled-exec").start();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopOverlayWatcher();
        super.onDestroy();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    // ══════════════════════ 悬浮球状态自动推送器 ══════════════════════

    /**
     * 起一个独立的小 node 进程，盯引擎写的 session 日志，
     * 把 AI 的活动状态自动 POST 给悬浮窗（端口 = enginePort + 10）。
     *
     * 为什么这么做，而不是写成 cordis 插件：
     *   插件要往 profile 的 cordis.patch.yml 里加条目，但引擎的插件树在【启动时】
     *   就构建好了 —— 实测改完 patch 文件、重启引擎两次，插件都没被加载
     *   （loader 的 patch 条目要求 id 必填，且看起来只支持按 id 覆盖/禁用既有节点）。
     *   这个推送器读的是引擎自己写的日志，与插件系统、与 AI 的自觉都无关；
     *   它还是【独立进程】，引擎重启也不受影响（实测有效）。
     *
     * 幂等：已经在跑就直接返回。
     */
    private void ensureOverlayWatcher() {
        try {
            if (isAlive(overlayWatchProc)) return;

            File files = getFilesDir();
            File payload = new File(files, "payload");
            File node = new File(payload, "runtime/bin/node");
            if (!node.isFile()) {
                Log.w(TAG, "watcher: node 不存在，跳过");
                return;
            }
            if (!node.canExecute()) node.setExecutable(true, false);
            File dshHome = new File(payload, "dshhome");
            if (!dshHome.isDirectory()) {
                Log.w(TAG, "watcher: dshhome 不存在，跳过");
                return;
            }

            // 脚本从 res/raw 落到磁盘（每次都覆盖，保证与 App 版本一致）
            File script = new File(files, WATCHER_SCRIPT);
            try {
                writeRawResource(R.raw.overlay_watch, script);
            } catch (Throwable t) {
                Log.w(TAG, "watcher: 脚本落盘失败", t);
                return;
            }

            int port = enginePortForWatcher() + 10;
            File diagDir = diagDir();
            File diag = new File(diagDir, "overlay-watch.log");

            ProcessBuilder pb = new ProcessBuilder(
                    node.getAbsolutePath(), script.getAbsolutePath(),
                    "--port", String.valueOf(port),
                    "--interval", "700",
                    "--home", dshHome.getAbsolutePath(),
                    "--diag", diag.getAbsolutePath());
            java.util.Map<String, String> env = pb.environment();
            File lib = new File(payload, "runtime/lib");
            if (lib.isDirectory()) env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
            // 与 spawnNode 一致：Termux 共存修复，避免 node 读 Termux 的 openssl.cnf 触发 EACCES
            File osslConf = new File(payload, "runtime/etc/openssl.cnf");
            if (osslConf.exists()) env.put("OPENSSL_CONF", osslConf.getAbsolutePath());
            env.put("DSH_HOME", dshHome.getAbsolutePath());
            pb.redirectErrorStream(true);
            overlayWatchProc = pb.start();
            Log.i(TAG, "overlay watcher started (port=" + port + ")");
        } catch (Throwable t) {
            Log.w(TAG, "ensureOverlayWatcher failed", t);
        }
    }

    private void stopOverlayWatcher() {
        try {
            if (overlayWatchProc != null) {
                overlayWatchProc.destroy();
                overlayWatchProc = null;
                Log.i(TAG, "overlay watcher stopped");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 进程是否还在跑。
     * 不用 Process.isAlive()：那是 API 26+，本应用 minSdk 24。
     */
    private static boolean isAlive(Process p) {
        if (p == null) return false;
        try {
            p.exitValue();          // 能拿到退出码 = 已结束
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 引擎端口：优先读 MainActivity 持久化的值（用户可能改过），读不到按包名推默认值。
     * 默认值与 MainActivity.defaultEnginePort 口径一致（正式版 3080 / Lite 3082 / 兼容版 3084）。
     */
    private int enginePortForWatcher() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            int p = sp.getInt(KEY_ENGINE_PORT, 0);
            if (p > 0) return p;
        } catch (Throwable ignored) {}
        String pkg = getPackageName();
        if (pkg.contains("beta")) return 3082;
        if (pkg.contains("compat")) return 3084;
        return 3080;
    }

    /** 诊断日志目录：兼容版写 /sdcard/DeepSeekHarnessCompat，其余写 /sdcard/DeepSeekHarness。 */
    private File diagDir() {
        String pkg = getPackageName();
        File dir = new File("/sdcard", pkg.contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** 把 res/raw 里的文件写到磁盘（先写 .tmp 再改名，避免半截文件）。 */
    private void writeRawResource(int resId, File out) throws IOException {
        InputStream in = getResources().openRawResource(resId);
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { os.close(); } catch (Throwable ignored) {}
        }
        if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out);
        if (!tmp.renameTo(out)) throw new IOException("rename failed: " + tmp);
        out.setReadable(true, false);
    }

    // ══════════════════════ 通知 ══════════════════════

    private Notification buildNotification(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)   // 常驻不可滑动删除
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "引擎保活",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("DeepSeek Harness 引擎运行状态");
            nm.createNotificationChannel(ch);
        }
    }
}
