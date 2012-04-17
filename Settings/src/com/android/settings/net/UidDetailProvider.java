/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.settings.net;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.settings.R;
import com.android.settings.Utils;

public class UidDetailProvider {
    private final Context mContext;
    private final SparseArray<UidDetail> mUidDetailCache;

    public UidDetailProvider(Context context) {
        mContext = context.getApplicationContext();
        mUidDetailCache = new SparseArray<UidDetail>();
    }

    public synchronized void clearCache() {
        mUidDetailCache.clear();
    }

    /**
     * Resolve best descriptive label for the given UID.
     */
    public synchronized UidDetail getUidDetail(int uid, boolean blocking) {
        final UidDetail cached = mUidDetailCache.get(uid);
        if (cached != null) {
            return cached;
        } else if (!blocking) {
            return null;
        }

        final Resources res = mContext.getResources();
        final PackageManager pm = mContext.getPackageManager();

        final UidDetail detail = new UidDetail();
        detail.label = pm.getNameForUid(uid);
        detail.icon = pm.getDefaultActivityIcon();

        // handle special case labels
        switch (uid) {
            case android.os.Process.SYSTEM_UID:
                detail.label = res.getString(R.string.process_kernel_label);
                detail.icon = pm.getDefaultActivityIcon();
                mUidDetailCache.put(uid, detail);
                return detail;
            case TrafficStats.UID_REMOVED:
                detail.label = res.getString(R.string.data_usage_uninstalled_apps);
                detail.icon = pm.getDefaultActivityIcon();
                mUidDetailCache.put(uid, detail);
                return detail;
            case TrafficStats.UID_TETHERING:
                final ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                        Context.CONNECTIVITY_SERVICE);
                detail.label = res.getString(Utils.getTetheringLabel(cm));
                detail.icon = pm.getDefaultActivityIcon();
                mUidDetailCache.put(uid, detail);
                return detail;
        }

        // otherwise fall back to using packagemanager labels
        final String[] packageNames = pm.getPackagesForUid(uid);
        final int length = packageNames != null ? packageNames.length : 0;

        try {
            if (length == 1) {
                final ApplicationInfo info = pm.getApplicationInfo(packageNames[0], 0);
                detail.label = info.loadLabel(pm).toString();
                detail.icon = info.loadIcon(pm);
            } else if (length > 1) {
                detail.detailLabels = new CharSequence[length];
                for (int i = 0; i < length; i++) {
                    final String packageName = packageNames[i];
                    final PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
                    final ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);

                    detail.detailLabels[i] = appInfo.loadLabel(pm).toString();
                    if (packageInfo.sharedUserLabel != 0) {
                        detail.label = pm.getText(packageName, packageInfo.sharedUserLabel,
                                packageInfo.applicationInfo).toString();
                        detail.icon = appInfo.loadIcon(pm);
                    }
                }
            }
        } catch (NameNotFoundException e) {
        }

        if (TextUtils.isEmpty(detail.label)) {
            detail.label = Integer.toString(uid);
        }

        mUidDetailCache.put(uid, detail);
        return detail;
    }
}
