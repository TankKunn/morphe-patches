/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.content.res.ColorStateList;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import java.util.function.Consumer;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.shared.PlayerControlsVisibility;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;
import app.morphe.extension.youtube.settings.Settings;

/** Phase 2a diagnostic: maps a video-relative crop to SurfaceView buffer pixels. */
public final class MagnifierRecon {
    private static final String TAG = "MagnifierRecon";
    private static final String PERF_TAG = "MagnifierPerf";
    private static final String PLAYER_VIEW_CLASS =
            "com.google.android.libraries.youtube.player.ui.PlayerView";
    private static final double DEFAULT_SOURCE_WIDTH = 0.25d;
    private static final double DEFAULT_SOURCE_ASPECT = 16d / 9d;
    private static final double DEFAULT_ZOOM_WIDTH = 0.40d;
    private static final int MAX_BITMAP_EDGE = 4096;
    private static final int SIGNATURE_GRID_SIZE = 16;
    private static final WeakHashMap<View, String> LAST_LAYOUTS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> WATCHED_DECORS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> KNOWN_DECORS = new WeakHashMap<>();
    private static final WeakHashMap<View, View> CONTROL_ANCHORS = new WeakHashMap<>();
    private static final WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener> INSPECTORS =
            new WeakHashMap<>();
    private static Prototype prototype;
    private static UiState savedUiState;
    private static String lastVideoId;
    private static boolean settingsObserverRegistered;
    private static final SharedPreferences.OnSharedPreferenceChangeListener SETTINGS_LISTENER =
            (preferences, key) -> {
                if (!"morphe_magnifier_enabled".equals(key)) return;
                Utils.runOnMainThread(MagnifierRecon::applyEnabledSettingToKnownPlayers);
            };

    /** Called from Morphe's shared video-ID hook; same-ID loop/quality callbacks are ignored. */
    public static void onVideoIdChanged(String videoId) {
        if (videoId == null || videoId.isEmpty() || videoId.equals(lastVideoId)) return;
        lastVideoId = videoId;
        if (!Settings.MAGNIFIER_ENABLED.get()) return;
        if (prototype != null) prototype.onNewVideo(videoId);
    }

    public static void resetStoredDefaults() {
        savedUiState = null;
        Settings.MAGNIFIER_BORDER_THICKNESS.save(2);
        Settings.MAGNIFIER_BORDER_OPACITY.save(100);
        Settings.MAGNIFIER_BORDER_COLOR.save(0);
        Activity activity = Utils.getActivity();
        if (activity != null) activity.getSharedPreferences("morphe_magnifier_geometry",
                android.content.Context.MODE_PRIVATE).edit().clear().apply();
        if (prototype != null && !prototype.stopped) prototype.resetDefaults();
    }

    private static void logPerf(String message) {
        if (Settings.DEBUG.get()) Log.i(PERF_TAG, message);
    }

    private static boolean isValidSourceGeometry(float left, float top, float width, float height) {
        return Float.isFinite(left) && Float.isFinite(top) && Float.isFinite(width)
                && Float.isFinite(height) && left >= 0f && top >= 0f
                && width > 0f && height > 0f && width <= 1f && height <= 1f
                && left + width <= 1.001f && top + height <= 1.001f;
    }

    /** Process-memory state only: survives player view replacement, never an app restart. */
    private static final class UiState {
        final float sourceLeft, sourceTop, sourceWidth, sourceHeight;
        final float zoomLeft, zoomTop, zoomWidth, chromeLeft, chromeTop;
        final int borderThicknessDp, borderOpacity, borderColor;
        final boolean editMode, stylePanelOpen;

        UiState(Prototype current) {
            sourceLeft = current.sourceLeftFraction;
            sourceTop = current.sourceTopFraction;
            sourceWidth = current.sourceWidthFraction;
            sourceHeight = current.sourceHeightFraction;
            zoomLeft = current.zoomLeftFraction;
            zoomTop = current.zoomTopFraction;
            zoomWidth = current.zoomWidthFraction;
            chromeLeft = current.chromeLeftFraction;
            chromeTop = current.chromeTopFraction;
            borderThicknessDp = current.borderThicknessDp;
            borderOpacity = current.borderOpacity;
            borderColor = current.borderColor;
            editMode = current.editMode;
            stylePanelOpen = current.stylePanelOpen;
        }
    }

    private MagnifierRecon() {
    }

    /** Additive injection point reached when YouTube initializes player controls. */
    public static void onPlayerControlsInitialized(View controlsView) {
        final Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) {
            Log.w(TAG, "Player controls initialized without an active Activity/window");
            return;
        }

        final View decorView = activity.getWindow().getDecorView();
        if (controlsView != null) CONTROL_ANCHORS.put(decorView, controlsView);
        KNOWN_DECORS.put(decorView, Boolean.TRUE);
        registerSettingsObserver();
        if (!Settings.MAGNIFIER_ENABLED.get()) {
            unwatchDecor(decorView);
            if (prototype != null && prototype.decor == decorView) {
                prototype.stop();
                prototype = null;
            }
            return;
        }
        watchDecor(decorView);
    }

    private static void registerSettingsObserver() {
        if (settingsObserverRegistered || Setting.preferences == null
                || Setting.preferences.preferences == null) return;
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(SETTINGS_LISTENER);
        settingsObserverRegistered = true;
    }

    private static void applyEnabledSettingToKnownPlayers() {
        final View[] decors = KNOWN_DECORS.keySet().toArray(new View[0]);
        if (!Settings.MAGNIFIER_ENABLED.get()) {
            if (prototype != null) {
                prototype.stop();
                prototype = null;
            }
            for (View decor : decors) unwatchDecor(decor);
            return;
        }
        for (View decor : decors) {
            if (decor != null && decor.isAttachedToWindow()) watchDecor(decor);
        }
    }

    private static void watchDecor(View decorView) {
        if (!Settings.MAGNIFIER_ENABLED.get() || decorView == null) return;
        if (WATCHED_DECORS.put(decorView, Boolean.TRUE) == null) {
            final ViewTreeObserver.OnGlobalLayoutListener listener =
                    () -> decorView.postOnAnimation(() -> inspect(decorView));
            INSPECTORS.put(decorView, listener);
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        }
        decorView.postOnAnimation(() -> inspect(decorView));
    }

    private static void unwatchDecor(View decorView) {
        if (decorView == null || WATCHED_DECORS.remove(decorView) == null) return;
        final ViewTreeObserver.OnGlobalLayoutListener listener = INSPECTORS.remove(decorView);
        if (listener != null && decorView.getViewTreeObserver().isAlive()) {
            decorView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }

    private static void inspect(View decorView) {
        if (!Settings.MAGNIFIER_ENABLED.get()) {
            unwatchDecor(decorView);
            if (prototype != null && prototype.decor == decorView) {
                prototype.stop();
                prototype = null;
            }
            return;
        }
        final StringBuilder snapshot = new StringBuilder();
        final SurfaceView[] largestSurface = new SurfaceView[1];
        final int[] largestArea = new int[1];
        inspectTree(decorView, snapshot, largestSurface, largestArea);
        final String value = snapshot.length() == 0 ? "none" : snapshot.toString();
        if (!value.equals(LAST_LAYOUTS.get(decorView))) {
            LAST_LAYOUTS.put(decorView, value);
            if (snapshot.length() == 0) {
                Log.i(TAG, "no SurfaceView or TextureView under decor view");
            } else {
                for (String line : value.split("\\n")) Log.i(TAG, line);
            }
        }

        PlayerType currentType = PlayerType.getCurrent();
        if (!isWatchSurfaceType(currentType)) {
            if (prototype != null && prototype.decor == decorView) {
                if (isTerminalPlayerType(currentType)) {
                    prototype.onPlayerClosed("player-type:" + currentType);
                    prototype = null;
                } else {
                    prototype.onVisibilityChanged("player-type:" + currentType);
                }
            }
            return;
        }

        if (largestSurface[0] == null || !(decorView instanceof ViewGroup)) {
            if (prototype != null && prototype.decor == decorView) {
                prototype.stop();
                prototype = null;
            }
            return;
        }

        if (prototype == null || prototype.surface != largestSurface[0]
                || prototype.decor != decorView) {
            if (prototype != null) prototype.stop();
            prototype = new Prototype((ViewGroup) decorView, largestSurface[0],
                    CONTROL_ANCHORS.get(decorView));
        }
        prototype.updatePositionAndGeometry();
    }

    private static boolean isWatchSurfaceType(PlayerType type) {
        return type == PlayerType.WATCH_WHILE_MAXIMIZED
                || type == PlayerType.WATCH_WHILE_FULLSCREEN
                || type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN;
    }

    private static boolean isTerminalPlayerType(PlayerType type) {
        return type == PlayerType.NONE || type == PlayerType.HIDDEN
                || type == PlayerType.INLINE_MINIMAL
                || type == PlayerType.VIRTUAL_REALITY_FULLSCREEN
                || type == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED
                || type == PlayerType.WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED;
    }

    private static void inspectTree(View view, StringBuilder snapshot,
                                    SurfaceView[] largestSurface, int[] largestArea) {
        if (view instanceof SurfaceView || view instanceof TextureView) {
            final int[] location = new int[2];
            view.getLocationOnScreen(location);
            final String secure = view instanceof SurfaceView
                    ? surfaceSecureState((SurfaceView) view)
                    : "not-applicable";

            if (snapshot.length() > 0) snapshot.append('\n');
            snapshot.append("surface class=").append(view.getClass().getName())
                    .append(" size=").append(view.getWidth()).append("x").append(view.getHeight())
                    .append(" positionOnScreen=").append(location[0]).append(',').append(location[1])
                    .append(" isSecure=").append(secure)
                    .append(" parentChain=").append(parentChain(view));
            if (view instanceof SurfaceView) {
                final SurfaceView surface = (SurfaceView) view;
                final Rect frame = surface.getHolder().getSurfaceFrame();
                snapshot.append(" surfaceFrame=").append(frame);
                final VideoSize videoSize = readVideoSize(surface);
                snapshot.append(" videoSize=").append(videoSize == null
                        ? "unavailable" : videoSize.width + "x" + videoSize.height);
                final int area = view.getWidth() * view.getHeight();
                if (area > largestArea[0]) {
                    largestArea[0] = area;
                    largestSurface[0] = surface;
                }
            }
        }

        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                inspectTree(group.getChildAt(index), snapshot, largestSurface, largestArea);
            }
        }
    }

    /** Read media dimensions through YouTube's named PlayerView interface, for diagnostics only. */
    private static VideoSize readVideoSize(View view) {
        View current = view;
        while (current != null) {
            Class<?> type = current.getClass();
            while (type != null) {
                if (PLAYER_VIEW_CLASS.equals(type.getName())) {
                    try {
                        final Field rendererField = type.getField("c");
                        final Object renderer = rendererField.get(current);
                        if (renderer == null) return null;
                        final Method widthMethod = renderer.getClass().getMethod("e");
                        final Method heightMethod = renderer.getClass().getMethod("d");
                        final int width = ((Number) widthMethod.invoke(renderer)).intValue();
                        final int height = ((Number) heightMethod.invoke(renderer)).intValue();
                        if (width > 0 && height > 0) return new VideoSize(width, height);
                    } catch (ReflectiveOperationException | RuntimeException exception) {
                        Log.d(TAG, "YouTube video-size diagnostic unavailable: "
                                + exception.getClass().getSimpleName());
                    }
                }
                type = type.getSuperclass();
            }
            final Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static View findPlayerArea(View surface) {
        View current = surface;
        while (current != null) {
            Class<?> type = current.getClass();
            while (type != null) {
                if (PLAYER_VIEW_CLASS.equals(type.getName())) return current;
                type = type.getSuperclass();
            }
            final Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return surface.getParent() instanceof View ? (View) surface.getParent() : surface;
    }

    /** Temporary 2a geometry and PixelCopy skeleton; later checkpoints replace the decor overlay. */
    private static final class Prototype {
        private final ViewGroup decor;
        private final SurfaceView surface;
        private final View playerArea;
        private final View playerControlsAnchor;
        private ViewGroup normalZoomLayer;
        private final View playerGearButton;
        private final FrameLayout overlay;
        private final SourceOutline sourceOutline;
        private final FrameLayout zoomWindow;
        private final ImageView zoomImage;
        private final TextView stateLabel;
        private final View touchShield;
        private final LinearLayout chrome;
        private final ImageView doneButton;
        private final ImageView offButton;
        private final ImageView styleButton;
        private final ImageView editModeButton;
        private final FrameLayout stylePanelViewport;
        private final ScrollView stylePanel;
        private final LinearLayout styleRows;
        private abstract static class CompatiblePlayerObserver<T>
                implements Consumer<T>, Function1<T, Unit> {
            @Override public final void accept(T value) {
                onChanged(value);
            }

            @Override public final Unit invoke(T value) {
                onChanged(value);
                return Unit.INSTANCE;
            }

            protected abstract void onChanged(T value);
        }

        private final WeakHashMap<View, Integer> savedControlVisibility = new WeakHashMap<>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Choreographer choreographer = Choreographer.getInstance();
        private final Bitmap[] bitmaps = new Bitmap[2];
        private boolean stopped;
        private final Choreographer.FrameCallback frameCallback = this::onVsync;
        private final CompatiblePlayerObserver<VideoState> videoStateObserver =
                new CompatiblePlayerObserver<VideoState>() {
                    @Override protected void onChanged(VideoState state) {
                        handler.post(() -> onVideoStateChanged(state, "video-state-hook"));
                    }
                };
        private final CompatiblePlayerObserver<PlayerControlsVisibility> controlsObserver =
                new CompatiblePlayerObserver<PlayerControlsVisibility>() {
                    @Override protected void onChanged(PlayerControlsVisibility state) {
                        handler.post(() -> {
                            if (Prototype.this.editMode) hidePlayerControls();
                            else updatePositionAndGeometry();
                        });
                    }
                };
        private final CompatiblePlayerObserver<PlayerType> playerTypeObserver =
                new CompatiblePlayerObserver<PlayerType>() {
                    @Override protected void onChanged(PlayerType type) {
                        handler.post(() -> {
                            if (isTerminalPlayerType(type)) {
                                onPlayerClosed("player-type:" + type);
                                if (prototype == Prototype.this) prototype = null;
                            } else onVisibilityChanged("player-type:" + type);
                        });
                    }
                };
        private final android.view.ViewTreeObserver.OnWindowFocusChangeListener focusListener =
                this::onWindowFocusChanged;
        private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) {
                String action = intent == null ? "screen-state-unknown" : intent.getAction();
                handler.post(() -> onVisibilityChanged("broadcast:" + action));
            }
        };
        private final ViewTreeObserver.OnPreDrawListener gearButtonLayoutListener = () -> {
            if (!stopped) updateEditModeButtonPosition();
            return true;
        };
        private final Runnable metricsReporter = new Runnable() {
            @Override public void run() {
                if (stopped) return;
                reportMetrics();
                handler.postDelayed(this, 1000L);
            }
        };
        private final Runnable controlsHideEnforcer = new Runnable() {
            @Override public void run() {
                if (stopped || !editMode) return;
                hidePlayerControls();
                handler.postDelayed(this, 250L);
            }
        };

        private boolean inFlight;
        private boolean editMode;
        // Enabled is deliberately not restored: every new app/player session starts OFF.
        private boolean enabled;
        private boolean geometryInitialized;
        private boolean stylePanelOpen;
        private boolean zoomInPlayerLayer;
        private boolean forwardingNormalTap;
        private boolean normalLongPressDragging;
        private boolean normalTapEligible;
        private boolean lastCaptureVisible;
        private boolean frameCallbackPending;
        private boolean oneShotFastRefresh;
        private CaptureMode captureMode;
        private VideoState playerState;
        private String modeReason = "initial-state-unavailable-default-fast";
        private int generation;
        private int desiredBitmapWidth;
        private int desiredBitmapHeight;
        private int allocatedBitmapWidth;
        private int allocatedBitmapHeight;
        private int displayedBuffer = -1;
        private int nextBuffer;
        private long lastFrameSignature = Long.MIN_VALUE;
        private int secondCopies;
        private int secondUniqueFrames;
        private int secondDuplicateFrames;
        private int secondBusyVsyncs;
        private long secondLatencyTotalMs;
        private long secondLatencyMaxMs;
        private long lastCopyMs;
        private long secondStartMs;
        private long lastErrorLogMs;
        private String lastRequestReason = "initial";
        private String lastGeometry = "";
        private String lastPosition = "";
        private float normalDownRawX;
        private float normalDownRawY;
        private float normalZoomLeft;
        private float normalZoomTop;
        private Rect lastCrop = new Rect();
        private float sourceLeftFraction = 0.375f;
        private float sourceTopFraction = 0.5f;
        private float sourceWidthFraction = (float) DEFAULT_SOURCE_WIDTH;
        private float sourceHeightFraction;
        private float zoomLeftFraction = 0.58f;
        private float zoomTopFraction = 0.30f;
        private float zoomWidthFraction = (float) DEFAULT_ZOOM_WIDTH;
        private float chromeLeftFraction = 1f;
        private float chromeTopFraction = 0f;
        private float chromeDownX, chromeDownY, chromeStartLeft, chromeStartTop;
        private boolean chromeDragging;
        private final int chromeTouchSlop;
        private final Runnable beginNormalZoomDrag = () -> {
            if (stopped || !enabled || editMode || !normalTapEligible) return;
            normalLongPressDragging = true;
            normalZoomLeft = zoomLeftFraction;
            normalZoomTop = zoomTopFraction;
            logPerf("zoom-window-move-start reason=long-press controls-gesture=blocked");
        };
        private int borderThicknessDp = 2;
        private int borderOpacity = 100;
        private int borderColor = Color.WHITE;
        private final int[] borderColors = new int[]{
                Color.WHITE, 0xff00b7bd, Color.YELLOW, Color.RED,
                Color.GREEN, 0xffff9800, Color.MAGENTA, Color.BLACK};
        private final String[] borderColorNames = new String[]{
                "White", "Cyan", "Yellow", "Red", "Green", "Orange", "Magenta", "Black"};
        private RectF videoRectOnSurface = new RectF();
        private int lastVideoWidth;
        private int lastVideoHeight;
        private int lastBufferWidth;
        private int lastBufferHeight;

        Prototype(ViewGroup decor, SurfaceView surface, View playerControlsAnchor) {
            this.decor = decor;
            chromeTouchSlop = ViewConfiguration.get(decor.getContext()).getScaledTouchSlop();
            this.surface = surface;
            this.playerArea = findPlayerArea(surface);
            this.playerControlsAnchor = playerControlsAnchor;
            final int playerGearId = ResourceUtils.getIdentifier(ResourceType.ID,
                    "player_overflow_button");
            this.playerGearButton = playerGearId == 0 ? null : decor.findViewById(playerGearId);
            final UiState restored = savedUiState;
            if (restored != null && isValidSourceGeometry(restored.sourceLeft, restored.sourceTop,
                    restored.sourceWidth, restored.sourceHeight)) {
                sourceLeftFraction = restored.sourceLeft;
                sourceTopFraction = restored.sourceTop;
                sourceWidthFraction = restored.sourceWidth;
                sourceHeightFraction = restored.sourceHeight;
                zoomLeftFraction = restored.zoomLeft;
                zoomTopFraction = restored.zoomTop;
                zoomWidthFraction = restored.zoomWidth;
                chromeLeftFraction = restored.chromeLeft;
                chromeTopFraction = restored.chromeTop;
                borderThicknessDp = restored.borderThicknessDp;
                borderOpacity = restored.borderOpacity;
                borderColor = restored.borderColor;
                editMode = false;
                stylePanelOpen = false;
                geometryInitialized = true;
            } else {
                android.content.SharedPreferences prefs = decor.getContext().getSharedPreferences(
                        "morphe_magnifier_geometry", android.content.Context.MODE_PRIVATE);
                sourceLeftFraction = prefs.getFloat("source_left", sourceLeftFraction);
                sourceTopFraction = prefs.getFloat("source_top", sourceTopFraction);
                sourceWidthFraction = prefs.getFloat("source_width", sourceWidthFraction);
                sourceHeightFraction = prefs.getFloat("source_height", sourceHeightFraction);
                zoomLeftFraction = prefs.getFloat("zoom_left", zoomLeftFraction);
                zoomTopFraction = prefs.getFloat("zoom_top", zoomTopFraction);
                zoomWidthFraction = prefs.getFloat("zoom_width", zoomWidthFraction);
                chromeLeftFraction = prefs.getFloat("chrome_left", chromeLeftFraction);
                chromeTopFraction = prefs.getFloat("chrome_top", chromeTopFraction);
                geometryInitialized = prefs.contains("source_left")
                        && isValidSourceGeometry(sourceLeftFraction, sourceTopFraction,
                        sourceWidthFraction, sourceHeightFraction);
                if (!geometryInitialized) {
                    sourceLeftFraction = 0.375f;
                    sourceTopFraction = 0.5f;
                    sourceWidthFraction = (float) DEFAULT_SOURCE_WIDTH;
                    sourceHeightFraction = 0f;
                }
            }
            borderThicknessDp = Settings.MAGNIFIER_BORDER_THICKNESS.get();
            borderOpacity = Settings.MAGNIFIER_BORDER_OPACITY.get();
            borderColor = borderColors[clamp(Settings.MAGNIFIER_BORDER_COLOR.get(),
                    0, borderColors.length - 1)];
            overlay = new FrameLayout(decor.getContext());
            overlay.setTag("morphe.magnifier.2a.overlay");
            // This decor-sized overlay contains only the edit affordance in normal mode. Keep
            // it above YouTube's player/control layers so that the visible icon also receives
            // touch dispatch; the transparent rest of the overlay remains pass-through.
            overlay.setElevation(playerControlStackZ() + dp(16));
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            sourceOutline = new SourceOutline(decor.getContext());
            sourceOutline.setTag("morphe.magnifier.2b.source");
            sourceOutline.setContentDescription("Magnifier source window; drag body or resize edges");
            sourceOutline.setVisibility(View.GONE);
            sourceOutline.setOnTouchListener(this::onSourceTouch);

            touchShield = new View(decor.getContext());
            touchShield.setTag("morphe.magnifier.2b.touch-shield");
            touchShield.setBackgroundColor(Color.TRANSPARENT);
            touchShield.setClickable(true);
            touchShield.setVisibility(View.GONE);
            touchShield.setOnTouchListener((view, event) -> true);

            zoomWindow = new ZoomFrameLayout(decor.getContext(), this);
            zoomWindow.setTag("morphe.magnifier.2b.zoom");
            zoomWindow.setBackgroundColor(Color.TRANSPARENT);
            zoomWindow.setContentDescription("Magnifier zoom window; drag body or resize sides in edit mode");
            zoomWindow.setOnTouchListener(this::onZoomTouch);

            zoomImage = new ImageView(decor.getContext());
            zoomImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            zoomImage.setBackgroundColor(0xff080808);
            zoomImage.setContentDescription("Magnified source crop");
            zoomImage.setTag("morphe.magnifier.2b.zoom-content");
            zoomWindow.addView(zoomImage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            stateLabel = new TextView(decor.getContext());
            stateLabel.setTextColor(0xffffffff);
            stateLabel.setTextSize(12f);
            stateLabel.setGravity(Gravity.CENTER);
            stateLabel.setBackgroundColor(0x99000000);
            stateLabel.setVisibility(View.GONE);
            zoomWindow.addView(stateLabel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            chrome = new LinearLayout(decor.getContext());
            chrome.setOrientation(LinearLayout.HORIZONTAL);
            chrome.setGravity(Gravity.CENTER_VERTICAL);
            chrome.setTag("morphe.magnifier.2b.chrome");
            doneButton = chromeIconButton(ChromeIconDrawable.DONE,
                    "Finish magnifier editing", "morphe.magnifier.2b.done");
            offButton = chromeIconButton(ChromeIconDrawable.OFF,
                    "Turn magnifier on or off", "morphe.magnifier.2b.off");
            styleButton = chromeIconButton(ChromeIconDrawable.STYLE,
                    "Open magnifier border style options", "morphe.magnifier.2b.style");
            doneButton.setOnClickListener(view -> exitEditMode());
            offButton.setOnClickListener(view -> disableMagnifier());
            styleButton.setOnClickListener(view -> toggleStylePanel());
            doneButton.setOnTouchListener(this::onChromeTouch);
            offButton.setOnTouchListener(this::onChromeTouch);
            styleButton.setOnTouchListener(this::onChromeTouch);
            chrome.setOnTouchListener(this::onChromeTouch);
            chrome.addView(doneButton, chromeButtonParams());
            chrome.addView(offButton, chromeButtonParams());
            chrome.addView(styleButton, chromeButtonParams());
            chrome.setVisibility(View.GONE);
            styleButton.setVisibility(View.GONE);

            editModeButton = new ImageView(decor.getContext());
            editModeButton.setTag("morphe.magnifier.2b.edit-icon");
            editModeButton.setContentDescription("Edit magnifier");
            editModeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            editModeButton.setPadding(dp(12), dp(12), dp(12), dp(12));
            final String editIconName = PlayerIcons.name("yt_outline_box_pencil");
            if (PlayerIcons.exists(editIconName)) {
                editModeButton.setImageResource(PlayerIcons.id(editIconName));
            } else {
                // The icon was added to newer YouTube builds; 21.16.256 has no matching drawable.
                editModeButton.setImageDrawable(new EditModeIconDrawable());
            }
            editModeButton.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            editModeButton.setClickable(true);
            editModeButton.setFocusable(true);
            editModeButton.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    enterEditMode();
                    view.performClick();
                }
                return true;
            });
            editModeButton.setOnClickListener(view -> { });
            editModeButton.setVisibility(View.GONE);

            styleRows = new LinearLayout(decor.getContext());
            styleRows.setOrientation(LinearLayout.VERTICAL);
            styleRows.setPadding(dp(8), dp(6), dp(8), dp(6));
            styleRows.setBackgroundColor(Color.TRANSPARENT);
            stylePanelViewport = new FrameLayout(decor.getContext());
            stylePanelViewport.setTag("morphe.magnifier.2b.style-viewport");
            stylePanelViewport.setClipChildren(true);
            stylePanelViewport.setClipToPadding(true);
            stylePanelViewport.setClickable(true);
            stylePanelViewport.setOnTouchListener((view, event) -> true);
            stylePanelViewport.setVisibility(View.GONE);
            stylePanel = new ScrollView(decor.getContext());
            stylePanel.setTag("morphe.magnifier.2b.style-panel");
            stylePanel.setBackgroundColor(0xc8202124);
            stylePanel.setClipChildren(true);
            stylePanel.setClipToPadding(true);
            stylePanel.addView(styleRows);
            stylePanel.setVisibility(View.GONE);
            stylePanelViewport.addView(stylePanel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            buildStylePanel();

            overlay.addView(touchShield, new FrameLayout.LayoutParams(1, 1));
            // Magnifier windows are attached only while enabled; the entry icon remains active.
            overlay.addView(stylePanelViewport, new FrameLayout.LayoutParams(1, 1));
            overlay.addView(editModeButton, new FrameLayout.LayoutParams(1, 1));
            overlay.addView(chrome, new FrameLayout.LayoutParams(1, 1));
            decor.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sourceOutline.setVisibility(View.GONE);
            sourceOutline.setClickable(false);
            zoomWindow.setVisibility(View.GONE);
            touchShield.setVisibility(View.GONE);
            styleButton.setAlpha(0.42f);
            styleButton.setEnabled(false);
            setStylePanelVisible(false);
            PlayerControlsVisibility.getOnChange().addObserver(controlsObserver);
            decor.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(Intent.ACTION_SCREEN_ON);
            screenFilter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                decor.getContext().registerReceiver(screenStateReceiver, screenFilter,
                        android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                decor.getContext().registerReceiver(screenStateReceiver, screenFilter);
            }
            if (playerGearButton != null) {
                playerGearButton.getViewTreeObserver().addOnPreDrawListener(gearButtonLayoutListener);
            } else Log.w(TAG, "YouTube player gear view not found; using a top-right edit-icon fallback");
            applyBorderStyle();
            secondStartMs = SystemClock.elapsedRealtime();
            captureMode = CaptureMode.IDLE;
            lastCaptureVisible = isCaptureTargetVisible();
        }

        int dp(float value) {
            return Math.round(value * decor.getResources().getDisplayMetrics().density);
        }

        private float playerControlStackZ() {
            float z = playerArea.getZ();
            View current = playerGearButton;
            while (current != null && current != decor) {
                z = Math.max(z, current.getZ());
                final Object parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            current = playerArea;
            while (current != null && current != decor) {
                z = Math.max(z, current.getZ());
                final Object parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            return z;
        }

        private int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
        }

        private TextView chromeButton(String text, String description) {
            TextView button = new TextView(decor.getContext());
            button.setText(text);
            button.setTextColor(Color.WHITE);
            button.setTextSize(14f);
            button.setGravity(Gravity.CENTER);
            button.setContentDescription(description);
            button.setBackground(buttonBackground());
            button.setPadding(dp(8), dp(5), dp(8), dp(5));
            button.setClickable(true);
            button.setFocusable(true);
            return button;
        }

        private ImageView chromeIconButton(int icon, String description, String tag) {
            ImageView button = new ImageView(decor.getContext());
            button.setTag(tag);
            button.setContentDescription(description);
            button.setImageDrawable(new ChromeIconDrawable(icon));
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setPadding(dp(9), dp(9), dp(9), dp(9));
            button.setBackground(new InsetDrawable(buttonBackground(), dp(5)));
            button.setClickable(true);
            button.setFocusable(true);
            return button;
        }

        private void setStylePanelVisible(boolean visible) {
            int state = visible ? View.VISIBLE : View.GONE;
            stylePanelViewport.setVisibility(state);
            stylePanel.setVisibility(state);
        }

        private GradientDrawable buttonBackground() {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(0xdd202124);
            drawable.setCornerRadius(dp(5));
            drawable.setStroke(dp(1), 0xffeeeeee);
            return drawable;
        }

        private LinearLayout.LayoutParams chromeButtonParams() {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(36), dp(36));
            params.leftMargin = dp(2);
            params.rightMargin = dp(2);
            return params;
        }

        private void buildStylePanel() {
            styleRows.removeAllViews();
            addStyleSlider("Thickness", " dp", 12, borderThicknessDp, value -> {
                borderThicknessDp = value;
                Settings.MAGNIFIER_BORDER_THICKNESS.save(value);
                applyBorderStyle();
                updatePositionAndGeometry();
            });
            addStyleSlider("Opacity", "%", 100, borderOpacity, value -> {
                borderOpacity = value;
                Settings.MAGNIFIER_BORDER_OPACITY.save(value);
                applyBorderStyle();
            });
            TextView colorTitle = new TextView(decor.getContext());
            colorTitle.setText("Color");
            colorTitle.setTextColor(Color.WHITE);
            colorTitle.setTextSize(13f);
            styleRows.addView(colorTitle);
            LinearLayout swatches = new LinearLayout(decor.getContext());
            swatches.setOrientation(LinearLayout.HORIZONTAL);
            swatches.setGravity(Gravity.CENTER_VERTICAL);
            for (int index = 0; index < borderColors.length; index++) {
                final int color = borderColors[index];
                final int colorIndex = index;
                final String name = borderColorNames[index];
                TextView swatch = new TextView(decor.getContext());
                swatch.setTag("morphe.magnifier.2b.swatch." + name.toLowerCase());
                swatch.setContentDescription(name + " border color");
                swatch.setText(" ");
                swatch.setBackgroundColor(color);
                swatch.setMinWidth(dp(24));
                swatch.setMinHeight(dp(28));
                swatch.setOnClickListener(view -> {
                    borderColor = color;
                    Settings.MAGNIFIER_BORDER_COLOR.save(colorIndex);
                    applyBorderStyle();
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(28), 1f);
                params.leftMargin = dp(2);
                params.rightMargin = dp(2);
                swatches.addView(swatch, params);
            }
            styleRows.addView(swatches);
            TextView reset = chromeButton("Reset", "Reset magnifier style and geometry");
            reset.setOnClickListener(view -> resetDefaults());
            LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(32));
            resetParams.topMargin = dp(2);
            styleRows.addView(reset, resetParams);
        }

        private interface IntChanged { void changed(int value); }

        private void addStyleSlider(String title, String unit, int max, int initial,
                                    IntChanged changed) {
            LinearLayout row = new LinearLayout(decor.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(decor.getContext());
            label.setTextColor(Color.WHITE);
            label.setTextSize(12f);
            label.setMinWidth(dp(82));
            label.setText(title + ": " + initial + unit);
            SeekBar seek = new SeekBar(decor.getContext());
            seek.setMax(max);
            seek.setProgress(initial);
            seek.setProgressTintList(ColorStateList.valueOf(0xff80cbc4));
            seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            seek.setProgressBackgroundTintList(ColorStateList.valueOf(0xff777777));
            seek.setContentDescription(title + " border");
            row.addView(label, new LinearLayout.LayoutParams(dp(76), dp(32)));
            row.addView(seek, new LinearLayout.LayoutParams(0, dp(32), 1f));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    label.setText(title + ": " + value + unit);
                    changed.changed(value);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            styleRows.addView(row);
        }

        private void applyBorderStyle() {
            if (zoomWindow == null) return;
            int alpha = Math.round(255f * borderOpacity / 100f);
            int color = (borderColor & 0x00ffffff) | (alpha << 24);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.TRANSPARENT);
            if (borderThicknessDp > 0) background.setStroke(dp(borderThicknessDp), color);
            background.setCornerRadius(dp(3));
            zoomWindow.setBackground(background);
            zoomWindow.setPadding(dp(borderThicknessDp), dp(borderThicknessDp),
                    dp(borderThicknessDp), dp(borderThicknessDp));
            zoomWindow.invalidate();
            rememberUiState();
        }

        private void resetDefaults() {
            borderThicknessDp = 2;
            borderOpacity = 100;
            borderColor = borderColors[0];
            Settings.MAGNIFIER_BORDER_THICKNESS.save(borderThicknessDp);
            Settings.MAGNIFIER_BORDER_OPACITY.save(borderOpacity);
            Settings.MAGNIFIER_BORDER_COLOR.save(0);
            sourceWidthFraction = (float) DEFAULT_SOURCE_WIDTH;
            final double aspect = surface.getWidth() / (double) Math.max(1, surface.getHeight());
            sourceHeightFraction = Math.max(
                    (float) (sourceWidthFraction * aspect / DEFAULT_SOURCE_ASPECT),
                    dp(48) / (float) Math.max(1, surface.getHeight()));
            sourceWidthFraction = Math.max(sourceWidthFraction,
                    (float) (sourceHeightFraction * DEFAULT_SOURCE_ASPECT / aspect));
            sourceLeftFraction = (1f - sourceWidthFraction) / 2f;
            sourceTopFraction = (1f - sourceHeightFraction) / 2f;
            zoomWidthFraction = (float) DEFAULT_ZOOM_WIDTH;
            zoomLeftFraction = 0.58f;
            zoomTopFraction = 0.30f;
            chromeLeftFraction = 1f;
            chromeTopFraction = 0f;
            applyBorderStyle();
            buildStylePanel();
            lastGeometry = "";
            updatePositionAndGeometry();
            if (!inFlight) requestCopy("reset-defaults");
            logPerf("geometry reset reason=style-panel-reset");
            rememberUiState();
        }

        private Rect safePlayerRect(Rect playerScreen) {
            Rect safe = new Rect(playerScreen);
            int[] decorLocation = new int[2];
            decor.getLocationOnScreen(decorLocation);
            int left = 0, top = 0, right = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 23) {
                android.view.WindowInsets insets = decor.getRootWindowInsets();
                if (insets != null) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets system = insets.getInsets(
                                android.view.WindowInsets.Type.systemBars()
                                        | android.view.WindowInsets.Type.displayCutout());
                        left = system.left; top = system.top;
                        right = system.right; bottom = system.bottom;
                    } else {
                        left = insets.getStableInsetLeft(); top = insets.getStableInsetTop();
                        right = insets.getStableInsetRight(); bottom = insets.getStableInsetBottom();
                        if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                            android.view.DisplayCutout cutout = insets.getDisplayCutout();
                            left = Math.max(left, cutout.getSafeInsetLeft());
                            top = Math.max(top, cutout.getSafeInsetTop());
                            right = Math.max(right, cutout.getSafeInsetRight());
                            bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                        }
                    }
                }
            }
            Rect safeDecor = new Rect(decorLocation[0] + left, decorLocation[1] + top,
                    decorLocation[0] + decor.getWidth() - right,
                    decorLocation[1] + decor.getHeight() - bottom);
            if (!safe.intersect(safeDecor)) return playerScreen;
            return safe;
        }

        private void placeChrome(Rect safePlayer, int[] decorAt) {
            final boolean editing = editMode;
            doneButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            offButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            styleButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            chrome.setVisibility(editing ? View.VISIBLE : View.GONE);
            chrome.measure(View.MeasureSpec.makeMeasureSpec(safePlayer.width(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.AT_MOST));
            int width = Math.min(safePlayer.width(), Math.max(dp(90), chrome.getMeasuredWidth()));
            int height = Math.max(dp(42), chrome.getMeasuredHeight());
            int left = safePlayer.left + Math.round(chromeLeftFraction
                    * Math.max(0, safePlayer.width() - width - dp(4)));
            int top = safePlayer.top + dp(4) + Math.round(chromeTopFraction
                    * Math.max(0, safePlayer.height() - height - dp(8)));
            if (chromeLeftFraction >= 0.999f && chromeTopFraction <= 0.001f) {
                left = safePlayer.right - width - dp(4);
                top = safePlayer.top + dp(4);
            }
            left = clamp(left, safePlayer.left + dp(2), safePlayer.right - width - dp(2));
            top = clamp(top, safePlayer.top + dp(2), safePlayer.bottom - height - dp(2));
            place(chrome, left - decorAt[0], top - decorAt[1], width, height);
        }

        private boolean onChromeTouch(View button, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                chromeDragging = false;
                chromeDownX = event.getRawX();
                chromeDownY = event.getRawY();
                int[] at = new int[2];
                chrome.getLocationOnScreen(at);
                chromeStartLeft = at[0];
                chromeStartTop = at[1];
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - chromeDownX;
                float dy = event.getRawY() - chromeDownY;
                if (!chromeDragging && Math.hypot(dx, dy) > chromeTouchSlop) {
                    chromeDragging = true;
                    stylePanelOpen = false;
                    setStylePanelVisible(false);
                    chrome.setAlpha(0.78f);
                }
                if (chromeDragging) {
                    int[] playerAt = new int[2];
                    playerArea.getLocationOnScreen(playerAt);
                    Rect safe = safePlayerRect(new Rect(playerAt[0], playerAt[1],
                            playerAt[0] + playerArea.getWidth(),
                            playerAt[1] + playerArea.getHeight()));
                    int width = chrome.getWidth(), height = chrome.getHeight();
                    float left = Math.max(safe.left + dp(2), Math.min(safe.right - width - dp(2),
                            chromeStartLeft + dx));
                    float top = Math.max(safe.top + dp(2), Math.min(safe.bottom - height - dp(2),
                            chromeStartTop + dy));
                    chromeLeftFraction = clampFraction((left - safe.left)
                            / (float) Math.max(1, safe.width() - width - dp(4)), 0f, 1f);
                    chromeTopFraction = clampFraction((top - safe.top - dp(4))
                            / (float) Math.max(1, safe.height() - height - dp(8)), 0f, 1f);
                    updatePositionAndGeometry();
                    rememberUiState();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                chrome.setAlpha(1f);
                if (!chromeDragging) button.performClick();
                else logPerf("edit-toolbar-moved left=" + chromeLeftFraction
                        + " top=" + chromeTopFraction);
                chromeDragging = false;
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                chrome.setAlpha(1f);
                chromeDragging = false;
                return true;
            }
            return true;
        }

        /** Place the edit-only affordance directly below YouTube's gear button. */
        private Rect updateEditModeButtonPosition() {
            if (editMode || stopped) {
                editModeButton.setVisibility(View.GONE);
                return null;
            }
            final float requiredElevation = playerControlStackZ() + dp(16);
            if (overlay.getElevation() != requiredElevation) overlay.setElevation(requiredElevation);
            if (playerGearButton == null) {
                Log.w(TAG, "YouTube player gear view not found; using top-right edit-icon fallback");
                final int[] playerAt = new int[2], decorAt = new int[2];
                playerArea.getLocationOnScreen(playerAt);
                decor.getLocationOnScreen(decorAt);
                final Rect safe = safePlayerRect(new Rect(playerAt[0], playerAt[1],
                        playerAt[0] + playerArea.getWidth(), playerAt[1] + playerArea.getHeight()));
                final int size = dp(48);
                final int left = safe.right - size - dp(8);
                final int top = safe.top + dp(52);
                place(editModeButton, left - decorAt[0], top - decorAt[1], size, size);
                editModeButton.setVisibility(playerArea.isShown() ? View.VISIBLE : View.GONE);
                return new Rect(left, top, left + size, top + size);
            }
            if (!playerGearButton.isShown()) {
                editModeButton.setVisibility(View.GONE);
                return null;
            }
            final Rect visibleGear = new Rect();
            if (!playerGearButton.getGlobalVisibleRect(visibleGear)
                    || playerGearButton.getWidth() <= 0 || playerGearButton.getHeight() <= 0) {
                editModeButton.setVisibility(View.GONE);
                return null;
            }
            float combinedAlpha = 1f;
            View current = playerGearButton;
            while (current != null) {
                combinedAlpha *= current.getAlpha();
                final Object parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            if (combinedAlpha < 0.05f) {
                editModeButton.setVisibility(View.GONE);
                return null;
            }
            int[] decorAt = new int[2];
            decor.getLocationOnScreen(decorAt);
            final int width = playerGearButton.getWidth();
            final int height = playerGearButton.getHeight();
            final int[] gearAt = new int[2];
            playerGearButton.getLocationOnScreen(gearAt);
            int left = gearAt[0] + (width - width) / 2;
            int top = gearAt[1] + height;
            final int[] playerAt = new int[2];
            playerArea.getLocationOnScreen(playerAt);
            final Rect playerScreen = new Rect(playerAt[0], playerAt[1],
                    playerAt[0] + playerArea.getWidth(), playerAt[1] + playerArea.getHeight());
            final Rect safePlayer = safePlayerRect(playerScreen);
            if (top + height > safePlayer.bottom) top = gearAt[1] - height;
            left = clamp(left, safePlayer.left, safePlayer.right - width);
            top = clamp(top, safePlayer.top, safePlayer.bottom - height);
            if (editModeButton.getParent() != overlay) {
                if (editModeButton.getParent() instanceof ViewGroup) {
                    ((ViewGroup) editModeButton.getParent()).removeView(editModeButton);
                }
                // Keep this control above YouTube's full-player touch target. Its screen
                // position still follows the gear, but the top-level overlay owns its taps.
                overlay.addView(editModeButton, overlay.getChildCount(),
                        new FrameLayout.LayoutParams(1, 1));
            }
            place(editModeButton, left - decorAt[0], top - decorAt[1], width, height);
            editModeButton.setVisibility(View.VISIBLE);
            return new Rect(left, top, left + width, top + height);
        }

        private void placeStylePanel(Rect safePlayer, int[] decorAt) {
            if (!editMode || !stylePanelOpen) {
                setStylePanelVisible(false);
                return;
            }
            setStylePanelVisible(true);
            // Dock to the player viewport, not an aspect-fitted SurfaceView rectangle.
            // SurfaceView bounds vary with inline/fullscreen layout and letterboxing; deriving
            // the panel bottom from them could lift the panel into the middle of the player.
            int width = Math.min(dp(420), Math.max(1, safePlayer.width()));
            int height = Math.min(dp(80), Math.max(1, safePlayer.height() / 3));
            int left = safePlayer.left + (safePlayer.width() - width) / 2;
            int top = safePlayer.bottom - height - dp(4);
            top = clamp(top, safePlayer.top, Math.max(safePlayer.top, safePlayer.bottom - height));
            place(stylePanelViewport, left - decorAt[0], top - decorAt[1], width, height);
        }

        private void enterEditMode() {
            if (stopped || editMode) return;
            editMode = true;
            if (enabled) attachZoomWindowToOverlay();
            editModeButton.setVisibility(View.GONE);
            stylePanelOpen = false;
            sourceOutline.setVisibility(enabled ? View.VISIBLE : View.GONE);
            sourceOutline.setClickable(enabled);
            touchShield.setVisibility(View.VISIBLE);
            styleButton.setEnabled(enabled);
            styleButton.setAlpha(enabled ? 1f : 0.42f);
            offButton.setAlpha(enabled ? 1f : 0.58f);
            hidePlayerControls();
            handler.removeCallbacks(controlsHideEnforcer);
            handler.postDelayed(controlsHideEnforcer, 250L);
            zoomWindow.invalidate();
            updatePositionAndGeometry();
            logPerf("edit-mode=true reason=edit-entry enabled=" + enabled);
            rememberUiState();
        }

        private void exitEditMode() {
            if (!editMode) return;
            editMode = false;
            if (enabled) attachZoomWindowToPlayerLayer();
            stylePanelOpen = false;
            setStylePanelVisible(false);
            touchShield.setVisibility(View.GONE);
            sourceOutline.setVisibility(View.GONE);
            sourceOutline.setClickable(false);
            handler.removeCallbacks(controlsHideEnforcer);
            zoomWindow.invalidate();
            restorePlayerControls();
            updatePositionAndGeometry();
            if (enabled && !inFlight) requestCopy("edit-done");
            logPerf("edit-mode=false reason=done-source-locked=true");
            rememberUiState();
        }

        private void toggleStylePanel() {
            if (!editMode || !enabled) return;
            stylePanelOpen = !stylePanelOpen;
            setStylePanelVisible(stylePanelOpen);
            updatePositionAndGeometry();
            logPerf("style-panel=" + (stylePanelOpen ? "expanded" : "collapsed"));
            rememberUiState();
        }

        private void disableMagnifier() {
            enabled = !enabled;
            styleButton.setEnabled(enabled);
            styleButton.setAlpha(enabled ? 1f : 0.42f);
            offButton.setAlpha(enabled ? 1f : 0.58f);
            stylePanelOpen = enabled && stylePanelOpen;
            setStylePanelVisible(stylePanelOpen);
            if (enabled) {
                if (editMode) attachZoomWindowToOverlay();
                else attachZoomWindowToPlayerLayer();
                if (sourceOutline.getParent() != overlay) {
                    if (sourceOutline.getParent() instanceof ViewGroup) {
                        ((ViewGroup) sourceOutline.getParent()).removeView(sourceOutline);
                    }
                    overlay.addView(sourceOutline, Math.min(2, overlay.getChildCount()),
                            new FrameLayout.LayoutParams(1, 1));
                }
                zoomWindow.setVisibility(View.VISIBLE);
                zoomWindow.setOnTouchListener(this::onZoomTouch);
                sourceOutline.setOnTouchListener(this::onSourceTouch);
                sourceOutline.setVisibility(editMode ? View.VISIBLE : View.GONE);
                sourceOutline.setClickable(editMode);
                VideoState.getOnChange().addObserver(videoStateObserver);
                PlayerType.getOnChange().addObserver(playerTypeObserver);
                handler.postDelayed(metricsReporter, 1000L);
                onVideoStateChanged(VideoState.getCurrent(), "user-toggle-on");
                if (captureMode == CaptureMode.PLAYING) scheduleVsync();
                else requestCopy("toggle-on");
            } else {
                cancelVsync();
                handler.removeCallbacks(pausedPoll);
                handler.removeCallbacks(metricsReporter);
                frameCallbackPending = false;
                VideoState.getOnChange().removeObserver(videoStateObserver);
                PlayerType.getOnChange().removeObserver(playerTypeObserver);
                zoomWindow.setVisibility(View.GONE);
                zoomWindow.setOnTouchListener(null);
                sourceOutline.setVisibility(View.GONE);
                sourceOutline.setClickable(false);
                sourceOutline.setOnTouchListener(null);
                setStylePanelVisible(false);
                zoomImage.setImageBitmap(null);
                stateLabel.setVisibility(View.GONE);
                if (zoomWindow.getParent() instanceof ViewGroup) {
                    ((ViewGroup) zoomWindow.getParent()).removeView(zoomWindow);
                }
                if (sourceOutline.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sourceOutline.getParent()).removeView(sourceOutline);
                }
            }
            updatePositionAndGeometry();
            logPerf("mode=" + (enabled ? "ON" : "OFF")
                    + " reason=user-toggle viewsRemoved=" + !enabled
                    + " loopsCancelled=" + !enabled + " editMode=" + editMode);
        }

        private void onNewVideo(String videoId) {
            if (editMode) exitEditMode();
            if (enabled) disableMagnifier();
            Log.i(TAG, "new video id observed; magnifier reset to OFF");
        }

        private void hidePlayerControls() {
            // In YouTube 21.16 the time, seekbar, and fullscreen affordance are in a sibling
            // overlay outside controls_layout. Some player layouts duplicate these IDs, so hide
            // every matching view instead of relying on findViewById's first match.
            final String[] controlIds = {
                    "controls_layout",
                    "controls_button_group_layout",
                    "watch_while_time_bar_view_overlay",
                    "watch_while_time_bar_view"
            };
            View current = playerControlsAnchor;
            while (current != null && current != decor) {
                for (String name : controlIds) {
                    final int id = decor.getResources().getIdentifier(name, "id",
                            decor.getContext().getPackageName());
                    if (id != 0 && current.getId() == id) hideControlView(current);
                }
                final Object parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            for (String name : controlIds) {
                final int id = decor.getResources().getIdentifier(name, "id",
                        decor.getContext().getPackageName());
                if (id != 0) hideControlViews((ViewGroup) decor, id);
            }
        }

        private void hideControlViews(View view, int id) {
            if (view.getId() == id) hideControlView(view);
            if (!(view instanceof ViewGroup)) return;
            final ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                hideControlViews(group.getChildAt(index), id);
            }
        }

        private void hideControlView(View controlView) {
            if (controlView == null || controlView == overlay || controlView == surface) return;
            if (!savedControlVisibility.containsKey(controlView)) {
                savedControlVisibility.put(controlView, controlView.getVisibility());
            }
            controlView.setVisibility(View.INVISIBLE);
        }

        private View findControlRoot(ViewGroup group) {
            int controlsId = decor.getResources().getIdentifier("controls_layout", "id",
                    decor.getContext().getPackageName());
            int groupId = decor.getResources().getIdentifier("controls_button_group_layout", "id",
                    decor.getContext().getPackageName());
            if (controlsId != 0) {
                View found = group.findViewById(controlsId);
                if (found != null) return found;
            }
            if (groupId != 0) {
                View found = group.findViewById(groupId);
                if (found != null) return found;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ViewGroup) {
                    View found = findControlRoot((ViewGroup) child);
                    if (found != null) return found;
                }
            }
            return null;
        }

        private void restorePlayerControls() {
            for (java.util.Map.Entry<View, Integer> entry : savedControlVisibility.entrySet()) {
                View view = entry.getKey();
                if (view != null && view.getParent() != null) view.setVisibility(entry.getValue());
            }
            savedControlVisibility.clear();
        }

        private void onWindowFocusChanged(boolean hasFocus) {
            onVisibilityChanged(hasFocus ? "window-focus" : "window-blur");
        }

        private void onVisibilityChanged(String reason) {
            if (stopped) return;
            boolean visible = isCaptureTargetVisible();
            if (!visible) {
                cancelVsync();
                handler.removeCallbacks(pausedPoll);
                handler.removeCallbacks(visibilityRetry);
                handler.removeCallbacks(metricsReporter);
                zoomWindow.setVisibility(View.GONE);
                sourceOutline.setVisibility(View.GONE);
                touchShield.setVisibility(View.GONE);
                setStylePanelVisible(false);
                chrome.setVisibility(View.GONE);
                if (isPlayerHostVisible()) updateEditModeButtonPosition();
                else editModeButton.setVisibility(View.GONE);
                logPerf("capture-visible=false reason=" + reason
                        + " playerType=" + PlayerType.getCurrent());
            } else {
                chrome.setVisibility(editMode ? View.VISIBLE : View.GONE);
                zoomWindow.setVisibility(enabled ? View.VISIBLE : View.GONE);
                sourceOutline.setVisibility(enabled && editMode ? View.VISIBLE : View.GONE);
                touchShield.setVisibility(editMode ? View.VISIBLE : View.GONE);
                updateEditModeButtonPosition();
                handler.removeCallbacks(metricsReporter);
                handler.postDelayed(metricsReporter, 1000L);
                logPerf("capture-visible=true reason=" + reason);
                if (VideoState.getCurrent() == VideoState.PLAYING || VideoState.getCurrent() == null) {
                    onVideoStateChanged(VideoState.getCurrent(), "visible-again:" + reason);
                } else if (VideoState.getCurrent() == VideoState.PAUSED) {
                    requestCopy("visible-again-paused");
                    handler.removeCallbacks(pausedPoll);
                    handler.postDelayed(pausedPoll, 500L);
                }
            }
        }

        private boolean isCaptureTargetVisible() {
            if (!enabled || stopped || !surface.isAttachedToWindow() || !surface.isShown()
                    || !playerArea.isAttachedToWindow() || !playerArea.isShown()
                    || !decor.isShown() || !decor.hasWindowFocus()) return false;
            PowerManager pm = (PowerManager) decor.getContext().getSystemService("power");
            if (pm != null && !pm.isInteractive()) return false;
            return isWatchSurfaceType(PlayerType.getCurrent());
        }

        private boolean isPlayerHostVisible() {
            return !stopped && surface.isAttachedToWindow() && surface.isShown()
                    && playerArea.isAttachedToWindow() && playerArea.isShown()
                    && decor.isShown() && decor.hasWindowFocus()
                    && isWatchSurfaceType(PlayerType.getCurrent());
        }

        private void onPlayerClosed(String reason) {
            if (stopped) return;
            if (enabled) disableMagnifier();
            if (editMode) exitEditMode();
            stop();
            Log.i(TAG, "magnifier session torn down reason=" + reason);
            logPerf("session=OFF reason=" + reason + " viewsRemoved=true loopsCancelled=true");
        }

        private boolean onSourceTouch(View view, MotionEvent event) {
            if (!editMode || stopped) return true;
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                sourceTouchDelegatesToZoom = isZoomResizeTarget(event.getRawX(), event.getRawY());
                if (sourceTouchDelegatesToZoom) return forwardSourceOverlapTouch(event);
                beginSourceGesture(event.getX(), event.getY(), event.getRawX(), event.getRawY());
                return true;
            }
            if (sourceTouchDelegatesToZoom) {
                boolean handled = forwardSourceOverlapTouch(event);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    sourceTouchDelegatesToZoom = false;
                }
                return handled;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                moveSourceGesture(event.getRawX(), event.getRawY());
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gestureKind = GestureKind.NONE;
                sourceOutline.performClick();
                return true;
            }
            return true;
        }

        private boolean isZoomResizeTarget(float rawX, float rawY) {
            int[] zoomAt = new int[2];
            zoomWindow.getLocationOnScreen(zoomAt);
            float x = rawX - zoomAt[0];
            float y = rawY - zoomAt[1];
            if (x < 0 || y < 0 || x >= zoomWindow.getWidth() || y >= zoomWindow.getHeight()) {
                return false;
            }
            int cornerTarget = dp(20);
            boolean left = x <= cornerTarget;
            boolean right = x >= zoomWindow.getWidth() - cornerTarget;
            boolean top = y <= cornerTarget;
            boolean bottom = y >= zoomWindow.getHeight() - cornerTarget;
            return (left && top) || (right && top) || (left && bottom) || (right && bottom);
        }

        private boolean forwardSourceOverlapTouch(MotionEvent sourceEvent) {
            int[] sourceAt = new int[2];
            int[] zoomAt = new int[2];
            sourceOutline.getLocationOnScreen(sourceAt);
            zoomWindow.getLocationOnScreen(zoomAt);
            MotionEvent zoomEvent = MotionEvent.obtain(sourceEvent);
            zoomEvent.offsetLocation(sourceAt[0] - zoomAt[0], sourceAt[1] - zoomAt[1]);
            try {
                return onZoomTouch(zoomWindow, zoomEvent);
            } finally {
                zoomEvent.recycle();
            }
        }

        private GestureKind gestureKind = GestureKind.NONE;
        private float gestureDownX, gestureDownY;
        private float gestureSourceLeft, gestureSourceTop, gestureSourceWidth, gestureSourceHeight;
        private float gestureZoomLeft, gestureZoomTop, gestureZoomWidth;
        private boolean sourceTouchDelegatesToZoom;
        private enum GestureKind { NONE, MOVE_SOURCE, SOURCE_LEFT, SOURCE_RIGHT,
            SOURCE_TOP, SOURCE_BOTTOM, SOURCE_TOP_LEFT, SOURCE_TOP_RIGHT,
            SOURCE_BOTTOM_LEFT, SOURCE_BOTTOM_RIGHT, MOVE_ZOOM,
            ZOOM_TOP_LEFT, ZOOM_TOP_RIGHT,
            ZOOM_BOTTOM_LEFT, ZOOM_BOTTOM_RIGHT }

        private void beginSourceGesture(float x, float y, float rawX, float rawY) {
            gestureDownX = rawX; gestureDownY = rawY;
            gestureSourceLeft = sourceLeftFraction; gestureSourceTop = sourceTopFraction;
            gestureSourceWidth = sourceWidthFraction; gestureSourceHeight = sourceHeightFraction;
            int target = Math.min(dp(48), Math.max(dp(8), Math.min(sourceOutline.getWidth(),
                    sourceOutline.getHeight()) / 3));
            boolean left = x < target, right = x > sourceOutline.getWidth() - target;
            boolean top = y < target, bottom = y > sourceOutline.getHeight() - target;
            if (top && left) gestureKind = GestureKind.SOURCE_TOP_LEFT;
            else if (top && right) gestureKind = GestureKind.SOURCE_TOP_RIGHT;
            else if (bottom && left) gestureKind = GestureKind.SOURCE_BOTTOM_LEFT;
            else if (bottom && right) gestureKind = GestureKind.SOURCE_BOTTOM_RIGHT;
            else if (left) gestureKind = GestureKind.SOURCE_LEFT;
            else if (right) gestureKind = GestureKind.SOURCE_RIGHT;
            else if (top) gestureKind = GestureKind.SOURCE_TOP;
            else if (bottom) gestureKind = GestureKind.SOURCE_BOTTOM;
            else gestureKind = GestureKind.MOVE_SOURCE;
        }

        private void moveSourceGesture(float x, float y) {
            final float dx = (x - gestureDownX) / Math.max(1f, surface.getWidth());
            final float dy = (y - gestureDownY) / Math.max(1f, surface.getHeight());
            float left = gestureSourceLeft, top = gestureSourceTop;
            float right = left + gestureSourceWidth, bottom = top + gestureSourceHeight;
            switch (gestureKind) {
                case MOVE_SOURCE:
                    left = clampFraction(gestureSourceLeft + dx, 0f, 1f - gestureSourceWidth);
                    top = clampFraction(gestureSourceTop + dy, 0f, 1f - gestureSourceHeight);
                    right = left + gestureSourceWidth;
                    bottom = top + gestureSourceHeight;
                    break;
                case SOURCE_LEFT: case SOURCE_TOP_LEFT: case SOURCE_BOTTOM_LEFT:
                    left = clampFraction(gestureSourceLeft + dx,
                            0f, right - dp(48) / (float) surface.getWidth()); break;
                default: break;
            }
            switch (gestureKind) {
                case SOURCE_RIGHT: case SOURCE_TOP_RIGHT: case SOURCE_BOTTOM_RIGHT:
                    right = clampFraction(gestureSourceLeft + gestureSourceWidth + dx,
                            left + dp(48) / (float) surface.getWidth(), 1f); break;
                default: break;
            }
            switch (gestureKind) {
                case SOURCE_TOP: case SOURCE_TOP_LEFT: case SOURCE_TOP_RIGHT:
                    top = clampFraction(gestureSourceTop + dy, 0f,
                            bottom - dp(48) / (float) surface.getHeight()); break;
                default: break;
            }
            switch (gestureKind) {
                case SOURCE_BOTTOM: case SOURCE_BOTTOM_LEFT: case SOURCE_BOTTOM_RIGHT:
                    bottom = clampFraction(gestureSourceTop + gestureSourceHeight + dy,
                            top + dp(48) / (float) surface.getHeight(), 1f); break;
                default: break;
            }
            sourceLeftFraction = left;
            sourceTopFraction = top;
            sourceWidthFraction = Math.max(dp(48) / (float) surface.getWidth(), right - left);
            sourceHeightFraction = Math.max(dp(48) / (float) surface.getHeight(), bottom - top);
            clampSourceFractions();
            lastGeometry = "";
            updatePositionAndGeometry();
        }

        private float clampFraction(float value, float min, float max) {
            return Math.max(min, Math.min(value, Math.max(min, max)));
        }

        private void clampSourceFractions() {
            float minW = Math.min(1f, dp(48) / (float) Math.max(1, surface.getWidth()));
            float minH = Math.min(1f, dp(48) / (float) Math.max(1, surface.getHeight()));
            sourceWidthFraction = Math.min(1f, Math.max(minW, sourceWidthFraction));
            sourceHeightFraction = Math.min(1f, Math.max(minH, sourceHeightFraction));
            sourceLeftFraction = clampFraction(sourceLeftFraction, 0f, 1f - sourceWidthFraction);
            sourceTopFraction = clampFraction(sourceTopFraction, 0f, 1f - sourceHeightFraction);
        }

        private boolean onZoomTouch(View view, MotionEvent event) {
            if (!enabled || stopped) return false;
            if (forwardingNormalTap) return false;
            if (!editMode && zoomInPlayerLayer) return onNormalZoomTouch(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                gestureDownX = event.getRawX(); gestureDownY = event.getRawY();
                int[] zoomAt = new int[2];
                int[] playerAt = new int[2];
                zoomWindow.getLocationOnScreen(zoomAt);
                playerArea.getLocationOnScreen(playerAt);
                gestureZoomLeft = (zoomAt[0] - playerAt[0])
                        / (float) Math.max(1, playerArea.getWidth());
                gestureZoomTop = (zoomAt[1] - playerAt[1])
                        / (float) Math.max(1, playerArea.getHeight());
                gestureZoomWidth = zoomWidthFraction;
                // The zoom window moves in normal mode; resize zones only exist in edit mode.
                gestureKind = GestureKind.MOVE_ZOOM;
                if (editMode) {
                    final int cornerTarget = dp(20);
                    final boolean left = event.getX() <= cornerTarget;
                    final boolean right = event.getX() >= zoomWindow.getWidth() - cornerTarget;
                    final boolean top = event.getY() <= cornerTarget;
                    final boolean bottom = event.getY() >= zoomWindow.getHeight() - cornerTarget;
                    if (left && top) gestureKind = GestureKind.ZOOM_TOP_LEFT;
                    else if (right && top) gestureKind = GestureKind.ZOOM_TOP_RIGHT;
                    else if (left && bottom) gestureKind = GestureKind.ZOOM_BOTTOM_LEFT;
                    else if (right && bottom) gestureKind = GestureKind.ZOOM_BOTTOM_RIGHT;
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = (event.getRawX() - gestureDownX) / Math.max(1f, playerArea.getWidth());
                float dy = (event.getRawY() - gestureDownY) / Math.max(1f, playerArea.getHeight());
                switch (gestureKind) {
                    case MOVE_ZOOM:
                        zoomLeftFraction = clampFraction(gestureZoomLeft + dx, 0f,
                                1f - zoomWidthFraction);
                        zoomTopFraction = clampFraction(gestureZoomTop + dy, 0f,
                                1f - (zoomWindow.getHeight() / (float) Math.max(1,
                                        playerArea.getHeight())));
                        break;
                    case ZOOM_TOP_LEFT: case ZOOM_TOP_RIGHT:
                    case ZOOM_BOTTOM_LEFT: case ZOOM_BOTTOM_RIGHT: {
                        double aspect = sourceWidthFraction * Math.max(1, surface.getWidth())
                                / (sourceHeightFraction * Math.max(1, surface.getHeight()));
                        float heightDrivenDelta = (float) (dy * playerArea.getHeight() * aspect
                                / Math.max(1, playerArea.getWidth()));
                        float horizontalDrivenDelta = (gestureKind == GestureKind.ZOOM_TOP_LEFT
                                || gestureKind == GestureKind.ZOOM_BOTTOM_LEFT) ? -dx : dx;
                        float verticalDrivenDelta = (gestureKind == GestureKind.ZOOM_TOP_LEFT
                                || gestureKind == GestureKind.ZOOM_TOP_RIGHT)
                                ? -heightDrivenDelta : heightDrivenDelta;
                        float oldWidth = gestureZoomWidth;
                        zoomWidthFraction = Math.max(dp(96) / (float) playerArea.getWidth(),
                                oldWidth + (horizontalDrivenDelta + verticalDrivenDelta) / 2f);
                        float widthChange = zoomWidthFraction - oldWidth;
                        float heightChangeFraction = (float) (widthChange * playerArea.getWidth()
                                / (aspect * Math.max(1, playerArea.getHeight())));
                        if (gestureKind == GestureKind.ZOOM_TOP_LEFT
                                || gestureKind == GestureKind.ZOOM_BOTTOM_LEFT) {
                            zoomLeftFraction = gestureZoomLeft - widthChange;
                        }
                        if (gestureKind == GestureKind.ZOOM_TOP_LEFT
                                || gestureKind == GestureKind.ZOOM_TOP_RIGHT) {
                            zoomTopFraction = gestureZoomTop - heightChangeFraction;
                        }
                        break;
                    }
                    default: break;
                }
                zoomWidthFraction = Math.min(1f, zoomWidthFraction);
                zoomLeftFraction = clampFraction(zoomLeftFraction, 0f, 1f - zoomWidthFraction);
                zoomTopFraction = clampFraction(zoomTopFraction, 0f, 1f);
                lastGeometry = "";
                updatePositionAndGeometry();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                gestureKind = GestureKind.NONE;
                zoomWindow.performClick();
                return true;
            }
            return true;
        }

        private boolean onNormalZoomTouch(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                handler.removeCallbacks(beginNormalZoomDrag);
                normalDownRawX = event.getRawX();
                normalDownRawY = event.getRawY();
                normalTapEligible = true;
                normalLongPressDragging = false;
                handler.postDelayed(beginNormalZoomDrag,
                        ViewConfiguration.getLongPressTimeout());
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dxPx = event.getRawX() - normalDownRawX;
                float dyPx = event.getRawY() - normalDownRawY;
                if (!normalLongPressDragging && (Math.abs(dxPx) > ViewConfiguration.get(
                        decor.getContext()).getScaledTouchSlop() || Math.abs(dyPx) >
                        ViewConfiguration.get(decor.getContext()).getScaledTouchSlop())) {
                    normalTapEligible = false;
                    handler.removeCallbacks(beginNormalZoomDrag);
                }
                if (normalLongPressDragging) {
                    zoomLeftFraction = clampFraction(normalZoomLeft + dxPx
                                    / Math.max(1f, playerArea.getWidth()), 0f,
                            1f - zoomWidthFraction);
                    zoomTopFraction = clampFraction(normalZoomTop + dyPx
                                    / Math.max(1f, playerArea.getHeight()), 0f,
                            1f - zoomWindow.getHeight() / (float) Math.max(1,
                                    playerArea.getHeight()));
                    lastGeometry = "";
                    updatePositionAndGeometry();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(beginNormalZoomDrag);
                if (action == MotionEvent.ACTION_UP && normalTapEligible
                        && !normalLongPressDragging) {
                    forwardNormalZoomTap(event);
                }
                if (normalLongPressDragging) zoomWindow.performClick();
                normalLongPressDragging = false;
                normalTapEligible = false;
                return true;
            }
            return true;
        }

        private void forwardNormalZoomTap(MotionEvent upEvent) {
            int[] zoomAt = new int[2];
            int[] playerAt = new int[2];
            zoomWindow.getLocationOnScreen(zoomAt);
            playerArea.getLocationOnScreen(playerAt);
            long downTime = upEvent.getDownTime();
            long eventTime = upEvent.getEventTime();
            MotionEvent down = MotionEvent.obtain(downTime, eventTime,
                    MotionEvent.ACTION_DOWN, upEvent.getX() + zoomAt[0] - playerAt[0],
                    upEvent.getY() + zoomAt[1] - playerAt[1], upEvent.getMetaState());
            MotionEvent up = MotionEvent.obtain(downTime, eventTime + 1L,
                    MotionEvent.ACTION_UP, upEvent.getX() + zoomAt[0] - playerAt[0],
                    upEvent.getY() + zoomAt[1] - playerAt[1], upEvent.getMetaState());
            forwardingNormalTap = true;
            int oldVisibility = zoomWindow.getVisibility();
            zoomWindow.setVisibility(View.INVISIBLE);
            try {
                playerArea.dispatchTouchEvent(down);
                playerArea.dispatchTouchEvent(up);
            } finally {
                zoomWindow.setVisibility(oldVisibility);
                forwardingNormalTap = false;
                down.recycle();
                up.recycle();
            }
        }

        private void attachZoomWindowToPlayerLayer() {
            Object controlsParent = playerControlsAnchor.getParent();
            if (!(controlsParent instanceof FrameLayout)) {
                attachZoomWindowToOverlay();
                return;
            }
            ViewGroup parent = (ViewGroup) controlsParent;
            if (parent.getWidth() < playerArea.getWidth() * 3 / 4
                    || parent.getHeight() < playerArea.getHeight() * 3 / 4) {
                attachZoomWindowToOverlay();
                return;
            }
            if (zoomWindow.getParent() == parent) {
                zoomInPlayerLayer = true;
                normalZoomLayer = parent;
                return;
            }
            if (zoomWindow.getParent() instanceof ViewGroup) {
                ((ViewGroup) zoomWindow.getParent()).removeView(zoomWindow);
            }
            int index = parent.indexOfChild(playerControlsAnchor);
            if (index < 0) index = parent.getChildCount();
            parent.addView(zoomWindow, index, new FrameLayout.LayoutParams(1, 1));
            zoomInPlayerLayer = true;
            normalZoomLayer = parent;
        }

        private void attachZoomWindowToOverlay() {
            if (zoomWindow.getParent() == overlay) {
                zoomInPlayerLayer = false;
                return;
            }
            if (zoomWindow.getParent() instanceof ViewGroup) {
                ((ViewGroup) zoomWindow.getParent()).removeView(zoomWindow);
            }
            overlay.addView(zoomWindow, 1, new FrameLayout.LayoutParams(1, 1));
            zoomInPlayerLayer = false;
            normalZoomLayer = null;
        }

        void updatePositionAndGeometry() {
            if (stopped || !surface.isAttachedToWindow() || surface.getWidth() <= 0
                    || surface.getHeight() <= 0 || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                return;
            }
            boolean visibleNow = isCaptureTargetVisible();
            if (visibleNow != lastCaptureVisible) {
                lastCaptureVisible = visibleNow;
                onVisibilityChanged("layout-visibility-change");
            }

            final int[] decorAt = new int[2];
            final int[] surfaceAt = new int[2];
            final int[] playerAt = new int[2];
            decor.getLocationOnScreen(decorAt);
            surface.getLocationOnScreen(surfaceAt);
            playerArea.getLocationOnScreen(playerAt);

            final Rect bufferFrame = surface.getHolder().getSurfaceFrame();
            final int bufferWidth = Math.max(1, bufferFrame.width());
            final int bufferHeight = Math.max(1, bufferFrame.height());
            lastBufferWidth = bufferWidth;
            lastBufferHeight = bufferHeight;
            final VideoSize videoSize = readVideoSize(surface);
            lastVideoWidth = videoSize == null ? 0 : videoSize.width;
            lastVideoHeight = videoSize == null ? 0 : videoSize.height;

            // YouTube's SurfaceView normally measures to the displayed video aspect. Keep the
            // complete SurfaceView as the 2a video rect and log any renderer/aspect disagreement.
            videoRectOnSurface.set(0f, 0f, surface.getWidth(), surface.getHeight());
            final double viewAspect = surface.getWidth() / (double) surface.getHeight();
            final double videoAspect = videoSize == null ? viewAspect
                    : videoSize.width / (double) videoSize.height;
            final double aspectError = Math.abs(viewAspect / videoAspect - 1d);
            if (!geometryInitialized) {
                sourceHeightFraction = Math.max((float) (sourceWidthFraction * viewAspect
                        / DEFAULT_SOURCE_ASPECT), dp(48) / (float) surface.getHeight());
                sourceWidthFraction = Math.max(sourceWidthFraction,
                        (float) (sourceHeightFraction * DEFAULT_SOURCE_ASPECT / viewAspect));
                sourceLeftFraction = (1f - sourceWidthFraction) / 2f;
                sourceTopFraction = (1f - sourceHeightFraction) / 2f;
                geometryInitialized = true;
            }
            if (!isValidSourceGeometry(sourceLeftFraction, sourceTopFraction,
                    sourceWidthFraction, sourceHeightFraction)) {
                geometryInitialized = false;
                return;
            }
            final Rect sourceScreen = new Rect(
                    surfaceAt[0] + Math.round(sourceLeftFraction * surface.getWidth()),
                    surfaceAt[1] + Math.round(sourceTopFraction * surface.getHeight()),
                    surfaceAt[0] + Math.round((sourceLeftFraction + sourceWidthFraction)
                            * surface.getWidth()),
                    surfaceAt[1] + Math.round((sourceTopFraction + sourceHeightFraction)
                            * surface.getHeight()));
            final Rect sourceInDecor = new Rect(sourceScreen);
            sourceInDecor.offset(-decorAt[0], -decorAt[1]);
            place(sourceOutline, sourceInDecor.left, sourceInDecor.top,
                    sourceInDecor.width(), sourceInDecor.height());
            sourceOutline.setVisibility(editMode && enabled ? View.VISIBLE : View.GONE);
            sourceOutline.setClickable(editMode && enabled);

            final int playerWidth = Math.max(1, playerArea.getWidth());
            final int playerHeight = Math.max(1, playerArea.getHeight());
            final Rect playerScreen = new Rect(playerAt[0], playerAt[1],
                    playerAt[0] + playerWidth, playerAt[1] + playerHeight);
            final Rect safePlayer = safePlayerRect(playerScreen);
            placeChrome(safePlayer, decorAt);
            updateEditModeButtonPosition();
            final int margin = dp(8);
            final double sourceBufferAspect = sourceWidthFraction * bufferWidth
                    / (sourceHeightFraction * bufferHeight);
            final int borderPx = dp(borderThicknessDp);
            zoomWindow.setPadding(borderPx, borderPx, borderPx, borderPx);
            int zoomContentWidth = Math.max(dp(96),
                    (int) Math.round(playerWidth * zoomWidthFraction));
            zoomContentWidth = Math.min(zoomContentWidth, Math.max(1, playerWidth - 2 * margin
                    - borderPx * 2));
            int zoomContentHeight = Math.max(1,
                    (int) Math.round(zoomContentWidth / sourceBufferAspect));
            final int maxContentHeight = Math.max(1, playerHeight - margin * 2 - borderPx * 2);
            if (zoomContentHeight > maxContentHeight) {
                zoomContentHeight = maxContentHeight;
                zoomContentWidth = Math.max(1,
                        (int) Math.round(zoomContentHeight * sourceBufferAspect));
            }
            final int zoomWidth = zoomContentWidth + borderPx * 2;
            final int zoomHeight = zoomContentHeight + borderPx * 2;
            int zoomX = playerAt[0] + Math.round(zoomLeftFraction * playerWidth);
            int zoomY = playerAt[1] + Math.round(zoomTopFraction * playerHeight);
            zoomX = clamp(zoomX, playerAt[0], playerAt[0] + playerWidth - zoomWidth);
            zoomY = clamp(zoomY, playerAt[1], playerAt[1] + playerHeight - zoomHeight);
            if (zoomInPlayerLayer) {
                int[] zoomLayerAt = new int[2];
                normalZoomLayer.getLocationOnScreen(zoomLayerAt);
                place(zoomWindow, zoomX - zoomLayerAt[0], zoomY - zoomLayerAt[1],
                        zoomWidth, zoomHeight);
            } else {
                place(zoomWindow, zoomX - decorAt[0], zoomY - decorAt[1],
                        zoomWidth, zoomHeight);
            }

            place(touchShield, playerScreen.left - decorAt[0], playerScreen.top - decorAt[1],
                    playerScreen.width(), playerScreen.height());
            touchShield.setVisibility(editMode ? View.VISIBLE : View.GONE);
            placeStylePanel(safePlayer, decorAt);

            final Rect crop = sourceBufferRect(bufferFrame, sourceLeftFraction,
                    sourceTopFraction, sourceWidthFraction, sourceHeightFraction);
            if (crop.width() < 1 || crop.height() < 1) {
                showError("Empty PixelCopy source");
                return;
            }
            // PixelCopy output is sized from the video content rect only; border pixels are outside it.
            final int innerDisplayWidth = Math.max(1, zoomContentWidth);
            final int innerDisplayHeight = Math.max(1, zoomContentHeight);
            final int[] bitmapSize = chooseBitmapSize(crop.width(), crop.height(),
                    innerDisplayWidth, innerDisplayHeight);

            final String geometry = "surfaceView=" + surface.getWidth() + "x" + surface.getHeight()
                    + "@" + surfaceAt[0] + "," + surfaceAt[1]
                    + " surfaceFrame=" + bufferFrame + " videoSize="
                    + (videoSize == null ? "unknown" : videoSize.width + "x" + videoSize.height)
                    + " aspectError=" + String.format(java.util.Locale.ROOT, "%.3f", aspectError)
                    + " videoRect=SurfaceViewBounds sourceFraction="
                    + sourceLeftFraction + "," + sourceTopFraction + ","
                    + sourceWidthFraction + "," + sourceHeightFraction
                    + " nativeCrop=" + crop.width() + "x" + crop.height()
                    + " srcRectBuffer=" + crop + " destinationBitmap="
                    + bitmapSize[0] + "x" + bitmapSize[1]
                    + " zoomDisplay=" + innerDisplayWidth + "x" + innerDisplayHeight
                    + " zoomWindowRequestedPx=" + zoomWidth + "x" + zoomHeight
                    + " playerArea=" + playerArea.getClass().getName() + "@"
                    + playerAt[0] + "," + playerAt[1] + "," + playerWidth + "x" + playerHeight
                    + " zoomFactor=" + String.format(java.util.Locale.ROOT, "%.3f",
                    innerDisplayWidth / (double) Math.max(1, sourceScreen.width()));
            lastCrop = crop;
            rememberUiState();
            ensureBitmapPool(bitmapSize[0], bitmapSize[1]);
            if (!geometry.equals(lastGeometry)) {
                lastGeometry = geometry;
                Log.i(TAG, "2a geometry " + geometry);
                if (!inFlight && playerState != VideoState.ENDED && playerState != VideoState.NEW) {
                    requestCopy("geometry-change");
                }
                if (captureMode == CaptureMode.PLAYING) scheduleVsync();
            }

            final String position = zoomX + "," + zoomY + "," + zoomWidth + "x" + zoomHeight;
            if (!position.equals(lastPosition)) {
                lastPosition = position;
                Log.i(TAG, "2a zoom window on screen=" + position);
            }
        }

        private void place(View view, int left, int top, int width, int height) {
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    Math.max(1, width), Math.max(1, height), Gravity.TOP | Gravity.LEFT);
            params.leftMargin = left;
            params.topMargin = top;
            final ViewGroup.LayoutParams existing = view.getLayoutParams();
            if (!(existing instanceof FrameLayout.LayoutParams)
                    || existing.width != params.width || existing.height != params.height
                    || ((FrameLayout.LayoutParams) existing).leftMargin != params.leftMargin
                    || ((FrameLayout.LayoutParams) existing).topMargin != params.topMargin) {
                view.setLayoutParams(params);
            }
        }

        private Rect sourceBufferRect(Rect frame, float leftFraction, float topFraction,
                                      float widthFraction, float heightFraction) {
            final int left = frame.left + (int) Math.floor(leftFraction * frame.width());
            final int top = frame.top + (int) Math.floor(topFraction * frame.height());
            final int right = frame.left + (int) Math.ceil((leftFraction + widthFraction)
                    * frame.width());
            final int bottom = frame.top + (int) Math.ceil((topFraction + heightFraction)
                    * frame.height());
            final Rect crop = new Rect(left, top, right, bottom);
            crop.intersect(frame);
            return crop;
        }

        private int[] chooseBitmapSize(int cropWidth, int cropHeight,
                                       int displayWidth, int displayHeight) {
            // PixelCopy scales the source rect to this destination. Keep the native crop when
            // enlarging; request a smaller bitmap only when the screen window is smaller.
            final double downScale = Math.min(1d, Math.min(
                    displayWidth / (double) cropWidth,
                    displayHeight / (double) cropHeight));
            final double capScale = Math.min(1d, MAX_BITMAP_EDGE
                    / (double) Math.max(cropWidth, cropHeight));
            final double scale = Math.min(downScale, capScale);
            return new int[]{Math.max(1, (int) Math.round(cropWidth * scale)),
                    Math.max(1, (int) Math.round(cropHeight * scale))};
        }

        private void ensureBitmapPool(int width, int height) {
            // A player can publish its visible Surface before the first valid source crop has
            // been measured. Do not allocate a zero-sized pool during that layout transition.
            if (width <= 0 || height <= 0) return;
            if (width == desiredBitmapWidth && height == desiredBitmapHeight
                    && bitmaps[0] != null && bitmaps[1] != null) return;
            desiredBitmapWidth = width;
            desiredBitmapHeight = height;
            generation++;
            if (!inFlight) replaceBitmapPool();
            else stateLabel.setVisibility(View.GONE);
        }

        private void replaceBitmapPool() {
            if (desiredBitmapWidth <= 0 || desiredBitmapHeight <= 0) return;
            zoomImage.setImageBitmap(null);
            recycle(bitmaps[0]);
            recycle(bitmaps[1]);
            bitmaps[0] = Bitmap.createBitmap(desiredBitmapWidth, desiredBitmapHeight,
                    Bitmap.Config.ARGB_8888);
            bitmaps[1] = Bitmap.createBitmap(desiredBitmapWidth, desiredBitmapHeight,
                    Bitmap.Config.ARGB_8888);
            allocatedBitmapWidth = desiredBitmapWidth;
            allocatedBitmapHeight = desiredBitmapHeight;
            displayedBuffer = -1;
            nextBuffer = 0;
            lastFrameSignature = Long.MIN_VALUE;
            stateLabel.setVisibility(View.GONE);
        }

        private final Runnable pausedPoll = new Runnable() {
            @Override public void run() {
                if (stopped || captureMode != CaptureMode.IDLE) return;
                if (!isCaptureTargetVisible()) return;
                final VideoState current = VideoState.getCurrent();
                if (current == VideoState.PLAYING || current == null) {
                    onVideoStateChanged(current, "paused-poll-state-check");
                    return;
                }
                if (current != VideoState.PAUSED) return;
                warnIfSlowWhilePlaying("paused-seek-poll");
                requestCopy("paused-seek-poll");
                handler.postDelayed(this, 500L);
            }
        };

        private final Runnable visibilityRetry = () -> {
            if (!stopped && captureMode == CaptureMode.PLAYING) scheduleVsync();
        };

        private enum CaptureMode { PLAYING, IDLE }

        private void onVideoStateChanged(VideoState state, String reason) {
            if (stopped) return;
            playerState = state;
            if (state == null || state == VideoState.PLAYING) {
                oneShotFastRefresh = false;
                transitionTo(CaptureMode.PLAYING,
                        state == null ? reason + "-state-unavailable-default-fast" : reason);
                return;
            }

            transitionTo(CaptureMode.IDLE, reason + "-" + state.name().toLowerCase());
            if (state == VideoState.PAUSED) {
                requestCopy("state-to-paused");
                handler.removeCallbacks(pausedPoll);
                handler.postDelayed(pausedPoll, 500L);
            } else if (state == VideoState.ENDED || state == VideoState.NEW
                    || state == VideoState.UNRECOVERABLE_ERROR) {
                handler.removeCallbacks(pausedPoll);
                logPerf("capture-stopped reason=video-" + state.name().toLowerCase()
                        + " editMode=" + editMode);
            } else {
                handler.removeCallbacks(pausedPoll);
                requestCopy("state-to-" + state.name().toLowerCase());
            }
        }

        private void transitionTo(CaptureMode next, String reason) {
            final boolean changed = captureMode != next;
            captureMode = next;
            modeReason = reason;
            if (changed || "initial-video-state".equals(reason)) {
                logPerf("mode=" + next + " reason=" + reason
                        + " videoState=" + stateName(VideoState.getCurrent()));
            }
            if (next == CaptureMode.PLAYING) {
                handler.removeCallbacks(pausedPoll);
                scheduleVsync();
            } else {
                cancelVsync();
            }
            warnIfSlowWhilePlaying("mode-transition:" + reason);
        }

        private void warnIfSlowWhilePlaying(String source) {
            if (captureMode == CaptureMode.IDLE && VideoState.getCurrent() == VideoState.PLAYING) {
                if (Settings.DEBUG.get()) Log.w(PERF_TAG,
                        "slow-mode-while-playing source=" + source + " modeReason=" + modeReason);
                onVideoStateChanged(VideoState.PLAYING, "slow-mode-state-guard:" + source);
            }
        }

        private void reconcileVideoState(String source) {
            warnIfSlowWhilePlaying(source);
            final VideoState current = VideoState.getCurrent();
            if (current == null || current == VideoState.PLAYING) {
                if (captureMode != CaptureMode.PLAYING) {
                    onVideoStateChanged(current, "state-guard:" + source);
                }
            } else if (captureMode != CaptureMode.IDLE && !oneShotFastRefresh) {
                onVideoStateChanged(current, "state-guard:" + source);
            }
            warnIfSlowWhilePlaying(source);
        }

        private void onVsync(long frameTimeNanos) {
            frameCallbackPending = false;
            if (stopped) return;

            final VideoState current = VideoState.getCurrent();
            if (oneShotFastRefresh && current != VideoState.PLAYING) {
                oneShotFastRefresh = false;
                if (!requestCopy("paused-seek-fast-followup")) {
                    onVideoStateChanged(current, "paused-seek-fast-followup-unavailable");
                }
                return;
            }

            reconcileVideoState("vsync");
            if (captureMode != CaptureMode.PLAYING) return;
            if (!isCaptureTargetVisible()) {
                cancelVsync();
                handler.removeCallbacks(pausedPoll);
                return;
            }
            if (inFlight) {
                secondBusyVsyncs++;
                return; // completion re-arms at the next display vsync
            }
            if (!requestCopy("vsync")) scheduleVsync();
        }

        private void scheduleVsync() {
            if (stopped || captureMode != CaptureMode.PLAYING || frameCallbackPending) return;
            frameCallbackPending = true;
            choreographer.postFrameCallback(frameCallback);
        }

        private void cancelVsync() {
            if (!frameCallbackPending) return;
            choreographer.removeFrameCallback(frameCallback);
            frameCallbackPending = false;
        }

        private boolean requestCopy(String reason) {
            if (!enabled || stopped || inFlight || !isCaptureTargetVisible()
                    || zoomWindow.getVisibility() != View.VISIBLE
                    || !surface.getHolder().getSurface().isValid()) {
                return false;
            }
            final Rect frame = surface.getHolder().getSurfaceFrame();
            final Rect crop = new Rect(lastCrop);
            // Geometry may be transiently unavailable as a watch surface is first attached or
            // restored from fullscreen/miniplayer. Validate the crop before replacing/dereferencing
            // the bitmap pool; the next layout/vsync will retry once the player publishes geometry.
            if (crop.isEmpty() || !frame.contains(crop)
                    || desiredBitmapWidth <= 0 || desiredBitmapHeight <= 0) return false;
            if (bitmaps[0] == null || bitmaps[1] == null
                    || allocatedBitmapWidth != desiredBitmapWidth
                    || allocatedBitmapHeight != desiredBitmapHeight) {
                replaceBitmapPool();
            }
            if (bitmaps[0] == null || bitmaps[1] == null) return false;
            final int requestedIndex = displayedBuffer < 0 ? nextBuffer : 1 - displayedBuffer;
            final Bitmap requestedBitmap = bitmaps[requestedIndex];
            final int requestedGeneration = generation;
            final long requestStart = SystemClock.elapsedRealtime();
            lastRequestReason = reason;
            inFlight = true;
            try {
                PixelCopy.request(surface, crop, requestedBitmap, result -> {
                    inFlight = false;
                    final long now = SystemClock.elapsedRealtime();
                    lastCopyMs = now - requestStart;
                    secondCopies++;
                    secondLatencyTotalMs += lastCopyMs;
                    secondLatencyMaxMs = Math.max(secondLatencyMaxMs, lastCopyMs);
                    if (stopped) {
                        releaseBitmapPool();
                        return;
                    }
                    if (requestedGeneration != generation) {
                        replaceBitmapPool();
                        rearmAfterCopy();
                        return;
                    }
                    if (result == PixelCopy.SUCCESS) {
                        stateLabel.setVisibility(View.GONE);
                        zoomImage.setVisibility(View.VISIBLE);
                        final long signature = sampleSignature(requestedBitmap);
                        final boolean unique = signature != lastFrameSignature;
                        if (unique) {
                            lastFrameSignature = signature;
                            secondUniqueFrames++;
                            displayedBuffer = requestedIndex;
                            nextBuffer = 1 - requestedIndex;
                            zoomImage.setImageBitmap(requestedBitmap);
                            zoomImage.invalidate();
                        } else {
                            // Hashes suppress redundant UI updates only. They never stop copying.
                            secondDuplicateFrames++;
                        }

                        if ("paused-seek-poll".equals(reason) && unique
                                && captureMode == CaptureMode.IDLE) {
                            final VideoState current = VideoState.getCurrent();
                            if (current == VideoState.PLAYING || current == null) {
                                onVideoStateChanged(current, "unique-frame-from-paused-poll");
                            } else {
                                oneShotFastRefresh = true;
                                transitionTo(CaptureMode.PLAYING,
                                        "unique-frame-from-paused-seek-poll");
                                scheduleVsync();
                            }
                        } else if ("paused-seek-fast-followup".equals(reason)
                                && VideoState.getCurrent() != VideoState.PLAYING) {
                            onVideoStateChanged(VideoState.getCurrent(),
                                    "paused-seek-fast-followup-complete");
                        }
                    } else {
                        zoomImage.setImageBitmap(null);
                        zoomImage.setVisibility(View.GONE);
                        showError("PixelCopy unavailable (" + result + ")");
                        if (now - lastErrorLogMs > 1000L) {
                            lastErrorLogMs = now;
                            Log.w(TAG, "PixelCopy result=" + result + " crop=" + crop
                                    + " frame=" + frame);
                        }
                    }
                    rearmAfterCopy();
                }, handler);
            } catch (RuntimeException exception) {
                inFlight = false;
                secondCopies++;
                lastCopyMs = SystemClock.elapsedRealtime() - requestStart;
                secondLatencyTotalMs += lastCopyMs;
                secondLatencyMaxMs = Math.max(secondLatencyMaxMs, lastCopyMs);
                zoomImage.setImageBitmap(null);
                zoomImage.setVisibility(View.GONE);
                showError("PixelCopy unavailable");
                Log.w(TAG, "PixelCopy request failed: " + exception.getClass().getSimpleName());
                rearmAfterCopy();
            }
            return true;
        }

        private void rearmAfterCopy() {
            if (stopped) return;
            if (captureMode == CaptureMode.PLAYING && isCaptureTargetVisible()) scheduleVsync();
        }

        private long sampleSignature(Bitmap bitmap) {
            long hash = 0xcbf29ce484222325L;
            for (int y = 0; y < SIGNATURE_GRID_SIZE; y++) {
                final int sampleY = Math.min(bitmap.getHeight() - 1,
                        y * bitmap.getHeight() / SIGNATURE_GRID_SIZE);
                for (int x = 0; x < SIGNATURE_GRID_SIZE; x++) {
                    final int sampleX = Math.min(bitmap.getWidth() - 1,
                            x * bitmap.getWidth() / SIGNATURE_GRID_SIZE);
                    hash ^= bitmap.getPixel(sampleX, sampleY);
                    hash *= 0x100000001b3L;
                }
            }
            return hash;
        }

        private void reportMetrics() {
            reconcileVideoState("metrics");
            final long now = SystemClock.elapsedRealtime();
            final long elapsed = Math.max(1L, now - secondStartMs);
            final Rect crop = lastCrop;
            final int windowWidth = zoomWindow.getWidth();
            final int windowHeight = zoomWindow.getHeight();
            final int imageWidth = zoomImage.getWidth();
            final int imageHeight = zoomImage.getHeight();
            final double upscaleX = imageWidth / (double) Math.max(1, crop.width());
            final double upscaleY = imageHeight / (double) Math.max(1, crop.height());
            final double effectiveUpscale = Math.min(upscaleX, upscaleY);
            final String sourceLimited = effectiveUpscale > 1d ? "true" : "false";
            final double averageLatency = secondCopies == 0 ? 0d
                    : secondLatencyTotalMs / (double) secondCopies;
            final Rect frame = surface.getHolder().getSurfaceFrame();

            if (Settings.DEBUG.get()) Log.i(PERF_TAG, "second spanMs=" + elapsed
                    + " mode=" + (!enabled ? "OFF" : captureMode)
                    + " captureVisible=" + isCaptureTargetVisible() + " editMode=" + editMode
                    + " reason=" + modeReason
                    + " videoState=" + stateName(VideoState.getCurrent())
                    + " copies=" + String.format(java.util.Locale.ROOT, "%.1f",
                    secondCopies * 1000d / elapsed)
                    + " uniqueFrames=" + String.format(java.util.Locale.ROOT, "%.1f",
                    secondUniqueFrames * 1000d / elapsed)
                    + " duplicates=" + String.format(java.util.Locale.ROOT, "%.1f",
                    secondDuplicateFrames * 1000d / elapsed)
                    + " busyVsyncs=" + secondBusyVsyncs
                    + " latencyAvgMs=" + String.format(java.util.Locale.ROOT, "%.2f", averageLatency)
                    + " latencyMaxMs=" + secondLatencyMaxMs + " latencyLastMs=" + lastCopyMs
                    + " decoded=" + lastVideoWidth + "x" + lastVideoHeight
                    + " surfaceFrame=" + frame.width() + "x" + frame.height()
                    + " nativeCrop=" + crop.width() + "x" + crop.height()
                    + " bitmap=" + allocatedBitmapWidth + "x" + allocatedBitmapHeight
                    + " zoomWindowPx=" + windowWidth + "x" + windowHeight
                    + " imagePx=" + imageWidth + "x" + imageHeight
                    + " effectiveUpscale=" + String.format(java.util.Locale.ROOT, "%.3f",
                    effectiveUpscale) + " sourceLimited=" + sourceLimited);

            secondStartMs = now;
            secondCopies = 0;
            secondUniqueFrames = 0;
            secondDuplicateFrames = 0;
            secondBusyVsyncs = 0;
            secondLatencyTotalMs = 0L;
            secondLatencyMaxMs = 0L;
        }

        private String stateName(VideoState state) {
            return state == null ? "unknown" : state.name();
        }

        private void rememberUiState() {
            // Constructor styling runs before the first measured player layout. Do not persist
            // its zero-height placeholder as if it were real first-use geometry.
            if (!geometryInitialized || !isValidSourceGeometry(sourceLeftFraction,
                    sourceTopFraction, sourceWidthFraction, sourceHeightFraction)) return;
            UiState next = new UiState(this);
            UiState previous = savedUiState;
            boolean changed = previous == null
                    || next.sourceLeft != previous.sourceLeft || next.sourceTop != previous.sourceTop
                    || next.sourceWidth != previous.sourceWidth || next.sourceHeight != previous.sourceHeight
                    || next.zoomLeft != previous.zoomLeft || next.zoomTop != previous.zoomTop
                    || next.zoomWidth != previous.zoomWidth
                    || next.chromeLeft != previous.chromeLeft || next.chromeTop != previous.chromeTop;
            if (!changed) return;
            geometryInitialized = true;
            savedUiState = next;
            decor.getContext().getSharedPreferences("morphe_magnifier_geometry",
                    android.content.Context.MODE_PRIVATE).edit()
                    .putFloat("source_left", sourceLeftFraction)
                    .putFloat("source_top", sourceTopFraction)
                    .putFloat("source_width", sourceWidthFraction)
                    .putFloat("source_height", sourceHeightFraction)
                    .putFloat("zoom_left", zoomLeftFraction)
                    .putFloat("zoom_top", zoomTopFraction)
                    .putFloat("zoom_width", zoomWidthFraction)
                    .putFloat("chrome_left", chromeLeftFraction)
                    .putFloat("chrome_top", chromeTopFraction)
                    .apply();
        }

        private void showError(String message) {
            if (stopped) return;
            // Never replace the magnifier with a standalone error panel. Keep the last
            // successful frame and report transient/protected-content failures in logcat.
            long now = SystemClock.elapsedRealtime();
            if (now - lastErrorLogMs >= 5000L) {
                lastErrorLogMs = now;
                Log.w(TAG, "PixelCopy unavailable: " + message);
            }
            stateLabel.setVisibility(View.GONE);
        }

        void stop() {
            if (stopped) return;
            rememberUiState();
            stopped = true;
            VideoState.getOnChange().removeObserver(videoStateObserver);
            PlayerControlsVisibility.getOnChange().removeObserver(controlsObserver);
            PlayerType.getOnChange().removeObserver(playerTypeObserver);
            if (decor.getViewTreeObserver().isAlive()) {
                decor.getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);
            }
            try {
                decor.getContext().unregisterReceiver(screenStateReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver may already be unregistered if the Activity is being torn down.
            }
            if (playerGearButton != null && playerGearButton.getViewTreeObserver().isAlive()) {
                playerGearButton.getViewTreeObserver().removeOnPreDrawListener(
                        gearButtonLayoutListener);
            }
            cancelVsync();
            handler.removeCallbacks(pausedPoll);
            handler.removeCallbacks(metricsReporter);
            handler.removeCallbacks(visibilityRetry);
            handler.removeCallbacks(controlsHideEnforcer);
            restorePlayerControls();
            if (zoomWindow.getParent() instanceof ViewGroup) {
                ((ViewGroup) zoomWindow.getParent()).removeView(zoomWindow);
            }
            if (editModeButton.getParent() instanceof ViewGroup) {
                ((ViewGroup) editModeButton.getParent()).removeView(editModeButton);
            }
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            if (!inFlight) releaseBitmapPool();
        }

        private void releaseBitmapPool() {
            zoomImage.setImageBitmap(null);
            recycle(bitmaps[0]);
            recycle(bitmaps[1]);
            bitmaps[0] = null;
            bitmaps[1] = null;
        }

        private void recycle(Bitmap bitmap) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** Small YouTube-style outline fallback for older APKs without the box-pencil drawable. */
    private static final class EditModeIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        EditModeIconDrawable() {
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            final Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            final int save = canvas.save();
            final float side = Math.min(bounds.width(), bounds.height());
            canvas.translate(bounds.left + (bounds.width() - side) / 2f,
                    bounds.top + (bounds.height() - side) / 2f);
            canvas.scale(side / 24f, side / 24f);
            path.reset();
            path.moveTo(14f, 4f);
            path.lineTo(5f, 4f);
            path.quadTo(4f, 4f, 4f, 5f);
            path.lineTo(4f, 19f);
            path.quadTo(4f, 20f, 5f, 20f);
            path.lineTo(19f, 20f);
            path.quadTo(20f, 20f, 20f, 19f);
            path.lineTo(20f, 14f);
            canvas.drawPath(path, paint);
            path.reset();
            path.moveTo(11f, 16f);
            path.lineTo(12f, 12f);
            path.lineTo(18f, 6f);
            path.quadTo(19f, 5f, 20f, 6f);
            path.lineTo(21f, 7f);
            path.quadTo(22f, 8f, 21f, 9f);
            path.lineTo(15f, 15f);
            path.close();
            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Compact white outline glyphs matching the player's existing control icon style. */
    private static final class ChromeIconDrawable extends Drawable {
        static final int DONE = 0;
        static final int OFF = 1;
        static final int STYLE = 2;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int icon;

        ChromeIconDrawable(int icon) {
            this.icon = icon;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            final Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            final int save = canvas.save();
            final float side = Math.min(bounds.width(), bounds.height());
            canvas.translate(bounds.left + (bounds.width() - side) / 2f,
                    bounds.top + (bounds.height() - side) / 2f);
            canvas.scale(side / 24f, side / 24f);
            if (icon == DONE) {
                canvas.drawLine(5f, 12f, 10f, 17f, paint);
                canvas.drawLine(10f, 17f, 20f, 7f, paint);
            } else if (icon == OFF) {
                canvas.drawArc(5f, 5f, 19f, 19f, 45f, 270f, false, paint);
                canvas.drawLine(12f, 3f, 12f, 12f, paint);
            } else {
                canvas.drawLine(4f, 6f, 20f, 6f, paint);
                canvas.drawLine(4f, 12f, 20f, 12f, paint);
                canvas.drawLine(4f, 18f, 20f, 18f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(9f, 6f, 2.2f, paint);
                canvas.drawCircle(15f, 12f, 2.2f, paint);
                canvas.drawCircle(11f, 18f, 2.2f, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final class SourceOutline extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SourceOutline(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            paint.setColor(0xffffd740);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, 2f * getResources().getDisplayMetrics().density));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float inset = paint.getStrokeWidth() / 2f;
            canvas.drawRect(new RectF(inset, inset, getWidth() - inset, getHeight() - inset), paint);
            final float radius = Math.max(3.45f * getResources().getDisplayMetrics().density,
                    paint.getStrokeWidth());
            float left = inset, right = getWidth() - inset;
            float top = inset, bottom = getHeight() - inset;
            canvas.drawCircle(left, top, radius, paint);
            canvas.drawCircle((left + right) / 2f, top, radius, paint);
            canvas.drawCircle(right, top, radius, paint);
            canvas.drawCircle(left, (top + bottom) / 2f, radius, paint);
            canvas.drawCircle(right, (top + bottom) / 2f, radius, paint);
            canvas.drawCircle(left, bottom, radius, paint);
            canvas.drawCircle((left + right) / 2f, bottom, radius, paint);
            canvas.drawCircle(right, bottom, radius, paint);
        }
    }

    /** Small visible corner grips; resize touch areas remain substantially smaller than the body. */
    private static final class ZoomFrameLayout extends FrameLayout {
        private final Prototype owner;
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ZoomFrameLayout(android.content.Context context, Prototype owner) {
            super(context);
            this.owner = owner;
            setWillNotDraw(false);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (!owner.editMode) return;
            float radius = Math.max(2f, 5f * getResources().getDisplayMetrics().density);
            float inset = radius + Math.max(1f, getResources().getDisplayMetrics().density);
            handlePaint.setStyle(Paint.Style.FILL);
            handlePaint.setColor(Color.WHITE);
            float right = getWidth() - inset;
            float bottom = getHeight() - inset;
            canvas.drawCircle(inset, inset, radius, handlePaint);
            canvas.drawCircle(right, inset, radius, handlePaint);
            canvas.drawCircle(inset, bottom, radius, handlePaint);
            canvas.drawCircle(right, bottom, radius, handlePaint);
            handlePaint.setStyle(Paint.Style.STROKE);
            handlePaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
            int alpha = Math.round(255f * owner.borderOpacity / 100f);
            handlePaint.setColor((owner.borderColor & 0x00ffffff) | (alpha << 24));
            canvas.drawCircle(inset, inset, radius, handlePaint);
            canvas.drawCircle(right, inset, radius, handlePaint);
            canvas.drawCircle(inset, bottom, radius, handlePaint);
            canvas.drawCircle(right, bottom, radius, handlePaint);
        }
    }

    private static final class VideoSize {
        final int width;
        final int height;

        VideoSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static String surfaceSecureState(SurfaceView view) {
        try {
            final Field flagsField = SurfaceView.class.getDeclaredField("mSurfaceFlags");
            flagsField.setAccessible(true);
            final int flags = flagsField.getInt(view);
            final Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
            final Field secureField = surfaceControl.getDeclaredField("SECURE");
            secureField.setAccessible(true);
            final int secureFlag = secureField.getInt(null);
            return Boolean.toString((flags & secureFlag) != 0);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return "unavailable:" + exception.getClass().getSimpleName();
        }
    }

    private static String parentChain(View view) {
        final StringBuilder chain = new StringBuilder();
        View current = view;
        while (current != null) {
            if (chain.length() > 0) chain.append(" <- ");
            chain.append(current.getClass().getName());
            final Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return chain.toString();
    }

}
