package com.aktivesolutions.rolodex;

import android.content.pm.ApplicationInfo;

import java.util.Locale;

/** A launchable app shown in the overlay search results. */
final class AppInfo {
    final String label;
    final String packageName;
    final String lowerLabel;
    final String lowerPackage;
    final ApplicationInfo appInfo;

    AppInfo(String label, String packageName, ApplicationInfo appInfo) {
        this.label = label;
        this.packageName = packageName;
        this.lowerLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        this.lowerPackage = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        this.appInfo = appInfo;
    }
}
