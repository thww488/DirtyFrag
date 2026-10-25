package df.root;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.StringRes;

/**
 * OneUI-system-update-style two-segment progress bar.
 * Left segment: exploit run progress (0..100%), labeled with the percentage.
 * Right segment: verification step (fills during cleanup), labeled
 * "Verification" while running, then "Verified" / "Failed".
 *
 * 文案全部走 string 资源，通过资源 id 判断状态，
 * 避免用显示文字（如 "Verified"）做逻辑比较。
 */
public class TwoStepProgressView extends View {

    private static final int BAR_H_DP = 4;
    private static final int GAP_DP = 12;
    private static final int TRACK = 0xFF2E2E30;
    private static final int FILL = 0xFF4E8AE8;
    private static final int FILL_GREY = 0xFF2A2A2E;
    private static final int FAILED_RED = 0xFFE57373;
    private static final int LABEL_GREY = 0xFF47474A;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float seg1 = 0f;
    private float seg2 = 0f;

    private String leftLabel;
    private String rightLabel;

    /** 右侧标签的资源 id，用来做状态判断（而不是比较显示文字）。 */
    @StringRes
    private int rightLabelRes = R.string.progress_verification;

    private int rightColor = 0xFF8E8E8E;
    private boolean failed;
    private float greyMix = 0f;
    private android.animation.ValueAnimator greyAnimator;

    public TwoStepProgressView(Context context) { this(context, null); }

    public TwoStepProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextAlign(Paint.Align.CENTER);
        // 字段初始化时 getContext() 还不可用，放到构造函数里读资源。
        leftLabel = context.getString(R.string.progress_waiting);
        rightLabel = context.getString(R.string.progress_verification);
    }

    /** @param p 0..1 */
    public void setSeg1(float p, String label) {
        seg1 = clamp(p);
        leftLabel = label;
        invalidate();
    }

    /** @param p 0..1 */
    public void setSeg1(float p, @StringRes int labelRes) {
        setSeg1(p, getContext().getString(labelRes));
    }

    /**
     * 设置右侧分段。
     *
     * @param labelRes 标签资源 id；同时用于内部状态判断（是否为"已验证"）
     */
    public void setSeg2(float p, @StringRes int labelRes, int labelColor) {
        seg2 = clamp(p);
        rightLabelRes = labelRes;
        rightLabel = getContext().getString(labelRes);
        rightColor = labelColor;

        // 关键改动：用资源 id 判断，而不是 "Verified".equals(label)
        if (labelRes == R.string.state_verified && seg1 >= 1f) {
            scheduleGreyOut();
        } else {
            cancelGreyOut();
        }
        invalidate();
    }

    /** 失败模式：进度条和文字变红。 */
    public void setFailed(boolean f) {
        failed = f;
        if (f) cancelGreyOut();
        invalidate();
    }

    public void reset() {
        cancelGreyOut();
        failed = false;
        setSeg1(0f, R.string.progress_waiting);
        setSeg2(0f, R.string.progress_verification, 0xFFFFFFFF);
    }

    /** 进度到 100% + 已验证后，延迟把蓝色渐变到灰色，融入深色 UI。 */
    private void scheduleGreyOut() {
        if (greyAnimator != null) return;
        greyAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        greyAnimator.setStartDelay(2000);
        greyAnimator.setDuration(400);
        greyAnimator.addUpdateListener(a -> {
            greyMix = (float) a.getAnimatedValue();
            invalidate();
        });
        greyAnimator.start();
    }

    private void cancelGreyOut() {
        if (greyAnimator != null) {
            greyAnimator.cancel();
            greyAnimator = null;
        }
        greyMix = 0f;
    }

    private static int mixColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(
                resolveSize((int) (200 * d), wSpec),
                resolveSize((int) (54 * d), hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float barH = BAR_H_DP * d;
        float gap = GAP_DP * d;
        float half = (w - gap) / 2f;

        trackPaint.setColor(TRACK);
        fillPaint.setColor(failed ? FAILED_RED
                : mixColor(FILL, FILL_GREY, greyMix));

        rect.set(0, 0, half, barH);
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, trackPaint);
        if (seg1 > 0f) {
            rect.set(0, 0, Math.max(barH, half * seg1), barH);
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint);
        }

        rect.set(half + gap, 0, w, barH);
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, trackPaint);
        if (seg2 > 0f) {
            rect.set(half + gap, 0, half + gap + Math.max(barH, half * seg2), barH);
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint);
        }

        // setTextSize 必须在 ascent() 之前，否则首次绘制字号不对、下次会跳。
        textPaint.setTextSize(15 * d);
        float ty = barH + 15 * d - textPaint.ascent();
        textPaint.setColor(mixColor(failed ? FAILED_RED : 0xFFFFFFFF,
                LABEL_GREY, greyMix));
        canvas.drawText(leftLabel, half / 2f, ty, textPaint);
        textPaint.setColor(mixColor(rightColor, LABEL_GREY, greyMix));
        canvas.drawText(rightLabel, half + gap + half / 2f, ty, textPaint);
    }
}