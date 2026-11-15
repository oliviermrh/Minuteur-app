package com.minuteur.applis;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Réglages partagés entre l'écran de configuration et le service. */
final class Prefs {

    private static final String FILE = "reglages";
    private static final String KEY_DELAY = "attente_s";
    private static final String KEY_WINDOW = "acces_s";
    private static final String KEY_APPS = "applis";
    private static final String KEY_MUTED = "son_coupe";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Durée du minuteur à laisser tourner avant l'accès, en secondes (5 min par défaut). */
    static int delaySeconds(Context c) {
        return sp(c).getInt(KEY_DELAY, 300);
    }

    static void setDelaySeconds(Context c, int seconds) {
        sp(c).edit().putInt(KEY_DELAY, seconds).apply();
    }

    /** Durée d'accès libre une fois le minuteur terminé, en secondes (15 min par défaut). */
    static int windowSeconds(Context c) {
        return sp(c).getInt(KEY_WINDOW, 900);
    }

    static void setWindowSeconds(Context c, int seconds) {
        sp(c).edit().putInt(KEY_WINDOW, seconds).apply();
    }

    static Set<String> apps(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_APPS, Collections.<String>emptySet()));
    }

    static void setApps(Context c, Set<String> apps) {
        sp(c).edit().putStringSet(KEY_APPS, new HashSet<>(apps)).apply();
    }

    static boolean isGuarded(Context c, String pkg) {
        return sp(c).getStringSet(KEY_APPS, Collections.<String>emptySet()).contains(pkg);
    }

    /** Vrai tant que le service a coupé le son des médias et ne l'a pas encore remis. */
    static boolean mutedByService(Context c) {
        return sp(c).getBoolean(KEY_MUTED, false);
    }

    static void setMutedByService(Context c, boolean muted) {
        sp(c).edit().putBoolean(KEY_MUTED, muted).apply();
    }

    static String format(int seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + " min";
        }
        return (seconds / 60) + " min " + (seconds % 60);
    }
}
