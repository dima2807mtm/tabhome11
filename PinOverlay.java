package local.tabhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Полноэкранный ввод PIN поверх домашнего экрана: большие точки-индикаторы,
 * круглая цифровая клавиатура, плавное появление снизу, "пульс" точки при вводе
 * и тряска при ошибке.
 */
public class PinOverlay extends FrameLayout {

    public interface Callback {
        /** Введены все 4 цифры. */
        void onPin(String pin);
        /** Нажали "Отмена". */
        void onCancel();
    }

    private static final int PIN_LENGTH = 4;

    private final Callback callback;
    private final StringBuilder entered = new StringBuilder();
    private final View[] dots = new View[PIN_LENGTH];
    private final float density;

    private LinearLayout content;
    private LinearLayout dotsRow;
    private TextView title;
    private TextView subtitle;

    private boolean opened = false;
    private boolean busy = false;
    private boolean errorTint = false;

    public PinOverlay(Context ctx, Callback cb) {
        super(ctx);
        this.callback = cb;
        this.density = ctx.getResources().getDisplayMetrics().density;
        build();
        setVisibility(GONE);
    }

    // ------------------------------------------------------------------ публичное

    public boolean isOpen() { return opened; }

    public void setTexts(String titleText, String subtitleText) {
        title.setText(titleText);
        subtitle.setText(subtitleText);
    }

    public void open(String titleText, String subtitleText) {
        setTexts(titleText, subtitleText);
        clear();
        busy = false;
        opened = true;
        animate().cancel();
        content.animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        content.setAlpha(0f);
        content.setTranslationY(dp(90));
        animate().alpha(1f).setDuration(240).start();
        content.animate().alpha(1f).translationY(0f).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    public void close() {
        if (!opened) return;
        opened = false;
        animate().cancel();
        content.animate().cancel();
        content.animate().translationY(dp(50)).alpha(0f).setDuration(200).start();
        animate().alpha(0f).setDuration(220).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                animate().setListener(null);
                if (!opened) setVisibility(GONE);
                clear();
            }
        }).start();
    }

    /** Неверный PIN: красные точки трясутся и сбрасываются. */
    public void rejectWithShake(String message) {
        busy = true;
        errorTint = true;
        subtitle.setText(message);
        refreshDots();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ObjectAnimator a = ObjectAnimator.ofFloat(dotsRow, "translationX",
                0f, -dp(16), dp(16), -dp(12), dp(12), -dp(6), dp(6), 0f);
        a.setDuration(460);
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                errorTint = false;
                clear();
                busy = false;
            }
        });
        a.start();
    }

    /** Ввод цифры с физической клавиатуры или с экрана. */
    public void addDigit(char d) {
        if (busy || !opened || entered.length() >= PIN_LENGTH) return;
        entered.append(d);
        refreshDots();
        pulse(dots[entered.length() - 1]);
        if (entered.length() == PIN_LENGTH) {
            busy = true;
            final String pin = entered.toString();
            postDelayed(new Runnable() {
                @Override public void run() {
                    busy = false;
                    callback.onPin(pin);
                }
            }, 160);
        }
    }

    public void backspace() {
        if (busy || entered.length() == 0) return;
        entered.deleteCharAt(entered.length() - 1);
        refreshDots();
    }

    public void clear() {
        entered.setLength(0);
        refreshDots();
    }

    // ------------------------------------------------------------------ построение

    private void build() {
        setClickable(true); // не пропускаем касания на экран под нами
        setFocusable(false);
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF00A0E1A, 0xF5141A2E});
        setBackground(scrim);

        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutParams clp = new LayoutParams(-2, -2, Gravity.CENTER);
        addView(content, clp);

        ImageView lock = new ImageView(getContext());
        lock.setImageDrawable(new IconDrawable("lock", 0xFF6C8CFF, 0xFF8A5CFF, true, false));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(60), dp(60));
        llp.bottomMargin = dp(18);
        content.addView(lock, llp);

        title = label(24, "sans-serif-medium", Color.WHITE);
        content.addView(title);

        subtitle = label(14, "sans-serif", 0x99FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(6);
        content.addView(subtitle, slp);

        dotsRow = new LinearLayout(getContext());
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(30);
        dlp.bottomMargin = dp(34);
        content.addView(dotsRow, dlp);
        for (int i = 0; i < PIN_LENGTH; i++) {
            View d = new View(getContext());
            dots[i] = d;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(16), dp(16));
            p.setMargins(dp(12), 0, dp(12), 0);
            dotsRow.addView(d, p);
        }
        refreshDots();

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (String[] row : rows) {
            LinearLayout r = keyRow();
            for (String k : row) r.addView(digitKey(k.charAt(0)), keyParams());
            content.addView(r);
        }
        LinearLayout last = keyRow();
        last.addView(cancelKey(), keyParams());
        last.addView(digitKey('0'), keyParams());
        last.addView(backspaceKey(), keyParams());
        content.addView(last);
    }

    private LinearLayout keyRow() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        return r;
    }

    private LinearLayout.LayoutParams keyParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(78), dp(78));
        p.setMargins(dp(12), dp(7), dp(12), dp(7));
        return p;
    }

    private View digitKey(final char digit) {
        FrameLayout key = new FrameLayout(getContext());
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x1FFFFFFF);
        key.setBackground(bg);
        TextView t = label(32, "sans-serif-light", Color.WHITE);
        t.setText(String.valueOf(digit));
        key.addView(t, new LayoutParams(-2, -2, Gravity.CENTER));
        pressEffect(key, bg);
        key.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                addDigit(digit);
            }
        });
        return key;
    }

    private View cancelKey() {
        FrameLayout key = new FrameLayout(getContext());
        TextView t = label(16, "sans-serif-medium", 0xCCFFFFFF);
        t.setText("Отмена");
        key.addView(t, new LayoutParams(-2, -2, Gravity.CENTER));
        pressEffect(key, null);
        key.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { callback.onCancel(); }
        });
        return key;
    }

    private View backspaceKey() {
        FrameLayout key = new FrameLayout(getContext());
        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(new IconDrawable("backspace", 0, 0, false, true));
        key.addView(icon, new LayoutParams(dp(34), dp(34), Gravity.CENTER));
        pressEffect(key, null);
        key.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                backspace();
            }
        });
        key.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { clear(); return true; }
        });
        return key;
    }

    /** Кнопка слегка сжимается под пальцем, фон становится ярче. */
    private void pressEffect(View v, final GradientDrawable bg) {
        v.setClickable(true);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).start();
                    if (bg != null) bg.setColor(0x42FFFFFF);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                    if (bg != null) bg.setColor(0x1FFFFFFF);
                }
                return false; // клик обрабатывается как обычно
            }
        });
    }

    // ------------------------------------------------------------------ точки

    private void refreshDots() {
        int filled = entered.length();
        for (int i = 0; i < PIN_LENGTH; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (errorTint) {
                g.setColor(0xFFFF6B6B);
            } else if (i < filled) {
                g.setColor(Color.WHITE);
            } else {
                g.setColor(Color.TRANSPARENT);
                g.setStroke(dp(2), 0x99FFFFFF);
            }
            dots[i].setBackground(g);
        }
    }

    private void pulse(final View dot) {
        dot.animate().cancel();
        dot.animate().scaleX(1.5f).scaleY(1.5f).setDuration(90).withEndAction(new Runnable() {
            @Override public void run() {
                dot.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(new OvershootInterpolator(3f)).start();
            }
        }).start();
    }

    // ------------------------------------------------------------------ вспомогательное

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // на низком экране (альбомная ориентация) слегка уменьшаем всё содержимое
        float need = dp(640);
        float scale = h > 0 ? Math.min(1f, h / need) : 1f;
        content.setScaleX(scale);
        content.setScaleY(scale);
    }

    private TextView label(float sp, String family, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
