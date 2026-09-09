package app.morphe.extension.jhc;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JhcUpdateCheckPatch {
    private static final String TAG = "CrimsonUpdate";
    private static final String PREFS_NAME = "crimson_update_prefs";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until";
    private static final String KEY_SKIPPED_TAG = "skipped_tag";
    private static final String KEY_LAST_CHECK_TIME = "last_check_time";
    private static final String KEY_LAST_REMOTE_TAG = "last_remote_tag";

    // Target repository
    private static final String REPO_OWNER_NAME = "MANCrimSon/rvx-test";
    private static final String REPO_RELEASES_API = "https://api.github.com/repos/" + REPO_OWNER_NAME + "/releases?per_page=5";

    // Obtainium deep link for test package
    private static final String OBTAINIUM_DEEP_LINK = 
        "obtainium://app/%7B%22id%22%3A%22app.morphe.android.youtube.test%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMANCrimSon%2Frvx-test%22%2C%22author%22%3A%22MANCrimSon%22%2C%22name%22%3A%22YouTube%20Morphe%20%28Test%29%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Eyoutube-morphe%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Afalse%7D%22%7D";
    private static final String OBTAINIUM_DOWNLOAD_URL = "https://github.com/ImranR98/Obtainium/releases/latest";

    // 4 seconds delay to avoid colliding with GmsCore dialogs
    private static final long STARTUP_DELAY_MS = 4000L;
    // Cooldown 0 during testing so user can verify dialog on relaunch
    private static final long API_COOLDOWN_MS = 0L;
    // 0 guarantees that any release is detected as an update for testing
    private static final int EMBEDDED_BUILD_CODE = 0;

    public static void checkUpdate(Context context) {
        if (context == null) return;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Context appContext = context.getApplicationContext();
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

                long now = System.currentTimeMillis();
                long snoozeUntil = prefs.getLong(KEY_SNOOZE_UNTIL, 0L);
                if (now < snoozeUntil) {
                    Log.d(TAG, "Update checks snoozed until: " + snoozeUntil + " (now: " + now + ")");
                    return;
                }

                long lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L);
                if (API_COOLDOWN_MS > 0 && (now - lastCheck < API_COOLDOWN_MS)) {
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

            String targetTag = null;
            String downloadUrl = null;
            String appVersion = "";
            String patchVersion = "";

            for (int i = 0; i < releases.length(); i++) {
                JSONObject rel = releases.getJSONObject(i);
                String tag = rel.optString("tag_name", "").trim();
                if (tag.isEmpty()) continue;

                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null || assets.length() == 0) continue;

                String matchedUrl = findMatchingAsset(assets);
                if (matchedUrl != null) {
                    targetTag = tag;
                    downloadUrl = matchedUrl;
                    appVersion = extractVersionFromUrl(matchedUrl);
                    String body = rel.optString("body", "");
                    patchVersion = extractPatchVersion(body);
                    break;
                }
            }

            if (targetTag == null || downloadUrl == null) {
                Log.d(TAG, "No matching APK found in recent releases");
                return;
            }

            String skippedTag = prefs.getString(KEY_SKIPPED_TAG, "");
            if (targetTag.equals(skippedTag)) {
                Log.d(TAG, "Build " + targetTag + " was skipped by user");
                return;
            }

            int remoteBuildCode = parseNumericTag(targetTag);
            if (remoteBuildCode > 0 && EMBEDDED_BUILD_CODE > 0) {
                if (remoteBuildCode <= EMBEDDED_BUILD_CODE) {
                    Log.d(TAG, "App is up to date (remote: " + remoteBuildCode + ", installed: " + EMBEDDED_BUILD_CODE + ")");
                    return;
                }
            }

            final String finalTag = targetTag;
            final String finalUrl = downloadUrl;
            final String finalVer = appVersion;
            final String finalPatchVer = patchVersion;

            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> 
                    showDialog((Activity) context, finalTag, finalVer, finalPatchVer, finalUrl));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error checking updates", t);
        }
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

    // --- UI DIALOG (Full-width AMOLED Bottom Sheet) ---
    private static void showDialog(Activity activity, String tag, String version, String patchVersion, String downloadUrl) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        try {
            Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float density = dm.density;

            // Fullscreen backdrop container
            FrameLayout rootFrame = new FrameLayout(activity);
            rootFrame.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            rootFrame.setBackgroundColor(Color.parseColor("#80000000")); // 50% dark dimming
            rootFrame.setOnClickListener(v -> dialog.dismiss());

            // Bottom sheet card
            LinearLayout sheet = new LinearLayout(activity);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setClickable(true); // Prevent dismiss on card clicks
            sheet.setPadding(dp(20, density), dp(12, density), dp(20, density), dp(28, density));

            GradientDrawable sheetBg = new GradientDrawable();
            sheetBg.setColor(Color.parseColor("#1C1C1E")); // AMOLED Dark
            float r = dp(24, density);
            sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            sheet.setBackground(sheetBg);

            FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT, 
                Gravity.BOTTOM
            );
            sheet.setLayoutParams(sheetLp);

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
                                sheet.setTranslationY(Math.max(0, dy));
                            }
                            lastY = rawY;
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (dragging) {
                                float totalDy = lastY - startY;
                                if (totalDy > dp(90, density)) {
                                    sheet.animate()
                                        .translationY(sheet.getHeight() + dp(40, density))
                                        .setDuration(180)
                                        .withEndAction(dialog::dismiss)
                                        .start();
                                } else {
                                    sheet.animate()
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
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(44, density), dp(5, density));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(14, density);
            handle.setLayoutParams(handleLp);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(Color.parseColor("#48484A"));
            handleBg.setCornerRadius(dp(3, density));
            handle.setBackground(handleBg);
            headerLayout.addView(handle);

            // Title
            TextView titleView = new TextView(activity);
            titleView.setText(getString("title"));
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(20);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            headerLayout.addView(titleView);

            // Subtitle Line 1: Build + App Version
            TextView subView = new TextView(activity);
            String verInfo = version.isEmpty() ? "" : " • YouTube " + version;
            subView.setText(String.format(getString("subtitle_fmt"), tag) + verInfo);
            subView.setTextColor(Color.parseColor("#8E8E93"));
            subView.setTextSize(14);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(4, density);
            subView.setLayoutParams(subLp);
            headerLayout.addView(subView);

            // Subtitle Line 2: Patch Version (if available)
            if (!patchVersion.isEmpty()) {
                TextView patchView = new TextView(activity);
                patchView.setText(String.format(getString("patches_fmt"), patchVersion));
                patchView.setTextColor(Color.parseColor("#8E8E93"));
                patchView.setTextSize(13);
                LinearLayout.LayoutParams patchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                patchLp.topMargin = dp(2, density);
                patchView.setLayoutParams(patchLp);
                headerLayout.addView(patchView);
            }

            sheet.addView(headerLayout);

            // --- PRIMARY ACTIONS BLOCK (Download & Obtainium) ---

            // Main Download Button
            TextView downloadBtn = createButton(activity, getString("download_btn"), Color.parseColor("#3EA6FF"), Color.BLACK, density);
            LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48, density));
            dlLp.topMargin = dp(16, density);
            downloadBtn.setLayoutParams(dlLp);
            downloadBtn.setOnClickListener(v -> {
                dialog.dismiss();
                openUrl(activity, downloadUrl);
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_LAST_REMOTE_TAG, tag).apply();
            });
            sheet.addView(downloadBtn);

            // Obtainium section header
            TextView obtainiumTitle = new TextView(activity);
            obtainiumTitle.setText(getString("obtainium_title"));
            obtainiumTitle.setTextColor(Color.parseColor("#8E8E93"));
            obtainiumTitle.setTextSize(12);
            LinearLayout.LayoutParams obTitleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            obTitleLp.topMargin = dp(14, density);
            obtainiumTitle.setLayoutParams(obTitleLp);
            sheet.addView(obtainiumTitle);

            // Obtainium actions row: [ 🚀 Открыть Obtainium ] [ 📲 Импорт профиля ]
            LinearLayout obtainiumRow = new LinearLayout(activity);
            obtainiumRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            obLp.topMargin = dp(8, density);
            obtainiumRow.setLayoutParams(obLp);

            // Left: Open Obtainium App
            TextView openObtainiumBtn = createSubButton(activity, getString("obtainium_open"), density);
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
            openLp.rightMargin = dp(6, density);
            openObtainiumBtn.setLayoutParams(openLp);
            openObtainiumBtn.setOnClickListener(v -> {
                dialog.dismiss();
                launchObtainium(activity);
            });
            obtainiumRow.addView(openObtainiumBtn);

            // Right: Import profile
            TextView importBtn = createSubButton(activity, getString("obtainium_import"), density);
            importBtn.setTextColor(Color.parseColor("#3EA6FF"));
            LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f);
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

            // --- DIVIDER ---
            View divider = new View(activity);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1, density));
            divLp.topMargin = dp(16, density);
            divLp.bottomMargin = dp(12, density);
            divider.setLayoutParams(divLp);
            divider.setBackgroundColor(Color.parseColor("#2C2C2E"));
            sheet.addView(divider);

            // --- SECONDARY BLOCK: SNOOZE / REMIND LATER (At Bottom) ---

            // Snooze section title
            TextView snoozeLabel = new TextView(activity);
            snoozeLabel.setText(getString("remind_label"));
            snoozeLabel.setTextColor(Color.parseColor("#8E8E93"));
            snoozeLabel.setTextSize(12);
            sheet.addView(snoozeLabel);

            // Snooze Chips Horizontal Row (1d, 3d, 7d, 14d, 1mo)
            HorizontalScrollView chipsScroll = new HorizontalScrollView(activity);
            chipsScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollLp.topMargin = dp(8, density);
            chipsScroll.setLayoutParams(scrollLp);

            LinearLayout chipsRow = new LinearLayout(activity);
            chipsRow.setOrientation(LinearLayout.HORIZONTAL);

            int[] days = {1, 3, 7, 14, 30};
            for (int d : days) {
                String label = d == 30 ? getString("chip_1mo") : d + " " + getString("chip_day");
                TextView chip = createChip(activity, label, density);
                chip.setOnClickListener(v -> {
                    snooze(activity, d);
                    dialog.dismiss();
                    showToast(activity, String.format(getString("toast_snoozed"), label));
                });
                chipsRow.addView(chip);
            }
            chipsScroll.addView(chipsRow);
            sheet.addView(chipsScroll);

            // Skip this build button
            TextView skipBtn = new TextView(activity);
            skipBtn.setText(getString("skip_btn"));
            skipBtn.setTextColor(Color.parseColor("#8E8E93"));
            skipBtn.setTextSize(13);
            skipBtn.setGravity(Gravity.CENTER);
            skipBtn.setPadding(0, dp(12, density), 0, dp(4, density));
            skipBtn.setOnClickListener(v -> {
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_SKIPPED_TAG, tag).apply();
                dialog.dismiss();
                showToast(activity, String.format(getString("toast_skipped"), tag));
            });
            sheet.addView(skipBtn);

            rootFrame.addView(sheet);
            dialog.setContentView(rootFrame);

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

    private static void snooze(Context context, int days) {
        long snoozeTime = System.currentTimeMillis() + (days * 86_400_000L);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_SNOOZE_UNTIL, snoozeTime).apply();
    }

    private static void openUrl(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
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

    private static TextView createButton(Context context, String text, int bgColor, int textColor, float density) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(14, density));
        tv.setBackground(gd);
        return tv;
    }

    private static TextView createChip(Context context, String text, float density) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#E5E5EA"));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14, density), dp(8, density), dp(14, density), dp(8, density));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#2C2C2E"));
        gd.setCornerRadius(dp(16, density));
        gd.setStroke(1, Color.parseColor("#3A3A3C"));
        tv.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8, density);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView createSubButton(Context context, String text, float density) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#E5E5EA"));
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8, density), dp(10, density), dp(8, density), dp(10, density));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#2C2C2E"));
        gd.setCornerRadius(dp(12, density));
        tv.setBackground(gd);
        return tv;
    }

    private static String emoji(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    // --- MULTILINGUAL DICTIONARY (15+ languages) ---
    private static String getString(String key) {
        String lang = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);

        // Russian, Ukrainian, Belarusian, Kazakh
        if (lang.equals("ru") || lang.equals("uk") || lang.equals("be") || lang.equals("kk")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Доступно обновление";
                case "subtitle_fmt": return "Сборка %s";
                case "patches_fmt": return "Патчи: %s";
                case "download_btn": return emoji(0x1F4E5) + "  СКАЧАТЬ APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Обновление через Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Открыть Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Импорт профиля";
                case "remind_label": return "\u23F1  Напомнить позже:";
                case "chip_day": return "дн";
                case "chip_1mo": return "1 месяц";
                case "skip_btn": return "\u23ED  Пропустить этот билд";
                case "toast_snoozed": return "Напоминание отложено на %s";
                case "toast_skipped": return "Билд %s пропущен";
                case "toast_install_obtainium": return "Установите Obtainium для автообновлений";
            }
        } 
        // Spanish
        else if (lang.equals("es")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Actualización disponible";
                case "subtitle_fmt": return "Versión %s";
                case "patches_fmt": return "Parches: %s";
                case "download_btn": return emoji(0x1F4E5) + "  DESCARGAR APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Actualización vía Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Abrir Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Importar perfil";
                case "remind_label": return "\u23F1  Recordar más tarde:";
                case "chip_day": return "d";
                case "chip_1mo": return "1 mes";
                case "skip_btn": return "\u23ED  Omitir esta versión";
                case "toast_snoozed": return "Recordatorio pospuesto por %s";
                case "toast_skipped": return "Versión %s omitida";
                case "toast_install_obtainium": return "Instala Obtainium para actualizaciones";
            }
        } 
        // Portuguese
        else if (lang.equals("pt")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Atualização disponível";
                case "subtitle_fmt": return "Versão %s";
                case "patches_fmt": return "Patches: %s";
                case "download_btn": return emoji(0x1F4E5) + "  BAIXAR APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Atualização via Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Abrir Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Importar perfil";
                case "remind_label": return "\u23F1  Lembrar mais tarde:";
                case "chip_day": return "d";
                case "chip_1mo": return "1 mês";
                case "skip_btn": return "\u23ED  Pular esta versão";
                case "toast_snoozed": return "Lembrete adiado por %s";
                case "toast_skipped": return "Versão %s pulada";
                case "toast_install_obtainium": return "Instale o Obtainium para atualizações";
            }
        } 
        // German
        else if (lang.equals("de")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Update verfügbar";
                case "subtitle_fmt": return "Build %s";
                case "patches_fmt": return "Patches: %s";
                case "download_btn": return emoji(0x1F4E5) + "  APK HERUNTERLADEN";
                case "obtainium_title": return emoji(0x1F4E6) + "  Aktualisierung über Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Obtainium öffnen";
                case "obtainium_import": return emoji(0x1F4F2) + " Profil importieren";
                case "remind_label": return "\u23F1  Später erinnern:";
                case "chip_day": return "T";
                case "chip_1mo": return "1 Monat";
                case "skip_btn": return "\u23ED  Diesen Build überspringen";
                case "toast_snoozed": return "Erinnerung verschoben um %s";
                case "toast_skipped": return "Build %s übersprungen";
                case "toast_install_obtainium": return "Installiere Obtainium für Updates";
            }
        }
        // French
        else if (lang.equals("fr")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Mise à jour disponible";
                case "subtitle_fmt": return "Version %s";
                case "patches_fmt": return "Patchs : %s";
                case "download_btn": return emoji(0x1F4E5) + "  TÉLÉCHARGER L'APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Mise à jour via Obtainium :";
                case "obtainium_open": return emoji(0x1F680) + " Ouvrir Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Importer profil";
                case "remind_label": return "\u23F1  Rappeler plus tard :";
                case "chip_day": return "j";
                case "chip_1mo": return "1 mois";
                case "skip_btn": return "\u23ED  Ignorer cette version";
                case "toast_snoozed": return "Rappel reporté de %s";
                case "toast_skipped": return "Version %s ignorée";
                case "toast_install_obtainium": return "Installez Obtainium pour les mises à jour";
            }
        }
        // Italian
        else if (lang.equals("it")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Aggiornamento disponibile";
                case "subtitle_fmt": return "Versione %s";
                case "patches_fmt": return "Patch: %s";
                case "download_btn": return emoji(0x1F4E5) + "  SCARICA APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Aggiornamento via Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Apri Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Importa profilo";
                case "remind_label": return "\u23F1  Ricorda più tardi:";
                case "chip_day": return "g";
                case "chip_1mo": return "1 mese";
                case "skip_btn": return "\u23ED  Salta questa versione";
                case "toast_snoozed": return "Promemoria posticipato di %s";
                case "toast_skipped": return "Versione %s saltata";
                case "toast_install_obtainium": return "Installa Obtainium per gli aggiornamenti";
            }
        }
        // Turkish
        else if (lang.equals("tr")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Güncelleme Mevcut";
                case "subtitle_fmt": return "Sürüm %s";
                case "patches_fmt": return "Yamalar: %s";
                case "download_btn": return emoji(0x1F4E5) + "  APK İNDİR";
                case "obtainium_title": return emoji(0x1F4E6) + "  Obtainium ile Güncelleme:";
                case "obtainium_open": return emoji(0x1F680) + " Obtainium Aç";
                case "obtainium_import": return emoji(0x1F4F2) + " Profili İçe Aktar";
                case "remind_label": return "\u23F1  Daha sonra hatırlat:";
                case "chip_day": return "g";
                case "chip_1mo": return "1 ay";
                case "skip_btn": return "\u23ED  Bu sürümü atla";
                case "toast_snoozed": return "Hatırlatıcı %s ertelendi";
                case "toast_skipped": return "Sürüm %s atlandı";
                case "toast_install_obtainium": return "Güncellemeler için Obtainium kurun";
            }
        }
        // Polish
        else if (lang.equals("pl")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Dostępna aktualizacja";
                case "subtitle_fmt": return "Wydanie %s";
                case "patches_fmt": return "Łatki: %s";
                case "download_btn": return emoji(0x1F4E5) + "  POBIERZ APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Aktualizacja przez Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Otwórz Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Importuj profil";
                case "remind_label": return "\u23F1  Przypomnij później:";
                case "chip_day": return "d";
                case "chip_1mo": return "1 mies.";
                case "skip_btn": return "\u23ED  Pomiń to wydanie";
                case "toast_snoozed": return "Przypomnienie odłożone o %s";
                case "toast_skipped": return "Wydanie %s pominięte";
                case "toast_install_obtainium": return "Zainstaluj Obtainium do aktualizacji";
            }
        }
        // Vietnamese
        else if (lang.equals("vi")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Có bản cập nhật mới";
                case "subtitle_fmt": return "Bản %s";
                case "patches_fmt": return "Bản vá: %s";
                case "download_btn": return emoji(0x1F4E5) + "  TẢI XUỐNG APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Cập nhật qua Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Mở Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Nhập cấu hình";
                case "remind_label": return "\u23F1  Nhắc tôi sau:";
                case "chip_day": return "ng";
                case "chip_1mo": return "1 tháng";
                case "skip_btn": return "\u23ED  Bỏ qua bản này";
                case "toast_snoozed": return "Đã hoãn nhắc nhở %s";
                case "toast_skipped": return "Đã bỏ qua bản %s";
                case "toast_install_obtainium": return "Cài đặt Obtainium để tự động cập nhật";
            }
        }
        // Indonesian
        else if (lang.equals("id")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Pembaruan Tersedia";
                case "subtitle_fmt": return "Rilis %s";
                case "patches_fmt": return "Tambalan: %s";
                case "download_btn": return emoji(0x1F4E5) + "  UNDUH APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  Pembaruan via Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " Buka Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " Impor Profil";
                case "remind_label": return "\u23F1  Ingatkan nanti:";
                case "chip_day": return "hr";
                case "chip_1mo": return "1 bulan";
                case "skip_btn": return "\u23ED  Lewati versi ini";
                case "toast_snoozed": return "Pengingat ditunda %s";
                case "toast_skipped": return "Versi %s dilewati";
                case "toast_install_obtainium": return "Pasang Obtainium untuk pembaruan";
            }
        }
        // Arabic
        else if (lang.equals("ar")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  تحديث متوفر";
                case "subtitle_fmt": return "الإصدار %s";
                case "patches_fmt": return "التصحيحات: %s";
                case "download_btn": return emoji(0x1F4E5) + "  تحميل APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  التحديث عبر Obtainium:";
                case "obtainium_open": return emoji(0x1F680) + " فتح Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " استيراد الملف";
                case "remind_label": return "\u23F1  تذكير لاحقاً:";
                case "chip_day": return "يوم";
                case "chip_1mo": return "شهر";
                case "skip_btn": return "\u23ED  تخطي هذا الإصدار";
                case "toast_snoozed": return "تم تأجيل التذكير لمدة %s";
                case "toast_skipped": return "تم تخطي الإصدار %s";
                case "toast_install_obtainium": return "قم بتثبيت Obtainium للتحديث التلقائي";
            }
        }
        // Chinese
        else if (lang.equals("zh")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  发现新版本";
                case "subtitle_fmt": return "构建 %s";
                case "patches_fmt": return "补丁版本: %s";
                case "download_btn": return emoji(0x1F4E5) + "  下载 APK";
                case "obtainium_title": return emoji(0x1F4E6) + "  通过 Obtainium 更新:";
                case "obtainium_open": return emoji(0x1F680) + " 打开 Obtainium";
                case "obtainium_import": return emoji(0x1F4F2) + " 导入配置";
                case "remind_label": return "\u23F1  稍后提醒:";
                case "chip_day": return "天";
                case "chip_1mo": return "1个月";
                case "skip_btn": return "\u23ED  跳过此版本";
                case "toast_snoozed": return "提醒已推迟 %s";
                case "toast_skipped": return "已跳过版本 %s";
                case "toast_install_obtainium": return "请安装 Obtainium 以自动更新";
            }
        }
        // Japanese
        else if (lang.equals("ja")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  アップデートがあります";
                case "subtitle_fmt": return "ビルド %s";
                case "patches_fmt": return "パッチ: %s";
                case "download_btn": return emoji(0x1F4E5) + "  APKをダウンロード";
                case "obtainium_title": return emoji(0x1F4E6) + "  Obtainiumで更新:";
                case "obtainium_open": return emoji(0x1F680) + " Obtainiumを開く";
                case "obtainium_import": return emoji(0x1F4F2) + " プロファイルをインポート";
                case "remind_label": return "\u23F1  後で通知:";
                case "chip_day": return "日";
                case "chip_1mo": return "1ヶ月";
                case "skip_btn": return "\u23ED  このビルドをスキップ";
                case "toast_snoozed": return "%s 後にリマインドします";
                case "toast_skipped": return "ビルド %s をスキップしました";
                case "toast_install_obtainium": return "自動更新にはObtainiumをインストールしてください";
            }
        }
        // Korean
        else if (lang.equals("ko")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  업데이트 가능";
                case "subtitle_fmt": return "빌드 %s";
                case "patches_fmt": return "패치: %s";
                case "download_btn": return emoji(0x1F4E5) + "  APK 다운로드";
                case "obtainium_title": return emoji(0x1F4E6) + "  Obtainium으로 업데이트:";
                case "obtainium_open": return emoji(0x1F680) + " Obtainium 열기";
                case "obtainium_import": return emoji(0x1F4F2) + " 프로필 가져오기";
                case "remind_label": return "\u23F1  나중에 알림:";
                case "chip_day": return "일";
                case "chip_1mo": return "1개월";
                case "skip_btn": return "\u23ED  이 빌드 건너뛰기";
                case "toast_snoozed": return "%s 후 다시 알립니다";
                case "toast_skipped": return "빌드 %s 건너뜀";
                case "toast_install_obtainium": return "자동 업데이트를 위해 Obtainium을 설치하세요";
            }
        }

        // English default
        switch (key) {
            case "title": return emoji(0x1F680) + "  Update Available";
            case "subtitle_fmt": return "Build %s";
            case "patches_fmt": return "Patches: %s";
            case "download_btn": return emoji(0x1F4E5) + "  DOWNLOAD APK";
            case "obtainium_title": return emoji(0x1F4E6) + "  Update via Obtainium:";
            case "obtainium_open": return emoji(0x1F680) + " Open Obtainium";
            case "obtainium_import": return emoji(0x1F4F2) + " Import Profile";
            case "remind_label": return "\u23F1  Remind me later:";
            case "chip_day": return "d";
            case "chip_1mo": return "1 month";
            case "skip_btn": return "\u23ED  Skip this build";
            case "toast_snoozed": return "Reminder snoozed for %s";
            case "toast_skipped": return "Build %s skipped";
            case "toast_install_obtainium": return "Install Obtainium for auto-updates";
            default: return key;
        }
    }
}
