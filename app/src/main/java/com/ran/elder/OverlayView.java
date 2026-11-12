package com.ran.elder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class OverlayView extends View {

    public static float TOP_RATIO = 0.130f;
    public static float BOTTOM_RATIO = 0.255f;
    public static float RIGHT_RATIO = 0.230f;

    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] btnRect = new RectF[3];
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private int pressed = -1;

    public OverlayView(Context c) {
        super(c);
        mask.setColor(0xFF000000);
        mask.setStyle(Paint.Style.FILL);

        txtPaint.setColor(Color.WHITE);
        txtPaint.setTextAlign(Paint.Align.CENTER);
        txtPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        int top = (int) (h * TOP_RATIO);
        int bottom = (int) (h * (1f - BOTTOM_RATIO));
        int right = (int) (w * (1f - RIGHT_RATIO));

        c.drawRect(0, 0, w, top, mask);
        c.drawRect(0, bottom, w, h, mask);
        c.drawRect(right, 0, w, h, mask);

        float pad = w * 0.014f;
        float bw = (w - pad * 4f) / 3f;
        float bh = (h - bottom) - pad * 2f;
        float by = bottom + pad;

        for (int i = 0; i < 3; i++) {
            float bx = pad + i * (bw + pad);
            if (btnRect[i] == null) btnRect[i] = new RectF();
            btnRect[i].set(bx, by, bx + bw, by + bh);

            int col;
            if (i == 0) {
                col = 0xFF1559A8;
            } else if (i == 1) {
                col = 0xFF1559A8;
            } else {
                col = 0xFF3A3A3A;
            }
            if (pressed == i) col = 0xFF7FB8E8;

            btnPaint.setColor(col);
            c.drawRoundRect(btnRect[i], 16f, 16f, btnPaint);

            txtPaint.setTextSize(bw * 0.235f);
            String label = (i == 0) ? "上一个" : ((i == 1) ? "下一个" : "返回桌面");
            c.drawText(label, btnRect[i].centerX(),
                    btnRect[i].centerY() + txtPaint.getTextSize() * 0.36f, txtPaint);
        }

        txtPaint.setTextSize(top * 0.50f);
        c.drawText(sdf.format(new Date()), w * 0.5f, top * 0.70f, txtPaint);

        postInvalidateDelayed(20000);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            pressed = hit(e.getX(), e.getY());
            if (pressed >= 0) invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            int up = hit(e.getX(), e.getY());
            int was = pressed;
            pressed = -1;
            invalidate();
            if (up >= 0 && up == was) fire(up);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            pressed = -1;
            invalidate();
            return true;
        }
        return true;
    }

    private int hit(float x, float y) {
        for (int i = 0; i < 3; i++) {
            if (btnRect[i] != null && btnRect[i].contains(x, y)) return i;
        }
        return -1;
    }

    private void fire(int i) {
        DouyinA11yService s = DouyinA11yService.get();
        if (s == null) return;
        if (i == 0) {
            s.swipePrev();
        } else if (i == 1) {
            s.swipeNext();
        } else {
            s.goHome();
        }
    }
}
