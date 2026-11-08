package local.tabhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Домашний экран для планшета: фото природы на всю площадь (без системных полос),
 * большие часы, плавающий док с нарисованными иконками и кнопка-замок, которая по PIN
 * открывает расчищенный рабочий стол HyperOS. Сам этот экран остаётся главным:
 * кнопка Home всегда возвращает сюда.
 */
public class HomeActivity extends Activity {

    /** Один значок в доке. Чтобы поменять набор приложений - правьте список APPS ниже. */
    private static final class App {
        final String label, pkg, kind;
        final int colorTop, colorBottom;
        App(String label, String pkg, String kind, int colorTop, int colorBottom) {
            this.label = label; this.pkg = pkg; this.kind = kind;
            this.colorTop = colorTop; this.colorBottom = colorBottom;
        }
    }

    private static final App[] APPS = {
            new App("Иви", "ru.ivi.client", "play", 0xFFFF4D8D, 0xFF7A3CFF),
            new App("Кинопоиск", "ru.kinopoisk", "clapper", 0xFFFFA23A, 0xFFFF4B2B),
            new App("Chrome", "com.android.chrome", "globe", 0xFF2F8BFF, 0xFF22D3EE),
    };

    private static final long WALLPAPER_MS = 20L * 60L * 1000L;
    private static final String PREFS = "tabhome";
    private static final String KEY_PIN = "pin";

    private static final int MODE_ENTER = 0;
    private static final int MODE_SET = 1;
    private static final int MODE_CONFIRM = 2;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private ImageView wallBack, wallFront;
    private boolean firstWall = true;
    private ObjectAnimator kenBurns;

    private TextView greeting, clock, date;
    private View accent, lockButton;
    private final List<View> dockItems = new ArrayList<View>();
    private PinOverlay overlay;

    private boolean needsIntro = true;
    private boolean resumedNow = false;

    private int pinMode = MODE_ENTER;
    private String pendingPin = null;
    private int failedAttempts = 0;
    private long lockedUntil = 0;

    private final Runnable rotate = new Runnable() {
        @Override public void run() { nextWallpaper(); }
    };
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setupWindow();
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumedNow = true;
        hideSystemUi();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        updateClock();
        refreshInstalled();
        if (wallIndex < 0) nextWallpaper(); else { schedule(WALLPAPER_MS); startKenBurns(); }
        if (needsIntro) { needsIntro = false; playIntro(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumedNow = false;
        handler.removeCallbacks(rotate);
        if (kenBurns != null) kenBurns.cancel();
        try { unregisterReceiver(tick); } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onStop() {
        super.onStop();
        needsIntro = true; // при следующем показе экран снова красиво "собирается"
        if (overlay != null && overlay.isOpen()) closePin();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // нажали Home, находясь на этом экране: закрыть PIN, если он был открыт
        if (overlay != null && overlay.isOpen()) closePin();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null && overlay.isOpen()) closePin();
        // на главном экране Back больше ничего не делает
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (overlay != null && overlay.isOpen()) {
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                overlay.addDigit((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) { overlay.backspace(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ окно без рамок

    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
    }

    private void hideSystemUi() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ------------------------------------------------------------------ интерфейс

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        wallBack = wallLayer();
        wallFront = wallLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        try { wallpapers = getAssets().list("wallpapers"); } catch (java.io.IOException ignored) { }

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x22000000, 0x00000000, 0x4D000000, 0xB8000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // ---- приветствие, часы, дата ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        hlp.setMargins(dp(56), dp(56), 0, 0);
        root.addView(header, hlp);

        greeting = text(14, "sans-serif-medium", 0xE6FFFFFF);
        greeting.setLetterSpacing(0.28f);
        shadow(greeting);
        header.addView(greeting);

        accent = new View(this);
        GradientDrawable ad = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF8AB4FF, 0xFFB78CFF});
        ad.setCornerRadius(dp(2));
        accent.setBackground(ad);
        accent.setPivotX(0f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(3));
        alp.topMargin = dp(10);
        header.addView(accent, alp);

        clock = text(92, "sans-serif-light", Color.WHITE);
        clock.setIncludeFontPadding(false);
        shadow(clock);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(6);
        header.addView(clock, clp);

        date = text(18, "sans-serif", 0xE6FFFFFF);
        date.setLetterSpacing(0.08f);
        shadow(date);
        header.addView(date);

        // ---- кнопка-замок (выход в HyperOS) ----
        ImageView lock = new ImageView(this);
        lock.setImageDrawable(new IconDrawable("lock", 0, 0, true, true));
        lock.setPadding(dp(13), dp(13), dp(13), dp(13));
        GradientDrawable glass = new GradientDrawable();
        glass.setShape(GradientDrawable.OVAL);
        glass.setColor(0x38FFFFFF);
        glass.setStroke(dp(1), 0x40FFFFFF);
        lock.setBackground(glass);
        lock.setContentDescription("Открыть рабочий стол HyperOS");
        lockButton = lock;
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.RIGHT);
        llp.setMargins(0, dp(48), dp(52), 0);
        root.addView(lock, llp);
        pressable(lock, 0.88f);
        lock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onLockTapped(); }
        });

        // ---- плавающий док: иконки прямо на фоне, без рамки ----
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(dp(20), dp(20), dp(20), dp(20));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.setMargins(0, 0, 0, dp(48));
        root.addView(dock, dlp);

        for (App app : APPS) {
            View item = buildAppItem(app);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
            ilp.setMargins(dp(26), 0, dp(26), 0);
            dock.addView(item, ilp);
            dockItems.add(item);
        }

        // ---- PIN-экран поверх всего ----
        overlay = new PinOverlay(this, new PinOverlay.Callback() {
            @Override public void onPin(String pin) { handlePin(pin); }
            @Override public void onCancel() { closePin(); }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildAppItem(final App app) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClipChildren(false);
        item.setClipToPadding(false);
        item.setTag(app);

        ImageView tile = new ImageView(this);
        String kind = app.kind != null ? app.kind : "letter:" + app.label.charAt(0);
        tile.setImageDrawable(new IconDrawable(kind, app.colorTop, app.colorBottom, false, false));
        tile.setElevation(dp(14));
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.27f);
                o.setAlpha(1f);
            }
        });
        item.addView(tile, new LinearLayout.LayoutParams(dp(108), dp(108)));

        TextView t = text(14, "sans-serif-medium", Color.WHITE);
        t.setText(app.label);
        t.setGravity(Gravity.CENTER);
        shadow(t);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(14);
        item.addView(t, tlp);

        pressable(item, 0.92f);
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchApp(app); }
        });
        return item;
    }

    /** Приложения, которых нет на планшете, чуть приглушены. */
    private void refreshInstalled() {
        PackageManager pm = getPackageManager();
        for (View item : dockItems) {
            App app = (App) item.getTag();
            item.setAlpha(pm.getLaunchIntentForPackage(app.pkg) != null ? 1f : 0.55f);
        }
    }

    private void launchApp(App app) {
        Intent i = getPackageManager().getLaunchIntentForPackage(app.pkg);
        if (i == null) {
            Toast.makeText(this, app.label + " не установлен(о). Установите из Play Маркета на экране HyperOS.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (RuntimeException e) {
            Toast.makeText(this, "Не удалось открыть " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ анимации

    private void playIntro() {
        View[] head = {greeting, accent, clock, date};
        for (int i = 0; i < head.length; i++) {
            View v = head[i];
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(-dp(22));
            if (v == accent) v.setScaleX(0f);
            v.animate().alpha(1f).translationY(0f).scaleX(1f)
                    .setStartDelay(i * 90L).setDuration(560)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
        lockButton.animate().cancel();
        lockButton.setAlpha(0f);
        lockButton.setScaleX(0.5f);
        lockButton.setScaleY(0.5f);
        lockButton.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(320).setDuration(520)
                .setInterpolator(new OvershootInterpolator(1.8f)).start();

        for (int i = 0; i < dockItems.size(); i++) {
            View v = dockItems.get(i);
            float target = ((App) v.getTag()) != null && getPackageManager()
                    .getLaunchIntentForPackage(((App) v.getTag()).pkg) != null ? 1f : 0.55f;
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(dp(90));
            v.animate().alpha(target).translationY(0f)
                    .setStartDelay(260 + i * 120L).setDuration(680)
                    .setInterpolator(new OvershootInterpolator(1.15f)).start();
        }
    }

    /** Лёгкое сжатие под пальцем и пружинистый возврат. */
    private void pressable(View v, final float pressedScale) {
        v.setClickable(true);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().setStartDelay(0).setDuration(80)
                            .setInterpolator(new DecelerateInterpolator())
                            .scaleX(pressedScale).scaleY(pressedScale).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().setStartDelay(0).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(2.2f))
                            .scaleX(1f).scaleY(1f).start();
                }
                return false; // обычный клик продолжает работать
            }
        });
    }

    /** Фото медленно "дышит": плавное приближение, пока оно на экране. */
    private void startKenBurns() {
        if (kenBurns != null) kenBurns.cancel();
        if (wallFront == null || wallFront.getDrawable() == null) return;
        wallFront.setScaleX(1f);
        wallFront.setScaleY(1f);
        kenBurns = ObjectAnimator.ofPropertyValuesHolder(wallFront,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.09f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.09f));
        kenBurns.setDuration(28000);
        kenBurns.setInterpolator(new LinearInterpolator());
        kenBurns.start();
    }

    // ------------------------------------------------------------------ PIN

    private void onLockTapped() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = p.getString(KEY_PIN, null);
        pendingPin = null;
        if (saved == null) {
            pinMode = MODE_SET;
            overlay.open("Придумайте PIN", "4 цифры для выхода на рабочий стол HyperOS");
        } else {
            pinMode = MODE_ENTER;
            overlay.open("Введите PIN", "Чтобы открыть рабочий стол HyperOS");
        }
    }

    private void closePin() {
        pendingPin = null;
        if (overlay != null) overlay.close();
    }

    private void handlePin(String pin) {
        if (pinMode == MODE_SET) {
            pendingPin = pin;
            pinMode = MODE_CONFIRM;
            overlay.clear();
            overlay.setTexts("Повторите PIN", "Введите те же 4 цифры ещё раз");
            return;
        }
        if (pinMode == MODE_CONFIRM) {
            if (pin.equals(pendingPin)) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PIN, pin).apply();
                pendingPin = null;
                overlay.close();
                Toast.makeText(this, "PIN сохранён", Toast.LENGTH_SHORT).show();
            } else {
                pendingPin = null;
                pinMode = MODE_SET;
                overlay.setTexts("Придумайте PIN", "");
                overlay.rejectWithShake("PIN не совпал. Придумайте заново");
            }
            return;
        }

        // MODE_ENTER
        long now = SystemClock.uptimeMillis();
        if (now < lockedUntil) {
            long left = (lockedUntil - now + 999) / 1000;
            overlay.rejectWithShake("Слишком много попыток. Подождите " + left + " с");
            return;
        }
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PIN, null);
        if (saved != null && saved.equals(pin)) {
            failedAttempts = 0;
            overlay.close();
            openHyperOs();
        } else {
            failedAttempts++;
            if (failedAttempts >= MAX_ATTEMPTS) {
                failedAttempts = 0;
                lockedUntil = now + LOCKOUT_MS;
                overlay.rejectWithShake("Слишком много попыток. Подождите 30 с");
            } else {
                overlay.rejectWithShake("Неверный PIN. Осталось попыток: " + (MAX_ATTEMPTS - failedAttempts));
            }
        }
    }

    // ------------------------------------------------------------------ выход в HyperOS

    /** Все "домашние" экраны, кроме нашего и служебных заглушек. Родной лаунчер Xiaomi - первым. */
    private List<ComponentName> findOtherHomes() {
        PackageManager pm = getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> found = pm.queryIntentActivities(homeIntent, 0);
        List<ComponentName> out = new ArrayList<ComponentName>();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            String pkg = ai.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (pkg.equals("com.android.settings")) continue;   // FallbackHome - пустая заглушка
            if (pkg.contains("setupwizard")) continue;
            out.add(new ComponentName(pkg, ai.name));
        }
        Collections.sort(out, new Comparator<ComponentName>() {
            @Override public int compare(ComponentName a, ComponentName b) {
                return rank(a.getPackageName()) - rank(b.getPackageName());
            }
            private int rank(String p) {
                if (p.equals("com.miui.home")) return 0;
                if (p.equals("com.mi.android.globallauncher")) return 1;
                return 2;
            }
        });
        return out;
    }

    private void openHyperOs() {
        tryLeave(findOtherHomes(), 0, "рабочий стол не найден среди установленных приложений");
    }

    /**
     * Запускает рабочий стол HyperOS и проверяет, что мы действительно ушли с экрана.
     * Если система вернула нас обратно (или запуск отклонён) - пробует следующий вариант,
     * а когда варианты кончились - показывает понятную подсказку.
     */
    private void tryLeave(final List<ComponentName> list, final int idx, final String lastError) {
        if (idx >= list.size()) { showHomeFallback(lastError); return; }
        final ComponentName target = list.get(idx);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.setComponent(target);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            tryLeave(list, idx + 1, target.flattenToShortString() + ": " + e.getClass().getSimpleName());
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (resumedNow && !isFinishing() && !isDestroyed()) {
                    tryLeave(list, idx + 1, target.flattenToShortString() + " вернул обратно");
                }
            }
        }, 1800);
    }

    private void showHomeFallback(String detail) {
        if (isFinishing() || isDestroyed()) return;
        String msg = "Система не пустила на рабочий стол HyperOS напрямую.\n\n"
                + "Можно переключиться вручную: нажмите «Выбрать рабочий стол» и отметьте там "
                + "рабочий стол HyperOS.\n\n"
                + "Чтобы потом вернуть этот экран: нажмите Home и выберите «Мой экран» -> «Всегда».\n\n"
                + "(подробности: " + detail + ")";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Не удалось открыть HyperOS")
                .setMessage(msg)
                .setPositiveButton("Выбрать рабочий стол", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openHomeSettings(); }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e2) {
                Toast.makeText(this, "Откройте Настройки -> Приложения -> Приложения по умолчанию -> Рабочий стол",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ часы

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "ДОБРОЙ НОЧИ" : h < 12 ? "ДОБРОЕ УТРО" : h < 18 ? "ДОБРЫЙ ДЕНЬ" : "ДОБРЫЙ ВЕЧЕР");
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.getTime()));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    // ------------------------------------------------------------------ обои

    private ImageView wallLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private void nextWallpaper() {
        handler.removeCallbacks(rotate);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        final String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bm = null;
                try {
                    java.io.InputStream in = getAssets().open(name);
                    try {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        bm = BitmapFactory.decodeStream(in, null, o);
                    } finally { in.close(); }
                } catch (java.io.IOException ignored) { }
                final Bitmap result = bm;
                runOnUiThread(new Runnable() {
                    @Override public void run() { showWallpaper(result); }
                });
            }
        }).start();
        schedule(WALLPAPER_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWall ? 500 : 1800).withEndAction(new Runnable() {
            @Override public void run() {
                wallBack.setImageDrawable(null);
                wallBack.setScaleX(1f);
                wallBack.setScaleY(1f);
            }
        }).start();
        firstWall = false;
        if (resumedNow) startKenBurns();
    }

    private void schedule(long delay) {
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, Math.max(1000, delay));
    }

    // ------------------------------------------------------------------ вспомогательное

    private void shadow(TextView t) {
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
