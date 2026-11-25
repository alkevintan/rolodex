package com.aktivesolutions.rolodex;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

/** Draws a single edge trigger and the translucent quick-search panel. */
public class OverlayService extends Service {
    /** Used by MainActivity to keep its control button in sync. */
    static boolean running = false;

    static final String PREFS = "rolodex_prefs";
    static final String KEY_SIDE = "trigger_side";
    static final String SIDE_RIGHT = "right";
    static final String SIDE_LEFT = "left";
    static final String KEY_WIDTH = "trigger_width_dp";
    static final String KEY_HEIGHT = "trigger_height_dp";
    static final int DEFAULT_WIDTH = 10;
    static final int DEFAULT_HEIGHT = 128;
    static final String KEY_LAUNCH_MODE = "launch_mode";
    static final String LAUNCH_FULL = "fullscreen";
    static final String LAUNCH_SPLIT = "split";
    static final String LAUNCH_FLOAT = "floating";
    static final String KEY_RATES_JSON = "exchange_rates_json";
    static final String KEY_RATES_TIME = "exchange_rates_time";
    private static final String RATES_URL = "https://open.er-api.com/v6/latest/USD";
    private static final long RATES_MAX_AGE_MS = 6L * 60 * 60 * 1000;

    private static OverlayService instance;

    private static final String CHANNEL_ID = "rolodex_overlay";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_RESULTS = 100;

    private WindowManager wm;
    private Handler mainHandler;

    private View trigger;
    private boolean triggerAdded = false;

    private FrameLayout searchRoot;
    private View dimLayer;
    private LinearLayout searchCard;
    private EditText searchInput;
    private LinearLayout resultsContainer;
    private ScrollView resultsScroll;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private Runnable firstAction;
    private boolean searchOpen = false;
    private boolean dismissing = false;

    /** Screen coordinates of the pill that opened the panel — the animation anchor. */
    private float originX;
    private float originY;

    private final List<AppInfo> allApps = new ArrayList<>();
    private final Map<String, String[]> dictionary = new HashMap<>();
    /** ISO code -> units of that currency per 1 USD. */
    private final Map<String, Double> exchangeRates = new HashMap<>();

    private static final Pattern CONVERSION_PATTERN = Pattern.compile(
            "^([-+]?\\d+(?:\\.\\d+)?)\\s*([A-Za-z\u00b0/]+)\\s+(?:to|in)\\s+([A-Za-z\u00b0/]+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY_PATTERN = Pattern.compile(
            "^([-+]?\\d+(?:\\.\\d+)?)\\s*([A-Za-z$\u20ac\u00a3\u00a5]{1,3})\\s+(?:to|in)\\s+([A-Za-z$\u20ac\u00a3\u00a5]{1,3})$",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Double> LENGTH_UNITS = new HashMap<>();
    private static final Map<String, Double> MASS_UNITS = new HashMap<>();
    private static final Map<String, Double> SPEED_UNITS = new HashMap<>();
    private static final Set<String> TEMPERATURE_UNITS = new HashSet<>();

    static {
        // Values are in metres.
        putAll(LENGTH_UNITS, "mm", 0.001, "millimeter", 0.001, "millimeters", 0.001,
                "cm", 0.01, "centimeter", 0.01, "centimeters", 0.01,
                "m", 1.0, "meter", 1.0, "meters", 1.0, "metre", 1.0, "metres", 1.0,
                "km", 1000.0, "kilometer", 1000.0, "kilometers", 1000.0,
                "in", 0.0254, "inch", 0.0254, "inches", 0.0254,
                "ft", 0.3048, "foot", 0.3048, "feet", 0.3048,
                "yd", 0.9144, "yard", 0.9144, "yards", 0.9144,
                "mi", 1609.344, "mile", 1609.344, "miles", 1609.344);
        // Values are in kilograms.
        putAll(MASS_UNITS, "mg", 0.000001, "milligram", 0.000001, "milligrams", 0.000001,
                "g", 0.001, "gram", 0.001, "grams", 0.001,
                "kg", 1.0, "kilogram", 1.0, "kilograms", 1.0,
                "oz", 0.0283495, "ounce", 0.0283495, "ounces", 0.0283495,
                "lb", 0.45359237, "lbs", 0.45359237, "pound", 0.45359237, "pounds", 0.45359237);
        // Values are in metres per second.
        putAll(SPEED_UNITS, "m/s", 1.0, "kph", 1.0 / 3.6, "km/h", 1.0 / 3.6,
                "mph", 0.44704, "mi/h", 0.44704);
        TEMPERATURE_UNITS.addAll(java.util.Arrays.asList(
                "c", "celsius", "\u00b0c", "f", "fahrenheit", "\u00b0f", "k", "kelvin"));
    }

    private static void putAll(Map<String, Double> map, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Double) pairs[i + 1]);
        }
    }

    /** One row in the result list. */
    private static final class Result {
        final Drawable icon;
        final String title;
        final String subtitle;
        final Runnable action;
        final boolean isApp;
        final boolean dictionary;

        Result(Drawable icon, String title, String subtitle, Runnable action,
               boolean isApp, boolean dictionary) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
            this.isApp = isApp;
            this.dictionary = dictionary;
        }
    }

    /** Called by MainActivity after the user changes a trigger preference. */
    static void onTriggerPrefsChanged() {
        OverlayService service = instance;
        if (service != null) service.mainHandler.post(service::applyTriggerPosition);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        loadDataInBackground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        running = true;
        addTrigger();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        dismissImmediate();
        removeTrigger();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isRightSide() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return SIDE_RIGHT.equals(prefs.getString(KEY_SIDE, SIDE_RIGHT));
    }

    // ------------------------------------------------------------------
    // Edge trigger
    // ------------------------------------------------------------------

    private void addTrigger() {
        if (triggerAdded || wm == null) return;
        try {
            trigger = createTrigger();
            wm.addView(trigger, triggerLayoutParams());
            triggerAdded = true;
        } catch (Exception e) {
            stopSelf();
        }
    }

    private WindowManager.LayoutParams triggerLayoutParams() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int width = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH);
        int height = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(width), dp(height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (isRightSide() ? Gravity.END : Gravity.START);
        lp.x = 0;
        lp.y = dp(168);
        return lp;
    }

    private void applyTriggerPosition() {
        if (trigger == null || wm == null) return;
        try {
            wm.updateViewLayout(trigger, triggerLayoutParams());
        } catch (Exception ignored) { }
    }

    private View createTrigger() {
        View view = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.argb(50, 255, 255, 255));
        float radius = dp(6);
        bg.setCornerRadii(new float[]{radius, radius, radius, radius,
                radius, radius, radius, radius});
        bg.setStroke(1, Color.argb(70, 255, 255, 255));
        view.setBackground(bg);
        view.setAlpha(0.5f);

        final float[] startX = new float[1];
        final float[] startY = new float[1];
        final boolean[] opened = new boolean[1];
        final int threshold = dp(28);

        view.setOnTouchListener((v, event) -> {
            originX = event.getRawX();
            originY = event.getRawY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = event.getX();
                    startY[0] = event.getY();
                    opened[0] = false;
                    v.setAlpha(0.85f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!opened[0] && (Math.abs(event.getY() - startY[0]) > threshold
                            || Math.abs(event.getX() - startX[0]) > threshold)) {
                        opened[0] = true;
                        openSearch();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(0.5f);
                    if (!opened[0]) openSearch();
                    return true;
                default:
                    return true;
            }
        });
        return view;
    }

    private void setTriggerVisible(boolean visible) {
        if (trigger != null) trigger.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void removeTrigger() {
        if (trigger != null) {
            safeRemove(trigger);
            trigger = null;
        }
        triggerAdded = false;
    }

    // ------------------------------------------------------------------
    // Search panel
    // ------------------------------------------------------------------

    private void openSearch() {
        if (searchOpen) {
            focusInput();
            return;
        }
        try {
            buildSearchPanel();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    0,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
            wm.addView(searchRoot, lp);
            searchOpen = true;
            setTriggerVisible(false);
            searchRoot.post(() -> {
                animateCard(true, null);
                focusInput();
            });
        } catch (Exception e) {
            searchOpen = false;
            stopSelf();
        }
    }

    private void buildSearchPanel() {
        searchRoot = new FrameLayout(this);
        searchRoot.setBackgroundColor(Color.TRANSPARENT);
        searchRoot.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) dismissSearch();
            return true;
        });

        dimLayer = new View(this);
        dimLayer.setBackgroundColor(Color.BLACK);
        dimLayer.setAlpha(0f);
        searchRoot.addView(dimLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cardWidth = Math.min(dp(560), screenWidth - dp(32));

        searchCard = new LinearLayout(this);
        searchCard.setOrientation(LinearLayout.VERTICAL);
        searchCard.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(225, 20, 22, 28));
        float corner = dp(24);
        cardBg.setCornerRadii(new float[]{corner, corner, corner, corner,
                corner, corner, corner, corner});
        cardBg.setStroke(1, Color.argb(70, 255, 255, 255));
        searchCard.setBackground(cardBg);
        searchCard.setElevation(dp(12));
        searchCard.setPadding(dp(10), dp(10), dp(10), dp(10));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = dp(64);
        searchRoot.addView(searchCard, cardLp);
        placeCardAtOrigin();

        // ---- Pill-shaped search field ----
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setMinimumHeight(dp(52));
        searchRow.setPadding(dp(14), 0, dp(8), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.argb(90, 255, 255, 255));
        float pillRadius = dp(26);
        pill.setCornerRadii(new float[]{pillRadius, pillRadius, pillRadius, pillRadius,
                pillRadius, pillRadius, pillRadius, pillRadius});
        searchRow.setBackground(pill);

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(android.R.drawable.ic_menu_search);
        searchIcon.setColorFilter(Color.WHITE);
        int iconSize = dp(20);
        searchIcon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        searchRow.addView(searchIcon);

        searchInput = new EditText(this);
        searchInput.setHint("Apps, words, maths, web…");
        searchInput.setHintTextColor(Color.argb(160, 255, 255, 255));
        searchInput.setTextColor(Color.WHITE);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.setMargins(dp(10), 0, dp(6), 0);
        searchInput.setLayoutParams(inputLp);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateResults(s.toString()); }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH && firstAction != null) {
                Runnable action = firstAction;
                dismissSearch(action);
                return true;
            }
            return false;
        });
        searchInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                dismissSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(searchInput);

        ImageView clearButton = new ImageView(this);
        clearButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        clearButton.setColorFilter(Color.argb(200, 255, 255, 255));
        clearButton.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        clearButton.setOnClickListener(v -> searchInput.setText(""));
        searchRow.addView(clearButton);

        searchCard.addView(searchRow);

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(40, 255, 255, 255));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dividerLp.topMargin = dp(8);
        divider.setLayoutParams(dividerLp);
        searchCard.addView(divider);

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);

        resultsScroll = new ScrollView(this);
        resultsScroll.setBackgroundColor(Color.TRANSPARENT);
        resultsScroll.setFillViewport(false);
        resultsScroll.addView(resultsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        searchCard.addView(resultsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        registerLayoutListener();
        updateResults("");
    }

    // ------------------------------------------------------------------
    // Expand / collapse animation
    // ------------------------------------------------------------------

    private void placeCardAtOrigin() {
        if (searchCard == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cardHeight = searchCard.getHeight() > 0 ? searchCard.getHeight() : dp(120);
        int topMargin = dp(64);
        ViewGroup.LayoutParams params = searchCard.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            topMargin = ((FrameLayout.LayoutParams) params).topMargin;
        }
        searchCard.setAlpha(0f);
        searchCard.setScaleX(0.05f);
        searchCard.setScaleY(0.05f);
        searchCard.setTranslationX(originX - screenWidth / 2f);
        searchCard.setTranslationY(originY - (topMargin + cardHeight / 2f));
    }

    /**
     * Keeps the results list inside the visible area when a soft keyboard is up.
     * getWindowVisibleDisplayFrame() reports the area above the keyboard even
     * though the overlay window itself does not resize.
     */
    private void registerLayoutListener() {
        if (layoutListener != null) return;
        layoutListener = () -> {
            if (searchRoot == null || searchCard == null || resultsScroll == null
                    || resultsContainer == null) return;

            Rect frame = new Rect();
            searchRoot.getWindowVisibleDisplayFrame(frame);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int visibleHeight = frame.height();
            if (visibleHeight <= 0) return;

            boolean keyboardOpen = visibleHeight < screenHeight - dp(120);
            int topMargin = keyboardOpen ? dp(8) : dp(64);
            FrameLayout.LayoutParams cardLp =
                    (FrameLayout.LayoutParams) searchCard.getLayoutParams();
            if (cardLp.topMargin != topMargin) {
                cardLp.topMargin = topMargin;
                searchCard.setLayoutParams(cardLp);
            }

            int headerHeight = dp(100);
            if (searchCard.getHeight() > 0 && resultsScroll.getHeight() > 0) {
                headerHeight = searchCard.getHeight() - resultsScroll.getHeight();
            }
            int wanted = visibleHeight - topMargin - headerHeight - dp(12);
            wanted = Math.max(dp(80), wanted);
            if (!keyboardOpen) wanted = Math.min(wanted, dp(420));
            int contentHeight = resultsContainer.getHeight();
            if (contentHeight > 0) wanted = Math.min(wanted, contentHeight);

            int current = resultsScroll.getHeight();
            if (current != wanted) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) resultsScroll.getLayoutParams();
                lp.height = wanted;
                resultsScroll.setLayoutParams(lp);
            }
        };
        searchRoot.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void unregisterLayoutListener() {
        if (layoutListener != null && searchRoot != null) {
            try {
                searchRoot.getViewTreeObserver()
                        .removeOnGlobalLayoutListener(layoutListener);
            } catch (Exception ignored) { }
        }
        layoutListener = null;
    }

    private void animateCard(boolean expand, final Runnable end) {
        if (searchCard == null) {
            if (end != null) end.run();
            return;
        }
        final View card = searchCard;
        card.animate().cancel();
        placeCardAtOrigin();

        if (dimLayer != null) {
            dimLayer.animate().cancel();
            dimLayer.animate()
                    .alpha(expand ? 0.4f : 0f)
                    .setDuration(expand ? 260 : 200)
                    .setInterpolator(expand
                            ? new DecelerateInterpolator()
                            : new AccelerateInterpolator())
                    .start();
        }

        ViewPropertyAnimator animator = card.animate()
                .setDuration(expand ? 260 : 200)
                .setInterpolator(expand
                        ? new DecelerateInterpolator()
                        : new AccelerateInterpolator());
        if (expand) {
            animator.alpha(1f).scaleX(1f).scaleY(1f)
                    .translationX(0f).translationY(0f);
        } else {
            animator.alpha(0f).scaleX(0.05f).scaleY(0.05f);
        }
        if (end != null) animator.withEndAction(end);
        animator.start();
    }

    private void focusInput() {
        if (searchInput == null) return;
        searchInput.requestFocus();
        searchInput.setFocusableInTouchMode(true);
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        if (searchInput == null) return;
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchInput.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private void dismissSearch() {
        dismissSearch(null);
    }

    private void dismissSearch(final Runnable after) {
        if (searchRoot == null) {
            if (after != null) after.run();
            return;
        }
        if (dismissing) return;
        dismissing = true;
        hideKeyboard();
        animateCard(false, () -> {
            finishDismiss();
            if (after != null) after.run();
        });
    }

    private void finishDismiss() {
        unregisterLayoutListener();
        if (searchRoot != null) {
            safeRemove(searchRoot);
            searchRoot = null;
        }
        searchCard = null;
        searchInput = null;
        resultsContainer = null;
        resultsScroll = null;
        dimLayer = null;
        firstAction = null;
        searchOpen = false;
        dismissing = false;
        setTriggerVisible(true);
    }

    private void dismissImmediate() {
        hideKeyboard();
        unregisterLayoutListener();
        if (searchRoot != null) {
            safeRemove(searchRoot);
            searchRoot = null;
        }
        searchCard = null;
        searchInput = null;
        resultsContainer = null;
        resultsScroll = null;
        dimLayer = null;
        firstAction = null;
        searchOpen = false;
        dismissing = false;
    }

    // ------------------------------------------------------------------
    // Results: dictionary, calculator, conversions, web, apps
    // ------------------------------------------------------------------

    private void updateResults(String query) {
        if (resultsContainer == null) return;
        resultsContainer.removeAllViews();
        firstAction = null;

        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            addHint("Type to search apps, words, maths…");
            return;
        }
        String lower = q.toLowerCase(Locale.ROOT);

        List<Result> results = new ArrayList<>();
        Runnable firstResultAction = null;
        Runnable firstAppAction = null;

        // Calculator
        Double value = evaluateMath(q);
        if (value != null) {
            String text = "= " + formatNumber(value);
            results.add(new Result(getDrawable(R.drawable.ic_calc), text,
                    "Calculator · tap to copy", copyAction(text), false, false));
        }

        // Exchange rate conversion
        String currency = convertCurrency(q);
        if (currency != null) {
            results.add(new Result(getDrawable(R.drawable.ic_convert), currency,
                    "Exchange rate · tap to copy", copyAction(currency), false, false));
        }

        // Unit conversion
        String conversion = convertUnits(q);
        if (conversion != null) {
            results.add(new Result(getDrawable(R.drawable.ic_convert), conversion,
                    "Unit conversion · tap to copy", copyAction(conversion), false, false));
        }

        // Offline dictionary
        String[] entry = dictionary.get(lower);
        if (entry != null) {
            String definition = entry[1];
            results.add(new Result(getDrawable(R.drawable.ic_dict), definition,
                    "Dictionary · " + entry[0], copyAction(definition), false, true));
        }

        // Web search (always available for a non-empty query)
        String searchUrl = "https://www.google.com/search?q=" + Uri.encode(q);
        results.add(new Result(getDrawable(R.drawable.ic_web),
                "Search web for “" + q + "”", "Opens in your browser",
                openUrlAction(searchUrl), false, false));

        // Installed apps — exact and prefix matches first so e.g. "X" surfaces
        // immediately instead of being buried behind other matches.
        List<AppInfo> exact = new ArrayList<>();
        List<AppInfo> prefix = new ArrayList<>();
        List<AppInfo> other = new ArrayList<>();
        for (AppInfo app : allApps) {
            if (app.lowerLabel.equals(lower)) exact.add(app);
            else if (app.lowerLabel.startsWith(lower)) prefix.add(app);
            else if (app.lowerLabel.contains(lower) || app.lowerPackage.contains(lower)) {
                other.add(app);
            }
        }
        List<AppInfo> orderedApps = new ArrayList<>();
        orderedApps.addAll(exact);
        orderedApps.addAll(prefix);
        orderedApps.addAll(other);
        for (AppInfo app : orderedApps) {
            final String packageName = app.packageName;
            Drawable icon;
            try {
                icon = getPackageManager().getApplicationIcon(app.appInfo);
            } catch (Exception e) {
                icon = getDrawable(android.R.drawable.sym_def_app_icon);
            }
            results.add(new Result(icon, app.label, null,
                    () -> launchApp(packageName), true, false));
        }

        Runnable chosen = null;
        int count = Math.min(MAX_RESULTS, results.size());
        for (int i = 0; i < count; i++) {
            Result result = results.get(i);
            if (firstResultAction == null) firstResultAction = result.action;
            if (result.isApp && firstAppAction == null) firstAppAction = result.action;
            addResultRow(result);
        }
        firstAction = firstAppAction != null ? firstAppAction : firstResultAction;
    }

    private void addHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(Color.argb(140, 255, 255, 255));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(dp(12), dp(12), dp(12), dp(12));
        resultsContainer.addView(hint);
    }

    private void addResultRow(final Result result) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(result.dictionary ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        int verticalPadding = result.dictionary ? dp(14) : dp(8);
        row.setPadding(dp(12), verticalPadding, dp(12), verticalPadding);

        int[] attrs = {android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        Drawable selectable = ta.getDrawable(0);
        ta.recycle();
        if (selectable != null) row.setBackground(selectable);

        if (result.icon != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(result.icon);
            int iconSize = dp(30);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
            row.addView(icon);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMargins(dp(12), 0, 0, 0);
        texts.setLayoutParams(textLp);

        TextView title = new TextView(this);
        title.setText(result.title);
        title.setTextColor(Color.WHITE);
        if (result.dictionary) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setSingleLine(false);
            title.setMaxLines(8);
            title.setLineSpacing(0, 1.15f);
        } else {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        texts.addView(title);

        if (result.subtitle != null) {
            TextView subtitle = new TextView(this);
            subtitle.setText(result.subtitle);
            subtitle.setTextColor(Color.argb(140, 255, 255, 255));
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle);
        }

        row.addView(texts);
        row.setOnClickListener(v -> dismissSearch(result.action));
        resultsContainer.addView(row);
    }

    private Runnable copyAction(final String text) {
        return () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Rolodex", text));
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        };
    }

    private Runnable openUrlAction(final String url) {
        return () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
            }
        };
    }

    private void launchApp(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Toast.makeText(this, "Cannot launch " + packageName, Toast.LENGTH_SHORT).show();
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String mode = prefs.getString(KEY_LAUNCH_MODE, LAUNCH_FULL);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;

            if (LAUNCH_FLOAT.equals(mode)) {
                int width = (int) (screenWidth * 0.8f);
                int height = (int) (screenHeight * 0.7f);
                int left = (screenWidth - width) / 2;
                int top = (screenHeight - height) / 2;
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchBounds(new Rect(left, top,
                        left + width, top + height));
                startActivity(intent, options.toBundle());
            } else if (LAUNCH_SPLIT.equals(mode)) {
                boolean launched = false;
                try {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    Method method = ActivityOptions.class
                            .getMethod("setLaunchWindowingMode", int.class);
                    method.invoke(options, 2); // WINDOWING_MODE_SPLIT_HORIZONTAL
                    startActivity(intent, options.toBundle());
                    launched = true;
                } catch (Throwable ignored) {
                    // Hidden API is unavailable on this device — use the fallback below.
                }
                if (!launched) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchBounds(new Rect(0, 0,
                            screenWidth / 2, screenHeight));
                    startActivity(intent, options.toBundle());
                }
            } else {
                startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not open that app", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Calculator
    // ------------------------------------------------------------------

    private static Double evaluateMath(String input) {
        String s = input.replace(" ", "")
                .replace('\u00d7', '*').replace('\u00f7', '/')
                .toLowerCase(Locale.ROOT);
        if (!s.matches(".*[0-9].*")) return null;
        if (!s.matches(".*[+\\-*/^%()a-z].*")) return null;
        try {
            MathParser parser = new MathParser(s);
            double result = parser.parseExpression();
            if (parser.pos != s.length()) return null;
            if (Double.isNaN(result) || Double.isInfinite(result)) return null;
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class MathParser {
        private final String input;
        private int pos;

        MathParser(String input) {
            this.input = input;
        }

        double parseExpression() {
            double value = parseTerm();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '+') {
                    pos++;
                    value += parseTerm();
                } else if (c == '-') {
                    pos++;
                    value -= parseTerm();
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseTerm() {
            double value = parseUnary();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '*') {
                    pos++;
                    value *= parseUnary();
                } else if (c == '/') {
                    pos++;
                    double divisor = parseUnary();
                    if (divisor == 0) throw new ArithmeticException();
                    value /= divisor;
                } else if (c == '%') {
                    pos++;
                    double divisor = parseUnary();
                    if (divisor == 0) throw new ArithmeticException();
                    value %= divisor;
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseUnary() {
            if (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '-') {
                    pos++;
                    return -parseUnary();
                }
                if (c == '+') {
                    pos++;
                    return parseUnary();
                }
            }
            return parsePower();
        }

        private double parsePower() {
            double base = parsePrimary();
            if (pos < input.length() && input.charAt(pos) == '^') {
                pos++;
                return Math.pow(base, parseUnary());
            }
            return base;
        }

        private double parsePrimary() {
            if (pos >= input.length()) throw new IllegalArgumentException();
            char c = input.charAt(pos);
            if (c == '(') {
                pos++;
                double value = parseExpression();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException();
                }
                pos++;
                return value;
            }
            int start = pos;
            boolean dotSeen = false;
            while (pos < input.length()) {
                char d = input.charAt(pos);
                if (Character.isDigit(d)) {
                    pos++;
                } else if (d == '.' && !dotSeen) {
                    dotSeen = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos > start) return Double.parseDouble(input.substring(start, pos));

            start = pos;
            while (pos < input.length() && Character.isLetter(input.charAt(pos))) {
                pos++;
            }
            String word = input.substring(start, pos);
            if (word.equals("pi")) return Math.PI;
            if (word.equals("e")) return Math.E;
            if (word.equals("sqrt") || word.equals("abs")
                    || word.equals("log") || word.equals("ln")) {
                if (pos >= input.length() || input.charAt(pos) != '(') {
                    throw new IllegalArgumentException();
                }
                pos++;
                double value = parseExpression();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException();
                }
                pos++;
                switch (word) {
                    case "sqrt": return Math.sqrt(value);
                    case "abs": return Math.abs(value);
                    case "log": return Math.log10(value);
                    case "ln": return Math.log(value);
                    default: throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }
    }

    // ------------------------------------------------------------------
    // Unit conversions
    // ------------------------------------------------------------------

    private String convertCurrency(String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("convert ")) text = text.substring(8).trim();
        Matcher matcher = CURRENCY_PATTERN.matcher(text);
        if (!matcher.matches()) return null;

        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String from = normalizeCurrency(matcher.group(2));
        String to = normalizeCurrency(matcher.group(3));
        Double rateFrom = exchangeRates.get(from);
        Double rateTo = exchangeRates.get(to);
        if (rateFrom == null || rateTo == null) return null;

        double converted = value * rateTo / rateFrom;
        return formatNumber(value) + " " + from + " = "
                + formatNumber(converted) + " " + to;
    }

    private static String normalizeCurrency(String code) {
        switch (code) {
            case "$": case "usd": return "USD";
            case "\u20ac": case "eur": return "EUR";
            case "\u00a3": case "gbp": return "GBP";
            case "\u00a5": case "jpy": return "JPY";
            default: return code.toUpperCase(Locale.ROOT);
        }
    }

    private static String convertUnits(String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("convert ")) text = text.substring(8).trim();
        Matcher matcher = CONVERSION_PATTERN.matcher(text);
        if (!matcher.matches()) return null;

        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String from = matcher.group(2);
        String to = matcher.group(3);
        double converted;

        if (TEMPERATURE_UNITS.contains(from) && TEMPERATURE_UNITS.contains(to)) {
            double celsius;
            switch (from) {
                case "f": case "fahrenheit": case "\u00b0f":
                    celsius = (value - 32) * 5.0 / 9.0; break;
                case "k": case "kelvin":
                    celsius = value - 273.15; break;
                default:
                    celsius = value; break;
            }
            switch (to) {
                case "f": case "fahrenheit": case "\u00b0f":
                    converted = celsius * 9.0 / 5.0 + 32; break;
                case "k": case "kelvin":
                    converted = celsius + 273.15; break;
                default:
                    converted = celsius; break;
            }
        } else if (LENGTH_UNITS.containsKey(from) && LENGTH_UNITS.containsKey(to)) {
            converted = value * LENGTH_UNITS.get(from) / LENGTH_UNITS.get(to);
        } else if (MASS_UNITS.containsKey(from) && MASS_UNITS.containsKey(to)) {
            converted = value * MASS_UNITS.get(from) / MASS_UNITS.get(to);
        } else if (SPEED_UNITS.containsKey(from) && SPEED_UNITS.containsKey(to)) {
            converted = value * SPEED_UNITS.get(from) / SPEED_UNITS.get(to);
        } else {
            return null;
        }
        return formatNumber(value) + " " + from + " = " + formatNumber(converted) + " " + to;
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9 && Math.abs(value) < 1e15) {
            return String.valueOf((long) Math.rint(value));
        }
        String s = String.format(Locale.US, "%.6f", value);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Background data loading
    // ------------------------------------------------------------------

    private void loadDataInBackground() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<AppInfo> apps = loadLaunchableApps();
            Map<String, String[]> words = loadDictionary();
            Map<String, Double> rates = loadExchangeRates();
            mainHandler.post(() -> {
                allApps.clear();
                allApps.addAll(apps);
                dictionary.clear();
                dictionary.putAll(words);
                exchangeRates.clear();
                exchangeRates.putAll(rates);
                if (searchOpen && searchInput != null) {
                    updateResults(searchInput.getText().toString());
                }
            });
        });
        // Live rates are fetched separately so a slow network never delays the panel.
        Executors.newSingleThreadExecutor().execute(this::refreshExchangeRates);
    }

    /** Cached live rates if present, otherwise the bundled fallback snapshot. */
    private Map<String, Double> loadExchangeRates() {
        Map<String, Double> rates = new HashMap<>();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cached = prefs.getString(KEY_RATES_JSON, null);
        if (cached != null) rates = parseRatesJson(cached);
        if (rates.isEmpty()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getAssets().open("rates.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        rates.put(parts[0], Double.parseDouble(parts[1]));
                    }
                }
            } catch (Exception ignored) { }
        }
        return rates;
    }

    private static Map<String, Double> parseRatesJson(String json) {
        Map<String, Double> rates = new HashMap<>();
        try {
            JSONObject ratesObject = new JSONObject(json).getJSONObject("rates");
            Iterator<String> keys = ratesObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                rates.put(key, ratesObject.getDouble(key));
            }
        } catch (Exception ignored) { }
        return rates;
    }

    private void refreshExchangeRates() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long last = prefs.getLong(KEY_RATES_TIME, 0);
        String cached = prefs.getString(KEY_RATES_JSON, null);
        if (cached != null && System.currentTimeMillis() - last < RATES_MAX_AGE_MS) return;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RATES_URL).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            InputStream stream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();

            String json = body.toString();
            JSONObject response = new JSONObject(json);
            if (!"success".equals(response.optString("result"))) return;
            Map<String, Double> fresh = parseRatesJson(json);
            if (fresh.isEmpty()) return;

            final Map<String, Double> updated = fresh;
            mainHandler.post(() -> {
                exchangeRates.clear();
                exchangeRates.putAll(updated);
                prefs.edit().putString(KEY_RATES_JSON, json)
                        .putLong(KEY_RATES_TIME, System.currentTimeMillis()).apply();
                if (searchOpen && searchInput != null) {
                    updateResults(searchInput.getText().toString());
                }
            });
        } catch (Exception ignored) {
            // Offline or API failure — the bundled/cached snapshot keeps working.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map<String, String[]> loadDictionary() {
        Map<String, String[]> words = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("words.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) {
                    words.put(parts[0], new String[]{parts[1], parts[2]});
                }
            }
        } catch (Exception ignored) { }
        return words;
    }

    private List<AppInfo> loadLaunchableApps() {
        List<AppInfo> apps = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN, null);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : resolved) {
                String pkg = info.activityInfo.packageName;
                if (pkg == null || !seen.add(pkg)) continue;
                ApplicationInfo appInfo = info.activityInfo.applicationInfo;
                String label = info.loadLabel(pm).toString();
                apps.add(new AppInfo(label, pkg, appInfo));
            }
            Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception ignored) { }
        return apps;
    }

    // ------------------------------------------------------------------
    // Foreground notification / helpers
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Rolodex overlay", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps the quick-launch overlay active");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Rolodex is active")
                .setContentText("Swipe an edge pill to search apps")
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void safeRemove(View view) {
        try {
            if (view.getParent() != null) wm.removeView(view);
        } catch (Exception ignored) { }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
