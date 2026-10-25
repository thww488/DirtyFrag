package df.root;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.util.Locale;

final class D2Status {

    enum Dot { GREY, GREEN, RED }

    /** 三态支持等级。 */
    enum Support {
        /** 漏洞存在，大概率可利用。 */
        YES,
        /** 内核已修或补丁处于过渡期，仍可能有用。 */
        MAYBE,
        /** 窗口外，不判断。 */
        NO
    }

    static final class Result {
        final boolean supported;      // 是否参与红/绿判断（YES 或 MAYBE）
        final Support support;        // 三态
        final boolean lockscreen;
        final boolean maintenance;
        final Dot dot;
        final String detail;

        Result(boolean supported, Support support, boolean lockscreen,
               boolean maintenance, Dot dot, String detail) {
            this.supported = supported;
            this.support = support;
            this.lockscreen = lockscreen;
            this.maintenance = maintenance;
            this.dot = dot;
            this.detail = detail;
        }

        String lockscreenLabel() { return lockscreen ? "是" : "不"; }
        String maintenanceLabel() { return maintenance ? "开" : "关"; }

        String deviceLabel() {
            switch (support) {
                case YES:   return "支持";
                case MAYBE: return "可能支持";
                default:    return "不支持";
            }
        }
    }

    private static final String[] MAINTENANCE_KEYS = {
            "maintenance_mode", "maintenance_mode_status", "sec_maintenance_mode",
    };

    private static final String FIRST_LOCKDOWN_PATCH = "2026-04-01";
    private static final String PATCH_MAYBE_START   = "2026-09-01";
    private static final String PATCH_MAYBE_END     = "2026-10-01"; // 不含

    // 内核分支 → 首个修复版本。key 形如 "5.10"，value {major, minor, patch}
    private static final int[][] KERNEL_FIX = {
            {5, 10, 255},
            {5, 15, 206},
            {6, 1, 172},
            {6, 6, 138},
            {6, 12, 87},
            {6, 18, 28},
            {7, 0, 5},
    };

    static Result read(Context ctx) {
        boolean samsung = isSamsung();

        // 内核三态
        Support kernelSupport = kernelSupport();

        // 补丁三态
        Support patchSupport = patchSupport();

        // 合成：取更保守（YES > MAYBE > NO）
        Support support;
        if (samsung) {
            // 三星仍走原规则：补丁 >= 2026-04-01 才参与判断
            support = predatesLockdown() ? Support.NO : Support.MAYBE;
            // 三星补丁在 9 月前视为 YES
            if (!predatesLockdown()) {
                Support ps = patchSupport;
                support = (ps == Support.YES) ? Support.YES : Support.MAYBE;
            }
        } else {
            support = combine(kernelSupport, patchSupport);
        }

        boolean supported = support != Support.NO;
        boolean secure = isLockScreenSecured(ctx);
        String maintenanceKey = maintenanceModeKey(ctx);
        boolean maintenance = maintenanceKey != null;

        Dot dot = !supported ? Dot.GREY
                : (secure && !maintenance) ? Dot.RED : Dot.GREEN;

        StringBuilder d = new StringBuilder();
        d.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", patch ").append(Build.VERSION.SECURITY_PATCH)
                .append(", kernel ").append(System.getProperty("os.version", "?"))
                .append(": samsung=").append(samsung)
                .append(" kernelSupport=").append(kernelSupport)
                .append(" patchSupport=").append(patchSupport)
                .append(" final=").append(support)
                .append(" lockscreen=").append(secure ? "credential" : "none")
                .append(" maintenance=").append(maintenanceKey != null ? maintenanceKey : "off or unreadable")
                .append(" at_command=0 assumed -> ");
        switch (dot) {
            case RED:   d.append("red, D2 error expected"); break;
            case GREEN: d.append("green, Odin allowed"); break;
            default:    d.append("grey, outside window"); break;
        }
        return new Result(supported, support, secure, maintenance, dot, d.toString());
    }

    /**
    * 合成内核与补丁两项支持等级：
    *   - 两项都 YES → YES
    *   - 两项都 NO  → NO
    *   - 其余（一项 YES 一项 NO、含 MAYBE）→ MAYBE
     */
    private static Support combine(Support a, Support b) {
       if (a == Support.YES && b == Support.YES) return Support.YES;
       if (a == Support.NO && b == Support.NO) return Support.NO;
       return Support.MAYBE;
    }
    /** 补丁三态：<2026-09-01 → YES；[2026-09-01, 2026-10-01) → MAYBE；否则 NO。 */
    private static Support patchSupport() {
        String patch = Build.VERSION.SECURITY_PATCH;
        if (patch == null || patch.length() < 10) return Support.NO;
        if (patch.compareTo(PATCH_MAYBE_START) < 0) return Support.YES;
        if (patch.compareTo(PATCH_MAYBE_END) < 0) return Support.MAYBE;
        return Support.NO;
    }

    /** 内核三态：低于所属分支修复版本 → YES；等于或高于 → MAYBE；解析失败 → NO。 */
    private static Support kernelSupport() {
        int[] kv = kernelMajorMinorPatch();
        if (kv == null) return Support.NO;
        int major = kv[0], minor = kv[1], patch = kv[2];
        // 找所属分支
        int[] fix = null;
        for (int[] f : KERNEL_FIX) {
            if (f[0] == major && f[1] == minor) { fix = f; break; }
        }
        if (fix == null) {
            // 不在已知分支内：低于 5.10 视为 YES，高于 7.0 视为 MAYBE
            if (major < 5 || (major == 5 && minor < 10)) return Support.YES;
            if (major > 7 || (major == 7 && minor > 0)) return Support.MAYBE;
            // 5.11~5.14 / 6.2~6.5 / 6.7~6.11 等中间分支：保守给 YES
            return Support.YES;
        }
        int cmp = compareKernel(major, minor, patch, fix[0], fix[1], fix[2]);
        return cmp < 0 ? Support.YES : Support.MAYBE;
    }

    private static int compareKernel(int ma, int mi, int pa, int mb, int mib, int pb) {
        if (ma != mb) return Integer.compare(ma, mb);
        if (mi != mib) return Integer.compare(mi, mib);
        return Integer.compare(pa, pb);
    }

    /** 从 os.version 解析 major.minor.patch。 */
    private static int[] kernelMajorMinorPatch() {
        try {
            String v = System.getProperty("os.version", "");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                    .matcher(v.trim());
            if (!m.find()) return null;
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            return new int[]{major, minor, patch};
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSamsung() {
        return contains(Build.MANUFACTURER, "samsung") || contains(Build.BRAND, "samsung");
    }

    private static boolean isLockScreenSecured(Context ctx) {
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    private static String maintenanceModeKey(Context ctx) {
        for (String key : MAINTENANCE_KEYS) {
            try {
                if (isOn(Settings.Global.getString(ctx.getContentResolver(), key))
                        || isOn(Settings.Secure.getString(ctx.getContentResolver(), key))) {
                    return key;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isOn(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    private static boolean predatesLockdown() {
        String patch = Build.VERSION.SECURITY_PATCH;
        if (patch == null || patch.length() < 10) return true;
        return patch.compareTo(FIRST_LOCKDOWN_PATCH) < 0;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private D2Status() {}
}