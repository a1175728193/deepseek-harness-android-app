package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import org.json.JSONObject;

/**
 * 小鲸鱼悬浮窗服务：
 *  - 常驻悬浮小鲸鱼图标（可拖动；松手自动贴边，静置时半藏在屏幕边缘）
 *  - 点击展开紧凑状态面板：引擎状态 / AI 会话状态 / 打开应用 / 虚拟屏 / 销毁屏 / 收起
 *  - 拖到屏幕底部区域松手 = 隐藏小鲸鱼（通知栏「显示小鲸鱼」可恢复）
 *  - 每 2 秒探测引擎端口；每 6 秒扫一次 /proc 统计"正在写入的会话"刷新 AI 状态
 *  - 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限；前台服务保活
 *
 * v1.13.12 大改（用户 15 条反馈里的悬浮窗部分）：
 *  ① 前台隐藏优先级最高 —— 之前虚拟屏预览「收起到小鲸鱼」的钉住状态会盖过前台隐藏，
 *     导致 App 里偶尔冒出小鲸鱼；现在 App 前台一律隐藏（预览仍由钉住状态保持拉帧）。
 *  ② 静置半藏：面板收起时小鲸鱼滑到屏幕边缘、只露一半（FLAG_LAYOUT_NO_LIMITS 允许越界）；
 *     点开面板/拖动时完整露出。收起与唤出都带旋转抖动动画。
 *  ③ 拖到底部隐藏：悬浮窗没有系统级的"拖底消失"（那是通知气泡的特权），
 *     这里自实现同款手势 + 通知栏恢复入口。
 *  ④ AI 状态不再走 HTTP：0.1.5 起接口要认证 cookie，悬浮窗拿不到（401 → 永远"空闲"）。
 *     改为扫 /proc/<同uid进程>/fd 里持着 dshhome/sessions/**​/session.lock 的文件描述符 ——
 *     内核的会话写入器持锁多久，fd 就开多久（dsh-session-persistence-jsonl 的 SessionWriteLease），
 *     这是"会话正在工作"的一手证据，无需任何认证。
 */
public class OverlayService extends Service {
    /**
     * 三版本共存的默认引擎端口，按包名区分（与 AccessibilityService 的口径一致）：
     * 正式版 3080 / Lite 3082 / 兼容版 3084。通知端口 = 引擎端口 + 1，无障碍端口 = +101。
     * 否则三套 App 同时安装会抢同一个 3080（表现为 EADDRINUSE、工具连到别的版本的服务）。
     */
    private static int defaultEnginePort(Context ctx) {
        String p = ctx != null ? ctx.getPackageName() : "";
        if (p.contains("beta")) return 3082;
        if (p.contains("compat")) return 3084;
        return 3080;
    }

    private static final String PREFS = "dsh_prefs";
    private static final String KEY_PORT = "engine_port";
    private static final String CHANNEL_ID = "dsh_overlay";
    private static final int NOTIF_ID = 9002;
    private static final long PROBE_MS = 2000L;
    /** 静置半藏时露在外面的比例（另一半越界到屏幕外）。 */
    private static final float TUCK_VISIBLE_FRACTION = 0.45f;
    /** 拖到距底部多少 dp 内松手 = 隐藏。 */
    private static final int DISMISS_ZONE_DP = 84;
    /** AI "已完成"提示在状态切换后保留的时长（毫秒）。 */
    private static final long FINISHED_TTL_MS = 60000L;

    // ==================== issue #54：悬浮球手势动作（可配置） ====================
    // 动作存 SharedPreferences（文件名 dsh_prefs，键 overlayTap/overlayLong/overlayDouble + Pkg）：
    // 默认单击=展开/收起面板（老行为），长按/双击=无动作；每次手势都重新读，改完立即生效。
    public static final String ACTION_PANEL = "panel";
    public static final String ACTION_OPEN_APP = "open_app";
    public static final String ACTION_VSCREEN_CREATE = "vscreen_create";
    public static final String ACTION_VSCREEN_CLOSE = "vscreen_close";
    public static final String ACTION_COLLAPSE = "collapse";
    public static final String ACTION_NONE = "none";
    /** 可选动作的展示顺序（控制台「悬浮球」页的动作选择框按这个顺序排）。 */
    private static final String[] ACTION_TYPES = {
            ACTION_PANEL, ACTION_OPEN_APP, ACTION_VSCREEN_CREATE,
            ACTION_VSCREEN_CLOSE, ACTION_COLLAPSE, ACTION_NONE};
    /** 双击判定窗口（两次抬起之间的最大间隔）与长按阈值（按下到抬起的最短时长）。 */
    private static final long DOUBLE_TAP_MS = 280L;
    private static final long LONG_PRESS_MS = 600L;

    /** 当前运行的 OverlayService 实例（供 MainActivity 前后台联动控制视图可见性）。 */
    private static OverlayService instance = null;

    /** 是否正在运行（供 MainActivity / HTTP 端点查询） */
    public static volatile boolean isRunning = false;
    /** 最近一次引擎探测结果 */
    public static volatile boolean engineUp = false;
    public static volatile long lastProbeAt = 0L;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout rootView;
    private ImageView iconView;
    private LinearLayout panelView;
    private TextView statusText;
    private TextView aiText;
    private Button destroyBtn;
    // v1.9 虚拟屏预览：悬浮窗实时显示虚拟屏画面（用户可看 AI 操作）
    private ImageView vscreenImageView = null;
    private volatile boolean vscreenPreviewRunning = false;
    private final Handler vscreenHandler = new Handler(Looper.getMainLooper());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int enginePort = 3080;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private boolean panelVisible = false;
    /** 拖动中进入"拖底删除"暗示区（图标缩小变淡提示松手即隐藏）。 */
    private boolean dismissHint = false;
    /** 用户拖底主动隐藏后为 true；从通知栏「显示小鲸鱼」恢复。 */
    private volatile boolean userHidden = false;
    /** v1.13.11：悬浮图标当前吸附在右边？由拖动松手时的 snapToEdge() 决定。 */
    private boolean snappedRight = false;
    /** v1.13.11：App 在前台 → 悬浮窗应隐藏（由 MainActivity.onStart/onStop 维护）。 */
    private volatile boolean foregroundWantsHidden = true;
    /** v1.13.11：被虚拟屏预览「收起到小鲸鱼」钉住 —— 只负责持续拉预览帧，不再影响可见性。 */
    private volatile boolean vscreenPinned = false;
    /** 探测计数：每 PROBE_MS 探测一次引擎；每 3 次（约 6 秒）顺带扫一次会话写入器 */
    private int probeCount = 0;
    /** 最近一次扫到的"持锁会话"目录集合；null 表示还扫过。 */
    private volatile HashSet<String> activeSessions = new HashSet<String>();
    private volatile long finishedAt = 0L;

    // ══ v1.17 显式状态上报 ══════════════════════════════════════════
    // AI 通过 POST http://127.0.0.1:<enginePort+10>/overlay-status 主动上报
    // 「我现在在干什么」，比 /proc 扫描会话文件准得多（那个只能猜"忙/闲"）。
    // state: "" 未上报 | idle 空闲 | working 进行中 | done 完成 | need 需要你
    private volatile String taskState = "";
    private volatile int    stepIndex = 0;
    private volatile int    stepTotal = 0;
    private volatile String stepText  = "";
    private volatile String stepEta   = "";
    private volatile String stepExtra = "";      // 已完成步骤的多行文本
    private volatile long   stateAt   = 0L;
    /** 超过这么久没收到新上报，就把状态当作废（免得永远停在"进行中"）。 */
    private static final long STATE_TTL_MS = 15 * 60 * 1000L;
    /** 面板里的步骤列表。 */
    private TextView stepsText;
    private java.net.ServerSocket statusServer;
    private volatile boolean statusServerRunning = false;
    private android.animation.ValueAnimator stateAnim;

    public static int enginePort(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return sp.getInt(KEY_PORT, defaultEnginePort(ctx));
    }

    // ---------- issue #54：手势动作的 prefs 读写（控制台与悬浮窗共用同一套口径） ----------

    /** 手势 → prefs 键（非法/未知手势按单击处理）。 */
    private static String gesturePrefKey(String gesture) {
        if ("long".equals(gesture)) return "overlayLong";
        if ("double".equals(gesture)) return "overlayDouble";
        return "overlayTap";
    }

    /** 六个可选动作（拷贝，调用方随便改不影响内置顺序）。 */
    public static String[] actionTypes() {
        return ACTION_TYPES.clone();
    }

    /** 动作的中文名（控制台「悬浮球」页与选择框用）。 */
    public static String actionLabel(String type) {
        if (ACTION_PANEL.equals(type)) return "状态面板";
        if (ACTION_OPEN_APP.equals(type)) return "打开应用";
        if (ACTION_VSCREEN_CREATE.equals(type)) return "创建虚拟屏";
        if (ACTION_VSCREEN_CLOSE.equals(type)) return "销毁虚拟屏";
        if (ACTION_COLLAPSE.equals(type)) return "收起悬浮球";
        if (ACTION_NONE.equals(type)) return "无动作";
        return "无动作";
    }

    /** 该动作是否需要再选一个应用（open_app 必选 / vscreen_create 可空）。 */
    public static boolean actionNeedsApp(String type) {
        return ACTION_OPEN_APP.equals(type) || ACTION_VSCREEN_CREATE.equals(type);
    }

    /** 读某个手势配置的动作；没配过时单击=panel、长按/双击=none。 */
    public static String overlayAction(Context ctx, String gesture) {
        String def = "tap".equals(gesture) ? ACTION_PANEL : ACTION_NONE;
        try {
            String v = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(gesturePrefKey(gesture), def);
            return normalizeAction(v, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 读某个手势绑定的应用包名（没配过 = 空串）。 */
    public static String overlayActionPkg(Context ctx, String gesture) {
        try {
            String v = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(gesturePrefKey(gesture) + "Pkg", "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 保存某个手势的动作。
     * @param pkg 非 null 时才写包名键（null = 保留原来的应用选择，切回「打开应用」时不用重选）。
     */
    public static void saveOverlayAction(Context ctx, String gesture, String type, String pkg) {
        try {
            SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putString(gesturePrefKey(gesture), normalizeAction(type, ACTION_NONE));
            if (pkg != null) e.putString(gesturePrefKey(gesture) + "Pkg", pkg.trim());
            e.apply();
        } catch (Throwable ignored) {}
    }

    /** 只认 6 个合法值；配置文件里被手改成别的值 → 回落默认，避免悬浮窗行为不确定。 */
    private static String normalizeAction(String v, String def) {
        if (v != null) {
            for (int i = 0; i < ACTION_TYPES.length; i++) {
                if (ACTION_TYPES[i].equals(v)) return v;
            }
        }
        return def;
    }

    private final Runnable probeRunnable = new Runnable() {
        @Override public void run() {
            if (!isRunning) return;
            probeCount++;
            final boolean scanSessions = probeCount % 3 == 0;
            // 探测放后台线程：HttpURLConnection / /proc 扫描在主线程会卡界面
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean up = engineAlive(enginePort);
                    if (up && scanSessions) {
                        activeSessions = scanActiveSessions();
                        lastProbeAt = System.currentTimeMillis();
                    }
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (!isRunning) return;
                            engineUp = up;
                            updateEngineStatusUi();
                        }
                    });
                }
            }, "overlay-probe").start();
            handler.postDelayed(this, PROBE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        instance = this;
        enginePort = enginePort(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundCompat();
        buildOverlay();
        addToWindow();
        // 前后台联动：App 前台时隐藏悬浮窗（不挡界面），退后台时显示
        foregroundWantsHidden = MainActivity.overlayForeground;   // v1.13.11：改为记状态再统一应用
        applyVisibleNow();
        handler.postDelayed(probeRunnable, 200);
        startStatusServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏「显示小鲸鱼」：解除用户隐藏并抖一下示意
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) {
            userHidden = false;
            applyVisibleNow();
            wiggle();
        }
        // 允许通过 intent 指定端口（如换端口后重启）
        if (intent != null && intent.hasExtra("port")) {
            enginePort = intent.getIntExtra("port", enginePort);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_PORT, enginePort).apply();
        }
        return START_STICKY;
    }

    private static final String ACTION_SHOW = "com.deepseek.harness.overlay.SHOW";

    @Override
    public void onDestroy() {
        isRunning = false;
        if (instance == this) instance = null;
        stopStatusServer();
        stopStatePulse();
        stopVscreenPreview();
        handler.removeCallbacksAndMessages(null);
        if (rootView != null && wm != null) {
            try { wm.removeView(rootView); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** 前台服务保活（引擎运行期间悬浮窗不被系统回收） */
    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "黑鲸鱼悬浮窗",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("黑鲸鱼悬浮窗运行中（引擎状态指示）");
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotification());
    }

    /** 常驻通知（内容随引擎状态更新；常驻「显示小鲸鱼」动作，拖底隐藏后靠它找回）。 */
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(this, OverlayService.class);
        show.setAction(ACTION_SHOW);
        PendingIntent showPi = PendingIntent.getService(this, 1, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { b.addAction(new Notification.Action.Builder(null, "显示小鲸鱼", showPi).build()); }
        catch (Throwable ignored) {
            // 老系统 Action.Builder(null, ...) 不吃图标时退化：不显示动作也不影响主流程
        }
        return b.setContentTitle("🐋 DeepSeek Harness 运行中")
                .setContentText("引擎状态：" + (engineUp ? "运行中（端口 " + enginePort + "）" : "未运行"))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void buildOverlay() {
        // ===== 根布局（竖排：图标行 + 状态面板）=====
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setPadding(dp(10), dp(8), dp(10), dp(8));
        // 收起态无背景（只留小鲸鱼图标）；背景移到展开面板 panelView 上

        // ===== 图标行（小鲸鱼）=====
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(dp(4), dp(2), dp(4), dp(2));

        iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_whale_black); // DSH 鲸鱼（品牌蓝+白描边）
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        iconRow.addView(iconView);
        rootView.addView(iconRow);
        refreshOverlayIcon();   // issue #47：外部目录有 overlay-icon.png 就换用户图

        // ===== 状态面板（紧凑版，默认隐藏）=====
        panelView = new LinearLayout(this);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(getColor(R.color.panel_bg));              // 深蓝半透明（统一配色资源）
        pbg.setCornerRadius(dp(12));
        panelView.setBackground(pbg);

        statusText = new TextView(this);
        statusText.setText("状态：检测中…");
        statusText.setTextColor(getColor(R.color.panel_text_bright));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        panelView.addView(statusText);

        // AI 会话状态（/proc 会话写入器扫描，每 ~6 秒刷新；v1.13.12 起不再是永远"空闲"）
        aiText = new TextView(this);
        aiText.setText("AI：—");
        aiText.setTextColor(getColor(R.color.panel_text_dim));
        aiText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        panelView.addView(aiText);

        // v1.17 步骤列表：AI 上报"第几步/共几步/正在做什么"
        stepsText = new TextView(this);
        stepsText.setText("");
        stepsText.setTextColor(getColor(R.color.panel_text_bright));
        stepsText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        stepsText.setPadding(0, dp(4), 0, 0);
        stepsText.setVisibility(View.GONE);
        panelView.addView(stepsText);

        // v1.9 虚拟屏预览（默认隐藏）：悬浮窗实时显示虚拟屏画面
        vscreenImageView = new ImageView(this);
        LinearLayout.LayoutParams vsp = new LinearLayout.LayoutParams(dp(176), dp(298));
        vsp.topMargin = dp(6);
        vscreenImageView.setLayoutParams(vsp);
        vscreenImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vscreenImageView.setVisibility(View.GONE);
        vscreenImageView.setBackgroundColor(0x88000000);
        panelView.addView(vscreenImageView);

        // v1.13.12：按钮重做 —— 面板不需要大按钮，两行小胶囊足够；端口行整体移除
        // （端口在控制台/通知里都有，天天显示在悬浮窗上没有信息量）。
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r1p.topMargin = dp(7);
        btnRow1.setLayoutParams(r1p);
        btnRow1.addView(pillButton("打开应用", new Runnable() { @Override public void run() {
            Intent i = new Intent(OverlayService.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }}));
        btnRow1.addView(pillButton("虚拟屏", new Runnable() { @Override public void run() {
            // 预览窗被「收起到小鲸鱼」后的回程入口；没有虚拟屏时桥服务会静默忽略
            try { VsreenBridgeService.showPreviewFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }}));

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2p.topMargin = dp(5);
        btnRow2.setLayoutParams(r2p);
        // 销毁屏：替代旧预览窗上的 ✕（用户要求销毁功能收进小鲸鱼面板）
        destroyBtn = pillButton("销毁屏", new Runnable() { @Override public void run() {
            try { VsreenBridgeService.destroyVscreenFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }});
        destroyBtn.setVisibility(View.GONE);
        btnRow2.addView(destroyBtn);
        btnRow2.addView(pillButton("收起", new Runnable() { @Override public void run() {
            setPanelVisible(false, true);
        }}));

        panelView.addView(btnRow1);
        panelView.addView(btnRow2);
        rootView.addView(panelView);
        setPanelVisible(false, false);

        // ===== 拖动 + 单击 / 长按 / 双击（issue #54：动作可配置）+ 拖底隐藏 =====
        rootView.setOnTouchListener(new View.OnTouchListener() {
            /** 本次按下时间（长按判定）与上一次「短按抬起」时间（双击判定）。 */
            private long downAt = 0;
            private long lastTapAt = 0L;
            /**
             * 单击延迟执行：先等一个双击窗口，确认不是双击后再跑单击动作。
             * 若立即执行单击，「单击=打开应用」这类动作会先跳出应用，双击永远轮不到。
             */
            private final Runnable tapRunnable = new Runnable() {
                @Override public void run() {
                    if (lastTapAt == 0L) return;
                    lastTapAt = 0L;
                    runGestureAction("tap");
                }
            };

            /** 放弃还没执行的单击（双击/长按/拖动都会走到这里）。 */
            private void cancelPendingTap() {
                lastTapAt = 0L;
                handler.removeCallbacks(tapRunnable);
            }

            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downAt = System.currentTimeMillis();
                        touchX = ev.getRawX(); touchY = ev.getRawY();
                        startX = lp.x; startY = lp.y;
                        dragging = false;
                        // 手指再次按下：上一次的单击先挂起不执行 —— 这一下可能是双击的第二下，
                        // 也可能变成拖动/长按；等抬起时再定（见 ACTION_UP）。
                        if (lastTapAt != 0L) handler.removeCallbacks(tapRunnable);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && (Math.abs(ev.getRawX() - touchX) > dp(8)
                                || Math.abs(ev.getRawY() - touchY) > dp(8))) {
                            dragging = true;
                            cancelPendingTap();   // 变成拖动 → 上一次的单击作废
                        }
                        if (dragging) {
                            lp.x = (int) (startX + (ev.getRawX() - touchX));
                            lp.y = (int) (startY + (ev.getRawY() - touchY));
                            try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                            updateDismissHint(ev.getRawY());
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long upAt = System.currentTimeMillis();
                        long held = upAt - downAt;
                        if (dragging && dismissHint) {
                            hideByDragToBottom();
                        } else if (dragging) {
                            snapToEdge();      // 拖完自动吸到最近的左右边缘（静置态=半藏）
                            if (panelVisible) setPanelVisible(true, false);
                        } else if (held >= LONG_PRESS_MS) {
                            cancelPendingTap();
                            runGestureAction("long");
                        } else if (lastTapAt != 0L && upAt - lastTapAt <= DOUBLE_TAP_MS) {
                            cancelPendingTap();
                            runGestureAction("double");
                        } else if ("none".equals(overlayAction(OverlayService.this, "double"))) {
                            // 双击没配动作 → 不必等双击窗口，单击立即执行（默认单击=面板，手感不变）
                            runGestureAction("tap");
                        } else {
                            lastTapAt = upAt;
                            handler.postDelayed(tapRunnable, DOUBLE_TAP_MS + 40);
                        }
                        setDismissHintInternal(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelPendingTap();
                        setDismissHintInternal(false);
                        return true;
                    case MotionEvent.ACTION_OUTSIDE:
                        // 点击悬浮窗外区域：收回面板（回到半藏态），并作废还没执行的单击
                        cancelPendingTap();
                        if (panelVisible) setPanelVisible(false, true);
                        return true;
                }
                return false;
            }
        });
    }

    /** 手势 → 读 prefs → 执行动作（每次手势都读，控制台改完立即生效）。 */
    private void runGestureAction(String gesture) {
        String type = overlayAction(this, gesture);
        String pkg = overlayActionPkg(this, gesture);
        runOverlayAction(type, pkg);
    }

    /** 执行一个悬浮球动作；任何异常都不许把悬浮窗主流程带崩。 */
    private void runOverlayAction(String type, String pkg) {
        try {
            if (type == null || ACTION_NONE.equals(type)) return;
            if (ACTION_PANEL.equals(type)) {
                setPanelVisible(!panelVisible, true);
                return;
            }
            if (ACTION_COLLAPSE.equals(type)) {
                // 复用拖到底部松手的隐藏逻辑（私有方法，类内直接调）
                hideByDragToBottom();
                return;
            }
            if (ACTION_VSCREEN_CLOSE.equals(type)) {
                VsreenBridgeService.destroyVscreenFromWhale();
                return;
            }
            if (ACTION_OPEN_APP.equals(type)) {
                openAppFromOverlay(pkg);
                return;
            }
            if (ACTION_VSCREEN_CREATE.equals(type)) {
                VsreenBridgeService.createVscreenForPackage(pkg == null ? "" : pkg.trim());
            }
        } catch (Throwable t) {
            toastOverlay("动作执行失败：" + t.getMessage());
        }
    }

    /** 「打开应用」动作：没配应用 / 应用已卸载都明确提示，不静默。 */
    private void openAppFromOverlay(String pkg) {
        String p = pkg == null ? "" : pkg.trim();
        if (p.length() == 0) {
            toastOverlay("未配置应用");
            return;
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i == null) {
                toastOverlay("未找到应用");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toastOverlay("未找到应用");
        }
    }

    private void toastOverlay(String msg) {
        try {
            android.widget.Toast.makeText(getApplicationContext(), msg,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    /**
     * 可启动应用列表：每项 {应用名, 包名}，按应用名排序、同一包名只留一条。
     * 控制台「悬浮球」页选应用用；包管理器查询失败返回空列表（页面会如实提示）。
     */
    public static List<String[]> launchableApps(Context ctx) {
        List<String[]> out = new ArrayList<String[]>();
        if (ctx == null) return out;
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
            if (infos == null) return out;
            HashSet<String> seen = new HashSet<String>();
            for (int i = 0; i < infos.size(); i++) {
                ResolveInfo ri = infos.get(i);
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.length() == 0 || !seen.add(pkg)) continue;
                CharSequence lab = ri.loadLabel(pm);
                String label = lab == null ? "" : lab.toString().trim();
                if (label.length() == 0) label = pkg;
                out.add(new String[]{label, pkg});
            }
            Collections.sort(out, new Comparator<String[]>() {
                @Override public int compare(String[] a, String[] b) {
                    int c = a[0].compareToIgnoreCase(b[0]);
                    return c != 0 ? c : a[0].compareTo(b[0]);
                }
            });
        } catch (Throwable ignored) {}
        return out;
    }

    /** 面板里的小胶囊按钮（v1.13.12 重做：小、轻、不抢眼）。 */
    private Button pillButton(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        b.setTextColor(getColor(R.color.accent_brand));
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(11));
        b.setBackground(bg);
        b.setSingleLine(true);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setHeight(dp(25));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(25), 1f);
        lp2.leftMargin = dp(3);
        lp2.rightMargin = dp(3);
        b.setLayoutParams(lp2);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try { action.run(); } catch (Throwable ignored) {}
        }});
        return b;
    }

    // ==================== 可见性 / 贴边 / 动画 ====================

    /** 悬浮窗整体可见性（App 前台隐藏、退后台显示；服务常驻只切视图）。 */
    public static void setOverlayVisible(boolean show) {
        OverlayService s = instance;
        if (s != null) { s.foregroundWantsHidden = !show; s.applyVisibleNow(); }
    }

    /**
     * v1.13.11：虚拟屏预览「收起到小鲸鱼」。
     * v1.13.12 语义收窄：pin 只代表"预览帧继续在小鲸鱼面板里拉"，**不再强制可见** ——
     * 之前它会盖过前台隐藏，用户在 App 里也会看到小鲸鱼（报过"偶尔在 dsh 中也显示小鲸鱼"）。
     * @param pin true=开始拉虚拟屏画面到面板；false=停止
     */
    public static void pinForVscreen(boolean pin) {
        OverlayService s = instance;
        if (s != null) s.applyVscreenPin(pin);
    }

    /**
     * issue #54：从控制台把「收起」掉的悬浮球显示回来。
     * 收起（双击动作 / 拖底隐藏）只置 userHidden；ColorOS 的通知栏动作偶尔不显示，
     * 所以控制台里留一个确定可用的找回入口。
     */
    public static void showBallNow() {
        OverlayService s = instance;
        if (s == null) return;
        s.userHidden = false;
        s.foregroundWantsHidden = false;
        s.applyVisibleNow();
        s.setPanelVisible(true, true);
    }

    private void applyVscreenPin(boolean pin) {
        try {
            vscreenPinned = pin;
            if (pin) startVscreenPreview();
            else stopVscreenPreview();
        } catch (Throwable ignored) {}
    }

    /**
     * 实际可见性。v1.13.12 起规则只有两条：
     * ① App 在前台（foregroundWantsHidden）→ 隐藏，无论虚拟屏是否钉住；
     * ② 用户拖底主动隐藏（userHidden）→ 隐藏，直到通知栏「显示小鲸鱼」。
     */
    private void applyVisibleNow() {
        try {
            if (rootView != null) {
                boolean show = !foregroundWantsHidden && !userHidden;
                rootView.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) refreshOverlayIcon();   // 每次变可见按 mtime 跟随用户换图
            }
        } catch (Throwable ignored) {}
    }

    // ==================== 自定义悬浮窗图标（issue #47 / v1.19.8 纳入主题体系） ====================
    // 图片来源（按优先级）：
    //   ① 控制台主题里的 appearance.overlayIcon（v1.19.8 起）—— 图片随主题包一起导入/导出，
    //      与控制台其它外观同一套机制（相对 console/ 的文件名，或 /sdcard/… 绝对路径）；
    //   ② 遗留约定路径 /sdcard/<DeepSeekHarness|…Lite|…Compat>/overlay-icon.png（issue #47 老用法，保持兼容）；
    //   ③ 都没有 → 内置小鲸鱼。
    //  · PNG/WebP/JPG 均可（BitmapFactory 原生支持）；>2048px 先降采样，避免大图 OOM / 拉帧卡顿；
    //  · 文件缺失 / 解码失败 / 主题配置坏掉 一律静默回落内置鲸鱼 —— 悬浮窗的稳定性比图标好看重要；
    //  · 换图 / 删图 / 换主题后，下一次可见性刷新时自动跟随（按 mtime 判断；解码放后台线程）。
    private volatile String overlayIconKey = null;   // 已加载图标的键 = "<路径>@<mtime>"；null=当前是内置图标
    private volatile long overlayIconThemeMtime = -3L;   // 上次读取时 console.json 的 mtime（换主题要重判）

    private File overlayIconFile() {
        try {
            String p = getPackageName();
            String root = p.contains("beta") ? "DeepSeekHarnessLite"
                    : p.contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
            File extRoot = new File(android.os.Environment.getExternalStorageDirectory(), root);
            File cfg = new File(new File(extRoot, "console"), "console.json");
            File legacy = new File(extRoot, "overlay-icon.png");
            // ⚠ 主题里指向的图片不存在时，ConsoleTheme.overlayIconFile 会自动落回 legacy（见其注释）
            return ConsoleTheme.overlayIconFile(cfg, legacy);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 控制台主题文件（换主题 → 图标可能跟着换，所以要盯它的 mtime）。 */
    private File overlayIconConfigFile() {
        try {
            String p = getPackageName();
            String root = p.contains("beta") ? "DeepSeekHarnessLite"
                    : p.contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
            return new File(new File(new File(android.os.Environment.getExternalStorageDirectory(), root), "console"), "console.json");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按 mtime 决定要不要换图标；需要解码时放后台线程，失败一律回落内置鲸鱼。
     *
     * ⚠ 缓存键必须是「**最终解析到的文件路径** + mtime」，不能只记 mtime：
     *   主题图被删后会静默回落到遗留路径的图，两条路径 mtime 可能都没变
     *   → 只比 mtime 的话会拿着旧图不放（真机实测踩过：删主题图后没回落）。
     *   主题文件本身的 mtime 也要一起比（换主题 → 图标可能跟着换）。
     */
    private void refreshOverlayIcon() {
        final File cfg = overlayIconConfigFile();
        long cfgMtime = (cfg != null && cfg.isFile()) ? cfg.lastModified() : -1L;
        final File f = overlayIconFile();
        String key = (f != null && f.isFile()) ? (f.getAbsolutePath() + "@" + f.lastModified()) : null;
        // 诊断（真机排查用）：logcat -s DeepSeekHarness 能看到图标到底取到哪个文件
        android.util.Log.i("DeepSeekHarness", "overlay icon: cfg=" + (cfg == null ? "-" : cfg.getAbsolutePath() + "@" + cfgMtime)
                + " file=" + (key == null ? "<none>" : key) + " cached=" + overlayIconKey);
        if (cfgMtime == overlayIconThemeMtime && String.valueOf(key).equals(String.valueOf(overlayIconKey))) return;
        overlayIconThemeMtime = cfgMtime;
        final String expectKey = key;
        if (key == null) {                        // 没图（主题没指定、约定路径也没有）→ 回内置鲸鱼
            overlayIconKey = null;
            try { if (iconView != null) iconView.setImageResource(R.drawable.ic_whale_black); } catch (Throwable ignored) {}
            return;
        }
        overlayIconKey = key;
        new Thread(new Runnable() { @Override public void run() {
            final android.graphics.Bitmap bmp = decodeIconBitmap(f);
            handler.post(new Runnable() { @Override public void run() {
                if (!isRunning || iconView == null) return;
                if (!String.valueOf(overlayIconKey).equals(String.valueOf(expectKey))) return;   // 期间又变了，下次刷新再处理
                if (bmp != null) iconView.setImageBitmap(bmp);
                else { overlayIconKey = null; iconView.setImageResource(R.drawable.ic_whale_black); }
            }});
        }}, "overlay-icon").start();
    }

    /** 解码自定义图标：先读尺寸，>2048px 降采样；任何异常返回 null（调用方回落默认图标）。 */
    private static android.graphics.Bitmap decodeIconBitmap(File f) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / (sample * 2) > 2048) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void addToWindow() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        // FLAG_LAYOUT_NO_LIMITS：允许窗口越出屏幕边界 —— 静置"半藏"就靠它
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(160);
        try {
            wm.addView(rootView, lp);
            // 只做一次"布局落定后摆正"。
            // ⚠ 不要在这里挂长期的 OnLayoutChangeListener：拖动窗口、以及"半藏"时
            // 系统重测宽度都会触发 layoutChange，长期监听会把 x 每帧拽回贴边位 ——
            // 表现就是"小鲸鱼横向拖不动、半藏也站不住"。而这里要修的只是"面板
            // 展开/收起瞬间 getWidth() 还是旧值"，一次性摆正就够。
            settleAfterLayout();
        } catch (Throwable t) {
            stopSelf();
        }
    }

    /**
     * v1.13.12：贴边 = 面板收起时把小鲸鱼**半藏**到屏幕边缘（露出约一半），
     * 面板展开时完整贴边（否则面板会被截掉）。
     */
    private void snapToEdge() {
        try {
            if (lp == null || rootView == null) return;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            int h = rootView.getHeight() > 0 ? rootView.getHeight() : dp(56);
            snappedRight = (lp.x + w / 2) > getResources().getDisplayMetrics().widthPixels / 2;
            lp.x = edgeXFor(w);
            if (lp.y < 0) lp.y = 0;
            if (lp.y > screenH - h) lp.y = Math.max(0, screenH - h);
            wm.updateViewLayout(rootView, lp);
        } catch (Throwable ignored) {}
    }

    /**
     * 贴边坐标：snappedRight 决定靠哪边；tucked 决定是否半藏。
     * 面板收起（静置）→ 半藏：x 让窗口越出屏幕 (1-露出的比例)；展开 → 完整可见 + 留 4dp 边距。
     */
    private int edgeXFor(int viewWidth) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        boolean tucked = !panelVisible;
        if (tucked) {
            int off = Math.round(viewWidth * (1f - TUCK_VISIBLE_FRACTION));
            return snappedRight ? screenW - viewWidth + off : -off;
        }
        return snappedRight ? Math.max(dp(4), screenW - viewWidth - dp(4)) : dp(4);
    }

    /** 面板显示/隐藏；animate=true 时带旋转抖动 + 位置过渡（唤出、收起共用）。 */
    private void setPanelVisible(boolean show, boolean animate) {
        panelVisible = show;
        if (panelView != null) panelView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) refreshPanelDynamicRows();
        if (lp == null) return;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
        if (!animate) {
            snapToEdge();
        } else {
            wiggle();
        }
        // 展开/收起必然改变窗口尺寸，而 getWidth() 在下一次 layout 之前仍是旧值：
        // 用它算贴边坐标 → 展开时按"图标宽度"摆（面板被推出右边）、收起时按"面板宽度"
        // 算半藏位（整只鲸鱼被推出屏幕）。又因为 FLAG_LAYOUT_NO_LIMITS 系统不夹边界，
        // 算错就真的出屏。原实现用 rootView.post() 补救，但 post 只延后一条消息、
        // 常常仍在下一次 layout 之前，等于没夹。改为等布局真正落定后再算。
        settleAfterLayout();
    }

    /**
     * 布局真正落定后，按**真实尺寸**重算贴边位置。
     * 这是"小鲸鱼/面板跑出屏幕"的根因修法 —— 不能再依赖调用时刻的 getWidth()。
     */
    private void settleAfterLayout() {
        try {
            if (rootView == null) return;
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        android.view.ViewTreeObserver vto = rootView.getViewTreeObserver();
                        if (vto.isAlive()) vto.removeOnGlobalLayoutListener(this);
                    } catch (Throwable ignored) {}
                    applyEdgePos(true);
                }
            });
        } catch (Throwable ignored) {
            applyEdgePos(false);
        }
    }

    /**
     * 按真实尺寸把窗口摆到正确的贴边位：
     * 面板展开 → 完整可见（留 4dp 边距）；面板收起 → 半藏（露出 TUCK_VISIBLE_FRACTION）。
     * @param animate 是否平滑过渡（布局刚落定时用 true，观感更顺）
     */
    private void applyEdgePos(boolean animate) {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            int h = rootView.getHeight() > 0 ? rootView.getHeight() : dp(56);
            int targetX = edgeXFor(w);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            if (lp.y < 0) lp.y = 0;
            if (lp.y > screenH - h) lp.y = Math.max(0, screenH - h);
            if (!animate || lp.x == targetX) {
                lp.x = targetX;
                wm.updateViewLayout(rootView, lp);
                return;
            }
            final int fromX = lp.x;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(fromX, targetX);
            va.setDuration(220);
            va.setInterpolator(new OvershootInterpolator(0.6f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (lp == null || rootView == null) return;
                    lp.x = (Integer) a.getAnimatedValue();
                    try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 面板每次展开时刷新"看场景才该出现"的行（如销毁屏按钮）。 */
    private void refreshPanelDynamicRows() {
        try {
            if (destroyBtn != null) {
                destroyBtn.setVisibility(VsreenBridgeService.sVscreenRunning
                        ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /** 位置过渡：从当前 x 平滑滑到目标贴边位（半藏/完整随面板状态）。 */
    private void animateToEdge() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            final int targetX = edgeXFor(w);
            final int fromX = lp.x;
            if (fromX == targetX) return;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(fromX, targetX);
            va.setDuration(260);
            va.setInterpolator(new OvershootInterpolator(0.6f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (lp == null || rootView == null) return;
                    lp.x = (Integer) a.getAnimatedValue();
                    try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 旋转抖动动画（唤出/收起时的"小鲸鱼摆尾巴"）。只转图标，不转整块面板。 */
    private void wiggle() {
        try {
            if (iconView == null) return;
            android.animation.ObjectAnimator.ofFloat(
                    iconView, View.ROTATION, 0f, -14f, 11f, -8f, 5f, 0f)
                    .setDuration(420)
                    .start();
        } catch (Throwable ignored) {}
    }

    /** 拖动中：接近屏幕底部时给出"松手即隐藏"的暗示（图标缩小变淡）。 */
    private void updateDismissHint(float rawY) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean inZone = rawY > screenH - dp(DISMISS_ZONE_DP);
        if (inZone != dismissHint) setDismissHintInternal(inZone);
    }

    private void setDismissHintInternal(boolean on) {
        dismissHint = on;
        try {
            if (iconView == null) return;
            iconView.animate().scaleX(on ? 0.62f : 1f).scaleY(on ? 0.62f : 1f)
                    .alpha(on ? 0.7f : 1f).setDuration(140).start();
        } catch (Throwable ignored) {}
    }

    /** 拖到底部松手：隐藏小鲸鱼（通知栏「显示小鲸鱼」可恢复）。 */
    private void hideByDragToBottom() {
        userHidden = true;
        try {
            rootView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180)
                    .withEndAction(new Runnable() { @Override public void run() {
                        try {
                            rootView.setAlpha(1f);
                            setDismissHintInternal(false);
                            applyVisibleNow();
                        } catch (Throwable ignored) {}
                    }}).start();
        } catch (Throwable ignored) {
            applyVisibleNow();
        }
        try {
            android.widget.Toast.makeText(getApplicationContext(),
                    "小鲸鱼已隐藏，可从通知栏「显示小鲸鱼」恢复", android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private void clampPanelOnScreen() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth();
            if (w <= 0) return;
            if (!panelVisible) {
                lp.x = edgeXFor(w);
            } else {
                int maxX = getResources().getDisplayMetrics().widthPixels - w - dp(4);
                if (lp.x > maxX) lp.x = Math.max(dp(4), maxX);
            }
            wm.updateViewLayout(rootView, lp);
        } catch (Throwable ignored) {}
    }

    // ==================== 引擎探测 / AI 会话状态 ====================

    /** 探测引擎是否在跑。
     *  v1.13：0.1.5 起首页需要一次性 token —— 不带 token 返回 401 + 纯文本
     *  “dsh web authentication required…”，带有效 token 返回 303 跳转；两种都说明“引擎在跑”。 */
    private boolean engineAlive(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-overlay-probe");
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 303 || code == 302) return true;
            if (code == 401) return bodyContains(c, "dsh web authentication required");
            if (code < 200 || code >= 500) return false;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int r;
            while ((r = in.read(chunk)) > 0 && total < 262144) {
                body.write(chunk, 0, r);
                total += r;
            }
            try { in.close(); } catch (Throwable ignored) {}
            return body.toString("UTF-8").contains("<title>DeepSeek Harness</title>");
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 读一小段响应正文（401 的正文在错误流里）。 */
    private boolean bodyContains(HttpURLConnection c, String needle) {
        try {
            InputStream in = null;
            try { in = c.getInputStream(); } catch (Throwable t) { in = c.getErrorStream(); }
            if (in == null) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int r;
            while ((r = in.read(buf)) > 0 && out.size() < 8192) out.write(buf, 0, r);
            try { in.close(); } catch (Throwable ignored) {}
            return out.toString("UTF-8").contains(needle);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 扫出"正在被引擎写入"的会话集合（session 目录路径）。
     *
     * 机制依据：dsh-session-persistence-jsonl 的 SessionWriteLease 在会话写入器存活期间
     * 持有 <会话目录>/session.lock 的独占锁，释放 = 关闭 fd。所以
     * 「同 uid 进程的 /proc/<pid>/fd 里存在指向 session.lock 的 fd」⟺ 该会话正在工作。
     * node 是本 App 的子进程（同 uid），/proc 对同 uid 可读 —— findEnginePid 已验证过这条路。
     *
     * 旧实现走 POST /api/session.list：0.1.5 起要认证 cookie，悬浮窗拿不到（永远 401），
     * 表现就是"AI：空闲"永远不变。此扫描不需要任何认证。
     *
     * /proc 完全扫不动（被 SELinux 拦等极端情况）返回 null，调用方保持上次结果。
     */
    private HashSet<String> scanActiveSessions() {
        HashSet<String> out = new HashSet<String>();
        try {
            File[] procs = new File("/proc").listFiles();
            if (procs == null) return null;
            for (File d : procs) {
                String name = d.getName();
                if (name == null || name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
                File fdDir = new File(d, "fd");
                String[] fds;
                try { fds = fdDir.list(); } catch (Throwable t) { fds = null; }
                if (fds == null) continue;   // 别的 uid 的进程：无权读，跳过
                for (String fd : fds) {
                    String target;
                    try { target = new File(fdDir, fd).getCanonicalPath(); } catch (Throwable t) { continue; }
                    int i = target.indexOf("/dshhome/sessions/");
                    if (i < 0) continue;
                    if (!target.endsWith("/session.lock")) continue;
                    // 会话目录 = session.lock 所在目录（…/sessions/<cwd编码>/<session-id>）
                    out.add(target.substring(0, target.lastIndexOf('/')));
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 更新悬浮窗状态文字 + 常驻通知（在主线程调用）。 */
    private void updateEngineStatusUi() {
        if (statusText != null) {
            statusText.setText("状态：" + (engineUp ? "引擎运行中 ✓" : "引擎未运行"));
        }
        if (aiText != null) {
            aiText.setText(aiStatusText());
        }
        applyStateColor();
        if (stepsText != null) {
            String s = stepsPanelText();
            stepsText.setText(s);
            stepsText.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
        }
        // 面板开着的话顺带刷新销毁屏按钮的可见性
        if (panelVisible) refreshPanelDynamicRows();
        // 更新常驻通知
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    /**
     * AI 状态行：空闲 / 工作中会话（可多个）/ 已完成。
     *  - 有持锁会话 → "工作中 N 个会话"；
     *  - 上次还工作中、现在没了 → 记一个"刚完成"时间点，60 秒内显示"已完成"；
     *  - 其余 → "空闲"。引擎不在跑时显示"—"。
     */
    private String aiStatusText() {
        if (!engineUp) return "AI：—";
        HashSet<String> cur = activeSessions;
        if (cur == null) return "AI：—";       // 还没扫过 / /proc 扫不了
        int n = cur.size();
        long now = System.currentTimeMillis();
        if (n > 0) {
            lastSessionsHadWork = true;   // 边沿触发源：从"有会话工作"变"没有"时报已完成
            finishedAt = 0L;
            return n == 1 ? "AI：1 个会话工作中…" : "AI：" + n + " 个会话工作中…";
        }
        if (finishedAt == 0L && lastSessionsHadWork) {
            finishedAt = now;
        }
        if (finishedAt > 0L && now - finishedAt < FINISHED_TTL_MS) {
            return "AI：会话已完成 ✓";
        }
        lastSessionsHadWork = false;
        return "AI：空闲";
    }
    // ══════════════════ v1.17 状态颜色 + 步骤 + 上报接口 ══════════════════

    /** 当前有效状态（超时未更新则返回空）。 */
    private String effectiveState() {
        String st = taskState;
        if (st == null || st.length() == 0) return "";
        if (stateAt > 0L && System.currentTimeMillis() - stateAt > STATE_TTL_MS) return "";
        return st;
    }

    /** 状态 → 颜色。空串 = 不染色（保持原图标）。 */
    private int stateColor() {
        String st = effectiveState();
        if ("working".equals(st)) return getColor(R.color.accent_brand);   // 蓝：进行中
        if ("done".equals(st))    return 0xFF34C759;                       // 绿：完成
        if ("need".equals(st))    return 0xFFFF3B30;                       // 红：需要你
        if ("idle".equals(st))    return 0xFF8E8E93;                       // 灰：空闲
        return 0;
    }

    /** 把状态颜色刷到小鲸鱼上。working 时做缓慢呼吸，need 时快闪。 */
    private void applyStateColor() {
        if (iconView == null) return;
        final int c = stateColor();
        if (c == 0) {
            stopStatePulse();
            try { iconView.clearColorFilter(); } catch (Throwable ignored) {}
            return;
        }
        String st = effectiveState();
        boolean pulse = "working".equals(st) || "need".equals(st);
        if (pulse) {
            startStatePulse(c, "need".equals(st) ? 420L : 1400L);
        } else {
            stopStatePulse();
            try {
                iconView.setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN);
            } catch (Throwable ignored) {}
        }
    }

    /** 呼吸/闪烁：在颜色上叠加 alpha 动画。periodMs 越小越急。 */
    private void startStatePulse(final int color, final long periodMs) {
        if (stateAnim != null && stateAnim.isRunning()) return;
        stopStatePulse();
        try {
            stateAnim = android.animation.ValueAnimator.ofFloat(1f, 0.35f);
            stateAnim.setDuration(periodMs);
            stateAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            stateAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            stateAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (iconView == null) return;
                    float f = (Float) a.getAnimatedValue();
                    int alpha = Math.max(1, Math.min(255, Math.round(255 * f)));
                    int cc = (alpha << 24) | (color & 0x00FFFFFF);
                    try { iconView.setColorFilter(cc, android.graphics.PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) {}
                }
            });
            stateAnim.start();
        } catch (Throwable ignored) {}
    }

    private void stopStatePulse() {
        try {
            if (stateAnim != null) { stateAnim.cancel(); stateAnim = null; }
        } catch (Throwable ignored) {}
    }

    /** 面板里的步骤列表文本。 */
    private String stepsPanelText() {
        String st = effectiveState();
        if (st.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        String head;
        if ("working".equals(st)) head = "进行中";
        else if ("done".equals(st)) head = "已完成";
        else if ("need".equals(st)) head = "需要你确认";
        else head = "空闲";
        if (stepTotal > 0) head = head + "  " + Math.min(stepIndex, stepTotal) + "/" + stepTotal;
        sb.append(head);
        if (stepText != null && stepText.length() > 0) {
            sb.append("\n").append("· ").append(stepText);
            if (stepEta != null && stepEta.length() > 0) sb.append("（").append(stepEta).append("）");
        }
        if (stepExtra != null && stepExtra.length() > 0) {
            sb.append("\n").append(stepExtra);
        }
        return sb.toString();
    }

    // ── 极简 HTTP 服务：接 AI 的状态上报与归位请求 ──────────────────

    private void startStatusServer() {
        if (statusServerRunning) return;
        statusServerRunning = true;
        final int port = enginePort + 10;
        new Thread(new Runnable() { @Override public void run() {
            try {
                statusServer = new java.net.ServerSocket();
                statusServer.setReuseAddress(true);
                statusServer.bind(new java.net.InetSocketAddress("127.0.0.1", port));
                Log.i(TAG, "overlay status server on 127.0.0.1:" + port);
                while (statusServerRunning && statusServer != null && !statusServer.isClosed()) {
                    java.net.Socket s = statusServer.accept();
                    try { handleStatusHit(s); } catch (Throwable ignored) {}
                    try { s.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                Log.w(TAG, "overlay status server stopped: " + t.getMessage());
            }
            statusServerRunning = false;
        }}, "overlay-status").start();
    }

    private void stopStatusServer() {
        statusServerRunning = false;
        try { if (statusServer != null) statusServer.close(); } catch (Throwable ignored) {}
        statusServer = null;
    }

    /** 处理一条上报请求。支持 POST 与 GET（GET 方便用浏览器/curl 调试）。 */
    private void handleStatusHit(java.net.Socket sock) throws Exception {
        sock.setSoTimeout(4000);
        java.io.InputStream in = sock.getInputStream();
        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) >= 0) {
            head.write(c);
            if (prev == '\n' && c == '\n') break;                 // 空行 = 头结束
            if (prev == '\r' && c == '\n') { /* 继续等第二个换行 */ }
            prev = c;
        }
        String headStr = head.toString("UTF-8");
        String firstLine = headStr.split("\r?\n")[0];
        String[] parts = firstLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : "/";

        int contentLength = 0;
        for (String ln : headStr.split("\r?\n")) {
            int i = ln.indexOf(':');
            if (i > 0 && ln.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(ln.substring(i + 1).trim()); } catch (Throwable ignored) {}
            }
        }
        byte[] body = new byte[Math.max(0, Math.min(contentLength, 65536))];
        int read = 0;
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n <= 0) break;
            read += n;
        }
        String bodyStr = new String(body, 0, read, "UTF-8");

        String json;
        boolean dock = false;
        if (path.startsWith("/overlay-dock")) {
            dock = true;
            json = "{\"ok\":true,\"docked\":true}";
        } else if (path.startsWith("/overlay-status")) {
            applyStatusPayload(bodyStr, path);
            json = "{\"ok\":true,\"state\":\"" + effectiveState() + "\",\"step\":" + stepIndex + ",\"total\":" + stepTotal + "}";
        } else if (path.startsWith("/overlay-ping")) {
            json = "{\"ok\":true,\"state\":\"" + effectiveState() + "\"}";
        } else {
            json = "{\"ok\":false,\"err\":\"unknown path\"}";
        }

        byte[] out = json.getBytes("UTF-8");
        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + out.length + "\r\nConnection: close\r\n\r\n";
        sock.getOutputStream().write(resp.getBytes("UTF-8"));
        sock.getOutputStream().write(out);
        sock.getOutputStream().flush();

        if (dock) handler.post(new Runnable() { @Override public void run() { dockToEdge(); } });
        else handler.post(new Runnable() { @Override public void run() { updateEngineStatusUi(); } });
    }

    /** 解析上报负载。既支持 JSON（{...}），也支持 GET 的 query（?state=working&step=2）。 */
    private void applyStatusPayload(String body, String path) {
        String src = body;
        int q = path.indexOf('?');
        if (q >= 0 && (src == null || src.trim().length() == 0)) src = path.substring(q + 1);
        if (src == null) src = "";
        src = src.trim();
        if (src.startsWith("{")) {
            taskState = strField(src, "state", taskState);
            stepIndex = intField(src, "index", stepIndex);
            stepTotal = intField(src, "total", stepTotal);
            stepText  = strField(src, "step", stepText);
            stepEta   = strField(src, "eta", stepEta);
            String ex  = strField(src, "extra", null);
            if (ex != null) stepExtra = ex;
        } else {
            for (String kv : src.split("&")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                String k = kv.substring(0, i).trim();
                String v;
                try { v = java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"); } catch (Throwable t) { v = kv.substring(i + 1); }
                if ("state".equals(k)) taskState = v;
                else if ("index".equals(k)) { try { stepIndex = Integer.parseInt(v.trim()); } catch (Throwable ignored) {} }
                else if ("total".equals(k)) { try { stepTotal = Integer.parseInt(v.trim()); } catch (Throwable ignored) {} }
                else if ("step".equals(k)) stepText = v;
                else if ("eta".equals(k)) stepEta = v;
                else if ("extra".equals(k)) stepExtra = v;
            }
        }
        stateAt = System.currentTimeMillis();
    }

    /** 从 JSON 文本里取字符串字段（够用的土办法，不引 JSON 库）。 */
    private String strField(String json, String key, String def) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
            if (m.find()) {
                String v = m.group(1);
                return v.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private int intField(String json, String key, int def) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {}
        return def;
    }

    /** 上次扫描是否看到过工作中的会话（用于"已完成"的边沿触发）。 */
    private volatile boolean lastSessionsHadWork = false;

    // ==================== v1.9 虚拟屏预览（悬浮窗实时看 AI 操作虚拟屏） ====================

    /** 开始虚拟屏预览：每 ~1s 拉 server /preview（base64 JPEG）并显示到悬浮窗。 */
    public void startVscreenPreview() {
        if (vscreenPreviewRunning) return;
        vscreenPreviewRunning = true;
        vscreenHandler.post(vscreenPreviewRunnable);
    }

    /** 停止虚拟屏预览。 */
    public void stopVscreenPreview() {
        vscreenPreviewRunning = false;
        vscreenHandler.removeCallbacks(vscreenPreviewRunnable);
        if (vscreenImageView != null) {
            vscreenHandler.post(new Runnable() {
                @Override public void run() {
                    vscreenImageView.setVisibility(View.GONE);
                }
            });
        }
    }

    /** 预览帧拉取任务：HTTP GET 127.0.0.1:8999/vscreen/preview → base64 JPEG → ImageView。 */
    private final Runnable vscreenPreviewRunnable = new Runnable() {
        @Override public void run() {
            if (!vscreenPreviewRunning || !isRunning) return;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8999/vscreen/preview").openConnection();
                c.setConnectTimeout(2000); c.setReadTimeout(2000);
                // v1.18.0：8999 桥已加调用方鉴权，App 自己拉预览也要带令牌（否则预览窗永远空着）
                c.setRequestProperty(LocalAuth.HEADER, LocalAuth.token(OverlayService.this));
                String resp = readAll(c.getInputStream());
                c.disconnect();
                JSONObject o = new JSONObject(resp);
                if (o.optBoolean("ok", false)) {
                    String b64 = o.optString("previewB64");
                    if (b64 != null && !b64.isEmpty()) {
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bmp != null) {
                            vscreenHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (vscreenImageView != null) {
                                        vscreenImageView.setImageBitmap(bmp);
                                        vscreenImageView.setVisibility(View.VISIBLE);
                                    }
                                }
                            });
                        }
                    }
                }
            } catch (Throwable ignored) {
                // server 未跑（虚拟屏未创建）时静默，预览保持隐藏
            }
            vscreenHandler.postDelayed(this, 1000);
        }
    };

    private String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toString("UTF-8");
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
