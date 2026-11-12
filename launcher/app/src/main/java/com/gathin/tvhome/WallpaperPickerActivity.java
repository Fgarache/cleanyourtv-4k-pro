package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Interactive picker for Home wallpaper ("wallpaper") or Screensaver ("screensaver")
 * with category tabs at the top ("Todas" + each folder category), sequential category labels
 * ("Game 1", "Game 2", "Nature 1"...), a "Sin fondo" option, live full-screen preview,
 * and a prompt to apply in "En bucle" or "Modo rebote".
 */
public final class WallpaperPickerActivity extends Activity {

    static final String EXTRA_MODE = "mode";
    static final String MODE_WALLPAPER = "wallpaper";
    static final String MODE_SCREENSAVER = "screensaver";

    static final String PREF_WALLPAPER_MODE = "custom_wallpaper_mode";
    static final String PREF_CUSTOM_WALLPAPER = "custom_wallpaper_path";
    static final String PREF_WALLPAPER_CATEGORY = "custom_wallpaper_category";
    static final String PREF_WALLPAPER_INTERVAL = "custom_wallpaper_interval_min";
    static final String PREF_WALLPAPER_STYLE = "custom_wallpaper_play_style";

    static final String PREF_SCREENSAVER_MODE = "custom_screensaver_mode";
    static final String PREF_CUSTOM_SCREENSAVER = "custom_screensaver_path";
    static final String PREF_SCREENSAVER_CATEGORY = "custom_screensaver_category";
    static final String PREF_SCREENSAVER_INTERVAL = "custom_screensaver_interval_min";
    static final String PREF_SCREENSAVER_STYLE = "custom_screensaver_play_style";

    static final class MediaEntry {
        final File file;       // null for "Sin fondo"
        final String category; // e.g. "Game", "Nature"
        final String displayName; // e.g. "Game 1", "Nature 2"

        MediaEntry(File file, String category, String displayName) {
            this.file = file;
            this.category = category;
            this.displayName = displayName;
        }
    }

    private String mode = MODE_WALLPAPER;
    private VideoView previewVideo;
    private TextView previewBadge;
    private LinearLayout tabsContainer;
    private LinearLayout listContainer;
    private ScrollView listScroll;
    private final List<String> categories = new ArrayList<>();
    private final List<MediaEntry> allEntries = new ArrayList<>();
    private String selectedCategory = "ALL";
    private String currentPreviewPath = "";
    private float dp;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    static File mediaDir(Context context) {
        File ext = Config.dir(context);
        if (ext != null) {
            File m = new File(ext, "media");
            if (m.isDirectory()) return m;
        }
        File sd = new File("/sdcard/wallpaper");
        if (sd.isDirectory()) return sd;
        return ext != null ? new File(ext, "media") : sd;
    }

    static boolean isVideo(String name) {
        if (name == null) return false;
        String low = name.toLowerCase();
        return low.endsWith(".mp4") || low.endsWith(".webm") || low.endsWith(".mkv");
    }

    static boolean isImage(String name) {
        if (name == null) return false;
        String low = name.toLowerCase();
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp");
    }

    static List<String> listCategories(Context context) {
        List<String> result = new ArrayList<>();
        File dir = mediaDir(context);
        File[] children = dir != null ? dir.listFiles() : null;
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory() && !c.getName().startsWith(".")) {
                    result.add(c.getName());
                }
            }
        }
        Collections.sort(result, String::compareToIgnoreCase);
        return result;
    }

    static List<File> collectFilesForCategory(Context context, String category) {
        List<File> out = new ArrayList<>();
        File base = mediaDir(context);
        if (base == null || !base.isDirectory()) return out;
        if (category == null || "ALL".equalsIgnoreCase(category)) {
            for (String cat : listCategories(context)) {
                out.addAll(listFilesInFolder(new File(base, cat)));
            }
            if (out.isEmpty()) {
                out.addAll(listFilesInFolder(base));
            }
        } else {
            out.addAll(listFilesInFolder(new File(base, category)));
        }
        return out;
    }

    private static List<File> listFilesInFolder(File folder) {
        List<File> list = new ArrayList<>();
        File[] arr = folder != null ? folder.listFiles() : null;
        if (arr != null) {
            for (File f : arr) {
                if (f.isFile() && f.length() > 0 && (isVideo(f.getName()) || isImage(f.getName()))) {
                    list.add(f);
                }
            }
        }
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        if (m != null) mode = m;

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);

        dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0D0D11);

        previewVideo = new VideoView(this);
        previewVideo.setVisibility(View.GONE);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        root.addView(previewVideo, vlp);

        // Subtle left gradient scrim so category tabs and list remain readable without hiding the preview
        View scrim = new View(this);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x99000000, 0x40000000, 0x00000000});
        scrim.setBackground(gd);
        root.addView(scrim, new FrameLayout.LayoutParams(
                Math.round(380 * dp), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        LinearLayout mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(44 * dp);
        int padV = Math.round(28 * dp);
        mainCol.setPadding(padH, padV, padH, padV);

        // Title & Subtitle
        TextView title = new TextView(this);
        title.setText(MODE_SCREENSAVER.equals(mode) ? "Salvapantalla" : "Fondo del launcher");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        try { title.setTypeface(UiFont.get(this, R.string.font_medium)); } catch (Exception ignored) {}
        mainCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Pestañas arriba para filtrar por categoría · OK para elegir En bucle o Modo rebote");
        sub.setTextColor(0xB3FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(3 * dp);
        subLp.bottomMargin = Math.round(14 * dp);
        mainCol.addView(sub, subLp);

        // Category Tabs Row
        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsContainer = new LinearLayout(this);
        tabsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabsScroll.addView(tabsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.bottomMargin = Math.round(14 * dp);
        mainCol.addView(tabsScroll, tabsLp);

        // Scrollable list of items (compact width so rows hug the short category names)
        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mainCol.addView(listScroll, new LinearLayout.LayoutParams(
                Math.round(180 * dp), 0, 1f));

        root.addView(mainCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        // Bottom-right preview badge
        previewBadge = new TextView(this);
        previewBadge.setTextColor(Color.WHITE);
        previewBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        int bPadH = Math.round(16 * dp), bPadV = Math.round(8 * dp);
        previewBadge.setPadding(bPadH, bPadV, bPadH, bPadV);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xA6000000);
        badgeBg.setCornerRadius(18 * dp);
        badgeBg.setStroke(Math.round(dp), 0x40FFFFFF);
        previewBadge.setBackground(badgeBg);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        badgeLp.setMargins(0, 0, Math.round(44 * dp), Math.round(30 * dp));
        root.addView(previewBadge, badgeLp);

        setContentView(root);
        loadAllEntries();
        buildTabs();
        populateRows(true);
    }

    private void loadAllEntries() {
        categories.clear();
        allEntries.clear();
        categories.addAll(listCategories(this));
        File base = mediaDir(this);
        for (String cat : categories) {
            List<File> files = listFilesInFolder(new File(base, cat));
            for (int i = 0; i < files.size(); i++) {
                allEntries.add(new MediaEntry(files.get(i), cat, cat + " " + (i + 1)));
            }
        }
    }

    private void buildTabs() {
        tabsContainer.removeAllViews();
        List<String> tabKeys = new ArrayList<>();
        tabKeys.add("ALL");
        tabKeys.addAll(categories);

        for (int i = 0; i < tabKeys.size(); i++) {
            final String key = tabKeys.get(i);
            final String label = "ALL".equals(key) ? "Todas" : key;
            TextView tab = new TextView(this);
            tab.setId(View.generateViewId());
            tab.setTag(key);
            tab.setText(label);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tab.setSingleLine(true);
            tab.setGravity(Gravity.CENTER);
            int padH = Math.round(16 * dp);
            tab.setPadding(padH, 0, padH, 0);
            tab.setFocusable(true);
            tab.setClickable(true);
            styleTab(tab, key.equals(selectedCategory), false);

            tab.setOnFocusChangeListener((v, hasFocus) -> {
                styleTab(tab, key.equals(selectedCategory), hasFocus);
                if (hasFocus && !key.equals(selectedCategory)) {
                    selectedCategory = key;
                    refreshAllTabStyles();
                    populateRows(false);
                }
            });
            tab.setOnClickListener(v -> {
                selectedCategory = key;
                refreshAllTabStyles();
                populateRows(false);
                if (listContainer.getChildCount() > 0) {
                    listContainer.getChildAt(0).requestFocus();
                }
            });
            tab.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (listContainer.getChildCount() > 0) {
                        listContainer.getChildAt(0).requestFocus();
                        return true;
                    }
                }
                return false;
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * dp));
            lp.rightMargin = Math.round(8 * dp);
            tabsContainer.addView(tab, lp);
        }
    }

    private void refreshAllTabStyles() {
        for (int i = 0; i < tabsContainer.getChildCount(); i++) {
            TextView t = (TextView) tabsContainer.getChildAt(i);
            String k = (String) t.getTag();
            styleTab(t, k.equals(selectedCategory), t.hasFocus());
        }
    }

    private void styleTab(TextView tab, boolean active, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * dp);
        if (focused) {
            bg.setColor(0xFFFFFFFF);
            tab.setTextColor(0xFF111114);
        } else if (active) {
            bg.setColor(0x40FFFFFF);
            bg.setStroke(Math.round(dp), 0x99FFFFFF);
            tab.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x1AFFFFFF);
            bg.setStroke(Math.round(dp), 0x26FFFFFF);
            tab.setTextColor(0xB3FFFFFF);
        }
        tab.setBackground(bg);
    }

    private void styleRow(TextView row, boolean selected, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * dp);
        if (focused) {
            bg.setColor(0x40FFFFFF);
            bg.setStroke(Math.round(1.5f * dp), 0xE6FFFFFF);
            row.setTextColor(0xFFFFFFFF);
            row.setShadowLayer(4 * dp, 0, dp, 0xCC000000);
        } else if (selected) {
            bg.setColor(0x28FFFFFF);
            bg.setStroke(Math.round(dp), 0x80FFFFFF);
            row.setTextColor(0xFFFFFFFF);
            row.setShadowLayer(4 * dp, 0, dp, 0xCC000000);
        } else {
            bg.setColor(0x18FFFFFF);
            bg.setStroke(Math.round(dp), 0x30FFFFFF);
            row.setTextColor(0xD9FFFFFF);
            row.setShadowLayer(4 * dp, 0, dp, 0xCC000000);
        }
        row.setBackground(bg);
    }

    private void populateRows(boolean focusInitial) {
        listContainer.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        boolean isScreensaver = MODE_SCREENSAVER.equals(mode);
        String modeKey = isScreensaver ? PREF_SCREENSAVER_MODE : PREF_WALLPAPER_MODE;
        String pathKey = isScreensaver ? PREF_CUSTOM_SCREENSAVER : PREF_CUSTOM_WALLPAPER;
        String currentMode = prefs.getString(modeKey, SeamlessVideoController.MODE_SINGLE);
        String activePath = prefs.getString(pathKey, "");

        List<MediaEntry> filtered = new ArrayList<>();
        if (!isScreensaver && "ALL".equals(selectedCategory)) {
            filtered.add(new MediaEntry(null, "NONE", "Sin fondo"));
        }
        for (MediaEntry e : allEntries) {
            if ("ALL".equals(selectedCategory) || e.category.equalsIgnoreCase(selectedCategory)) {
                filtered.add(e);
            }
        }

        View toFocus = null;
        for (int i = 0; i < filtered.size(); i++) {
            final MediaEntry entry = filtered.get(i);
            final int index = i;
            TextView row = new TextView(this);
            boolean isNone = entry.file == null;
            boolean isSelected = (isNone && SeamlessVideoController.MODE_NONE.equals(currentMode))
                    || (!isNone && SeamlessVideoController.MODE_SINGLE.equals(currentMode)
                    && entry.file.getAbsolutePath().equals(activePath));

            String label = (isNone ? "🚫  " : "🎬  ") + entry.displayName + (isSelected ? "  ✓" : "");
            row.setText(label);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            row.setTextColor(Color.WHITE);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = Math.round(14 * dp);
            row.setPadding(padH, 0, padH, 0);
            styleRow(row, isSelected, false);
            row.setFocusable(true);
            row.setClickable(true);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(38 * dp));
            lp.bottomMargin = Math.round(6 * dp);

            final boolean rowSelected = isSelected;
            row.setOnFocusChangeListener((v, hasFocus) -> {
                styleRow(row, rowSelected, hasFocus);
                if (hasFocus) showPreview(entry);
            });
            row.setOnClickListener(v -> {
                if (entry.file == null) {
                    applyNoWallpaper();
                } else {
                    promptPlayStyleAndApply(entry);
                }
            });
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && index == 0) {
                    focusActiveTab();
                    return true;
                }
                return false;
            });

            listContainer.addView(row, lp);
            if (isSelected || toFocus == null) {
                toFocus = row;
            }
        }

        listScroll.scrollTo(0, 0);
        if (focusInitial && toFocus != null) {
            final View first = toFocus;
            first.post(first::requestFocus);
        }
    }

    private void focusActiveTab() {
        for (int i = 0; i < tabsContainer.getChildCount(); i++) {
            View t = tabsContainer.getChildAt(i);
            if (selectedCategory.equals(t.getTag())) {
                t.requestFocus();
                return;
            }
        }
        if (tabsContainer.getChildCount() > 0) {
            tabsContainer.getChildAt(0).requestFocus();
        }
    }

    private void showPreview(MediaEntry entry) {
        if (entry.file == null) {
            currentPreviewPath = "NONE";
            previewVideo.stopPlayback();
            previewVideo.setVisibility(View.GONE);
            previewBadge.setText("Sin fondo  ·  Pulsa OK para dejar el launcher con fondo oscuro limpio");
            return;
        }
        String path = entry.file.getAbsolutePath();
        if (path.equals(currentPreviewPath)) return;
        currentPreviewPath = path;
        previewBadge.setText("Vista previa: " + entry.displayName + "  ·  Pulsa OK para aplicar");
        previewVideo.setVisibility(View.VISIBLE);
        previewVideo.setVideoPath(path);
        previewVideo.setOnPreparedListener(mp -> {
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            previewVideo.start();
        });
        previewVideo.setOnErrorListener((mp, what, extra) -> true);
    }

    private void applyNoWallpaper() {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1;
        prefs.edit()
                .putString(PREF_WALLPAPER_MODE, SeamlessVideoController.MODE_NONE)
                .putString(PREF_CUSTOM_WALLPAPER, "")
                .putLong(Config.SETTINGS_REVISION_KEY, rev)
                .commit();
        Toast.makeText(this, "Fondo desactivado (Sin fondo)", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void promptPlayStyleAndApply(MediaEntry entry) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * dp);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF218181C);
        bg.setCornerRadius(20 * dp);
        bg.setStroke(Math.round(dp), 0x38FFFFFF);
        box.setBackground(bg);

        TextView heading = new TextView(this);
        heading.setText("¿Cómo deseas aplicar " + entry.displayName + "?");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        try { heading.setTypeface(UiFont.get(this, R.string.font_medium)); } catch (Exception ignored) {}
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = Math.round(16 * dp);
        box.addView(heading, hLp);

        View btnLoop = makeDialogOption("🔁  En bucle (repite de inicio a fin)", () -> {
            dialog.dismiss();
            saveSingleSelection(entry, SeamlessVideoController.STYLE_LOOP);
        });
        View btnBounce = makeDialogOption("🪃  Modo rebote (avanza y retrocede suave)", () -> {
            dialog.dismiss();
            saveSingleSelection(entry, SeamlessVideoController.STYLE_BOUNCE);
        });

        box.addView(btnLoop, new LinearLayout.LayoutParams(
                Math.round(340 * dp), Math.round(46 * dp)));
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(
                Math.round(340 * dp), Math.round(46 * dp));
        b2Lp.topMargin = Math.round(8 * dp);
        box.addView(btnBounce, b2Lp);

        dialog.setContentView(box);
        dialog.show();
        btnBounce.requestFocus();
    }

    private View makeDialogOption(String text, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(18 * dp);
        tv.setPadding(padH, 0, padH, 0);
        tv.setBackgroundResource(R.drawable.settings_choice_bg);
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private void saveSingleSelection(MediaEntry entry, String style) {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        boolean isScreensaver = MODE_SCREENSAVER.equals(mode);
        String modeKey = isScreensaver ? PREF_SCREENSAVER_MODE : PREF_WALLPAPER_MODE;
        String pathKey = isScreensaver ? PREF_CUSTOM_SCREENSAVER : PREF_CUSTOM_WALLPAPER;
        String styleKey = isScreensaver ? PREF_SCREENSAVER_STYLE : PREF_WALLPAPER_STYLE;

        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1;
        prefs.edit()
                .putString(modeKey, SeamlessVideoController.MODE_SINGLE)
                .putString(pathKey, entry.file.getAbsolutePath())
                .putString(styleKey, style)
                .putLong(Config.SETTINGS_REVISION_KEY, rev)
                .commit();

        String styleName = SeamlessVideoController.STYLE_BOUNCE.equals(style) ? "Modo rebote" : "En bucle";
        Toast.makeText(this,
                (isScreensaver ? "Salvapantalla aplicado: " : "Fondo aplicado: ")
                        + entry.displayName + " (" + styleName + ")",
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void onPause() {
        if (previewVideo != null && previewVideo.isPlaying()) {
            previewVideo.stopPlayback();
        }
        super.onPause();
    }
}
