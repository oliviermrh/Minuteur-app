package com.minuteur.applis;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Écran de configuration : état du service, durées, et choix des applis. */
public class MainActivity extends Activity {

    /** Valeurs proposées, en secondes. */
    private static final int[] DELAYS = {10, 30, 60, 90, 120, 180, 240, 300};
    private static final int[] WINDOWS = {60, 120, 300, 600, 900, 1200, 1800, 2700, 3600};

    private final List<AppItem> items = new ArrayList<>();
    private final AppAdapter adapter = new AppAdapter();
    private Set<String> selected = new HashSet<>();

    private TextView statusView;
    private View helpBlock;
    private TextView delayValue;
    private TextView windowValue;

    private static final class AppItem {
        String pkg;
        String label;
        Drawable icon;
    }

    private static final class Holder {
        ImageView icon;
        TextView label;
        CheckBox box;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selected = Prefs.apps(this);
        // Une durée enregistrée par une ancienne version peut ne plus être proposée.
        Prefs.setDelaySeconds(this, DELAYS[nearest(DELAYS, Prefs.delaySeconds(this))]);

        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.addHeaderView(buildHeader(), null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = parent.getItemAtPosition(position);
                if (o instanceof AppItem) {
                    toggle((AppItem) o);
                }
            }
        });
        setContentView(list);

        refreshValues();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ------------------------------------------------------------------ en-tête

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        header.setPadding(pad, pad, pad, dp(8));

        header.addView(text("Minuteur d'applis", 26, true), block(0));
        header.addView(text("Un minuteur à lancer avant d'accéder aux applis que tu choisis.", 15, false), block(4));

        statusView = text("", 16, true);
        header.addView(statusView, block(20));

        LinearLayout help = new LinearLayout(this);
        help.setOrientation(LinearLayout.VERTICAL);
        help.addView(text(
                "L'appli a besoin du service d'accessibilité : il sert uniquement à détecter "
                        + "quelle appli s'ouvre. Rien n'est lu ni envoyé (l'appli n'a pas accès à Internet).\n\n"
                        + "1. Ouvre « Accessibilité », puis « Minuteur d'applis » dans les applis installées, "
                        + "et active le service.\n"
                        + "2. Si Android affiche « Paramètre restreint » : ouvre « Infos de l'appli », "
                        + "menu ⋮ en haut à droite, « Autoriser les paramètres restreints », "
                        + "puis recommence l'étape 1.",
                14, false), block(8));

        Button accessibility = new Button(this);
        accessibility.setAllCaps(false);
        accessibility.setText("Ouvrir « Accessibilité »");
        accessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        help.addView(accessibility, block(8));

        Button appInfo = new Button(this);
        appInfo.setAllCaps(false);
        appInfo.setText("Ouvrir « Infos de l'appli »");
        appInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        help.addView(appInfo, block(4));

        helpBlock = help;
        header.addView(help, block(0));

        delayValue = addStepper(header, "Durée du minuteur", true);
        windowValue = addStepper(header, "Durée d'accès ensuite", false);

        header.addView(text("Applications concernées", 18, true), block(28));
        return header;
    }

    private TextView addStepper(LinearLayout parent, String label, final boolean isDelay) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(text(label, 16, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button minus = new Button(this);
        minus.setText("−");
        minus.setTextSize(20);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(isDelay, -1);
            }
        });
        row.addView(minus, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView value = text("", 16, true);
        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button plus = new Button(this);
        plus.setText("+");
        plus.setTextSize(20);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(isDelay, 1);
            }
        });
        row.addView(plus, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row, block(16));
        return value;
    }

    private void step(boolean isDelay, int direction) {
        int[] values = isDelay ? DELAYS : WINDOWS;
        int current = isDelay ? Prefs.delaySeconds(this) : Prefs.windowSeconds(this);
        int index = nearest(values, current);
        index = Math.max(0, Math.min(values.length - 1, index + direction));
        if (isDelay) {
            Prefs.setDelaySeconds(this, values[index]);
        } else {
            Prefs.setWindowSeconds(this, values[index]);
        }
        refreshValues();
    }

    /** Indice de la valeur proposée la plus proche de {@code current}. */
    private static int nearest(int[] values, int current) {
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - current) < Math.abs(values[index] - current)) {
                index = i;
            }
        }
        return index;
    }

    private void refreshValues() {
        delayValue.setText(Prefs.format(Prefs.delaySeconds(this)));
        windowValue.setText(Prefs.format(Prefs.windowSeconds(this)));
    }

    private void refreshStatus() {
        boolean on = isServiceEnabled();
        statusView.setText(on ? "● Service actif" : "● Service inactif — à activer ci-dessous");
        statusView.setTextColor(on ? 0xFF1B7F3B : 0xFFC0392B);
        helpBlock.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName me = new ComponentName(this, GuardService.class);
        return enabled.contains(me.flattenToString()) || enabled.contains(me.flattenToShortString());
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ------------------------------------------------------------ liste d'applis

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppItem> loaded = queryApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        items.clear();
                        items.addAll(loaded);
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private List<AppItem> queryApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null || !seen.add(info.activityInfo.packageName)) {
                continue;
            }
            AppItem item = new AppItem();
            item.pkg = info.activityInfo.packageName;
            CharSequence label = info.loadLabel(pm);
            item.label = label != null ? label.toString() : item.pkg;
            item.icon = info.loadIcon(pm);
            result.add(item);
        }
        final Set<String> chosen = new HashSet<>(selected);
        final Collator collator = Collator.getInstance(Locale.FRANCE);
        Collections.sort(result, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                boolean ca = chosen.contains(a.pkg);
                boolean cb = chosen.contains(b.pkg);
                if (ca != cb) {
                    return ca ? -1 : 1;
                }
                return collator.compare(a.label, b.label);
            }
        });
        return result;
    }

    private void toggle(AppItem item) {
        if (!selected.remove(item.pkg)) {
            selected.add(item.pkg);
        }
        Prefs.setApps(this, selected);
        adapter.notifyDataSetChanged();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                row.setMinimumHeight(dp(60));

                holder = new Holder();
                holder.icon = new ImageView(MainActivity.this);
                row.addView(holder.icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

                holder.label = text("", 16, false);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(16);
                row.addView(holder.label, lp);

                holder.box = new CheckBox(MainActivity.this);
                holder.box.setClickable(false);
                holder.box.setFocusable(false);
                row.addView(holder.box, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }
            AppItem item = items.get(position);
            holder.icon.setImageDrawable(item.icon);
            holder.label.setText(item.label);
            holder.box.setChecked(selected.contains(item.pkg));
            return convertView;
        }
    }

    // ------------------------------------------------------------------- outils

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF1C2430);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private LinearLayout.LayoutParams block(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
