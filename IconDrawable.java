package local.tabhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Собственные иконки, нарисованные кодом: цветная плитка с градиентом, мягким бликом
 * и простым белым значком. Никаких системных иконок и картинок - всё векторное.
 *
 * Виды значков (kind): play, clapper, globe, lock, backspace, letter:Х
 */
public class IconDrawable extends Drawable {

    private final String kind;
    private final int colorTop;
    private final int colorBottom;
    private final boolean circle;      // true - круглая подложка (кнопки), false - скруглённый квадрат (приложения)
    private final boolean plainGlyph;  // true - без подложки, только значок

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public IconDrawable(String kind, int colorTop, int colorBottom, boolean circle, boolean plainGlyph) {
        this.kind = kind;
        this.colorTop = colorTop;
        this.colorBottom = colorBottom;
        this.circle = circle;
        this.plainGlyph = plainGlyph;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h);
        float left = b.left + (w - s) / 2f;
        float top = b.top + (h - s) / 2f;

        if (!plainGlyph) {
            rect.set(left, top, left + s, top + s);
            bg.setShader(new LinearGradient(left, top, left + s, top + s,
                    colorTop, colorBottom, Shader.TileMode.CLAMP));
            float r = circle ? s / 2f : s * 0.27f;
            c.drawRoundRect(rect, r, r, bg);

            // мягкий блик в верхней половине
            gloss.setShader(new LinearGradient(0, top, 0, top + s * 0.6f,
                    0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rect.set(left, top, left + s, top + s * 0.6f);
            c.save();
            c.clipRect(rect);
            rect.set(left, top, left + s, top + s);
            c.drawRoundRect(rect, r, r, gloss);
            c.restore();
        }

        c.save();
        c.translate(left, top);
        drawGlyph(c, s);
        c.restore();
    }

    /** Рисует значок в квадрате s x s с началом координат в (0,0). */
    private void drawGlyph(Canvas c, float s) {
        stroke.setStrokeWidth(s * 0.07f);

        if ("play".equals(kind)) {
            // кольцо и треугольник "воспроизвести" со скруглёнными углами
            stroke.setStrokeWidth(s * 0.06f);
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.3f, stroke);
            float x0 = s * 0.44f, x1 = s * 0.65f, y0 = s * 0.38f, y1 = s * 0.62f;
            path.reset();
            path.moveTo(x0, y0);
            path.lineTo(x1, s * 0.5f);
            path.lineTo(x0, y1);
            path.close();
            Paint tri = new Paint(fill);
            tri.setStyle(Paint.Style.FILL_AND_STROKE);
            tri.setStrokeJoin(Paint.Join.ROUND);
            tri.setStrokeWidth(s * 0.03f);
            c.drawPath(path, tri);

        } else if ("clapper".equals(kind)) {
            // хлопушка: корпус и наклонная верхняя планка с полосками
            float l = s * 0.24f, r = s * 0.76f, t = s * 0.42f, bt = s * 0.74f;
            rect.set(l, t, r, bt);
            c.drawRoundRect(rect, s * 0.04f, s * 0.04f, fill);
            c.save();
            c.rotate(-12f, l, t - s * 0.03f);
            rect.set(l, t - s * 0.14f, r, t - s * 0.025f);
            c.drawRoundRect(rect, s * 0.03f, s * 0.03f, fill);
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setColor(colorBottom);
            cut.setStyle(Paint.Style.STROKE);
            cut.setStrokeWidth(s * 0.04f);
            for (int i = 1; i <= 3; i++) {
                float x = l + (r - l) * i / 4f;
                c.drawLine(x - s * 0.03f, t - s * 0.14f, x + s * 0.03f, t - s * 0.025f, cut);
            }
            c.restore();
            // маленькая "звезда-точка" на корпусе
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(colorBottom);
            c.drawCircle(s * 0.5f, s * 0.58f, s * 0.045f, dot);

        } else if ("globe".equals(kind)) {
            // глобус: круг, меридиан-эллипс и две параллели
            stroke.setStrokeWidth(s * 0.055f);
            float cx = s * 0.5f, cy = s * 0.5f, rad = s * 0.28f;
            c.drawCircle(cx, cy, rad, stroke);
            rect.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad);
            c.drawOval(rect, stroke);
            c.drawLine(cx - rad, cy, cx + rad, cy, stroke);
            c.drawLine(cx, cy - rad, cx, cy + rad, stroke);

        } else if ("lock".equals(kind)) {
            // замочек: дужка и корпус
            stroke.setStrokeWidth(s * 0.07f);
            rect.set(s * 0.33f, s * 0.2f, s * 0.67f, s * 0.6f);
            c.drawArc(rect, 180f, 180f, false, stroke);
            c.drawLine(s * 0.33f, s * 0.4f, s * 0.33f, s * 0.5f, stroke);
            c.drawLine(s * 0.67f, s * 0.4f, s * 0.67f, s * 0.5f, stroke);
            rect.set(s * 0.26f, s * 0.46f, s * 0.74f, s * 0.78f);
            c.drawRoundRect(rect, s * 0.07f, s * 0.07f, fill);
            Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
            hole.setColor(0x66000000);
            c.drawCircle(s * 0.5f, s * 0.61f, s * 0.045f, hole);

        } else if ("backspace".equals(kind)) {
            // "стереть": пятиугольная стрелка с крестиком
            stroke.setStrokeWidth(s * 0.06f);
            path.reset();
            path.moveTo(s * 0.18f, s * 0.5f);
            path.lineTo(s * 0.34f, s * 0.3f);
            path.lineTo(s * 0.8f, s * 0.3f);
            path.lineTo(s * 0.8f, s * 0.7f);
            path.lineTo(s * 0.34f, s * 0.7f);
            path.close();
            c.drawPath(path, stroke);
            c.drawLine(s * 0.5f, s * 0.42f, s * 0.66f, s * 0.58f, stroke);
            c.drawLine(s * 0.66f, s * 0.42f, s * 0.5f, s * 0.58f, stroke);

        } else {
            // запасной вариант: первая буква названия
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFFFFFFF);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(s * 0.5f);
            tp.setFakeBoldText(true);
            String letter = kind != null && kind.startsWith("letter:") ? kind.substring(7) : "?";
            c.drawText(letter, s * 0.5f, s * 0.5f + s * 0.18f, tp);
        }
    }

    @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { bg.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
