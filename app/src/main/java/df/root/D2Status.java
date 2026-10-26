package df.root;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.StringRes;

import java.util.Locale;

final class D2Status {

    enum Dot { GREY, GREEN, RED }

    enum Support { YES, MAYBE, NO }

    static final class Result {
        final boolean supported;
        final Support support;
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

        @StringRes
        int lockscreenLabelRes() {
            return lockscreen ? R.string.value_yes : R.string.value_no;
        }

        @StringRes
        int maintenanceLabelRes() {
            return maintenance ? R.string.value_on : R.string.value_off;
        }

        @StringRes
        int deviceLabelRes() {
            switch (support) {
                case YES:   return R.string.value_supported;
                case MAYBE: return R.string.value_maybe_supported;
                default:    return R.string.value_unsupported;
            }
        }
    }

    private static final String[] MAINTENANCE_KEYS = {
            "maintenance_mode",
            "maintenance_mode_status",
            "sec_maintenance_mode",
    };

    private static final String FIRST_LOCKDOWN_PATCH = "2026-04-01";
    private static final String PATCH_MAYBE_START = "2026-09-01";
    private static final String PATCH_MAYBE_END   = "2026-10-01";

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

        Support kernelSupport = kernelSupport();
        Support patchSupport = patchSupport();

        Support support;
        if (kernelBelow510()) {
            // 内核低于 5.10：直接不支持，不再看补丁
            support = Support.NO;
        } else if (samsung) {
            if (predatesLockdown()) {
                support = Support.NO;
            } else {
                support = (patchSupport == Support.YES) ? Support.YES : Support.MAYBE;
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
                .append(" below510=").append(kernelBelow510())
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

    /** 内核低于 5.10：直接不支持。解析失败按"低于"处理（保守）。 */
    private static boolean kernelBelow510() {
        int[] kv = kernelMajorMinorPatch();
        if (kv == null) return true;
        int major = kv[0], minor = kv[1];
        if (major < 5) return true;
        if (major == 5 && minor < 10) return true;
        return false;
    }

    private static Support combine(Support a, Support b) {
        if (a == Support.YES && b == Support.YES) return Support.YES;
        if (a == Support.NO && b == Support.NO) return Support.NO;
        return Support.MAYBE;
    }

    private static Support patchSupport() {
        String patch = Build.VERSION.SECURITY_PATCH;
        if (patch == null || patch.length() < 10) return Support.NO;
        if (patch.compareTo(PATCH_MAYBE_START) < 0) return Support.YES;
        if (patch.compareTo(PATCH_MAYBE_END) < 0) return Support.MAYBE;
        return Support.NO;
    }

    private static Support kernelSupport() {
        int[] kv = kernelMajorMinorPatch();
        if (kv == null) return Support.NO;
        int major = kv[0], minor = kv[1], patch = kv[2];

        int[] fix = null;
        for (int[] f : KERNEL_FIX) {
            if (f[0] == major && f[1] == minor) { fix = f; break; }
        }
        if (fix == null) {
            // 低于 5.10 已在 read() 短路；这里只剩 5.10 以上的未知分支
            if (major > 7 || (major == 7 && minor > 0)) return Support.MAYBE;
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