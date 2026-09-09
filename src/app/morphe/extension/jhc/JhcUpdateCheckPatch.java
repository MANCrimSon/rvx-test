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
    // 0 guarantees that any release (e.g. 10, 11) is detected as an update for testing
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

            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> showDialog((Activity) context, finalTag, finalVer, finalUrl));
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

    private static int parseNumericTag(String tag) {
        try {
            String clean = tag.replaceAll("[^0-9]", "");
            return Integer.parseInt(clean);
        } catch (Exception e) {
            return 0;
        }
    }

    // --- UI DIALOG (Full-width AMOLED Bottom Sheet) ---
    private static void showDialog(Activity activity, String tag, String version, String downloadUrl) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        try {
            // Use Theme_Translucent_NoTitleBar to avoid floating dialog sizing bugs
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

            // Drag handle
            View handle = new View(activity);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(40, density), dp(4, density));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(16, density);
            handle.setLayoutParams(handleLp);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(Color.parseColor("#48484A"));
            handleBg.setCornerRadius(dp(2, density));
            handle.setBackground(handleBg);
            sheet.addView(handle);

            // Title
            TextView titleView = new TextView(activity);
            titleView.setText(getString("title"));
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(20);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            sheet.addView(titleView);

            // Subtitle
            TextView subView = new TextView(activity);
            String verInfo = version.isEmpty() ? "" : " • YouTube " + version;
            subView.setText(String.format(getString("subtitle_fmt"), tag) + verInfo);
            subView.setTextColor(Color.parseColor("#8E8E93"));
            subView.setTextSize(14);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(4, density);
            subLp.bottomMargin = dp(18, density);
            subView.setLayoutParams(subLp);
            sheet.addView(subView);

            // Main Download Button
            TextView downloadBtn = createButton(activity, getString("download_btn"), Color.parseColor("#3EA6FF"), Color.BLACK, density);
            downloadBtn.setOnClickListener(v -> {
                dialog.dismiss();
                openUrl(activity, downloadUrl);
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_LAST_REMOTE_TAG, tag).apply();
            });
            sheet.addView(downloadBtn);

            // Snooze section title
            TextView snoozeLabel = new TextView(activity);
            snoozeLabel.setText(getString("remind_label"));
            snoozeLabel.setTextColor(Color.parseColor("#8E8E93"));
            snoozeLabel.setTextSize(12);
            LinearLayout.LayoutParams snoozeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            snoozeLp.topMargin = dp(16, density);
            snoozeLp.bottomMargin = dp(8, density);
            snoozeLabel.setLayoutParams(snoozeLp);
            sheet.addView(snoozeLabel);

            // Snooze Chips Horizontal Row (1d, 3d, 7d, 14d, 1mo)
            HorizontalScrollView chipsScroll = new HorizontalScrollView(activity);
            chipsScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
            skipBtn.setPadding(0, dp(12, density), 0, dp(12, density));
            skipBtn.setOnClickListener(v -> {
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_SKIPPED_TAG, tag).apply();
                dialog.dismiss();
                showToast(activity, String.format(getString("toast_skipped"), tag));
            });
            sheet.addView(skipBtn);

            // Divider
            View divider = new View(activity);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1, density));
            divLp.topMargin = dp(4, density);
            divLp.bottomMargin = dp(14, density);
            divider.setLayoutParams(divLp);
            divider.setBackgroundColor(Color.parseColor("#2C2C2E"));
            sheet.addView(divider);

            // Obtainium section header
            TextView obtainiumTitle = new TextView(activity);
            obtainiumTitle.setText(getString("obtainium_title"));
            obtainiumTitle.setTextColor(Color.parseColor("#8E8E93"));
            obtainiumTitle.setTextSize(12);
            sheet.addView(obtainiumTitle);

            // Obtainium actions row: [ ⏸ Не напоминать (1 мес) ] [ 📲 Импорт профиля ]
            LinearLayout obtainiumRow = new LinearLayout(activity);
            obtainiumRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            obLp.topMargin = dp(8, density);
            obtainiumRow.setLayoutParams(obLp);

            // Left: Hide for 1 month
            TextView hideBtn = createSubButton(activity, getString("obtainium_hide"), density);
            LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
            hideLp.rightMargin = dp(6, density);
            hideBtn.setLayoutParams(hideLp);
            hideBtn.setOnClickListener(v -> {
                snooze(activity, 30);
                dialog.dismiss();
                showToast(activity, getString("toast_obtainium"));
            });
            obtainiumRow.addView(hideBtn);

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

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48, density));
        tv.setLayoutParams(lp);
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

    // --- MULTILINGUAL DICTIONARY ---
    private static String getString(String key) {
        String lang = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);

        if (lang.equals("ru") || lang.equals("uk") || lang.equals("be") || lang.equals("kk")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Доступно обновление";
                case "subtitle_fmt": return "Сборка %s";
                case "download_btn": return emoji(0x1F4E5) + "  СКАЧАТЬ APK";
                case "remind_label": return "\u23F1  Напомнить через:";
                case "chip_day": return "дн";
                case "chip_1mo": return "1 месяц";
                case "skip_btn": return "\u23ED  Пропустить этот билд";
                case "obtainium_title": return emoji(0x1F4E6) + "  Обновляетесь через Obtainium?";
                case "obtainium_hide": return "\u23F8 Не напоминать (1 мес)";
                case "obtainium_import": return emoji(0x1F4F2) + " Импорт профиля";
                case "toast_snoozed": return "Напоминание отложено на %s";
                case "toast_skipped": return "Билд %s пропущен";
                case "toast_obtainium": return "Уведомления скрыты на 1 месяц";
                case "toast_install_obtainium": return "Установите Obtainium для обновлений";
            }
        } else if (lang.equals("es")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Actualización disponible";
                case "subtitle_fmt": return "Versión %s";
                case "download_btn": return emoji(0x1F4E5) + "  DESCARGAR APK";
                case "remind_label": return "\u23F1  Recordar en:";
                case "chip_day": return "d";
                case "chip_1mo": return "1 mes";
                case "skip_btn": return "\u23ED  Omitir esta versión";
                case "obtainium_title": return emoji(0x1F4E6) + "  ¿Actualizas con Obtainium?";
                case "obtainium_hide": return "\u23F8 No recordar (1 mes)";
                case "obtainium_import": return emoji(0x1F4F2) + " Importar perfil";
                case "toast_snoozed": return "Recordatorio pospuesto por %s";
                case "toast_skipped": return "Versión %s omitida";
                case "toast_obtainium": return "Notificaciones pausadas por 1 mes";
                case "toast_install_obtainium": return "Instala Obtainium para actualizaciones";
            }
        } else if (lang.equals("pt")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Atualização disponível";
                case "subtitle_fmt": return "Versão %s";
                case "download_btn": return emoji(0x1F4E5) + "  BAIXAR APK";
                case "remind_label": return "\u23F1  Lembrar em:";
                case "chip_day": return "d";
                case "chip_1mo": return "1 mês";
                case "skip_btn": return "\u23ED  Pular esta versão";
                case "obtainium_title": return emoji(0x1F4E6) + "  Atualizando via Obtainium?";
                case "obtainium_hide": return "\u23F8 Não lembrar (1 mês)";
                case "obtainium_import": return emoji(0x1F4F2) + " Importar perfil";
                case "toast_snoozed": return "Lembrete adiado por %s";
                case "toast_skipped": return "Versão %s pulada";
                case "toast_obtainium": return "Notificações pausadas por 1 mês";
                case "toast_install_obtainium": return "Instale o Obtainium para atualizações";
            }
        } else if (lang.equals("de")) {
            switch (key) {
                case "title": return emoji(0x1F680) + "  Update verfügbar";
                case "subtitle_fmt": return "Build %s";
                case "download_btn": return emoji(0x1F4E5) + "  APK HERUNTERLADEN";
                case "remind_label": return "\u23F1  Erinnern in:";
                case "chip_day": return "T";
                case "chip_1mo": return "1 Monat";
                case "skip_btn": return "\u23ED  Diesen Build überspringen";
                case "obtainium_title": return emoji(0x1F4E6) + "  Aktualisierung über Obtainium?";
                case "obtainium_hide": return "\u23F8 Nicht erinnern (1 Monat)";
                case "obtainium_import": return emoji(0x1F4F2) + " Profil importieren";
                case "toast_snoozed": return "Erinnerung verschoben um %s";
                case "toast_skipped": return "Build %s übersprungen";
                case "toast_obtainium": return "Benachrichtigungen für 1 Monat stumm";
                case "toast_install_obtainium": return "Installiere Obtainium für Updates";
            }
        }

        // English default
        switch (key) {
            case "title": return emoji(0x1F680) + "  Update Available";
            case "subtitle_fmt": return "Build %s";
            case "download_btn": return emoji(0x1F4E5) + "  DOWNLOAD APK";
            case "remind_label": return "\u23F1  Remind me in:";
            case "chip_day": return "d";
            case "chip_1mo": return "1 month";
            case "skip_btn": return "\u23ED  Skip this build";
            case "obtainium_title": return emoji(0x1F4E6) + "  Updating via Obtainium?";
            case "obtainium_hide": return "\u23F8 Snooze for 1 mo";
            case "obtainium_import": return emoji(0x1F4F2) + " Import profile";
            case "toast_snoozed": return "Reminder snoozed for %s";
            case "toast_skipped": return "Build %s skipped";
            case "toast_obtainium": return "Notifications muted for 1 month";
            case "toast_install_obtainium": return "Install Obtainium for auto-updates";
            default: return key;
        }
    }
}
