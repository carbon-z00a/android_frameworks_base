/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.AttributeSet;
import android.view.View;

import com.android.internal.util.bliss.ColorHelper;
import com.android.systemui.cm.UserContentObserver;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryStateRegistar;

public class BatteryMeterView extends View implements DemoMode,
        BatteryController.BatteryStateChangeCallback {
    public static final String TAG = BatteryMeterView.class.getSimpleName();
    public static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";

    private static final String STATUS_BAR_BATTERY_STYLE = "status_bar_battery_status_style";
    private static final String STATUS_BAR_BATTERY_STATUS_CIRCLE_DOT_LENGTH = "status_bar_battery_status_circle_dot_length";
    private static final String STATUS_BAR_BATTERY_STATUS_CIRCLE_DOT_INTERVAL = "status_bar_battery_status_circle_dot_interval";
    private static final String STATUS_BAR_SHOW_BATTERY_PERCENT = "status_bar_battery_status_percent_style";
    private static final String STATUS_BAR_BATTERY_STATUS_CHARGING_ANIMATION_SPEED = "status_bar_battery_status_charging_animation_speed";
    private static final String STATUS_BAR_BATTERY_STATUS_COLOR = "status_bar_battery_status_color";
    private static final String STATUS_BAR_BATTERY_STATUS_TEXT_COLOR = "status_bar_battery_status_text_color";

    private static final boolean SINGLE_DIGIT_PERCENT = false;
    private static final boolean SHOW_100_PERCENT = false;

    private static final int DEFAULT_BATTERY_COLOR = 0xffffffff;
    private static final int FULL = 96;

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction

    protected boolean mShowPercent = true;
    private float mButtonHeightFraction;
    private float mSubpixelSmoothingLeft;
    private float mSubpixelSmoothingRight;

    public static enum BatteryMeterMode {
        BATTERY_METER_ICON_PORTRAIT,
        BATTERY_METER_ICON_LANDSCAPE,
        BATTERY_METER_CIRCLE,
        BATTERY_METER_TEXT,
        BATTERY_METER_GONE
    }

    private int mHeight;
    private int mWidth;

    private String mWarningString;
    private final int mCriticalLevel;

    private boolean mIsAnimating = false;
    private int mAnimLevel;
    private int mChargeAnimSpeed;
    private boolean mChargeAnimDisabled;

    private boolean mIsCircleDotted;
    private int mDotLength;
    private int mDotInterval;

    private Animator mColorTransitionAnimator;
    private boolean mAnimateColorTransition = false;
    private boolean mAnimateTextColorTransition = false;
    private float mColorAnimatimationPosition;
    private int mStatusColor;
    private int mOldStatusColor;
    private int mTextColor;
    private int mOldTextColor;

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();

    private BatteryStateRegistar mBatteryStateRegistar;
    private BatteryController mBatteryController;
    private boolean mPowerSaveEnabled;

    private final Handler mHandler;

    protected BatteryMeterMode mMeterMode = null;

    protected boolean mAttached;

    private boolean mDemoMode;
    protected BatteryTracker mDemoTracker = new BatteryTracker();
    protected BatteryTracker mTracker = new BatteryTracker();
    private BatteryMeterDrawable mBatteryMeterDrawable;
    private final Object mLock = new Object();

    private ContentResolver mResolver;

    private SettingsObserver mObserver;

    private class SettingsObserver extends UserContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();

            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STYLE),
                    false, this);
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STATUS_CIRCLE_DOT_LENGTH),
                    false, this);
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STATUS_CIRCLE_DOT_INTERVAL),
                    false, this);
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT),
                    false, this);
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STATUS_CHARGING_ANIMATION_SPEED),
                    false, this);
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STATUS_COLOR),
                    false, this);
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR),
                    false, this);

            update();
        }

        @Override
        protected void unobserve() {
            super.unobserve();

            getContext().getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            loadShowBatterySetting();
            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STATUS_COLOR))
                || uri.equals(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR))) {
                if (!mIsAnimating && !mDemoMode && mTracker.level > 16) {
                    mAnimateColorTransition = true;
                    mAnimateTextColorTransition = false;
                    mColorTransitionAnimator.start();
                }
            }
            update();
        }

        @Override
        public void update() {
            invalidateIfVisible();
        }
    };

    protected class BatteryTracker extends BroadcastReceiver {
        public static final int UNKNOWN_LEVEL = -1;

        // current battery status
        boolean present = true;
        int level = UNKNOWN_LEVEL;
        String percentStr;
        int plugType;
        boolean plugged;
        int health;
        int status;
        int chargeRate;
        String technology;
        int voltage;
        int temperature;
        boolean testmode = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                if (testmode && ! intent.getBooleanExtra("testmode", false)) return;

                level = (int)(100f
                        * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                        / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));

                present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
                plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                plugged = plugType != 0;
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                        BatteryManager.BATTERY_HEALTH_UNKNOWN);
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                chargeRate = intent.getIntExtra(BatteryManager.EXTRA_CHARGE_RATE,
                        BatteryManager.BATTERY_CHARGE_RATE_UNKNOWN);
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

                setContentDescription(
                        context.getString(R.string.accessibility_battery_level, level));
                synchronized (mLock) {
                    if (mBatteryMeterDrawable != null) {
                        setVisibility(View.VISIBLE);
                        invalidateIfVisible();
                    }
                }
            } else if (action.equals(ACTION_LEVEL_TEST)) {
                testmode = true;
                post(new Runnable() {
                    int curLevel = 0;
                    int incr = 1;
                    int saveLevel = level;
                    int savePlugged = plugType;
                    Intent dummy = new Intent(Intent.ACTION_BATTERY_CHANGED);
                    @Override
                    public void run() {
                        if (curLevel < 0) {
                            testmode = false;
                            dummy.putExtra("level", saveLevel);
                            dummy.putExtra("plugged", savePlugged);
                            dummy.putExtra("testmode", false);
                        } else {
                            dummy.putExtra("level", curLevel);
                            dummy.putExtra("plugged", incr > 0
                                    ? BatteryManager.BATTERY_PLUGGED_AC : 0);
                            dummy.putExtra("testmode", true);
                        }
                        getContext().sendBroadcast(dummy);

                        if (!testmode) return;

                        curLevel += incr;
                        if (curLevel == 100) {
                            incr *= -1;
                        }
                        postDelayed(this, 200);
                    }
                });
            }
        }

        protected boolean shouldIndicateCharging() {
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                return true;
            }
            if (plugged) {
                return status == BatteryManager.BATTERY_STATUS_FULL;
            }
            return false;
        }
    }

    private final Runnable mInvalidate = new Runnable() {
        public void run() {
            invalidateIfVisible();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ACTION_LEVEL_TEST);
        final Intent sticky = getContext().registerReceiver(mTracker, filter);
        if (sticky != null) {
            // preload the battery level
            mTracker.onReceive(getContext(), sticky);
        }
        if (mBatteryStateRegistar != null) {
            mBatteryStateRegistar.addStateChangedCallback(this);
        }

        int currentUserId = ActivityManager.getCurrentUser();
        mStatusColor = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STATUS_COLOR,
                DEFAULT_BATTERY_COLOR, currentUserId);
        mTextColor = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR,
                DEFAULT_BATTERY_COLOR, currentUserId);
        mOldStatusColor = mStatusColor;
        mOldTextColor = mTextColor;

        mColorTransitionAnimator = createColorTransitionAnimator(0, 1);
        mObserver.observe();
        mAttached = true;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mAttached = false;
        mResolver.unregisterContentObserver(mObserver);
        getContext().unregisterReceiver(mTracker);
        if (mBatteryStateRegistar != null) {
            mBatteryStateRegistar.removeStateChangedCallback(this);
        }
    }

    private void loadShowBatterySetting() {
        int currentUserId = ActivityManager.getCurrentUser();

        boolean showInsidePercent = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT,
                2, currentUserId) == 1;

        int batteryStyle = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STYLE,
                0, currentUserId);

        mIsCircleDotted = false;
        BatteryMeterMode meterMode = BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT;
        switch (batteryStyle) {
            case 2:
                meterMode = BatteryMeterMode.BATTERY_METER_CIRCLE;
                break;
            case 3:
                meterMode = BatteryMeterMode.BATTERY_METER_CIRCLE;//DOTTED_CIRCLE;
                mIsCircleDotted = true;
                break;
            case 4:
                meterMode = BatteryMeterMode.BATTERY_METER_GONE;
                showInsidePercent = false;
                break;
            case 5:
                meterMode = BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE;
                break;
            case 6:
                meterMode = BatteryMeterMode.BATTERY_METER_TEXT;
                showInsidePercent = false;
                break;
            default:
                meterMode = BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT;
                break;
        }

        mDotLength = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STATUS_CIRCLE_DOT_LENGTH,
                3, currentUserId);
        mDotInterval = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STATUS_CIRCLE_DOT_INTERVAL,
                2, currentUserId);
        int chargeAnimSpeed = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STATUS_CHARGING_ANIMATION_SPEED,
                3, currentUserId);

        if (chargeAnimSpeed == 0) {
            mChargeAnimDisabled = true;
        } else {
            mChargeAnimDisabled = false;
            mChargeAnimSpeed = chargeAnimSpeed;
        }
        setMode(meterMode);
        mShowPercent = showInsidePercent;
        invalidateIfVisible();
    }

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mHandler = new Handler();

        mResolver = context.getContentResolver();
        final Resources res = context.getResources();

        mObserver = new SettingsObserver(mHandler);

        mWarningString = context.getString(
                R.string.battery_meter_very_low_overlay_symbol);
        mCriticalLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mButtonHeightFraction = context.getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);
        mSubpixelSmoothingLeft = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_left, 1, 1);
        mSubpixelSmoothingRight = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_right, 1, 1);

        loadShowBatterySetting();
        mBatteryMeterDrawable = createBatteryMeterDrawable(mMeterMode);
    }

    protected BatteryMeterDrawable createBatteryMeterDrawable(BatteryMeterMode mode) {
        Resources res = mContext.getResources();
        switch (mode) {
            case BATTERY_METER_ICON_LANDSCAPE:
                return new NormalBatteryMeterDrawable(res, true);
            case BATTERY_METER_CIRCLE:
                return new CircleBatteryMeterDrawable(res);
            case BATTERY_METER_TEXT:
            case BATTERY_METER_GONE:
                return null;
            default:
                return new NormalBatteryMeterDrawable(res, false);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (mMeterMode.compareTo(BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE) == 0) {
            width = (int)(height * 1.2f);
        } else if (mMeterMode == BatteryMeterMode.BATTERY_METER_CIRCLE) {
            height += (CircleBatteryMeterDrawable.STROKE_WITH / 3);
            width = height;
        } else if (mMeterMode == BatteryMeterMode.BATTERY_METER_TEXT) {
            onSizeChanged(width, height, 0, 0); // Force a size changed event
        } else if (mMeterMode == BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE) {
            width = (int)(height * 1.2f);
        }

        setMeasuredDimension(width, height);
    }

    public void setBatteryStateRegistar(BatteryStateRegistar batteryStateRegistar) {
        mBatteryStateRegistar = batteryStateRegistar;
        if (!mAttached) {
            mBatteryStateRegistar.addStateChangedCallback(this);
        }
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mPowerSaveEnabled = mBatteryController.isPowerSave();
    }

    @Override
    public void onBatteryLevelChanged(boolean present, int level, boolean pluggedIn,
            boolean charging) {
        // TODO: Use this callback instead of own broadcast receiver.
    }

    @Override
    public void onPowerSaveChanged() {
        mPowerSaveEnabled = mBatteryController.isPowerSave();
        invalidate();
    }

    @Override
    public void onBatteryStyleChanged(int style, int percentMode) {
        boolean showInsidePercent = percentMode == BatteryController.PERCENTAGE_MODE_INSIDE;
        BatteryMeterMode meterMode = BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT;

        switch (style) {
            case BatteryController.STYLE_CIRCLE:
                meterMode = BatteryMeterMode.BATTERY_METER_CIRCLE;
                break;
            case BatteryController.STYLE_DOTTED_CIRCLE:
                meterMode = BatteryMeterMode.BATTERY_METER_CIRCLE;
                mIsCircleDotted = true;
                break;
            case BatteryController.STYLE_GONE:
                meterMode = BatteryMeterMode.BATTERY_METER_GONE;
                showInsidePercent = false;
                break;
            case BatteryController.STYLE_ICON_LANDSCAPE:
                meterMode = BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE;
                break;
            case BatteryController.STYLE_TEXT:
                meterMode = BatteryMeterMode.BATTERY_METER_TEXT;
                showInsidePercent = false;
                break;
            default:
                break;
        }

        setMode(meterMode);
        mShowPercent = showInsidePercent;
        invalidateIfVisible();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mHeight = h;
        mWidth = w;
        synchronized (mLock) {
            if (mBatteryMeterDrawable != null) {
                mBatteryMeterDrawable.onSizeChanged(w, h, oldw, oldh);
            }
        }
    }

    protected void invalidateIfVisible() {
        if (getVisibility() == View.VISIBLE && mAttached) {
            if (mAttached) {
                postInvalidate();
            } else {
                invalidate();
            }
        }
    }

    public void setMode(BatteryMeterMode mode) {
        if (mMeterMode == mode) {
            return;
        }

        mMeterMode = mode;
        BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
        if (mode == BatteryMeterMode.BATTERY_METER_GONE ||
                mode == BatteryMeterMode.BATTERY_METER_TEXT) {
            setVisibility(View.GONE);
            synchronized (mLock) {
                mBatteryMeterDrawable = null;
            }
        } else {
            synchronized (mLock) {
                if (mBatteryMeterDrawable != null) {
                    mBatteryMeterDrawable.onDispose();
                }
                mBatteryMeterDrawable = createBatteryMeterDrawable(mode);
            }
            if (mMeterMode == BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT ||
                    mMeterMode == BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE) {
                ((NormalBatteryMeterDrawable)mBatteryMeterDrawable).loadBoltPoints(
                        mContext.getResources());
            }
            if (tracker.present) {
                setVisibility(View.VISIBLE);
                postInvalidate();
                requestLayout();
            } else {
                setVisibility(View.GONE);
            }
        }
    }

    public int getColorForLevel(int percent, boolean text) {
        int currentUserId = ActivityManager.getCurrentUser();

        mStatusColor = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STATUS_COLOR,
                DEFAULT_BATTERY_COLOR, currentUserId);
        mTextColor = Settings.System.getIntForUser(mResolver,
                Settings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR,
                DEFAULT_BATTERY_COLOR, currentUserId);

        if (mAnimateColorTransition) {
            int blendedStatusColor = ColorHelper.getBlendColor(
                    mOldStatusColor, mStatusColor, mColorAnimatimationPosition);
            int blendedTextColor = ColorHelper.getBlendColor(
                    mOldTextColor, mTextColor, mColorAnimatimationPosition);
            if (mAnimateTextColorTransition) {
                return text ? blendedTextColor : mStatusColor;
            } else {
                return text ? mTextColor : blendedStatusColor;
            }
        } else {
            if (percent <= 15 && !mPowerSaveEnabled) {
                return 0xfff4511e;
            } else {
                return text ? mTextColor : mStatusColor;
            }
        }
    }

    private ValueAnimator createColorTransitionAnimator(float start, float end) {
        ValueAnimator animator = ValueAnimator.ofFloat(start, end);

        animator.setDuration(500);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener(){
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float position = animation.getAnimatedFraction();
                mColorAnimatimationPosition = position;
                postInvalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mOldStatusColor = mStatusColor;
                mOldTextColor = mTextColor;
                mAnimateColorTransition = false;
            }
        });
        return animator;
    }

    @Override
    public void draw(Canvas c) {
        synchronized (mLock) {
            if (mBatteryMeterDrawable != null) {
                BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
                mBatteryMeterDrawable.onDraw(c, tracker);
            }
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (getVisibility() == View.VISIBLE) {
            if (!mDemoMode && command.equals(COMMAND_ENTER)) {
                mDemoMode = true;
                mDemoTracker.level = mTracker.level;
                mDemoTracker.plugged = mTracker.plugged;
            } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
                mDemoMode = false;
                postInvalidate();
            } else if (mDemoMode && command.equals(COMMAND_BATTERY)) {
               String level = args.getString("level");
               String plugged = args.getString("plugged");
               if (level != null) {
                   mDemoTracker.level = Math.min(Math.max(Integer.parseInt(level), 0), 100);
               }
               if (plugged != null) {
                   mDemoTracker.plugged = Boolean.parseBoolean(plugged);
               }
               postInvalidate();
            }
        }
    }

    protected interface BatteryMeterDrawable {
        void onDraw(Canvas c, BatteryTracker tracker);
        void onSizeChanged(int w, int h, int oldw, int oldh);
        void onDispose();
    }

    protected class NormalBatteryMeterDrawable implements BatteryMeterDrawable {
        private static final boolean SINGLE_DIGIT_PERCENT = false;
        private static final boolean SHOW_100_PERCENT = false;

        private boolean mDisposed;

        protected final boolean mHorizontal;

        private final Paint mFramePaint, mBatteryPaint, mWarningTextPaint, mTextPaint, mBoltPaint;
        private float mTextHeight, mWarningTextHeight;

        private final float[] mBoltPoints;
        private final Path mBoltPath = new Path();

        private final RectF mFrame = new RectF();
        private final RectF mButtonFrame = new RectF();
        private final RectF mBoltFrame = new RectF();
        private boolean mFastCharging;

        public NormalBatteryMeterDrawable(Resources res, boolean horizontal) {
            super();
            mHorizontal = horizontal;
            mDisposed = false;

            mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFramePaint.setDither(true);
            mFramePaint.setStrokeWidth(0);
            mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);

            mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBatteryPaint.setDither(true);
            mBatteryPaint.setStrokeWidth(0);
            mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextPaint.setTypeface(font);
            mTextPaint.setTextAlign(Paint.Align.CENTER);

            mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mWarningTextPaint.setTypeface(font);
            mWarningTextPaint.setTextAlign(Paint.Align.CENTER);

            mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBoltPoints = loadBoltPoints(res);
        }

        @Override
        public void onDraw(Canvas c, BatteryTracker tracker) {
            if (mDisposed) return;

            final int level = tracker.level;

            if (level == BatteryTracker.UNKNOWN_LEVEL) return;

            float drawFrac;

            if (mIsAnimating) {
                updateChargeAnim(tracker);
                drawFrac = (float) mAnimLevel / 100f;
            } else {
                startChargeAnim(tracker);
                drawFrac = (float) level / 100f;
            }

            final int pt = getPaddingTop() + (mHorizontal ? (int)(mHeight * 0.12f) : 0);
            final int pl = getPaddingLeft();
            final int pr = getPaddingRight();
            final int pb = getPaddingBottom() + (mHorizontal ? (int)(mHeight * 0.08f) : 0);
            final int height = mHeight - pt - pb;
            final int width = mWidth - pl - pr;
            final int buttonHeight = (int) ((mHorizontal ? width : height) * mButtonHeightFraction);
            final boolean fastCharging;

            fastCharging = tracker.chargeRate == BatteryManager.BATTERY_CHARGE_RATE_FAST_CHARGING;

            mFrame.set(0, 0, width, height);
            mFrame.offset(pl, pt);

            if (mHorizontal) {
                mButtonFrame.set(
                        /*cover frame border of intersecting area*/
                        width - buttonHeight - mFrame.left,
                        mFrame.top + Math.round(height * 0.25f),
                        mFrame.right,
                        mFrame.bottom - Math.round(height * 0.25f));

                mButtonFrame.top += mSubpixelSmoothingLeft;
                mButtonFrame.bottom -= mSubpixelSmoothingRight;
                mButtonFrame.right -= mSubpixelSmoothingRight;
            } else {
                // button-frame: area above the battery body
                mButtonFrame.set(
                        mFrame.left + Math.round(width * 0.25f),
                        mFrame.top,
                        mFrame.right - Math.round(width * 0.25f),
                        mFrame.top + buttonHeight);

                mButtonFrame.top += mSubpixelSmoothingLeft;
                mButtonFrame.left += mSubpixelSmoothingLeft;
                mButtonFrame.right -= mSubpixelSmoothingRight;
            }

            // frame: battery body area

            if (mHorizontal) {
                mFrame.right -= buttonHeight;
            } else {
                mFrame.top += buttonHeight;
            }
            mFrame.left += mSubpixelSmoothingLeft;
            mFrame.top += mSubpixelSmoothingLeft;
            mFrame.right -= mSubpixelSmoothingRight;
            mFrame.bottom -= mSubpixelSmoothingRight;

            // set the battery colors
            mFramePaint.setColor(getColorForLevel(50, false));
            mFramePaint.setAlpha(102);
            mBatteryPaint.setColor(tracker.plugged ? getColorForLevel(50, false) : getColorForLevel(level, false));
            // set the text colors
            mWarningTextPaint.setColor(getColorForLevel(10, true));
            mBoltPaint.setColor(getColorForLevel(50, true));
            mTextPaint.setColor(getColorForLevel(level, true));

            if (mIsAnimating) {
                if (mAnimLevel >= FULL) {
                    drawFrac = 1f;
                } else if (mAnimLevel <= mCriticalLevel) {
                    drawFrac = 0f;
                }
            } else {
                if (level >= FULL) {
                    drawFrac = 1f;
                } else if (level <= mCriticalLevel) {
                    drawFrac = 0f;
                }
            }

            final float levelTop;

            if (drawFrac == 1f) {
                if (mHorizontal) {
                    levelTop = mButtonFrame.right;
                } else {
                    levelTop = mButtonFrame.top;
                }
            } else {
                if (mHorizontal) {
                    levelTop = (mFrame.right - (mFrame.width() * (1f - drawFrac)));
                } else {
                    levelTop = (mFrame.top + (mFrame.height() * (1f - drawFrac)));
                }
            }

            // define the battery shape
            mShapePath.reset();
            mShapePath.moveTo(mButtonFrame.left, mButtonFrame.top);
            if (mHorizontal) {
                mShapePath.lineTo(mButtonFrame.right, mButtonFrame.top);
                mShapePath.lineTo(mButtonFrame.right, mButtonFrame.bottom);
                mShapePath.lineTo(mButtonFrame.left, mButtonFrame.bottom);
                mShapePath.lineTo(mFrame.right, mFrame.bottom);
                mShapePath.lineTo(mFrame.left, mFrame.bottom);
                mShapePath.lineTo(mFrame.left, mFrame.top);
                mShapePath.lineTo(mButtonFrame.left, mFrame.top);
                mShapePath.lineTo(mButtonFrame.left, mButtonFrame.top);
            } else {
                mShapePath.lineTo(mButtonFrame.right, mButtonFrame.top);
                mShapePath.lineTo(mButtonFrame.right, mFrame.top);
                mShapePath.lineTo(mFrame.right, mFrame.top);
                mShapePath.lineTo(mFrame.right, mFrame.bottom);
                mShapePath.lineTo(mFrame.left, mFrame.bottom);
                mShapePath.lineTo(mFrame.left, mFrame.top);
                mShapePath.lineTo(mButtonFrame.left, mFrame.top);
                mShapePath.lineTo(mButtonFrame.left, mButtonFrame.top);
            }

            final float frameTop = mFrame.top;
            final float frameRight = mFrame.right;

            // draw the battery shape background
            c.drawPath(mShapePath, mFramePaint);

            // draw the battery shape, clipped to charging level
            if (mHorizontal) {
                mFrame.right = levelTop;
            } else {
                mFrame.top = levelTop;
            }
            mClipPath.reset();
            mClipPath.addRect(mFrame,  Path.Direction.CCW);
            mShapePath.op(mClipPath, Path.Op.INTERSECT);
            c.drawPath(mShapePath, mBatteryPaint);

            if (tracker.shouldIndicateCharging()
                    && (!mShowPercent || mChargeAnimDisabled)) {
                // define the bolt shape
                final float bl = mFrame.left + mFrame.width() / (mHorizontal ? 9f : 4.5f);
                final float bt = frameTop + mFrame.height() / (mHorizontal ? 4.5f : 6f);
                final float br = frameRight - mFrame.width() / (mHorizontal ? 6f : 7f);
                final float bb = mFrame.bottom - mFrame.height() / (mHorizontal ? 7f : 10f);
                if (mBoltFrame.left != bl || mBoltFrame.top != bt
                        || mBoltFrame.right != br || mBoltFrame.bottom != bb
                        || mFastCharging != fastCharging) {
                    mBoltFrame.set(bl, bt, br, bb);
                    mFastCharging = fastCharging;
                    mBoltPath.reset();
                    if (fastCharging) {
                        final float size = Math.min(mFrame.width(), mFrame.height()) * .66f;
                        final float thick = size * .33f;
                        final float bx = (mFrame.width() - size) / 2;
                        final float by = (mFrame.height() - size) / 3;
                        final float x = bx + size/2 - thick/2;
                        final float y = by*2 + size/2 - thick/2;
                        mBoltPath.addRect(bx, y, bx + size, y + thick, Path.Direction.CW);
                        mBoltPath.addRect(x, by*2, x + thick, by*2 + size, Path.Direction.CW);
                    } else {
                        mBoltPath.moveTo(
                                mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                                mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                        for (int i = 2; i < mBoltPoints.length; i += 2) {
                            mBoltPath.lineTo(
                                    mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                                    mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                        }
                        mBoltPath.lineTo(
                                mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                                mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                    }
                }
                // draw the bolt
                c.drawPath(mBoltPath, mBoltPaint);
            } else if (!tracker.plugged && level <= mCriticalLevel) {
                // draw the warning text
                final float x = mWidth * 0.5f;
                final float y = (mHeight + mWarningTextHeight) * 0.48f;
                c.drawText(mWarningString, x, y, mWarningTextPaint);
            } else if (mShowPercent
                    && !(tracker.level == 100 && !SHOW_100_PERCENT)) {
                // compute percentage text
                float pctX = 0, pctY = 0;
                String pctText = null;
                final float full = mHorizontal ? 0.60f : 0.45f;
                final float nofull = mHorizontal ? 0.75f : 0.6f;
                final float single = mHorizontal ? 0.86f : 0.75f;
                mTextPaint.setTextSize(height *
                        (SINGLE_DIGIT_PERCENT ? single
                                : (tracker.level == 100 ? full : nofull)));
                mTextHeight = -mTextPaint.getFontMetrics().ascent;
                pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level/10) : level);
                pctX = mWidth * 0.5f;
                pctY = (mHeight + mTextHeight) * 0.47f;
                // draw the percentage text
                c.drawText(pctText, pctX, pctY, mTextPaint);
            }
        }

        @Override
        public void onDispose() {
            mHandler.removeCallbacks(mInvalidate);
            mDisposed = true;
        }

        @Override
        public void onSizeChanged(int w, int h, int oldw, int oldh) {
            mHeight = h;
            mWidth = w;
            mWarningTextPaint.setTextSize(h * 0.75f);
            mWarningTextHeight = -mWarningTextPaint.getFontMetrics().ascent;
        }

        private float[] loadBoltPoints(Resources res) {
            final int[] pts = res.getIntArray(getBoltPointsArrayResource());
            int maxX = 0, maxY = 0;
            for (int i = 0; i < pts.length; i += 2) {
                maxX = Math.max(maxX, pts[i]);
                maxY = Math.max(maxY, pts[i + 1]);
            }
            final float[] ptsF = new float[pts.length];
            for (int i = 0; i < pts.length; i += 2) {
                ptsF[i] = (float)pts[i] / maxX;
                ptsF[i + 1] = (float)pts[i + 1] / maxY;
            }
            return ptsF;
        }

        protected int getBoltPointsArrayResource() {
            return mHorizontal
                    ? R.array.batterymeter_inverted_bolt_points
                    : R.array.batterymeter_bolt_points;
        }
    }

    protected class CircleBatteryMeterDrawable implements BatteryMeterDrawable {
        private static final boolean SINGLE_DIGIT_PERCENT = false;
        private static final boolean SHOW_100_PERCENT = false;

        private static final int FULL = 96;

        public static final float STROKE_WITH = 6.5f;

        private boolean mDisposed;

        private int     mCircleSize;    // draw size of circle
        private RectF   mRectLeft;      // contains the precalculated rect used in drawArc(),
                                        // derived from mCircleSize
        private float   mTextX, mTextY; // precalculated position for drawText() to appear centered

        private Paint   mTextPaint;
        private Paint   mFastChargingPaint;
        private Paint   mFrontPaint;
        private Paint   mBackPaint;
        private Paint   mBoltPaint;
        private Paint   mWarningTextPaint;

        private final RectF mBoltFrame = new RectF();

        private final float[] mBoltPoints;
        private final Path mBoltPath = new Path();

        public CircleBatteryMeterDrawable(Resources res) {
            super();
            mDisposed = false;

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextPaint.setTypeface(font);
            mTextPaint.setTextAlign(Paint.Align.CENTER);

            mFastChargingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mFastChargingPaint.setTypeface(font);
            mFastChargingPaint.setTextAlign(Paint.Align.CENTER);

            mFrontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFrontPaint.setStrokeCap(Paint.Cap.BUTT);
            mFrontPaint.setDither(true);
            mFrontPaint.setStrokeWidth(0);
            mFrontPaint.setStyle(Paint.Style.STROKE);

            mBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBackPaint.setStrokeCap(Paint.Cap.BUTT);
            mBackPaint.setDither(true);
            mBackPaint.setStrokeWidth(0);
            mBackPaint.setStyle(Paint.Style.STROKE);

            mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mWarningTextPaint.setTypeface(font);
            mWarningTextPaint.setTextAlign(Paint.Align.CENTER);

            mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBoltPoints = loadBoltPoints(res);
        }

        @Override
        public void onDraw(Canvas c, BatteryTracker tracker) {
            if (mDisposed) return;

            if (mRectLeft == null) {
                initSizeBasedStuff();
            }

            if (mIsAnimating) {
                updateChargeAnim(tracker);
            } else {
                startChargeAnim(tracker);
            }
            drawCircle(c, tracker, mTextX, mRectLeft);
        }

        @Override
        public void onDispose() {
            mHandler.removeCallbacks(mInvalidate);
            mDisposed = true;
        }

        @Override
        public void onSizeChanged(int w, int h, int oldw, int oldh) {
            initSizeBasedStuff();
        }

        private float[] loadBoltPoints(Resources res) {
            final int[] pts = res.getIntArray(getBoltPointsArrayResource());
            int maxX = 0, maxY = 0;
            for (int i = 0; i < pts.length; i += 2) {
                maxX = Math.max(maxX, pts[i]);
                maxY = Math.max(maxY, pts[i + 1]);
            }
            final float[] ptsF = new float[pts.length];
            for (int i = 0; i < pts.length; i += 2) {
                ptsF[i] = (float)pts[i] / maxX;
                ptsF[i + 1] = (float)pts[i + 1] / maxY;
            }
            return ptsF;
        }

        protected int getBoltPointsArrayResource() {
            return R.array.batterymeter_bolt_points;
        }

        private void drawCircle(Canvas canvas, BatteryTracker tracker,
                float textX, RectF drawRect) {
            boolean unknownStatus = tracker.status == BatteryManager.BATTERY_STATUS_UNKNOWN;
            boolean fastCharging;
            int level = tracker.level;

            // set the battery colors
            mBackPaint.setColor(getColorForLevel(50, false));
            mBackPaint.setAlpha(102);
            mFrontPaint.setColor(tracker.plugged ? getColorForLevel(50, false) : getColorForLevel(level, false));
            // set the text colors
            mWarningTextPaint.setColor(getColorForLevel(10, true));
            mTextPaint.setColor(getColorForLevel(level, true));
            mBoltPaint.setColor(getColorForLevel(50, true));

            if (mIsCircleDotted) {
                // change mPaintStatus from solid to dashed
                mFrontPaint.setPathEffect(
                        new DashPathEffect(new float[]{mDotLength,mDotInterval},0));
            } else {
                mFrontPaint.setPathEffect(null);
            }

            Paint paint;

            fastCharging = tracker.chargeRate == BatteryManager.BATTERY_CHARGE_RATE_FAST_CHARGING;

            if (unknownStatus) {
                paint = mBackPaint;
                level = 100; // Draw all the circle;
            } else {
                paint = mFrontPaint;
                if (tracker.status == BatteryManager.BATTERY_STATUS_FULL) {
                    level = 100;
                }
            }

            // draw thin gray ring first
            canvas.drawArc(drawRect, 270, 360, false, mBackPaint);
            // draw colored arc representing charge level
            canvas.drawArc(drawRect, 270, mIsAnimating ? 3.6f * mAnimLevel : 3.6f * level, false, paint);
            // if chosen by options, draw percentage text in the middle
            // always skip percentage when 100, so layout doesnt break
            if (unknownStatus) {
                canvas.drawText("?", textX, mTextY, mTextPaint);
            } else if (fastCharging) {
                //not needed? mFastChargingPaint.setColor(getColorForLevel(level));
                canvas.drawText("+", textX, mTextY, mFastChargingPaint);
            } else if (tracker.shouldIndicateCharging()
                    && (!mShowPercent || mChargeAnimDisabled)) {
                // draw the bolt
                final float bl = (int)(drawRect.left + drawRect.width() / 3.2f);
                final float bt = (int)(drawRect.top + drawRect.height() / 4f);
                final float br = (int)(drawRect.right - drawRect.width() / 5.2f);
                final float bb = (int)(drawRect.bottom - drawRect.height() / 8f);
                if (mBoltFrame.left != bl || mBoltFrame.top != bt
                        || mBoltFrame.right != br || mBoltFrame.bottom != bb) {
                    mBoltFrame.set(bl, bt, br, bb);
                    mBoltPath.reset();
                    mBoltPath.moveTo(
                            mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                    for (int i = 2; i < mBoltPoints.length; i += 2) {
                        mBoltPath.lineTo(
                                mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                                mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                    }
                    mBoltPath.lineTo(
                            mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                }
                canvas.drawPath(mBoltPath, mBoltPaint);
            } else if (!tracker.plugged && level <= mCriticalLevel) {
                // draw the warning text
                canvas.drawText(mWarningString, textX, mTextY, mWarningTextPaint);
            } else if (mShowPercent
                    && !(tracker.level == 100 && !SHOW_100_PERCENT)) {
                // draw the percentage text
                String pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level/10) : level);
                canvas.drawText(pctText, textX, mTextY, mTextPaint);
            }
        }

        /**
         * initializes all size dependent variables
         * sets stroke width and text size of all involved paints
         * YES! i think the method name is appropriate
         */
        private void initSizeBasedStuff() {
            mCircleSize = Math.min(getMeasuredWidth(), getMeasuredHeight());
            mTextPaint.setTextSize(mCircleSize / 2f);
            mFastChargingPaint.setTextSize(mCircleSize / 1.5f);
            mWarningTextPaint.setTextSize(mCircleSize / 2f);

            float strokeWidth = mCircleSize / STROKE_WITH;
            mFrontPaint.setStrokeWidth(strokeWidth);
            mBackPaint.setStrokeWidth(strokeWidth);

            // calculate rectangle for drawArc calls
            int pLeft = getPaddingLeft();
            mRectLeft = new RectF(pLeft + strokeWidth / 2.0f, 0 + strokeWidth / 2.0f, mCircleSize
                    - strokeWidth / 2.0f + pLeft, mCircleSize - strokeWidth / 2.0f);

            // calculate Y position for text
            Rect bounds = new Rect();
            mTextPaint.getTextBounds("99", 0, "99".length(), bounds);
            mTextX = mCircleSize / 2.0f + getPaddingLeft();
            // the +1dp at end of formula balances out rounding issues.works out on all resolutions
            mTextY = mCircleSize / 2.0f + (bounds.bottom - bounds.top) / 2.0f
                    - strokeWidth / 2.0f + getResources().getDisplayMetrics().density;
        }
    }

    private void startChargeAnim(BatteryTracker tracker) {
        if (!tracker.shouldIndicateCharging()
                || tracker.status == BatteryManager.BATTERY_STATUS_FULL
                || mChargeAnimDisabled) {
            return;
        }
        mIsAnimating = true;
        mAnimLevel = tracker.level;

        updateChargeAnim(tracker);
    }

    /**
     * updates the animation counter
     * cares for timed callbacks to continue animation cycles
     * uses mInvalidate for delayed invalidate() callbacks
     */
    private void updateChargeAnim(BatteryTracker tracker) {
        // Stop animation when battery is full or after the meter
        // rotated back to 0 after unplugging.
        if ((!tracker.shouldIndicateCharging() && mAnimLevel == 0)
                || (mChargeAnimDisabled && mAnimLevel == 0)
                || tracker.status == BatteryManager.BATTERY_STATUS_FULL) {
            stopChargeAnim();
            return;
        }

        if (mAnimLevel > 100) {
            mAnimLevel = 0;
        } else {
            mAnimLevel += mChargeAnimSpeed;
        }

        mHandler.removeCallbacks(mInvalidate);
        mHandler.postDelayed(mInvalidate, 50);
    }

    private void stopChargeAnim() {
        mIsAnimating = false;
        mAnimLevel = 0;
        mHandler.removeCallbacks(mInvalidate);
        invalidateIfVisible();
    }
}
