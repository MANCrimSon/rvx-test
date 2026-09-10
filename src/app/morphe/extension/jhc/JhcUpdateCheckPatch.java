package app.morphe.extension.jhc;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JhcUpdateCheckPatch {
    private static final String TAG = "CrimsonUpdate";
    private static final String PREFS_NAME = "crimson_update_prefs";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until";
    private static final String KEY_SNOOZED_TAG = "snoozed_tag";
    private static final String KEY_SKIPPED_TAG = "skipped_tag";
    private static final String KEY_LAST_CHECK_TIME = "last_check_time";
    private static final String KEY_LAST_REMOTE_TAG = "last_remote_tag";
    private static final String KEY_NOTIFY_DEV = "notify_dev_builds";

    // Target repository
    private static final String REPO_OWNER_NAME = "MANCrimSon/rvx-test";
    private static final String REPO_RELEASES_API = "https://api.github.com/repos/" + REPO_OWNER_NAME + "/releases?per_page=10";

    // Obtainium deep link for test package
    private static final String OBTAINIUM_DEEP_LINK = 
        "obtainium://app/%7B%22id%22%3A%22app.morphe.android.youtube.test%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMANCrimSon%2Frvx-test%22%2C%22author%22%3A%22MANCrimSon%22%2C%22name%22%3A%22YouTube%20Morphe%20%28Test%29%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Eyoutube-morphe%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Afalse%7D%22%7D";
    private static final String OBTAINIUM_DOWNLOAD_URL = "https://github.com/ImranR98/Obtainium/releases/latest";

    // 3.5 seconds delay on startup
    private static final long STARTUP_DELAY_MS = 3500L;
    // 0 during testing
    private static final long API_COOLDOWN_MS = 0L;
    // 0 for test builds
    private static final int EMBEDDED_BUILD_CODE = 0;
    // TRUE: always show dialog on every launch for testing (ignores snooze/skip)
    private static final boolean FORCE_TEST_ALWAYS_SHOW = true;

    public static void checkUpdate(Context context) {
        if (context == null) return;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Context appContext = context.getApplicationContext();
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

                long now = System.currentTimeMillis();
                long lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L);
                if (!FORCE_TEST_ALWAYS_SHOW && API_COOLDOWN_MS > 0 && (now - lastCheck < API_COOLDOWN_MS)) {
                    Log.d(TAG, "Cooldown active, skipping check");
                    return;
                }

                new Thread(() -> performCheck(context)).start();
            } catch (Throwable t) {
                Log.e(TAG, "Error in checkUpdate scheduler", t);
            }
        }, STARTUP_DELAY_MS);
    }

    private static void performCheck(Context context) {
        try {
            Context appContext = context.getApplicationContext();
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            HttpURLConnection conn = (HttpURLConnection) new URL(REPO_RELEASES_API).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Crimson-Update-Checker");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "GitHub API returned HTTP " + code);
                conn.disconnect();
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();

            prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply();

            JSONArray releases = new JSONArray(sb.toString());
            if (releases.length() == 0) return;

            boolean notifyDev = prefs.getBoolean(KEY_NOTIFY_DEV, true);

            String targetTag = null;
            String downloadUrl = null;
            String appVersion = "";
            String patchVersion = "";
            boolean targetIsDev = false;

            for (int i = 0; i < releases.length(); i++) {
                JSONObject rel = releases.getJSONObject(i);
                String tag = rel.optString("tag_name", "").trim();
                if (tag.isEmpty()) continue;

                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null || assets.length() == 0) continue;

                String matchedUrl = findMatchingAsset(assets);
                if (matchedUrl == null) continue;

                String body = rel.optString("body", "");
                String patchVer = extractPatchVersion(body);
                boolean isDev = isDevPatch(patchVer, rel.optBoolean("prerelease", false));

                // If user only wants stable releases, skip dev builds
                if (!notifyDev && isDev) {
                    Log.d(TAG, "Skipping dev build " + tag + " (patch: " + patchVer + ")");
                    continue;
                }

                targetTag = tag;
                downloadUrl = matchedUrl;
                appVersion = extractVersionFromUrl(matchedUrl);
                patchVersion = patchVer;
                targetIsDev = isDev;
                break;
            }

            if (targetTag == null || downloadUrl == null) {
                Log.d(TAG, "No matching APK found in releases");
                return;
            }

            // If not in forced test mode, check snooze and skip
            if (!FORCE_TEST_ALWAYS_SHOW) {
                String skippedTag = prefs.getString(KEY_SKIPPED_TAG, "");
                if (targetTag.equals(skippedTag)) {
                    Log.d(TAG, "Build " + targetTag + " was skipped by user");
                    return;
                }

                if (EMBEDDED_BUILD_CODE > 0) {
                    String snoozedTag = prefs.getString(KEY_SNOOZED_TAG, "");
                    long snoozeUntil = prefs.getLong(KEY_SNOOZE_UNTIL, 0L);
                    long now = System.currentTimeMillis();
                    if (targetTag.equals(snoozedTag) && now < snoozeUntil) {
                        Log.d(TAG, "Build " + targetTag + " is snoozed until " + snoozeUntil);
                        return;
                    }

                    int remoteBuildCode = parseNumericTag(targetTag);
                    if (remoteBuildCode > 0 && remoteBuildCode <= EMBEDDED_BUILD_CODE) {
                        Log.d(TAG, "App is up to date (remote: " + remoteBuildCode + ", installed: " + EMBEDDED_BUILD_CODE + ")");
                        return;
                    }
                }
            }

            final String finalTag = targetTag;
            final String finalUrl = downloadUrl;
            final String finalVer = appVersion;
            final String finalPatchVer = patchVersion;
            final boolean finalIsDev = targetIsDev;

            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> 
                    showDialog((Activity) context, finalTag, finalVer, finalPatchVer, finalUrl, finalIsDev));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error checking updates", t);
        }
    }

    private static boolean isDevPatch(String patchVer, boolean isPrerelease) {
        if (isPrerelease) return true;
        if (patchVer != null && !patchVer.isEmpty()) {
            String lower = patchVer.toLowerCase(Locale.ROOT);
            return lower.contains("dev") || lower.contains("beta") || lower.contains("alpha") || lower.contains("rc");
        }
        return false;
    }

    private static String findMatchingAsset(JSONArray assets) {
        boolean is64Bit = false;
        if (Build.SUPPORTED_ABIS != null) {
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64")) {
                    is64Bit = true;
                    break;
                }
            }
        }

        String allApkUrl = null;
        String archApkUrl = null;

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;

            String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
            String url = asset.optString("browser_download_url", "");

            if (!name.endsWith(".apk") || !name.contains("youtube") || name.contains("module")) {
                continue;
            }

            if (name.contains("-all.apk")) {
                allApkUrl = url;
            }
            if (is64Bit && name.contains("arm64")) {
                archApkUrl = url;
            } else if (!is64Bit && (name.contains("arm-v7a") || name.contains("armeabi-v7a"))) {
                archApkUrl = url;
            }
        }

        return archApkUrl != null ? archApkUrl : allApkUrl;
    }

    private static String extractVersionFromUrl(String url) {
        try {
            int vIdx = url.indexOf("-v");
            if (vIdx != -1) {
                int endIdx = url.indexOf("-", vIdx + 2);
                if (endIdx != -1) {
                    return url.substring(vIdx + 2, endIdx);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String extractPatchVersion(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            Pattern p = Pattern.compile("patches-(?:v)?([0-9a-zA-Z._-]+)\\.mpp");
            Matcher m = p.matcher(body);
            if (m.find()) {
                return m.group(1);
            }
            Pattern p2 = Pattern.compile("Patches:[^\\n]*?([0-9]+\\.[0-9]+[0-9a-zA-Z._-]*)");
            Matcher m2 = p2.matcher(body);
            if (m2.find()) {
                return m2.group(1);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static int parseNumericTag(String tag) {
        try {
            String clean = tag.replaceAll("[^0-9]", "");
            return Integer.parseInt(clean);
        } catch (Exception e) {
            return 0;
        }
    }

    // --- ACCURATE THEME DETECTION (Morphe Utils, ThemeUtils, DecorView, Attributes, System) ---
    private static boolean isDarkTheme(Activity activity) {
        if (activity == null) return true;

        // 1. Morphe / ReVanced internal API via reflection
        try {
            Class<?> utilsClass = Class.forName("app.morphe.extension.shared.Utils");
            Method isDarkMethod = utilsClass.getMethod("isDarkModeEnabled");
            Object result = isDarkMethod.invoke(null);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Throwable ignored) {}

        // 2. ThemeUtils getDialogBackgroundColor via reflection
        try {
            Class<?> themeUtilsClass = Class.forName("app.morphe.extension.shared.theme.ThemeUtils");
            Method getBgMethod = themeUtilsClass.getMethod("getDialogBackgroundColor");
            Object result = getBgMethod.invoke(null);
            if (result instanceof Integer) {
                return isColorDark((Integer) result);
            }
        } catch (Throwable ignored) {}

        // 3. SharedPreferences of Morphe
        try {
            SharedPreferences sp = activity.getSharedPreferences(activity.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            if (sp.contains("morphe_theme_last_used_dark_mode")) {
                return sp.getBoolean("morphe_theme_last_used_dark_mode", true);
            }
        } catch (Throwable ignored) {}

        // 4. Activity DecorView background color
        try {
            if (activity.getWindow() != null && activity.getWindow().getDecorView() != null) {
                Drawable decorBg = activity.getWindow().getDecorView().getBackground();
                if (decorBg instanceof ColorDrawable) {
                    int c = ((ColorDrawable) decorBg).getColor();
                    if (c != 0) return isColorDark(c);
                }
            }
        } catch (Throwable ignored) {}

        // 5. Activity Theme attributes
        try {
            TypedValue tv = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return isColorDark(tv.data);
                }
            }
            if (activity.getTheme().resolveAttribute(android.R.attr.windowBackground, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return isColorDark(tv.data);
                }
            }
            if (activity.getTheme().resolveAttribute(android.R.attr.isLightTheme, tv, true)) {
                return (tv.data == 0);
            }
        } catch (Throwable ignored) {}

        // 6. System Configuration uiMode
        try {
            int uiMode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (uiMode == Configuration.UI_MODE_NIGHT_YES) return true;
            if (uiMode == Configuration.UI_MODE_NIGHT_NO) return false;
        } catch (Throwable ignored) {}

        return true;
    }

    private static boolean isColorDark(int color) {
        double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return lum < 0.5;
    }

    // --- UI DIALOG (Modern Beautiful Minimalism & Interactive DEV Toggle) ---
    private static void showDialog(Activity activity, String tag, String version, String patchVersion, String downloadUrl, boolean isDev) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        try {
            Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float density = dm.density;
            boolean dark = isDarkTheme(activity);

            // Palette Definition (Minimalist subtle tones)
            int darkCardBg = Color.parseColor("#141416");
            try {
                Class<?> themeUtilsClass = Class.forName("app.morphe.extension.shared.theme.ThemeUtils");
                Method getBgMethod = themeUtilsClass.getMethod("getDialogBackgroundColor");
                int morpheBg = (Integer) getBgMethod.invoke(null);
                if (morpheBg != 0) darkCardBg = morpheBg;
            } catch (Throwable ignored) {}

            final int colCardBg = dark ? darkCardBg : Color.parseColor("#FFFFFF");
            final int colBackdrop = dark ? Color.parseColor("#90000000") : Color.parseColor("#50000000");
            final int colTitle = dark ? Color.WHITE : Color.parseColor("#0F0F0F");
            final int colSubtitle = dark ? Color.parseColor("#8E8E93") : Color.parseColor("#606060");
            final int colHandle = dark ? Color.parseColor("#38383A") : Color.parseColor("#D1D1D6");
            final int colSurface = dark ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
            final int colSurfaceBorder = dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#E5E5EA");
            final int colPrimaryBtnBg = dark ? Color.parseColor("#3EA6FF") : Color.parseColor("#065FD4");
            final int colPrimaryBtnText = dark ? Color.BLACK : Color.WHITE;
            final int colPillText = dark ? Color.parseColor("#F2F2F7") : Color.parseColor("#0F0F0F");
            final int colSwitchActive = dark ? Color.parseColor("#3EA6FF") : Color.parseColor("#065FD4");
            final int colSwitchInactive = dark ? Color.parseColor("#3A3A3C") : Color.parseColor("#D1D1D6");

            // Fullscreen backdrop container
            FrameLayout rootFrame = new FrameLayout(activity);
            rootFrame.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            rootFrame.setBackgroundColor(colBackdrop);
            rootFrame.setOnClickListener(v -> dialog.dismiss());

            // Bottom sheet card layout
            LinearLayout sheet = new LinearLayout(activity);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setClickable(true);

            GradientDrawable sheetBg = new GradientDrawable();
            sheetBg.setColor(colCardBg);
            sheet.setBackground(sheetBg);

            // ScrollWrapper
            ScrollView scrollWrapper = new ScrollView(activity);
            scrollWrapper.setVerticalScrollBarEnabled(false);

            // Drag to dismiss touch listener
            View.OnTouchListener dragListener = new View.OnTouchListener() {
                private float startY;
                private float lastY;
                private boolean dragging = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startY = event.getRawY();
                            lastY = startY;
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float rawY = event.getRawY();
                            float dy = rawY - startY;
                            if (dy > dp(6, density)) {
                                dragging = true;
                                scrollWrapper.setTranslationY(Math.max(0, dy));
                            }
                            lastY = rawY;
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (dragging) {
                                float totalDy = lastY - startY;
                                if (totalDy > dp(80, density)) {
                                    scrollWrapper.animate()
                                        .translationY(scrollWrapper.getHeight() + dp(50, density))
                                        .setDuration(180)
                                        .withEndAction(dialog::dismiss)
                                        .start();
                                } else {
                                    scrollWrapper.animate()
                                        .translationY(0)
                                        .setDuration(180)
                                        .start();
                                }
                                dragging = false;
                                return true;
                            }
                            return false;
                    }
                    return false;
                }
            };

            // Header Container (Drag area)
            LinearLayout headerLayout = new LinearLayout(activity);
            headerLayout.setOrientation(LinearLayout.VERTICAL);
            headerLayout.setOnTouchListener(dragListener);

            // Drag handle
            View handle = new View(activity);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36, density), dp(4, density));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handle.setLayoutParams(handleLp);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(colHandle);
            handleBg.setCornerRadius(dp(2, density));
            handle.setBackground(handleBg);
            headerLayout.addView(handle);

            // 1. Status Pill Badge (e.g. [ ⚡ DEV • Patches 1.42.0-dev.10 ] or [ ✨ Release • Patches 1.42.0 ])
            TextView badgeView = new TextView(activity);
            String badgePrefix = isDev ? getString("badge_dev") : getString("badge_release");
            String patchStr = patchVersion.isEmpty() ? "" : " • " + patchVersion;
            badgeView.setText(badgePrefix + patchStr);
            badgeView.setTextSize(11);
            badgeView.setTypeface(Typeface.DEFAULT_BOLD);
            badgeView.setTextColor(isDev ? Color.parseColor("#E5A93C") : colPrimaryBtnBg);
            badgeView.setPadding(dp(10, density), dp(4, density), dp(10, density), dp(4, density));

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(colSurface);
            badgeBg.setCornerRadius(dp(12, density));
            badgeBg.setStroke(1, colSurfaceBorder);
            badgeView.setBackground(badgeBg);

            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.gravity = Gravity.START;
            badgeLp.topMargin = dp(12, density);
            badgeView.setLayoutParams(badgeLp);
            headerLayout.addView(badgeView);

            // 2. Minimalist Clean Title
            TextView titleView = new TextView(activity);
            titleView.setText(getString("title"));
            titleView.setTextColor(colTitle);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setTextSize(19);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLp.topMargin = dp(6, density);
            titleView.setLayoutParams(titleLp);
            headerLayout.addView(titleView);

            // 3. Compact Subtitle (Build & YouTube Version)
            TextView subView = new TextView(activity);
            String verInfo = version.isEmpty() ? "" : "YouTube " + version + " • ";
            subView.setText(verInfo + String.format(getString("subtitle_fmt"), tag));
            subView.setTextColor(colSubtitle);
            subView.setTextSize(13);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(3, density);
            subView.setLayoutParams(subLp);
            headerLayout.addView(subView);

            sheet.addView(headerLayout);

            // --- DEV / RELEASE TOGGLE ROW (Minimalist Switch) ---
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean currentNotifyDev = prefs.getBoolean(KEY_NOTIFY_DEV, true);

            LinearLayout toggleRow = new LinearLayout(activity);
            toggleRow.setOrientation(LinearLayout.HORIZONTAL);
            toggleRow.setGravity(Gravity.CENTER_VERTICAL);
            toggleRow.setPadding(dp(14, density), dp(10, density), dp(14, density), dp(10, density));

            GradientDrawable toggleRowBg = new GradientDrawable();
            toggleRowBg.setColor(colSurface);
            toggleRowBg.setCornerRadius(dp(14, density));
            toggleRowBg.setStroke(1, colSurfaceBorder);
            toggleRow.setBackground(toggleRowBg);

            LinearLayout.LayoutParams toggleRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            toggleRowLp.topMargin = dp(14, density);
            toggleRow.setLayoutParams(toggleRowLp);

            // Left text block: Title & Explanation
            LinearLayout toggleTextLayout = new LinearLayout(activity);
            toggleTextLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ttlLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            toggleTextLayout.setLayoutParams(ttlLp);

            TextView toggleTitle = new TextView(activity);
            toggleTitle.setText(getString("dev_toggle_title"));
            toggleTitle.setTextColor(colTitle);
            toggleTitle.setTextSize(14);
            toggleTitle.setTypeface(Typeface.DEFAULT_BOLD);
            toggleTextLayout.addView(toggleTitle);

            TextView toggleSub = new TextView(activity);
            toggleSub.setText(getString("dev_toggle_sub"));
            toggleSub.setTextColor(colSubtitle);
            toggleSub.setTextSize(11);
            toggleTextLayout.addView(toggleSub);

            toggleRow.addView(toggleTextLayout);

            // Right: Custom Smooth Minimalist Switch
            int switchW = dp(44, density);
            int switchH = dp(24, density);
            int thumbSize = dp(18, density);
            int thumbMargin = dp(3, density);
            int translationX = switchW - thumbSize - (thumbMargin * 2);

            FrameLayout switchTrack = new FrameLayout(activity);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(switchW, switchH);
            switchTrack.setLayoutParams(trackLp);

            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(switchH / 2.0f);
            trackBg.setColor(currentNotifyDev ? colSwitchActive : colSwitchInactive);
            switchTrack.setBackground(trackBg);

            View thumb = new View(activity);
            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(thumbSize, thumbSize);
            thumbLp.gravity = Gravity.CENTER_VERTICAL;
            thumbLp.leftMargin = thumbMargin;
            thumb.setLayoutParams(thumbLp);

            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setCornerRadius(thumbSize / 2.0f);
            thumbBg.setColor(Color.WHITE);
            thumb.setBackground(thumbBg);

            if (currentNotifyDev) {
                thumb.setTranslationX(translationX);
            } else {
                thumb.setTranslationX(0);
            }

            switchTrack.addView(thumb);
            toggleRow.addView(switchTrack);

            // Toggle click listener (clicking either switch or row toggles state)
            final boolean[] switchState = {currentNotifyDev};
            View.OnClickListener toggleClick = v -> {
                switchState[0] = !switchState[0];
                boolean isChecked = switchState[0];

                thumb.animate()
                    .translationX(isChecked ? translationX : 0)
                    .setDuration(160)
                    .start();

                trackBg.setColor(isChecked ? colSwitchActive : colSwitchInactive);
                prefs.edit().putBoolean(KEY_NOTIFY_DEV, isChecked).apply();
                showToast(activity, isChecked ? getString("toast_dev_on") : getString("toast_dev_off"));
            };
            toggleRow.setOnClickListener(toggleClick);

            sheet.addView(toggleRow);

            // --- PRIMARY DOWNLOAD BUTTON (Sleek Pill) ---
            TextView downloadBtn = createButton(activity, getString("download_btn"), colPrimaryBtnBg, colPrimaryBtnText, density, 14);
            LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44, density));
            dlLp.topMargin = dp(14, density);
            downloadBtn.setLayoutParams(dlLp);
            downloadBtn.setOnClickListener(v -> {
                dialog.dismiss();
                openUrl(activity, downloadUrl);
            });
            sheet.addView(downloadBtn);

            // --- OBTAINIUM GROUP (Two balanced pill buttons side-by-side) ---
            LinearLayout obtainiumRow = new LinearLayout(activity);
            obtainiumRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams obRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            obRowLp.topMargin = dp(8, density);
            obtainiumRow.setLayoutParams(obRowLp);

            // Left: Open Obtainium
            TextView openObtainiumBtn = createSubButton(activity, getString("obtainium_open"), colSurface, colSurfaceBorder, colPillText, density);
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, dp(40, density), 1.0f);
            openLp.rightMargin = dp(6, density);
            openObtainiumBtn.setLayoutParams(openLp);
            openObtainiumBtn.setOnClickListener(v -> {
                dialog.dismiss();
                launchObtainium(activity);
            });
            obtainiumRow.addView(openObtainiumBtn);

            // Right: Import profile
            TextView importBtn = createSubButton(activity, getString("obtainium_import"), colSurface, colSurfaceBorder, colPrimaryBtnBg, density);
            LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(0, dp(40, density), 1.0f);
            importBtn.setLayoutParams(importLp);
            importBtn.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(OBTAINIUM_DEEP_LINK));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (ActivityNotFoundException ex) {
                    openUrl(activity, OBTAINIUM_DOWNLOAD_URL);
                    showToast(activity, getString("toast_install_obtainium"));
                }
            });
            obtainiumRow.addView(importBtn);
            sheet.addView(obtainiumRow);

            // --- SNOOZE ROW (Clean Pill Chips: 1д, 3д, 7д, 1мес) ---
            HorizontalScrollView chipsScroll = new HorizontalScrollView(activity);
            chipsScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollLp.topMargin = dp(14, density);
            chipsScroll.setLayoutParams(scrollLp);

            LinearLayout chipsRow = new LinearLayout(activity);
            chipsRow.setOrientation(LinearLayout.HORIZONTAL);

            int[] days = {1, 3, 7, 14, 30};
            for (int d : days) {
                String label = d == 30 ? getString("chip_1mo") : d + " " + getString("chip_day");
                TextView chip = createChip(activity, "⏰ " + label, colSurface, colSurfaceBorder, colSubtitle, density);
                chip.setOnClickListener(v -> {
                    snooze(activity, tag, d);
                    dialog.dismiss();
                    showToast(activity, String.format(getString("toast_snoozed"), label));
                });
                chipsRow.addView(chip);
            }
            chipsScroll.addView(chipsRow);
            sheet.addView(chipsScroll);

            // --- DISMISS / SKIP BUTTON (Delicate Text Action) ---
            TextView skipBtn = new TextView(activity);
            skipBtn.setText(getString("skip_btn"));
            skipBtn.setTextColor(colSubtitle);
            skipBtn.setTextSize(13);
            skipBtn.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams skipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            skipLp.topMargin = dp(12, density);
            skipBtn.setLayoutParams(skipLp);
            skipBtn.setPadding(0, dp(4, density), 0, dp(4, density));
            skipBtn.setOnClickListener(v -> {
                prefs.edit().putString(KEY_SKIPPED_TAG, tag).apply();
                dialog.dismiss();
                showToast(activity, String.format(getString("toast_skipped"), tag));
            });
            sheet.addView(skipBtn);

            scrollWrapper.addView(sheet);
            rootFrame.addView(scrollWrapper);
            dialog.setContentView(rootFrame);

            // Dynamic Layout Updater for both Portrait and Landscape (on launch and rotation)
            rootFrame.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int totalWidth = right - left;
                int totalHeight = bottom - top;
                if (totalWidth <= 0 || totalHeight <= 0) return;

                boolean isLandscape = totalWidth > totalHeight;

                if (isLandscape) {
                    int cardWidth = Math.min(totalWidth - dp(32, density), dp(480, density));
                    FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams(
                        cardWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    );
                    scrollWrapper.setLayoutParams(wrapLp);

                    sheetBg.setCornerRadius(dp(20, density));
                    sheet.setPadding(dp(20, density), dp(8, density), dp(20, density), dp(10, density));
                    handleLp.bottomMargin = dp(4, density);
                    titleView.setTextSize(16);
                    toggleRowLp.topMargin = dp(8, density);
                    dlLp.topMargin = dp(8, density);
                    obRowLp.topMargin = dp(6, density);
                    scrollLp.topMargin = dp(8, density);
                    skipLp.topMargin = dp(6, density);
                } else {
                    FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    );
                    scrollWrapper.setLayoutParams(wrapLp);

                    float r = dp(24, density);
                    sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                    sheet.setPadding(dp(20, density), dp(10, density), dp(20, density), dp(24, density));
                    handleLp.bottomMargin = dp(12, density);
                    titleView.setTextSize(19);
                    toggleRowLp.topMargin = dp(14, density);
                    dlLp.topMargin = dp(14, density);
                    obRowLp.topMargin = dp(8, density);
                    scrollLp.topMargin = dp(14, density);
                    skipLp.topMargin = dp(12, density);
                }
            });

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }

            dialog.show();
        } catch (Throwable t) {
            Log.e(TAG, "Error displaying update dialog", t);
        }
    }

    private static void launchObtainium(Context context) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage("dev.imranr.obtainium");
            if (launchIntent == null) {
                launchIntent = context.getPackageManager().getLaunchIntentForPackage("dev.imranr.obtainium.fdroid");
            }
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
            } else {
                openUrl(context, OBTAINIUM_DOWNLOAD_URL);
                showToast(context, getString("toast_install_obtainium"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch Obtainium", e);
            openUrl(context, OBTAINIUM_DOWNLOAD_URL);
        }
    }

    private static void snooze(Context context, String tag, int days) {
        long snoozeTime = System.currentTimeMillis() + (days * 86_400_000L);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putLong(KEY_SNOOZE_UNTIL, snoozeTime)
            .putString(KEY_SNOOZED_TAG, tag)
            .apply();
    }

    private static void openUrl(Context context, String url) {
        try {
            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://"));
            PackageManager pm = context.getPackageManager();
            ResolveInfo resolveInfo = pm.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY);

            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                String browserPkg = resolveInfo.activityInfo.packageName;
                if (browserPkg != null && !browserPkg.equals(context.getPackageName()) && !browserPkg.contains("youtube")) {
                    intent.setPackage(browserPkg);
                }
            }

            context.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Failed to open url: " + url, e);
        }
    }

    private static void showToast(Context context, String msg) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private static int dp(float dp, float density) {
        return (int) (dp * density + 0.5f);
    }

    private static TextView createButton(Context context, String text, int bgColor, int textColor, float density, int textSize) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(textSize);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(14, density));
        tv.setBackground(gd);
        return tv;
    }

    private static TextView createChip(Context context, String text, int bgColor, int borderColor, int textColor, float density) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12, density), dp(6, density), dp(12, density), dp(6, density));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(14, density));
        gd.setStroke(1, borderColor);
        tv.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6, density);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView createSubButton(Context context, String text, int bgColor, int borderColor, int textColor, float density) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8, density), dp(8, density), dp(8, density), dp(8, density));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(12, density));
        gd.setStroke(1, borderColor);
        tv.setBackground(gd);
        return tv;
    }

    private static String emoji(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    // --- MULTILINGUAL DICTIONARY ---
    private static String getString(String key) {
        String lang = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);

        // Ukrainian
        if (lang.equals("uk") || lang.equals("ua")) {
            switch (key) {
                case "title": return "Доступне оновлення";
                case "subtitle_fmt": return "Збірка %s";
                case "badge_dev": return "⚡ DEV";
                case "badge_release": return "✨ Реліз";
                case "dev_toggle_title": return "DEV-патчі";
                case "dev_toggle_sub": return "Сповіщати про тестові версії";
                case "toast_dev_on": return "Сповіщення про DEV-патчі увімкнено";
                case "toast_dev_off": return "Лише стабільні релізи";
                case "download_btn": return "⬇️  Завантажити APK";
                case "obtainium_open": return emoji(0x1F4F1) + "  Obtainium";
                case "obtainium_import": return "➕  Профіль";
                case "chip_day": return "дн";
                case "chip_1mo": return "1 міс";
                case "skip_btn": return "Пропустити цю версію";
                case "toast_snoozed": return "Нагадування відкладено на %s";
                case "toast_skipped": return "Збірку %s пропущено";
                case "toast_install_obtainium": return "Встановіть Obtainium для автооновлень";
            }
        }
        // Russian, Belarusian, Kazakh
        else if (lang.equals("ru") || lang.equals("be") || lang.equals("kk")) {
            switch (key) {
                case "title": return "Доступно обновление";
                case "subtitle_fmt": return "Сборка %s";
                case "badge_dev": return "⚡ DEV";
                case "badge_release": return "✨ Релиз";
                case "dev_toggle_title": return "DEV-патчи";
                case "dev_toggle_sub": return "Уведомлять о тестовых версиях";
                case "toast_dev_on": return "Уведомления о DEV-патчах включены";
                case "toast_dev_off": return "Только стабильные релизы";
                case "download_btn": return "⬇️  Скачать APK";
                case "obtainium_open": return emoji(0x1F4F1) + "  Obtainium";
                case "obtainium_import": return "➕  Профиль";
                case "chip_day": return "дн";
                case "chip_1mo": return "1 мес";
                case "skip_btn": return "Пропустить эту версию";
                case "toast_snoozed": return "Напоминание отложено на %s";
                case "toast_skipped": return "Билд %s пропущен";
                case "toast_install_obtainium": return "Установите Obtainium для автообновлений";
            }
        } 
        // Spanish
        else if (lang.equals("es")) {
            switch (key) {
                case "title": return "Actualización disponible";
                case "subtitle_fmt": return "Versión %s";
                case "badge_dev": return "⚡ DEV";
                case "badge_release": return "✨ Release";
                case "dev_toggle_title": return "Parches DEV";
                case "dev_toggle_sub": return "Avisar sobre versiones de prueba";
                case "toast_dev_on": return "Notificaciones DEV activadas";
                case "toast_dev_off": return "Solo versiones estables";
                case "download_btn": return "⬇️  Descargar APK";
                case "obtainium_open": return emoji(0x1F4F1) + "  Obtainium";
                case "obtainium_import": return "➕  Perfil";
                case "chip_day": return "d";
                case "chip_1mo": return "1 mes";
                case "skip_btn": return "Omitir esta versión";
                case "toast_snoozed": return "Recordatorio pospuesto por %s";
                case "toast_skipped": return "Versión %s omitida";
                case "toast_install_obtainium": return "Instala Obtainium para actualizaciones";
            }
        } 
        // German
        else if (lang.equals("de")) {
            switch (key) {
                case "title": return "Update verfügbar";
                case "subtitle_fmt": return "Build %s";
                case "badge_dev": return "⚡ DEV";
                case "badge_release": return "✨ Release";
                case "dev_toggle_title": return "DEV-Patches";
                case "dev_toggle_sub": return "Über Testversionen benachrichtigen";
                case "toast_dev_on": return "DEV-Benachrichtigungen aktiviert";
                case "toast_dev_off": return "Nur stabile Versionen";
                case "download_btn": return "⬇️  APK herunterladen";
                case "obtainium_open": return emoji(0x1F4F1) + "  Obtainium";
                case "obtainium_import": return "➕  Profil";
                case "chip_day": return "T";
                case "chip_1mo": return "1 Monat";
                case "skip_btn": return "Diese Version überspringen";
                case "toast_snoozed": return "Erinnerung verschoben um %s";
                case "toast_skipped": return "Build %s übersprungen";
                case "toast_install_obtainium": return "Installiere Obtainium für Updates";
            }
        }

        // English default
        switch (key) {
            case "title": return "Update Available";
            case "subtitle_fmt": return "Build %s";
            case "badge_dev": return "⚡ DEV";
            case "badge_release": return "✨ Release";
            case "dev_toggle_title": return "DEV Patches";
            case "dev_toggle_sub": return "Notify about pre-release builds";
            case "toast_dev_on": return "DEV notifications enabled";
            case "toast_dev_off": return "Stable releases only";
            case "download_btn": return "⬇️  Download APK";
            case "obtainium_open": return emoji(0x1F4F1) + "  Obtainium";
            case "obtainium_import": return "➕  Profile";
            case "chip_day": return "d";
            case "chip_1mo": return "1 mo";
            case "skip_btn": return "Skip this version";
            case "toast_snoozed": return "Reminder snoozed for %s";
            case "toast_skipped": return "Build %s skipped";
            case "toast_install_obtainium": return "Install Obtainium for auto-updates";
            default: return key;
        }
    }
}
