package de.dreiersteckdosenhausen.snap24;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

public class Snap24Application extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(updateBaseContextLocale(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Force locale at application startup as well
        forceLocale(this);
    }

    private Context updateBaseContextLocale(Context context) {
        Locale locale = getSystemLocale(context);
        Locale.setDefault(locale);
        
        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(new LocaleList(locale));
        } else {
            configuration.setLocale(locale);
        }
        
        return context.createConfigurationContext(configuration);
    }

    public static void forceLocale(Context context) {
        Locale locale = Locale.getDefault();
        Locale.setDefault(locale);
        
        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(new android.os.LocaleList(locale));
        } else {
            configuration.setLocale(locale);
        }
        
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    private Locale getSystemLocale(Context context) {
        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList localeList = configuration.getLocales();
            if (!localeList.isEmpty()) {
                return localeList.get(0);
            }
        } else {
            return configuration.locale;
        }
        
        return Locale.getDefault();
    }
}