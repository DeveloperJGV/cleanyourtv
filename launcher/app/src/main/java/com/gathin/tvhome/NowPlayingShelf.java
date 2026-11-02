package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * "Sonando ahora" en grande sobre el dock: portada, canción, artista, app y progreso, en el color de la portada.
 * Solo aparece mientras algo suena (o está en pausa con algo cargado) y esconde el nombre grande de la app.
 * Se enciende por tema con "now_playing": "hero" o "both" en config.json (apagado si no está).
 */
final class NowPlayingShelf {
    private static final String KEY = "now_playing";

    private final Activity activity;
    private final FrameLayout root;
    private final View hero;
    private final View header;
    private final Handler handler;
    private final float density;
    private final boolean animate;
    private final NowPlaying source;

    private LinearLayout panel;
    private ImageView cover;
    private TextView kicker;
    private TextView title;
    private TextView artist;
    private View fill;
    private View track;
    private TextView time;

    private boolean shown;
    private boolean running;
    private boolean dockFocused = true;
    private int heroVisibility = -1;
    private float dockTop = -1;
    private Bitmap lastCover;
    private int accent = Color.WHITE;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            updateProgress();
            if (source.playing()) handler.postDelayed(this, 1000);
        }
    };

    private NowPlayingShelf(Activity activity, FrameLayout root, View hero, View header, Handler handler,
                            float density, boolean animate) {
        this.activity = activity;
        this.root = root;
        this.hero = hero;
        this.header = header;
        this.handler = handler;
        this.density = density;
        this.animate = animate;
        this.source = new NowPlaying(activity, handler, this::update);
    }

    /** Null when the active theme does not ask for the big Now playing. */
    static NowPlayingShelf create(Activity activity, FrameLayout root, View hero, View header, Handler handler,
                                  float density, boolean animate) {
        String mode = mode(activity);
        if (!"hero".equals(mode) && !"both".equals(mode)) return null;
        return new NowPlayingShelf(activity, root, hero, header, handler, density, animate);
    }

    /** "off", "hero", "dock" or "both", from the active theme's config.json. */
    static String mode(Activity activity) {
        File dir = Config.dir(activity);
        File file = dir == null ? null : new File(dir, Config.FILE_NAME);
        if (file == null || !file.isFile()) return "off";
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int n = 0;
            while (n < data.length) {
                int r = in.read(data, n, data.length - n);
                if (r < 0) break;
                n += r;
            }
            return new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8))
                    .optString(KEY, "off").trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "off";
        }
    }

    void start() {
        running = true;
        source.start();   // calls update() once with what is playing now
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
        source.stop();
    }

    /** Where the dock's top edge is, in root coordinates; the panel sits in the free space above it. */
    void place(float dockTopInRoot) {
        if (Math.abs(dockTopInRoot - dockTop) < 1) return;
        dockTop = dockTopInRoot;
        if (shown) layoutPanel();
    }

    private void update() {
        if (!running) return;
        if (source.active() && dockFocused) show();
        else hide();
    }

    /**
     * Focus on the dock (true) or on the rows below it (false): the grid scrolls up there and the panel would sit
     * over the apps, so it steps aside until the dock is back.
     */
    void dockFocused(boolean onDock) {
        if (onDock == dockFocused) return;
        dockFocused = onDock;
        update();
    }

    /** The wallpaper's caption shares the top right with the panel: hide it while the song is on screen. */
    private void captionHidden(boolean hidden) {
        View caption = root.findViewWithTag(WallpaperPlayer.CAPTION_TAG);
        if (caption != null) caption.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    private void show() {
        build();
        title.setText(cleanTitle(source.title()));
        String who = source.artist();
        artist.setText(who != null ? who : "");
        kicker.setText(activity.getString(R.string.now_playing_kicker, appLabel(source.packageName()))
                .toUpperCase(Locale.getDefault()));

        Bitmap art = source.cover();
        if (art != lastCover) {
            lastCover = art;
            cover.setImageBitmap(art);
            accent = art != null ? accentOf(art) : Color.WHITE;
            kicker.setTextColor(accent);
            ((GradientDrawable) fill.getBackground()).setColor(accent);
        }
        if (panel.getVisibility() != View.GONE) panel.post(this::layoutPanel);
        updateProgress();
        handler.removeCallbacks(tick);
        if (source.playing()) handler.postDelayed(tick, 1000);

        if (!shown) {
            shown = true;
            if (heroVisibility < 0) heroVisibility = hero.getVisibility();
            if (heroVisibility == View.VISIBLE) hero.setVisibility(View.INVISIBLE);
            captionHidden(true);
            panel.setVisibility(View.VISIBLE);
            // After the next layout pass: header and dock have their real positions by then.
            panel.post(this::layoutPanel);
            if (animate) {
                panel.setAlpha(0f);
                panel.setTranslationX(-16 * density);
                panel.animate().alpha(1f).translationX(0f).setDuration(320).start();
            } else {
                panel.setAlpha(1f);
            }
        }
    }

    private void hide() {
        handler.removeCallbacks(tick);
        if (!shown) return;
        shown = false;
        if (heroVisibility == View.VISIBLE) hero.setVisibility(View.VISIBLE);
        captionHidden(false);
        if (animate) {
            panel.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                if (!shown) panel.setVisibility(View.GONE);
            }).start();
        } else {
            panel.setVisibility(View.GONE);
        }
    }

    private void updateProgress() {
        if (panel == null) return;
        long total = source.totalSeconds();
        long done = source.elapsedSeconds();
        boolean known = total > 0 && done >= 0;
        track.setVisibility(known ? View.VISIBLE : View.GONE);
        time.setVisibility(known ? View.VISIBLE : View.GONE);
        if (!known) return;
        float f = Math.min(1f, done / (float) total);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        int w = Math.round(track.getLayoutParams().width * f);
        if (lp.width != w) {
            lp.width = w;
            fill.setLayoutParams(lp);
        }
        time.setText((source.playing() ? "" : "❚❚  ") + clock(done) + " / " + clock(total));
    }

    /**
     * Fits the panel between the clock block and the dock: the cover shrinks with the space, below ~110dp the
     * panel goes compact (one line, no cover), and with no room at all it stays hidden rather than cover the dock.
     */
    private void layoutPanel() {
        if (!shown) return;
        int[] at = new int[2];
        int[] rootAt = new int[2];
        root.getLocationInWindow(rootAt);
        header.getLocationInWindow(at);
        float top = at[1] - rootAt[1] + header.getHeight() + 14 * density;
        // The dock's glass band starts ~20dp above its tiles; keep a clear gap above the band.
        float bottom = (dockTop > 0 ? dockTop : gridTop(rootAt)) - 34 * density;
        float room = bottom - top;
        // From biggest to smallest; the first size that fits wins. Compact (one line, no cover) is the last try.
        float[] titles = {46f, 40f, 34f, 30f, 26f, 24f};
        float h = Float.MAX_VALUE;
        for (int step = 0; step <= titles.length && h > room; step++) {
            boolean compact = step == titles.length;
            float big = compact ? 24f : titles[step];
            int pad = compact ? dp(10) : step < 2 ? dp(18) : dp(14);
            panel.setPadding(pad, pad, dp(compact ? 24 : 32), pad);
            cover.setVisibility(compact || lastCover == null ? View.GONE : View.VISIBLE);
            artist.setVisibility(compact || TextUtils.isEmpty(artist.getText()) ? View.GONE : View.VISIBLE);
            kicker.setVisibility(compact || step >= 4 ? View.GONE : View.VISIBLE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, big);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(16f, big * 0.5f));
            // The cover first takes the text's height, then grows up to 200dp if there is room left.
            int side = 0;
            if (cover.getVisibility() == View.VISIBLE) {
                cover.setVisibility(View.GONE);
                measurePanel(0, pad);
                int textH = panel.getMeasuredHeight() - 2 * pad;
                cover.setVisibility(View.VISIBLE);
                side = (int) Math.max(textH, Math.min(200 * density, room - 2 * pad));
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cover.getLayoutParams();
                if (lp.width != side) {
                    lp.width = side;
                    lp.height = side;
                    cover.setLayoutParams(lp);
                }
            }
            measurePanel(side, pad);
            h = panel.getMeasuredHeight();
        }
        if (h > room + 8 * density) {
            panel.setVisibility(View.INVISIBLE);   // no room in this theme: never cover the dock
            return;
        }
        panel.setVisibility(View.VISIBLE);
        panel.setY(bottom - h);   // sits just above the dock
    }

    /**
     * Measures the panel so it ends before ~68% of the screen (the painting caption lives on the right),
     * with the progress bar as wide as the text column.
     */
    private void measurePanel(int side, int pad) {
        int left = (int) panel.getX();
        int maxText = (int) (root.getWidth() * 0.68f) - left - (side > 0 ? side + dp(28) : 0) - dp(32) - pad;
        title.setMaxWidth(maxText);
        artist.setMaxWidth(maxText);
        ViewGroup.LayoutParams bar = track.getLayoutParams();
        int barWidth = Math.max(dp(160), maxText - dp(140));
        if (bar.width != barWidth) {
            bar.width = barWidth;
            track.setLayoutParams(bar);
            updateProgress();
        }
        panel.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    }

    /** "Song - 2004 Remaster" / "Song (Remastered 2011)" -> "Song": the extra words only cost room on a TV. */
    static String cleanTitle(String t) {
        if (t == null) return null;
        String c = t.replaceAll("(?i)\\s+-\\s+[^-]*\\bremaster(ed)?\\b.*$", "")
                .replaceAll("(?i)\\s*[(\\[][^)\\]]*\\bremaster(ed)?\\b[^)\\]]*[)\\]]", "").trim();
        return c.isEmpty() ? t : c;
    }

    /** The grid's first row (the dock) in root coordinates, before any focus has told us where it is. */
    private float gridTop(int[] rootAt) {
        android.view.ViewGroup grid = activity.findViewById(R.id.grid);
        if (grid == null || grid.getChildCount() == 0) return root.getHeight() * 0.5f;
        // The top of the highest tile on screen: the dock row, with its band drawn a few dp above it.
        float best = Float.MAX_VALUE;
        int[] at = new int[2];
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            child.getLocationInWindow(at);
            best = Math.min(best, at[1] - rootAt[1]);
        }
        return best - 14 * density;
    }

    private void build() {
        if (panel != null) return;
        int pad = dp(18);
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(pad, pad, dp(32), pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(28));
        panel.setBackground(bg);
        panel.setVisibility(View.GONE);
        panel.setFocusable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        cover = new ImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        cover.setClipToOutline(true);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(160), dp(160));
        coverLp.setMarginEnd(dp(28));
        panel.addView(cover, coverLp);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        panel.addView(text, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        kicker = label(14, 0xFFFFFFFF);
        kicker.setLetterSpacing(0.22f);
        text.addView(kicker);

        title = label(46, 0xFFFFFFFF);
        title.setLetterSpacing(-0.02f);
        title.setMaxWidth(dp(820));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(6);
        text.addView(title, titleLp);

        artist = label(23, 0xCCFFFFFF);
        artist.setMaxWidth(dp(820));
        text.addView(artist);

        LinearLayout progress = new LinearLayout(activity);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        progressLp.topMargin = dp(14);
        text.addView(progress, progressLp);

        FrameLayout bar = new FrameLayout(activity);
        track = bar;
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x40FFFFFF);
        trackBg.setCornerRadius(dp(3));
        bar.setBackground(trackBg);
        fill = new View(activity);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(Color.WHITE);
        fillBg.setCornerRadius(dp(3));
        fill.setBackground(fillBg);
        bar.addView(fill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(360), dp(5));
        barLp.setMarginEnd(dp(16));
        progress.addView(bar, barLp);

        time = label(14, 0xB3FFFFFF);
        progress.addView(time);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMarginStart(dp(68));
        // Above the wallpaper and the glow, below the glass highlight and the dock.
        int index = Math.max(0, root.indexOfChild(hero) + 1);
        root.addView(panel, index, lp);
    }

    private TextView label(float sp, int color) {
        TextView t = new TextView(activity);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setTypeface(activity.getResources().getFont(R.font.outfit_light));
        t.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        return t;
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "";
        try {
            PackageManager pm = activity.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The cover's average colour, lifted so it reads as an accent on a dark panel. */
    static int accentOf(Bitmap art) {
        Bitmap small = Bitmap.createScaledBitmap(art, 12, 12, true);
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                r += Color.red(c);
                g += Color.green(c);
                b += Color.blue(c);
                n++;
            }
        }
        if (small != art) small.recycle();
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
        hsv[1] = hsv[1] < 0.12f ? hsv[1] : Math.max(0.45f, hsv[1]);
        hsv[2] = Math.max(0.88f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static String clock(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
