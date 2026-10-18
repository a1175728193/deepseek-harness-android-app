package com.deepseek.harness;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户补丁层（v1.19.8）——解决「用户/AI 自己改的内核文件，重启引擎或重装后就没了」。
 *
 * <p>为什么需要它：App 每次准备运行环境时会做树同步，其中
 * <ul>
 *   <li>{@code FORCE_OVERWRITE_PREFIXES} 名单里的文件（正好是 AI 最常改的那些 UI/内核补丁）<b>无条件覆盖</b>；</li>
 *   <li>7 个 {@code dsh-tool-*} 插件每次 App 进程启动都被 payload 刷回出厂；</li>
 *   <li>重装/重新解压走全量同步，<b>整棵树重写</b>。</li>
 * </ul>
 * 这些都是必要的（防"旧文件挡住官方修复"），所以不能靠"别覆盖"来解决；改为<b>覆盖之后再盖回用户的改动</b>。
 *
 * <p>做法：
 * <ol>
 *   <li><b>保存</b>：用随包的 {@code assets/kernel-manifest.tsv}（相对路径/size/sha256）与现役内核树逐文件比对，
 *       把"与出厂不一致"的文件快照到 {@code /sdcard/<包名目录>/user-patch/} 下（保持相对路径）；</li>
 *   <li><b>回放</b>：每次启动引擎、<b>在所有同步动作之后</b>，把 user-patch 里的文件盖回内核树；</li>
 *   <li><b>撤销</b>：清空 user-patch 即回到出厂（下次启动自然恢复）。</li>
 * </ol>
 *
 * <p>安全边界（红线）：
 * <ul>
 *   <li>只允许落在内核树 {@code dshroot/} 内；拒绝绝对路径与 {@code ..}；</li>
 *   <li>只回放"看起来是源码/配置/图片"的扩展名（白名单），<b>拒绝 .so/.dex/.apk/.sh 等可执行物</b>
 *       —— 与"payload 不带原生模块"的红线一致；</li>
 *   <li>单个文件上限 {@link #MAX_FILE_BYTES}；回放失败只记日志，绝不让引擎起不来；</li>
 *   <li>只碰内核树，永远不碰用户数据（{@code dshhome/**}、会话、插件配置）。</li>
 * </ul>
 */
public final class UserPatches {

    /** 外部目录名（挂在 /sdcard/<包名目录>/ 下，卸载/重装不丢）。 */
    public static final String DIR_NAME = "user-patch";
    /** 补丁清单文件名（写在 DIR_NAME 里，回放时跳过）。 */
    public static final String MANIFEST_NAME = "manifest.json";
    /**
     * 出厂基线文件名（写在 DIR_NAME 里）。**这是"什么算用户补丁"的唯一判据**：
     * 直接把内核树与随包 manifest 比是不行的 —— App 自己也会在运行时改文件
     * （ensurePatchConfig 恢复配置、applyLinks、secureDexFiles 等），真机实测会有
     * 几十个"非用户"差异被误判成补丁；这些一旦被回放，将来会回退官方更新。
     * 所以改为：**首次安装后**把当时的整棵树记成基线，之后只有相对基线的差异才算用户补丁；
     * App 版本变化（升级）时基线自动重建。
     */
    public static final String BASELINE_NAME = ".baseline.tsv";
    /** 单个补丁文件上限：超过就不快照（内核里没有这么大的源码文件，多半不是补丁）。 */
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    /** 允许回放的扩展名（源码 / 配置 / 文本 / 图片）。刻意不含 .so/.dex/.apk/.jar/.sh/.bin。 */
    private static final String[] ALLOWED_EXT = {
            ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".map",
            ".json", ".json5", ".yaml", ".yml", ".toml", ".ini", ".conf",
            ".css", ".scss", ".html", ".htm", ".txt", ".md", ".mjs",
            ".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif"
    };

    private UserPatches() {}

    // ==================== 路径 ====================

    /** App 外部根目录名（与 MainActivity.pkgRoot / OverlayService 的规则保持一致）。 */
    public static String pkgRoot(String packageName) {
        if (packageName == null) return "DeepSeekHarness";
        if (packageName.endsWith(".beta")) return "DeepSeekHarnessLite";
        if (packageName.endsWith(".compat")) return "DeepSeekHarnessCompat";
        return "DeepSeekHarness";
    }

    /** {@code /sdcard/<包名目录>/user-patch}。 */
    public static File dir(Context ctx) {
        return new File(new File(Environment.getExternalStorageDirectory(), pkgRoot(ctx.getPackageName())), DIR_NAME);
    }

    // ==================== 查询 ====================

    /** 当前 user-patch 里有多少个待回放的补丁文件（不含清单/基线文件）。 */
    public static int count(Context ctx) {
        try {
            List<File> all = new ArrayList<File>();
            collect(dir(ctx), all);
            int n = 0;
            for (File f : all) if (isPatchCandidate(f)) n++;
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ==================== 出厂基线 ====================

    /**
     * 确保"出厂基线"存在且与当前 App 版本匹配；不匹配（升级）就重建。
     * 基线 = 当前内核树的 路径/size/sha256 全量快照，是「什么算用户补丁」的唯一判据。
     */
    public static void ensureBaseline(Context ctx, File kernelRoot) {
        if (kernelRoot == null || !kernelRoot.isDirectory()) return;
        File root = dir(ctx);
        if (!root.isDirectory() && !root.mkdirs()) return;
        File base = new File(root, BASELINE_NAME);
        String ver = baselineTag(ctx, kernelRoot);
        if (base.isFile() && base.length() > 0) {
            try {
                BufferedReader br = new BufferedReader(new java.io.InputStreamReader(new FileInputStream(base), "UTF-8"));
                String first = br.readLine();
                br.close();
                if (first != null && first.startsWith("# app=") && first.substring("# app=".length()).trim().equals(ver)) {
                    return;   // 同一版本：沿用
                }
            } catch (Throwable ignored) {
            }
        }
        // 重建基线（覆盖）
        File payloadRoot = kernelRoot.getParentFile();
        java.io.OutputStream out = null;
        try {
            out = new FileOutputStream(base);
            StringBuilder head = new StringBuilder();
            head.append("# app=").append(ver).append('\n');
            out.write(head.toString().getBytes("UTF-8"));
            List<File> files = new ArrayList<File>();
            collect(kernelRoot, files);
            int n = 0;
            StringBuilder buf = new StringBuilder();
            for (File f : files) {
                String rel = relative(payloadRoot, f);
                String h = sha256(f);
                if (h == null) continue;
                buf.append(rel).append('\t').append(f.length()).append('\t').append(h).append('\n');
                n++;
                if (buf.length() > 256 * 1024) {
                    out.write(buf.toString().getBytes("UTF-8"));
                    buf.setLength(0);
                }
            }
            if (buf.length() > 0) out.write(buf.toString().getBytes("UTF-8"));
            android.util.Log.i("DeepSeekHarness", "user-patch baseline built: files=" + n + " app=" + ver);
        } catch (Throwable t) {
            android.util.Log.w("DeepSeekHarness", "user-patch baseline build failed", t);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private static void readBaseline(Context ctx, Map<String, long[]> sizes, Map<String, String> hashes) throws IOException {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new java.io.InputStreamReader(new FileInputStream(new File(dir(ctx), BASELINE_NAME)), "UTF-8"), 1 << 16);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                int t1 = line.indexOf('\t');
                if (t1 <= 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 <= t1) continue;
                String rel = line.substring(0, t1);
                long size;
                try { size = Long.parseLong(line.substring(t1 + 1, t2).trim()); } catch (Throwable t) { continue; }
                sizes.put(rel, new long[]{size});
                hashes.put(rel, line.substring(t2 + 1).trim());
            }
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 回放（引擎启动时调用） ====================

    /**
     * 把 user-patch 里的文件盖回内核树。<b>必须在所有同步动作之后调用</b>。
     * 幂等、永不抛异常（失败只返回 0 并记日志由调用方决定）。
     *
     * @param payloadRoot 内部 payload 目录（其中 dshroot/ 是内核树）
     * @return 实际回放成功的文件数
     */
    public static int apply(Context ctx, File payloadRoot) {
        if (payloadRoot == null) return 0;
        File root = dir(ctx);
        if (!root.isDirectory()) return 0;
        List<File> files = new ArrayList<File>();
        collect(root, files);
        int ok = 0, skipped = 0;
        for (File src : files) {
            if (!isPatchCandidate(src)) continue;
            String rel = relative(root, src);
            if (!isSafeRelPath(rel)) { skipped++; continue; }
            if (!isAllowedExt(rel)) { skipped++; continue; }
            File dst = new File(payloadRoot, rel);
            try {
                copyTo(src, dst);
                ok++;
            } catch (Throwable t) {
                skipped++;
            }
        }
        if (ok > 0 || skipped > 0) {
            android.util.Log.i("DeepSeekHarness", "user-patch: applied=" + ok + " skipped=" + skipped + " root=" + root.getAbsolutePath());
        }
        return ok;
    }

    // ==================== 快照（「保存我的修改」） ====================

    /** 一次快照的结果（给界面显示）。 */
    public static final class Snapshot {
        public int saved = 0;         // 保存下来的文件数
        public int scanned = 0;       // 比对的清单条目数
        public int skippedLarge = 0;  // 超过大小上限而跳过的
        public String error = null;   // 非空=整体失败
    }

    /**
     * 用<b>出厂基线</b>找出"与基线不一致"的内核文件，快照到 user-patch（**替换**旧快照）。
     *
     * @param kernelRoot 内核树根（…/payload/dshroot）
     */
    public static Snapshot snapshot(Context ctx, File kernelRoot) {
        Snapshot s = new Snapshot();
        if (kernelRoot == null || !kernelRoot.isDirectory()) {
            s.error = "找不到内核树：" + kernelRoot;
            return s;
        }
        File root = dir(ctx);
        try {
            if (!root.isDirectory() && !root.mkdirs()) {
                s.error = "建不了目录：" + root.getAbsolutePath();
                return s;
            }
        } catch (Throwable t) {
            s.error = "建不了目录：" + t.getMessage();
            return s;
        }
        // ⚠ **绝对不要在这里再 ensureBaseline()**（第 14 轮真机撞出来的坑）：
        // 基线缺失时"照着当前树重建"= 把用户刚改的东西写成出厂状态 ⇒ 保存恒为 0 个，
        // 用户却看到"已保存"，改动等于白改。缺基线只有一条正路：
        // 重启一次引擎（App 会在官方同步之后、回放补丁之前重建基线），再回来保存。
        File base = new File(root, BASELINE_NAME);
        if (!base.isFile() || base.length() == 0) {
            s.error = "出厂基线不存在：先重启一次引擎（App 会重建基线），再回来保存";
            return s;
        }
        String wantTag = "# app=" + baselineTag(ctx, kernelRoot);
        String gotTag = firstLine(base);
        if (gotTag == null || !gotTag.trim().equals(wantTag)) {
            s.error = "基线属于旧版本（" + (gotTag == null ? "?" : gotTag.trim())
                    + "）：先重启一次引擎重建基线，再回来保存";
            return s;
        }
        Map<String, long[]> sizes = new HashMap<String, long[]>();
        Map<String, String> hashes = new HashMap<String, String>();
        try {
            readBaseline(ctx, sizes, hashes);
        } catch (Throwable t) {
            s.error = "基线读取失败：" + t.getMessage();
            return s;
        }
        if (sizes.isEmpty()) {
            s.error = "基线是空的（先重启一次引擎，让 App 记下出厂状态）";
            return s;
        }

        List<File> found = new ArrayList<File>();
        try {
            for (Map.Entry<String, long[]> e : sizes.entrySet()) {
                String rel = e.getKey();                      // 相对 payload 根，形如 dshroot/...
                if (!isAllowedExt(rel)) continue;
                File f = new File(kernelRoot.getParentFile(), rel);
                if (!f.isFile()) continue;
                s.scanned++;
                if (f.length() != e.getValue()[0]) {
                    if (f.length() > MAX_FILE_BYTES) { s.skippedLarge++; continue; }
                    found.add(f);
                    continue;
                }
                String want = hashes.get(rel);
                if (want == null || want.length() == 0) continue;
                String got = sha256(f);
                if (got != null && !got.equalsIgnoreCase(want)) {
                    if (f.length() > MAX_FILE_BYTES) { s.skippedLarge++; continue; }
                    found.add(f);
                }
            }
        } catch (Throwable t) {
            s.error = "扫描失败：" + t.getMessage();
            return s;
        }

        // 替换旧快照（只清 user-patch 目录内部，不碰外部任何东西）
        // ⚠ 必须跳过基线：它跟"补丁"是两回事，删掉就只剩"重建"这条会吸收改动的歪路。
        try { clearFiles(root, BASELINE_NAME); } catch (Throwable ignored) {}

        StringBuilder man = new StringBuilder();
        man.append("{\n  \"schema\": 1,\n  \"savedAt\": \"")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                .append("\",\n  \"app\": \"").append(jesc(versionOf(ctx))).append("\",\n  \"files\": [\n");
        int n = 0;
        for (File f : found) {
            String rel = relative(kernelRoot.getParentFile(), f);
            File dst = new File(root, rel);
            try {
                copyTo(f, dst);
                if (n > 0) man.append(",\n");
                man.append("    {\"path\": \"").append(jesc(rel))
                   .append("\", \"size\": ").append(f.length())
                   .append(", \"sha256\": \"").append(String.valueOf(sha256(f))).append("\"}");
                n++;
            } catch (Throwable t) {
                // 单个文件失败不影响整体
            }
        }
        man.append("\n  ]\n}\n");
        try {
            writeText(new File(root, MANIFEST_NAME), man.toString());
        } catch (Throwable ignored) {}
        s.saved = n;
        android.util.Log.i("DeepSeekHarness", "user-patch snapshot: saved=" + n + " scanned=" + s.scanned + " large=" + s.skippedLarge);
        return s;
    }

    /** 清空 user-patch（回到出厂），**保留出厂基线**（它不是补丁，删了只能重建）。
     *
     * <p>返回被清掉的补丁文件**相对路径**（形如 {@code dshroot/...}）——调用方要拿它
     * 去随包 payload 里把出厂内容取回来，否则树里那份被回放覆盖过的文件会永久留下
     * （重启时 `dshrootNeedsSync()` 为假，不会重新解压）。第 14 轮真机就是这么撞出来的：
     * 「清空补丁」+ 重启后 `/mobile.js` 里标记还在。
     */
    public static List<String> clear(Context ctx) {
        List<String> rels = new ArrayList<String>();
        File root = dir(ctx);
        try {
            List<File> all = new ArrayList<File>();
            collect(root, all);
            for (File f : all) {
                if (!isPatchCandidate(f)) continue;
                String rel = relative(root, f);
                if (isSafeRelPath(rel) && isAllowedExt(rel)) rels.add(rel);
            }
        } catch (Throwable ignored) {
        }
        try { clearFiles(root, BASELINE_NAME); } catch (Throwable ignored) {}
        return rels;
    }

    // ==================== 内部工具 ====================

    private static void clearFiles(File dir, String keepName) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (keepName != null && k.isFile() && keepName.equals(k.getName())) continue;
            if (k.isDirectory()) clearFiles(k, keepName); else k.delete();
        }
    }

    /** 基线版本标签 = App versionName + 内核树 REVISION（构建时间戳）。
     *
     * <p>⚠ 只认 versionName 不够：本项目所有官方构建的 versionName 恒为 {@code 1.19.0}，
     * 换包重装后内核文件已经变了、基线却还认旧值 ⇒ 官方改过的文件被当成"用户补丁"
     * （就是当初要消灭的"几十个文件"）。REVISION 每次都变，正好当指纹。
     */
    private static String baselineTag(Context ctx, File kernelRoot) {
        String v = versionOf(ctx);
        try {
            File rev = new File(kernelRoot, "REVISION");
            if (rev.isFile()) {
                String line = firstLine(rev);
                if (line != null && line.trim().length() > 0) v = v + "+" + line.trim();
            }
        } catch (Throwable ignored) {
        }
        return v;
    }

    /** 文件首行（读不到返回 null）。 */
    private static String firstLine(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            return br.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else out.add(k);
        }
        Collections.sort(out);
    }

    private static boolean isPatchCandidate(File f) {
        if (f == null || !f.isFile()) return false;
        String n = f.getName();
        if (MANIFEST_NAME.equals(n)) return false;
        if (BASELINE_NAME.equals(n)) return false;
        if (n.startsWith(".")) return false;
        return true;
    }

    private static String relative(File root, File f) {
        String r = root.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (a.startsWith(r)) {
            String rel = a.substring(r.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
            return rel.replace('\\', '/');
        }
        return f.getName();
    }

    /** 拒绝绝对路径 / .. / 空段；必须落在 dshroot/ 下。 */
    private static boolean isSafeRelPath(String rel) {
        if (rel == null || rel.length() == 0) return false;
        if (rel.startsWith("/")) return false;
        if (rel.indexOf('\0') >= 0) return false;
        String[] seg = rel.split("/");
        for (String p : seg) {
            if (p.length() == 0 || p.equals(".") || p.equals("..")) return false;
        }
        return rel.startsWith("dshroot/");
    }

    private static boolean isAllowedExt(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.US);
        for (String e : ALLOWED_EXT) if (name.endsWith(e)) return true;
        return false;
    }

    private static void copyTo(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir: " + parent);
        if (dst.exists() && !dst.canWrite()) {
            try { dst.setWritable(true, true); } catch (Throwable ignored) {}
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir: " + parent);
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }


    private static String sha256(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static String versionOf(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String jesc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
