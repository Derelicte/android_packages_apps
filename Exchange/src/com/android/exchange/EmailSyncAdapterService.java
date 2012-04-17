/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.exchange;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;

import android.accounts.Account;
import android.accounts.OperationCanceledException;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class EmailSyncAdapterService extends Service {
    private static final String TAG = "EAS EmailSyncAdapterService";
    private static SyncAdapterImpl sSyncAdapter = null;
    private static final Object sSyncAdapterLock = new Object();

    private static final String[] ID_PROJECTION = new String[] {EmailContent.RECORD_ID};
    private static final String ACCOUNT_AND_TYPE_INBOX =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_INBOX;

    public EmailSyncAdapterService() {
        super();
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        private Context mContext;

        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
            mContext = context;
        }

        @Override
        public void onPerformSync(Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            try {
                EmailSyncAdapterService.performSync(mContext, account, extras,
                        authority, provider, syncResult);
            } catch (OperationCanceledException e) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new SyncAdapterImpl(getApplicationContext());
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return sSyncAdapter.getSyncAdapterBinder();
    }

    /**
     * Partial integration with system SyncManager; we tell our EAS ExchangeService to start an
     * inbox sync when we get the signal from the system SyncManager.
     */
    private static void performSync(Context context, Account account, Bundle extras,
            String authority, ContentProviderClient provider, SyncResult syncResult)
            throws OperationCanceledException {
        ContentResolver cr = context.getContentResolver();
        Log.i(TAG, "performSync");

        // Find the (EmailProvider) account associated with this email address
        Cursor accountCursor =
            cr.query(com.android.emailcommon.provider.Account.CONTENT_URI,
                    ID_PROJECTION, AccountColumns.EMAIL_ADDRESS + "=?", new String[] {account.name},
                    null);
        try {
            if (accountCursor.moveToFirst()) {
                long accountId = accountCursor.getLong(0);
                // Now, find the inbox associated with the account
                Cursor mailboxCursor = cr.query(Mailbox.CONTENT_URI, ID_PROJECTION,
                        ACCOUNT_AND_TYPE_INBOX, new String[] {Long.toString(accountId)}, null);
                try {
                     if (mailboxCursor.moveToFirst()) {
                        Log.i(TAG, "Mail sync requested for " + account.name);
                        // Ask for a sync from our sync manager
                        ExchangeService.serviceRequest(mailboxCursor.getLong(0),
                                ExchangeService.SYNC_KICK);
                    }
                } finally {
                    mailboxCursor.close();
                }
            }
        } finally {
            accountCursor.close();
        }
    }
}