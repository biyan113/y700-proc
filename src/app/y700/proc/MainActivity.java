package app.y700.proc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 平板上的进程查看。打开时用 Magisk su 跑采样脚本，离开页面就停。
 * 百分比按一颗核计算，100% 表示占满一核。内存是驻留，几个进程加在一起。
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFFF3EEE6;
    private static final int INK = 0xFF1C1915;
    private static final int MUTED = 0xFF6E675E;
    private static final int CARD = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF1F4E5F;
    private static final int BAR = 0xFFC4552A;
    private static final int TRACK = 0xFFE6E0D6;
    private static final String[] DENY = {
            "com.zui.launcher",
            "com.android.systemui",
            "android",
            "com.android.settings",
            "com.android.phone",
            "app.y700.proc"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Face> faces = new HashMap<Integer, Face>();
    private TextView availView;
    private TextView detailView;
    private TextView statusView;
    private TextView memTab;
    private TextView cpuTab;
    private LinearLayout rows;
    private ScrollView scroll;
    private boolean byCpu;
    private boolean cleaning;
    private int generation;
    private Process loop;
    private List<Item> items = new ArrayList<Item>();
    private int memAvailKb;
    private int memTotalKb;
    private int swapUsedKb;
    private double cpuTotal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLoop();
    }

    @Override
    protected void onPause() {
        stopLoop();
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(18), dp(20), dp(12));

        LinearLayout head = row();
        TextView title = text("进程", 28, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        memTab = tab("按内存", true);
        cpuTab = tab("按 CPU", false);
        memTab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(false); }
        });
        cpuTab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(true); }
        });
        head.addView(memTab);
        head.addView(space(8));
        head.addView(cpuTab);
        root.addView(head);

        LinearLayout summary = card();
        availView = text("正在读取", 26, INK);
        availView.setTypeface(Typeface.DEFAULT_BOLD);
        detailView = text("打开后每两秒看一次。离开这个页面就停止。", 14, MUTED);
        summary.addView(availView);
        summary.addView(spaceV(4));
        summary.addView(detailView);
        root.addView(spaceV(12));
        root.addView(summary);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout foot = row();
        statusView = text("", 13, MUTED);
        foot.addView(statusView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView logBtn = text("日志", 16, ACCENT);
        logBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLog(); }
        });
        foot.addView(logBtn);
        root.addView(foot);
        TextView path = text("日志在 /data/adb/y700-proc/proc.log", 12, MUTED);
        root.addView(path);
        setContentView(root);
    }

    private void setSort(boolean cpu) {
        byCpu = cpu;
        paintTab(memTab, !cpu);
        paintTab(cpuTab, cpu);
        rebuildRows();
    }

    private void startLoop() {
        stopLoop();
        final int gen = ++generation;
        statusView.setText("正在向 Magisk 要 root");
        new Thread(new Runnable() {
            @Override public void run() {
                String install = ""
                        + "mkdir -p /data/adb/y700-proc\n"
                        + "cat > /data/adb/y700-proc/sample.sh << 'ENDSAMPLE'\n"
                        + Scripts.SAMPLE
                        + "ENDSAMPLE\n"
                        + "cat > /data/adb/y700-proc/clean.sh << 'ENDCLEAN'\n"
                        + Scripts.CLEAN
                        + "ENDCLEAN\n"
                        + "chmod 755 /data/adb/y700-proc/sample.sh /data/adb/y700-proc/clean.sh\n"
                        + "echo INSTALL_OK\n";
                String installed = su(install, 45);
                if (gen != generation) return;
                if (installed == null || !installed.contains("INSTALL_OK")) {
                    postStatus(gen, "拿不到 root。请在 Magisk 里允许「进程」。");
                    return;
                }
                Process proc = startSu("sh /data/adb/y700-proc/sample.sh");
                if (proc == null) {
                    postStatus(gen, "采样没启动。");
                    return;
                }
                synchronized (MainActivity.this) {
                    if (gen != generation) {
                        proc.destroyForcibly();
                        return;
                    }
                    loop = proc;
                }
                try {
                    byte[] buf = new byte[65536];
                    StringBuilder line = new StringBuilder();
                    InputStream in = proc.getInputStream();
                    int n;
                    while (gen == generation && (n = in.read(buf)) >= 0) {
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xff);
                            if (c == '\n') {
                                final String one = line.toString();
                                line.setLength(0);
                                if (one.startsWith("{")) {
                                    main.post(new Runnable() {
                                        @Override public void run() {
                                            if (gen == generation) applyJson(one);
                                        }
                                    });
                                }
                            } else {
                                line.append(c);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 离开页面时流会被关掉。
                }
            }
        }, "y700-proc").start();
    }

    private void stopLoop() {
        generation++;
        Process proc;
        synchronized (this) {
            proc = loop;
            loop = null;
        }
        if (proc != null) proc.destroyForcibly();
    }

    private void applyJson(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (!o.optBoolean("ok")) {
                statusView.setText("采样失败");
                return;
            }
            memTotalKb = o.optInt("mem_total");
            memAvailKb = o.optInt("mem_avail");
            swapUsedKb = o.optInt("swap_used");
            cpuTotal = o.optDouble("cpu");
            JSONArray arr = o.optJSONArray("items");
            ArrayList<Item> next = new ArrayList<Item>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.getJSONObject(i);
                    Item item = new Item();
                    item.uid = it.optInt("uid");
                    item.pid = it.optInt("pid");
                    item.name = it.optString("name");
                    item.rssKb = it.optInt("rss");
                    item.cpu = it.optDouble("cpu");
                    item.n = it.optInt("n", 1);
                    Face face = faceFor(item);
                    item.label = face.label;
                    item.pkg = face.pkg;
                    item.icon = face.icon;
                    item.cleanable = face.cleanable;
                    next.add(item);
                }
            }
            items = next;
            availView.setText("可用 " + gb(memAvailKb));
            detailView.setText("一共 " + gb(memTotalKb)
                    + "  ·  整机 CPU " + String.format(Locale.US, "%.0f%%", cpuTotal)
                    + "  ·  交换 " + gb(swapUsedKb));
            statusView.setText("右边的百分比按一颗核算，100% 是占满一核");
            rebuildRows();
        } catch (Exception e) {
            statusView.setText("读不懂采样结果");
        }
    }

    private void rebuildRows() {
        int y = scroll.getScrollY();
        rows.removeAllViews();
        ArrayList<Item> show = new ArrayList<Item>(items);
        Collections.sort(show, byCpu ? Item.BY_CPU : Item.BY_MEM);
        if (show.size() > 12) show = new ArrayList<Item>(show.subList(0, 12));
        int max = 1;
        for (Item item : show) {
            int v = byCpu ? (int) Math.ceil(item.cpu) : item.rssKb;
            if (v > max) max = v;
        }
        if (show.isEmpty()) {
            rows.addView(text("还没有占得上榜的进程。", 16, MUTED));
        }
        for (int i = 0; i < show.size(); i++) {
            rows.addView(spaceV(10));
            rows.addView(render(show.get(i), max));
        }
        final int keep = y;
        scroll.post(new Runnable() {
            @Override public void run() { scroll.scrollTo(0, keep); }
        });
    }

    private View render(final Item item, int max) {
        LinearLayout card = card();
        LinearLayout top = row();
        if (item.icon != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(item.icon);
            top.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        } else {
            TextView badge = text(badgeChar(item.label), 18, 0xFFFFFFFF);
            badge.setGravity(Gravity.CENTER);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ACCENT);
            badge.setBackground(circle);
            top.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        top.addView(space(12));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(item.label, 18, INK);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        names.addView(label);
        String sub = "";
        if (item.n > 1) sub = item.n + " 个进程";
        else if (item.pkg == null) sub = item.name + "  ·  系统";
        if (sub.length() > 0) names.addView(text(sub, 13, MUTED));
        top.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.VERTICAL);
        nums.setGravity(Gravity.END);
        TextView big = text(byCpu
                ? String.format(Locale.US, "%.0f%%", item.cpu)
                : mb(item.rssKb), 18, INK);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.END);
        TextView small = text(byCpu ? mb(item.rssKb) : String.format(Locale.US, "%.0f%%", item.cpu), 13, MUTED);
        small.setGravity(Gravity.END);
        nums.addView(big);
        nums.addView(small);
        top.addView(nums);
        card.addView(top);

        int value = byCpu ? (int) Math.ceil(item.cpu) : item.rssKb;
        card.addView(spaceV(8));
        card.addView(bar(value, max));

        if (item.cleanable) {
            Button clean = new Button(this);
            clean.setText("清理");
            clean.setAllCaps(false);
            clean.setTextColor(0xFFFFFFFF);
            clean.setTextSize(15);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ACCENT);
            bg.setCornerRadius(dp(12));
            clean.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            lp.topMargin = dp(10);
            lp.gravity = Gravity.END;
            clean.setMinWidth(dp(88));
            clean.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmClean(item); }
            });
            card.addView(clean, lp);
        }
        return card;
    }

    private View bar(int value, int max) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TRACK);
        bg.setCornerRadius(dp(6));
        track.setBackground(bg);
        float w = max <= 0 ? 0 : Math.max(0.04f, value / (float) max);
        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(byCpu ? ACCENT : BAR);
        fg.setCornerRadius(dp(6));
        fill.setBackground(fg);
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(8), w));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(8), Math.max(0.01f, 1f - w)));
        return track;
    }

    private void confirmClean(final Item item) {
        if (cleaning) return;
        new AlertDialog.Builder(this)
                .setMessage("清理「" + item.label + "」？它的后台会被关掉。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        runClean(item);
                    }
                })
                .show();
    }

    private void runClean(final Item item) {
        cleaning = true;
        statusView.setText("正在清理 " + item.label);
        final int before = memAvailKb;
        new Thread(new Runnable() {
            @Override public void run() {
                final String out = su("sh /data/adb/y700-proc/clean.sh " + item.pkg, 20);
                main.post(new Runnable() {
                    @Override public void run() {
                        cleaning = false;
                        boolean ok = out != null && out.contains("\"ok\":true");
                        if (ok) {
                            Toast.makeText(MainActivity.this, "已清理 " + item.label, Toast.LENGTH_SHORT).show();
                            statusView.setText("已清理 " + item.label + "，清之前可用 " + gb(before));
                        } else {
                            statusView.setText("没清掉 " + item.label);
                        }
                    }
                });
            }
        }, "y700-clean").start();
    }

    private void showLog() {
        statusView.setText("正在读日志");
        new Thread(new Runnable() {
            @Override public void run() {
                final String out = su("tail -n 40 /data/adb/y700-proc/proc.log", 15);
                main.post(new Runnable() {
                    @Override public void run() {
                        String body = out == null || out.trim().isEmpty() ? "还没有日志。" : out.trim();
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("日志")
                                .setMessage(body)
                                .setPositiveButton("关闭", null)
                                .show();
                        statusView.setText("日志在 /data/adb/y700-proc/proc.log");
                    }
                });
            }
        }, "y700-log").start();
    }

    private Face faceFor(Item item) {
        if (item.uid < 10000 || item.uid >= 100000) {
            return systemFace(item);
        }
        Face cached = faces.get(Integer.valueOf(item.uid));
        if (cached != null) return cached;
        Face face = new Face();
        face.label = prettySystem(item.name);
        try {
            String[] pkgs = getPackageManager().getPackagesForUid(item.uid);
            if (pkgs != null && pkgs.length > 0) {
                String pkg = pickPackage(pkgs);
                face.pkg = pkg;
                ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
                CharSequence label = getPackageManager().getApplicationLabel(info);
                if (label != null && label.length() > 0) face.label = label.toString();
                face.icon = getPackageManager().getApplicationIcon(info);
                face.cleanable = !denied(pkg);
            }
        } catch (Exception ignored) {
            face.cleanable = false;
        }
        faces.put(Integer.valueOf(item.uid), face);
        return face;
    }

    private Face systemFace(Item item) {
        Face face = new Face();
        face.label = prettySystem(item.name);
        face.cleanable = false;
        return face;
    }

    private static String prettySystem(String name) {
        if (name == null || name.isEmpty()) return "系统";
        if (name.contains("surfaceflinger")) return "画面合成";
        if (name.contains("system_server")) return "系统服务";
        if (name.contains("audioserver")) return "音频";
        if (name.startsWith("kworker") || name.startsWith("binder:")) return "内核";
        return name;
    }

    private String pickPackage(String[] pkgs) {
        PackageManager pm = getPackageManager();
        String fallback = pkgs[0];
        for (String pkg : pkgs) {
            if (pm.getLaunchIntentForPackage(pkg) != null) return pkg;
            if ("com.google.android.gms".equals(pkg)) fallback = pkg;
        }
        return fallback;
    }

    private static boolean denied(String pkg) {
        for (String one : DENY) {
            if (one.equals(pkg)) return true;
        }
        return false;
    }

    private void postStatus(final int gen, final String text) {
        main.post(new Runnable() {
            @Override public void run() {
                if (gen == generation) statusView.setText(text);
            }
        });
    }

    private String su(String command, int timeoutSec) {
        String[] bins = new String[]{"/product/bin/su", "/debug_ramdisk/su", "su"};
        String last = "";
        for (String bin : bins) {
            Process proc = null;
            try {
                proc = new ProcessBuilder(bin, "-c", command)
                        .redirectErrorStream(true)
                        .start();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                Thread reader = new Thread(copy(proc.getInputStream(), buf), "y700-su");
                reader.start();
                boolean done = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
                if (!done) proc.destroyForcibly();
                reader.join(1000);
                last = buf.toString(StandardCharsets.UTF_8.name());
                if (done && (last.contains("INSTALL_OK") || last.contains("{") || last.contains("avail=") || command.startsWith("tail "))) {
                    return last;
                }
            } catch (Exception e) {
                last = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (proc != null && proc.isAlive()) proc.destroyForcibly();
            }
        }
        return last;
    }

    private Process startSu(String command) {
        String[] bins = new String[]{"/product/bin/su", "/debug_ramdisk/su", "su"};
        for (String bin : bins) {
            try {
                return new ProcessBuilder(bin, "-c", command)
                        .redirectErrorStream(true)
                        .start();
            } catch (Exception ignored) {
                // 换下一个 su。
            }
        }
        return null;
    }

    private static Runnable copy(final InputStream in, final ByteArrayOutputStream out) {
        return new Runnable() {
            @Override public void run() {
                byte[] b = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(b)) >= 0) out.write(b, 0, n);
                } catch (Exception ignored) {
                    // 进程结束时读流会断。
                }
            }
        };
    }

    private static String gb(int kb) {
        if (kb >= 1024 * 100) {
            return String.format(Locale.US, "%.1f GB", kb / 1024.0 / 1024.0);
        }
        return Math.max(0, kb / 1024) + " MB";
    }

    private static String mb(int kb) {
        return Math.max(0, kb / 1024) + " MB";
    }

    private static String badgeChar(String label) {
        if (label == null || label.isEmpty()) return "·";
        return label.substring(0, 1);
    }

    private TextView tab(String value, boolean on) {
        TextView view = text(value, 15, on ? 0xFFFFFFFF : ACCENT);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        paintTab(view, on);
        return view;
    }

    private void paintTab(TextView view, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(on ? ACCENT : 0x00000000);
        if (!on) bg.setStroke(dp(1), ACCENT);
        view.setTextColor(on ? 0xFFFFFFFF : ACCENT);
        view.setBackground(bg);
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        return card;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private View space(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), 1));
        return view;
    }

    private View spaceV(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Item {
        int uid;
        int pid;
        String name = "";
        String label = "";
        String pkg;
        Drawable icon;
        int rssKb;
        double cpu;
        int n = 1;
        boolean cleanable;

        static final java.util.Comparator<Item> BY_MEM = new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Integer.compare(b.rssKb, a.rssKb);
            }
        };
        static final java.util.Comparator<Item> BY_CPU = new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Double.compare(b.cpu, a.cpu);
            }
        };
    }

    private static final class Face {
        String label = "系统";
        String pkg;
        Drawable icon;
        boolean cleanable;
    }
}
