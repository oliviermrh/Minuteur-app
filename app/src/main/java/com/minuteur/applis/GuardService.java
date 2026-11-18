package com.minuteur.applis;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Détecte l'ouverture des applis choisies et affiche un écran de minuteur par-dessus.
 *
 * Fonctionnement, par appli :
 *  - à l'ouverture, l'écran d'attente s'affiche et le minuteur (durée réglable) démarre aussitôt ;
 *    un bouton « Retour à l'accueil » permet de renoncer ;
 *  - le minuteur ne tourne que tant qu'on reste sur cet écran :
 *    si on le quitte, il est annulé et repart de zéro à la prochaine ouverture ;
 *  - tant que l'écran d'attente est affiché, le son des médias est coupé
 *    (l'appli continue de tourner derrière) ;
 *  - une fois terminé, l'appli est accessible librement pendant la « durée d'accès » ;
 *  - ensuite, tout recommence.
 */
public class GuardService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Pour chaque appli : instant (elapsedRealtime, ms) jusqu'auquel l'accès est libre. */
    private final Map<String, Long> accessUntil = new HashMap<>();

    /** Fin du minuteur affiché (elapsedRealtime, ms), ou 0 quand aucun écran d'attente n'est affiché. */
    private long timerEnd;

    /** Délai avant de remettre le son quand on quitte l'écran, le temps que l'appli se mette en pause. */
    private static final long UNMUTE_DELAY_MS = 600;

    private WindowManager windowManager;
    private AudioManager audioManager;
    /** Vrai si c'est ce service qui a coupé le son des médias (et doit donc le remettre). */
    private boolean mutedByUs;
    private String homePackage;
    private String currentPkg;
    private String pkgAtScreenOff;
    private boolean receiverRegistered;

    private View overlay;
    private String overlayPkg;
    private int overlayGeneration;
    private TextView countdownView;
    private TextView hintView;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshOverlay();
        }
    };

    private final Runnable unmute = new Runnable() {
        @Override
        public void run() {
            unmuteNow();
        }
    };

    /** Fin de la durée d'accès : si l'appli est toujours à l'écran, on la reverrouille. */
    private final Runnable accessEnd = new Runnable() {
        @Override
        public void run() {
            if (currentPkg != null && Prefs.isGuarded(GuardService.this, currentPkg)) {
                evaluate(currentPkg);
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Ne jamais laisser l'écran d'attente par-dessus l'écran de verrouillage.
                if (currentPkg != null) {
                    pkgAtScreenOff = currentPkg;
                }
                currentPkg = null;
                hideOverlay();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)
                    || (Intent.ACTION_SCREEN_ON.equals(action) && !isKeyguardLocked())) {
                restoreAfterUnlock();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        homePackage = resolveHomePackage();
        if (Prefs.mutedByService(this)) {
            // Le service a été arrêté alors qu'il avait coupé le son : on le remet.
            Prefs.setMutedByService(this, false);
            setMediaMuted(false);
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null) {
            return;
        }
        String pkg = p.toString();

        if (Prefs.isGuarded(this, pkg)) {
            currentPkg = pkg;
            evaluate(pkg);
            return;
        }

        // Autre appli : on ne tient compte que des vrais écrans (pas du clavier,
        // du volet de notifications, ni de notre propre écran d'attente).
        CharSequence cls = event.getClassName();
        boolean realScreen = pkg.equals(homePackage)
                || (cls != null && isActivity(pkg, cls.toString()));
        if (!realScreen) {
            return;
        }
        currentPkg = pkg;
        hideOverlay();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
        unmuteNow();
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (RuntimeException ignored) {
            }
            receiverRegistered = false;
        }
    }

    // ---------------------------------------------------------------- logique

    private void evaluate(String pkg) {
        long now = SystemClock.elapsedRealtime();
        Long until = accessUntil.get(pkg);
        if (until != null && now < until) {
            // Minuteur déjà terminé : accès libre jusqu'à la fin de la durée d'accès.
            hideOverlay();
            scheduleAccessEnd(until - now);
        } else {
            accessUntil.remove(pkg);
            showOverlay(pkg);
        }
    }

    /** Le minuteur est allé au bout sans qu'on quitte l'écran : on ouvre l'accès. */
    private void grantAccess(String pkg) {
        long window = Prefs.windowSeconds(this) * 1000L;
        accessUntil.put(pkg, SystemClock.elapsedRealtime() + window);
        hideOverlay();
        unmuteNow();
        scheduleAccessEnd(window);
    }

    private void scheduleAccessEnd(long delayMs) {
        handler.removeCallbacks(accessEnd);
        handler.postDelayed(accessEnd, Math.max(0, delayMs) + 100);
    }

    private void restoreAfterUnlock() {
        final String pkg = pkgAtScreenOff;
        pkgAtScreenOff = null;
        if (pkg == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Si aucune autre appli n'est passée au premier plan entre-temps,
                // on est revenu sur l'appli qui était ouverte avant la mise en veille.
                if (currentPkg == null && Prefs.isGuarded(GuardService.this, pkg)) {
                    currentPkg = pkg;
                    evaluate(pkg);
                }
            }
        }, 700);
    }

    private boolean isKeyguardLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private boolean isActivity(String pkg, String cls) {
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String resolveHomePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null ? info.activityInfo.packageName : null;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------- écran d'attente

    private void showOverlay(String pkg) {
        if (windowManager == null || isKeyguardLocked()) {
            return;
        }
        overlayGeneration++;
        if (overlay != null && pkg.equals(overlayPkg)) {
            // Déjà affiché pour cette appli : on ne touche pas au minuteur en cours.
            return;
        }
        hideOverlay();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.CENTER;

        View view = buildOverlay(appLabel(pkg));
        try {
            windowManager.addView(view, lp);
        } catch (RuntimeException e) {
            return;
        }
        overlay = view;
        overlayPkg = pkg;
        // Le minuteur démarre dès l'ouverture de l'appli.
        timerEnd = SystemClock.elapsedRealtime() + Prefs.delaySeconds(this) * 1000L;
        hintView.setText("Reste sur cet écran : si tu le quittes, le minuteur repart de zéro.\n"
                + "Ensuite : accès libre pendant " + Prefs.format(Prefs.windowSeconds(this)) + ".");
        // L'écran reste allumé pendant le décompte : une mise en veille l'annulerait.
        view.setKeepScreenOn(true);
        muteMedia();
        refreshOverlay();
    }

    /** Retire l'écran d'attente. Un minuteur en cours est annulé : il repartira de zéro. */
    private void hideOverlay() {
        handler.removeCallbacks(tick);
        timerEnd = 0;
        if (overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (RuntimeException ignored) {
            }
            overlay = null;
        }
        overlayPkg = null;
        if (mutedByUs) {
            handler.removeCallbacks(unmute);
            handler.postDelayed(unmute, UNMUTE_DELAY_MS);
        }
    }

    // ------------------------------------------------------------------- son

    /** Coupe le son des médias, sauf s'il l'est déjà (on ne le remettrait pas à la place de l'utilisateur). */
    private void muteMedia() {
        handler.removeCallbacks(unmute);
        if (mutedByUs || audioManager == null) {
            return;
        }
        try {
            if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                return;
            }
        } catch (RuntimeException ignored) {
        }
        if (setMediaMuted(true)) {
            mutedByUs = true;
            Prefs.setMutedByService(this, true);
        }
    }

    private void unmuteNow() {
        handler.removeCallbacks(unmute);
        if (!mutedByUs) {
            return;
        }
        mutedByUs = false;
        Prefs.setMutedByService(this, false);
        setMediaMuted(false);
    }

    private boolean setMediaMuted(boolean muted) {
        if (audioManager == null) {
            return false;
        }
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void refreshOverlay() {
        handler.removeCallbacks(tick);
        if (overlay == null || overlayPkg == null) {
            return;
        }
        long remaining = timerEnd - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            grantAccess(overlayPkg);
            return;
        }
        countdownView.setText(clock((remaining + 999) / 1000));
        handler.postDelayed(tick, Math.min(remaining, 250));
    }

    private static String clock(long seconds) {
        return String.format(Locale.FRANCE, "%d:%02d", seconds / 60, seconds % 60);
    }

    private View buildOverlay(String label) {
        LinearLayout root = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        goHome();
                    }
                    return true;
                }
                if (mutedByUs && (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                        || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                        || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_MUTE)) {
                    // Les touches de volume remettraient le son pendant l'attente.
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF10151C);
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text(label, 26, 0xFFFFFFFF, true), params(false, 0));

        countdownView = text("", 72, 0xFFFFFFFF, true);
        root.addView(countdownView, params(false, 12));

        hintView = text("", 16, 0xFFB6C2CF, false);
        root.addView(hintView, params(false, 12));

        Button home = button("Retour à l'accueil", 0xFF2A323D);
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHome();
            }
        });
        root.addView(home, params(true, 32));

        return root;
    }

    private void goHome() {
        final int generation = overlayGeneration;
        boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
        // Sécurité : quoi qu'il arrive, l'écran d'attente ne doit jamais rester coincé.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation == overlayGeneration) {
                    hideOverlay();
                }
            }
        }, ok ? 2000 : 0);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private Button button(String s, int background) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(background);
        int pad = dp(14);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    private LinearLayout.LayoutParams params(boolean fullWidth, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                fullWidth ? LinearLayout.LayoutParams.MATCH_PARENT : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
