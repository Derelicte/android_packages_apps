/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.ContactsContract;
import android.util.Log;

import com.android.emailcommon.Api;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.HostAuthColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.emailcommon.service.AccountServiceProxy;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.PolicyServiceProxy;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.adapter.CalendarSyncAdapter;
import com.android.exchange.adapter.ContactsSyncAdapter;
import com.android.exchange.adapter.Search;
import com.android.exchange.provider.MailboxUtilities;
import com.android.exchange.utility.FileLogger;

import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ExchangeService handles all aspects of starting, maintaining, and stopping the various sync
 * adapters used by Exchange.  However, it is capable of handing any kind of email sync, and it
 * would be appropriate to use for IMAP push, when that functionality is added to the Email
 * application.
 *
 * The Email application communicates with EAS sync adapters via ExchangeService's binder interface,
 * which exposes UI-related functionality to the application (see the definitions below)
 *
 * ExchangeService uses ContentObservers to detect changes to accounts, mailboxes, and messages in
 * order to maintain proper 2-way syncing of data.  (More documentation to follow)
 *
 */
public class ExchangeService extends Service implements Runnable {

    private static final String TAG = "ExchangeService";

    // The ExchangeService's mailbox "id"
    public static final int EXTRA_MAILBOX_ID = -1;
    public static final int EXCHANGE_SERVICE_MAILBOX_ID = 0;

    private static final int SECONDS = 1000;
    private static final int MINUTES = 60*SECONDS;
    private static final int ONE_DAY_MINUTES = 1440;

    private static final int EXCHANGE_SERVICE_HEARTBEAT_TIME = 15*MINUTES;
    private static final int CONNECTIVITY_WAIT_TIME = 10*MINUTES;

    // Sync hold constants for services with transient errors
    private static final int HOLD_DELAY_MAXIMUM = 4*MINUTES;

    // Reason codes when ExchangeService.kick is called (mainly for debugging)
    // UI has changed data, requiring an upsync of changes
    public static final int SYNC_UPSYNC = 0;
    // A scheduled sync (when not using push)
    public static final int SYNC_SCHEDULED = 1;
    // Mailbox was marked push
    public static final int SYNC_PUSH = 2;
    // A ping (EAS push signal) was received
    public static final int SYNC_PING = 3;
    // Misc.
    public static final int SYNC_KICK = 4;
    // A part request (attachment load, for now) was sent to ExchangeService
    public static final int SYNC_SERVICE_PART_REQUEST = 5;

    // Requests >= SYNC_CALLBACK_START generate callbacks to the UI
    public static final int SYNC_CALLBACK_START = 6;
    // startSync was requested of ExchangeService (other than due to user request)
    public static final int SYNC_SERVICE_START_SYNC = SYNC_CALLBACK_START + 0;
    // startSync was requested of ExchangeService (due to user request)
    public static final int SYNC_UI_REQUEST = SYNC_CALLBACK_START + 1;

    private static final String WHERE_PUSH_OR_PING_NOT_ACCOUNT_MAILBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.TYPE + "!=" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + " and " + MailboxColumns.SYNC_INTERVAL +
        " IN (" + Mailbox.CHECK_INTERVAL_PING + ',' + Mailbox.CHECK_INTERVAL_PUSH + ')';
    protected static final String WHERE_IN_ACCOUNT_AND_PUSHABLE =
        MailboxColumns.ACCOUNT_KEY + "=? and type in (" + Mailbox.TYPE_INBOX + ','
        + Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + ',' + Mailbox.TYPE_CONTACTS + ','
        + Mailbox.TYPE_CALENDAR + ')';
    protected static final String WHERE_IN_ACCOUNT_AND_TYPE_INBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and type = " + Mailbox.TYPE_INBOX ;
    private static final String WHERE_MAILBOX_KEY = Message.MAILBOX_KEY + "=?";
    private static final String WHERE_PROTOCOL_EAS = HostAuthColumns.PROTOCOL + "=\"" +
        AbstractSyncService.EAS_PROTOCOL + "\"";
    private static final String WHERE_NOT_INTERVAL_NEVER_AND_ACCOUNT_KEY_IN =
        "(" + MailboxColumns.TYPE + '=' + Mailbox.TYPE_OUTBOX
        + " or " + MailboxColumns.SYNC_INTERVAL + "!=" + Mailbox.CHECK_INTERVAL_NEVER + ')'
        + " and " + MailboxColumns.ACCOUNT_KEY + " in (";
    private static final String ACCOUNT_KEY_IN = MailboxColumns.ACCOUNT_KEY + " in (";
    private static final String WHERE_CALENDAR_ID = Events.CALENDAR_ID + "=?";

    // Offsets into the syncStatus data for EAS that indicate type, exit status, and change count
    // The format is S<type_char>:<exit_char>:<change_count>
    public static final int STATUS_TYPE_CHAR = 1;
    public static final int STATUS_EXIT_CHAR = 3;
    public static final int STATUS_CHANGE_COUNT_OFFSET = 5;

    // Ready for ping
    public static final int PING_STATUS_OK = 0;
    // Service already running (can't ping)
    public static final int PING_STATUS_RUNNING = 1;
    // Service waiting after I/O error (can't ping)
    public static final int PING_STATUS_WAITING = 2;
    // Service had a fatal error; can't run
    public static final int PING_STATUS_UNABLE = 3;

    private static final int MAX_CLIENT_CONNECTION_MANAGER_SHUTDOWNS = 1;

    // We synchronize on this for all actions affecting the service and error maps
    private static final Object sSyncLock = new Object();
    // All threads can use this lock to wait for connectivity
    public static final Object sConnectivityLock = new Object();
    public static boolean sConnectivityHold = false;

    // Keeps track of running services (by mailbox id)
    private final HashMap<Long, AbstractSyncService> mServiceMap =
        new HashMap<Long, AbstractSyncService>();
    // Keeps track of services whose last sync ended with an error (by mailbox id)
    /*package*/ ConcurrentHashMap<Long, SyncError> mSyncErrorMap =
        new ConcurrentHashMap<Long, SyncError>();
    // Keeps track of which services require a wake lock (by mailbox id)
    private final HashMap<Long, Boolean> mWakeLocks = new HashMap<Long, Boolean>();
    // Keeps track of PendingIntents for mailbox alarms (by mailbox id)
    private final HashMap<Long, PendingIntent> mPendingIntents = new HashMap<Long, PendingIntent>();
    // The actual WakeLock obtained by ExchangeService
    private WakeLock mWakeLock = null;
    // Keep our cached list of active Accounts here
    public final AccountList mAccountList = new AccountList();

    // Observers that we use to look for changed mail-related data
    private final Handler mHandler = new Handler();
    private AccountObserver mAccountObserver;
    private MailboxObserver mMailboxObserver;
    private SyncedMessageObserver mSyncedMessageObserver;

    // Concurrent because CalendarSyncAdapter can modify the map during a wipe
    private final ConcurrentHashMap<Long, CalendarObserver> mCalendarObservers =
        new ConcurrentHashMap<Long, CalendarObserver>();

    private ContentResolver mResolver;

    // The singleton ExchangeService object, with its thread and stop flag
    protected static ExchangeService INSTANCE;
    private static Thread sServiceThread = null;
    // Cached unique device id
    private static String sDeviceId = null;
    // ConnectionManager that all EAS threads can use
    private static EmailClientConnectionManager sClientConnectionManager = null;
    // Count of ClientConnectionManager shutdowns
    private static volatile int sClientConnectionManagerShutdownCount = 0;

    private static volatile boolean sStartingUp = false;
    private static volatile boolean sStop = false;

    // The reason for ExchangeService's next wakeup call
    private String mNextWaitReason;
    // Whether we have an unsatisfied "kick" pending
    private boolean mKicked = false;

    // Receiver of connectivity broadcasts
    private ConnectivityReceiver mConnectivityReceiver = null;
    private ConnectivityReceiver mBackgroundDataSettingReceiver = null;
    private volatile boolean mBackgroundData = true;
    // The most current NetworkInfo (from ConnectivityManager)
    private NetworkInfo mNetworkInfo;

    // Callbacks as set up via setCallback
    private final RemoteCallbackList<IEmailServiceCallback> mCallbackList =
        new RemoteCallbackList<IEmailServiceCallback>();

    private interface ServiceCallbackWrapper {
        public void call(IEmailServiceCallback cb) throws RemoteException;
    }

    /**
     * Proxy that can be used by various sync adapters to tie into ExchangeService's callback system
     * Used this way:  ExchangeService.callback().callbackMethod(args...);
     * The proxy wraps checking for existence of a ExchangeService instance
     * Failures of these callbacks can be safely ignored.
     */
    static private final IEmailServiceCallback.Stub sCallbackProxy =
        new IEmailServiceCallback.Stub() {

        /**
         * Broadcast a callback to the everyone that's registered
         *
         * @param wrapper the ServiceCallbackWrapper used in the broadcast
         */
        private synchronized void broadcastCallback(ServiceCallbackWrapper wrapper) {
            RemoteCallbackList<IEmailServiceCallback> callbackList =
                (INSTANCE == null) ? null: INSTANCE.mCallbackList;
            if (callbackList != null) {
                // Call everyone on our callback list
                int count = callbackList.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) {
                        try {
                            wrapper.call(callbackList.getBroadcastItem(i));
                        } catch (RemoteException e) {
                            // Safe to ignore
                        } catch (RuntimeException e) {
                            // We don't want an exception in one call to prevent other calls, so
                            // we'll just log this and continue
                            Log.e(TAG, "Caught RuntimeException in broadcast", e);
                        }
                    }
                } finally {
                    // No matter what, we need to finish the broadcast
                    callbackList.finishBroadcast();
                }
            }
        }

        public void loadAttachmentStatus(final long messageId, final long attachmentId,
                final int status, final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.loadAttachmentStatus(messageId, attachmentId, status, progress);
                }
            });
        }

        public void sendMessageStatus(final long messageId, final String subject, final int status,
                final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.sendMessageStatus(messageId, subject, status, progress);
                }
            });
        }

        public void syncMailboxListStatus(final long accountId, final int status,
                final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.syncMailboxListStatus(accountId, status, progress);
                }
            });
        }

        public void syncMailboxStatus(final long mailboxId, final int status,
                final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.syncMailboxStatus(mailboxId, status, progress);
                }
            });
        }
    };

    /**
     * Create our EmailService implementation here.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {

        public int getApiLevel() {
            return Api.LEVEL;
        }

        public Bundle validate(HostAuth hostAuth) throws RemoteException {
            return AbstractSyncService.validate(EasSyncService.class,
                    hostAuth, ExchangeService.this);
        }

        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return new EasSyncService().tryAutodiscover(userName, password);
        }

        public void startSync(long mailboxId, boolean userRequest) throws RemoteException {
            ExchangeService exchangeService = INSTANCE;
            if (exchangeService == null) return;
            checkExchangeServiceServiceRunning();
            Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
            if (m == null) return;
            Account acct = Account.restoreAccountWithId(exchangeService, m.mAccountKey);
            if (acct == null) return;
            // If this is a user request and we're being held, release the hold; this allows us to
            // try again (the hold might have been specific to this account and released already)
            if (userRequest) {
                if (onSyncDisabledHold(acct)) {
                    releaseSyncHolds(exchangeService, AbstractSyncService.EXIT_ACCESS_DENIED, acct);
                    log("User requested sync of account in sync disabled hold; releasing");
                } else if (onSecurityHold(acct)) {
                    releaseSyncHolds(exchangeService, AbstractSyncService.EXIT_SECURITY_FAILURE,
                            acct);
                    log("User requested sync of account in security hold; releasing");
                }
                if (sConnectivityHold) {
                    try {
                        // UI is expecting the callbacks....
                        sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.IN_PROGRESS,
                                0);
                        sCallbackProxy.syncMailboxStatus(mailboxId,
                                EmailServiceStatus.CONNECTION_ERROR, 0);
                    } catch (RemoteException ignore) {
                    }
                    return;
                }
            }
            if (m.mType == Mailbox.TYPE_OUTBOX) {
                // We're using SERVER_ID to indicate an error condition (it has no other use for
                // sent mail)  Upon request to sync the Outbox, we clear this so that all messages
                // are candidates for sending.
                ContentValues cv = new ContentValues();
                cv.put(SyncColumns.SERVER_ID, 0);
                exchangeService.getContentResolver().update(Message.CONTENT_URI,
                    cv, WHERE_MAILBOX_KEY, new String[] {Long.toString(mailboxId)});
                // Clear the error state; the Outbox sync will be started from checkMailboxes
                exchangeService.mSyncErrorMap.remove(mailboxId);
                kick("start outbox");
                // Outbox can't be synced in EAS
                return;
            } else if (!isSyncable(m)) {
                try {
                    // UI may be expecting the callbacks, so send them
                    sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.IN_PROGRESS, 0);
                    sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.SUCCESS, 0);
                } catch (RemoteException ignore) {
                    // We tried
                }
                return;
            }
            startManualSync(mailboxId, userRequest ? ExchangeService.SYNC_UI_REQUEST :
                ExchangeService.SYNC_SERVICE_START_SYNC, null);
        }

        public void stopSync(long mailboxId) throws RemoteException {
            stopManualSync(mailboxId);
        }

        public void loadAttachment(long attachmentId, boolean background) throws RemoteException {
            Attachment att = Attachment.restoreAttachmentWithId(ExchangeService.this, attachmentId);
            log("loadAttachment " + attachmentId + ": " + att.mFileName);
            sendMessageRequest(new PartRequest(att, null, null));
        }

        public void updateFolderList(long accountId) throws RemoteException {
            reloadFolderList(ExchangeService.this, accountId, false);
        }

        public void hostChanged(long accountId) throws RemoteException {
            ExchangeService exchangeService = INSTANCE;
            if (exchangeService == null) return;
            ConcurrentHashMap<Long, SyncError> syncErrorMap = exchangeService.mSyncErrorMap;
            // Go through the various error mailboxes
            for (long mailboxId: syncErrorMap.keySet()) {
                SyncError error = syncErrorMap.get(mailboxId);
                // If it's a login failure, look a little harder
                Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
                // If it's for the account whose host has changed, clear the error
                // If the mailbox is no longer around, remove the entry in the map
                if (m == null) {
                    syncErrorMap.remove(mailboxId);
                } else if (error != null && m.mAccountKey == accountId) {
                    error.fatal = false;
                    error.holdEndTime = 0;
                }
            }
            // Stop any running syncs
            exchangeService.stopAccountSyncs(accountId, true);
            // Kick ExchangeService
            kick("host changed");
        }

        public void setLogging(int flags) throws RemoteException {
            Eas.setUserDebug(flags);
        }

        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
            sendMessageRequest(new MeetingResponseRequest(messageId, response));
        }

        public void loadMore(long messageId) throws RemoteException {
        }

        // The following three methods are not implemented in this version
        public boolean createFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        public boolean renameFolder(long accountId, String oldName, String newName)
                throws RemoteException {
            return false;
        }

        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
            mCallbackList.register(cb);
        }

        /**
         * Delete PIM (calendar, contacts) data for the specified account
         *
         * @param accountId the account whose data should be deleted
         * @throws RemoteException
         */
        public void deleteAccountPIMData(long accountId) throws RemoteException {
            // Stop any running syncs
            ExchangeService.stopAccountSyncs(accountId);
            // Delete the data
            ExchangeService.deleteAccountPIMData(accountId);
        }

        public int searchMessages(long accountId, SearchParams searchParams, long destMailboxId) {
            ExchangeService exchangeService = INSTANCE;
            if (exchangeService == null) return 0;
            return Search.searchMessages(exchangeService, accountId, searchParams,
                    destMailboxId);
        }
    };

    /**
     * Return a list of all Accounts in EmailProvider.  Because the result of this call may be used
     * in account reconciliation, an exception is thrown if the result cannot be guaranteed accurate
     * @param context the caller's context
     * @param accounts a list that Accounts will be added into
     * @return the list of Accounts
     * @throws ProviderUnavailableException if the list of Accounts cannot be guaranteed valid
     */
    private static AccountList collectEasAccounts(Context context, AccountList accounts) {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null,
                null);
        // We must throw here; callers might use the information we provide for reconciliation, etc.
        if (c == null) throw new ProviderUnavailableException();
        try {
            ContentValues cv = new ContentValues();
            while (c.moveToNext()) {
                long hostAuthId = c.getLong(Account.CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
                if (hostAuthId > 0) {
                    HostAuth ha = HostAuth.restoreHostAuthWithId(context, hostAuthId);
                    if (ha != null && ha.mProtocol.equals("eas")) {
                        Account account = new Account();
                        account.restore(c);
                        // Cache the HostAuth
                        account.mHostAuthRecv = ha;
                        accounts.add(account);
                        // Fixup flags for inbox (should accept moved mail)
                        Mailbox inbox = Mailbox.restoreMailboxOfType(context, account.mId,
                                Mailbox.TYPE_INBOX);
                        if (inbox != null &&
                                ((inbox.mFlags & Mailbox.FLAG_ACCEPTS_MOVED_MAIL) == 0)) {
                            cv.put(MailboxColumns.FLAGS,
                                    inbox.mFlags | Mailbox.FLAG_ACCEPTS_MOVED_MAIL);
                            resolver.update(
                                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, inbox.mId), cv,
                                    null, null);
                        }
                    }
                }
            }
        } finally {
            c.close();
        }
        return accounts;
    }

    static class AccountList extends ArrayList<Account> {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean add(Account account) {
            // Cache the account manager account
            account.mAmAccount = new android.accounts.Account(account.mEmailAddress,
                    Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            super.add(account);
            return true;
        }

        public boolean contains(long id) {
            for (Account account : this) {
                if (account.mId == id) {
                    return true;
                }
            }
            return false;
        }

        public Account getById(long id) {
            for (Account account : this) {
                if (account.mId == id) {
                    return account;
                }
            }
            return null;
        }

        public Account getByName(String accountName) {
            for (Account account : this) {
                if (account.mEmailAddress.equalsIgnoreCase(accountName)) {
                    return account;
                }
            }
            return null;
        }
    }

    public static void deleteAccountPIMData(long accountId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        Mailbox mailbox =
            Mailbox.restoreMailboxOfType(exchangeService, accountId, Mailbox.TYPE_CONTACTS);
        if (mailbox != null) {
            EasSyncService service = new EasSyncService(exchangeService, mailbox);
            ContactsSyncAdapter adapter = new ContactsSyncAdapter(service);
            adapter.wipe();
        }
        mailbox =
            Mailbox.restoreMailboxOfType(exchangeService, accountId, Mailbox.TYPE_CALENDAR);
        if (mailbox != null) {
            EasSyncService service = new EasSyncService(exchangeService, mailbox);
            CalendarSyncAdapter adapter = new CalendarSyncAdapter(service);
            adapter.wipe();
        }
    }

    private boolean onSecurityHold(Account account) {
        return (account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0;
    }

    private boolean onSyncDisabledHold(Account account) {
        return (account.mFlags & Account.FLAGS_SYNC_DISABLED) != 0;
    }

    class AccountObserver extends ContentObserver {
        String mSyncableEasMailboxSelector = null;
        String mEasAccountSelector = null;

        // Runs when ExchangeService first starts
        public AccountObserver(Handler handler) {
            super(handler);
            // At startup, we want to see what EAS accounts exist and cache them
            // TODO: Move database work out of UI thread
            Context context = getContext();
            synchronized (mAccountList) {
                try {
                    collectEasAccounts(context, mAccountList);
                } catch (ProviderUnavailableException e) {
                    // Just leave if EmailProvider is unavailable
                    return;
                }
                // Create an account mailbox for any account without one
                for (Account account : mAccountList) {
                    int cnt = Mailbox.count(context, Mailbox.CONTENT_URI, "accountKey="
                            + account.mId, null);
                    if (cnt == 0) {
                        // This case handles a newly created account
                        addAccountMailbox(account.mId);
                    }
                }
            }
            // Run through accounts and update account hold information
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    synchronized (mAccountList) {
                        for (Account account : mAccountList) {
                            if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                                // If we're in a security hold, and our policies are active, release
                                // the hold; otherwise, ping PolicyService that this account's
                                // policies are required
                                if (PolicyServiceProxy.isActive(ExchangeService.this, null)) {
                                    PolicyServiceProxy.setAccountHoldFlag(ExchangeService.this,
                                            account, false);
                                    log("isActive true; release hold for " + account.mDisplayName);
                                } else {
                                    PolicyServiceProxy.policiesRequired(ExchangeService.this,
                                            account.mId);
                                }
                            }
                        }
                    }
                }});
        }

        /**
         * Returns a String suitable for appending to a where clause that selects for all syncable
         * mailboxes in all eas accounts
         * @return a complex selection string that is not to be cached
         */
        public String getSyncableEasMailboxWhere() {
            if (mSyncableEasMailboxSelector == null) {
                StringBuilder sb = new StringBuilder(WHERE_NOT_INTERVAL_NEVER_AND_ACCOUNT_KEY_IN);
                boolean first = true;
                synchronized (mAccountList) {
                    for (Account account : mAccountList) {
                        if (!first) {
                            sb.append(',');
                        } else {
                            first = false;
                        }
                        sb.append(account.mId);
                    }
                }
                sb.append(')');
                mSyncableEasMailboxSelector = sb.toString();
            }
            return mSyncableEasMailboxSelector;
        }

        /**
         * Returns a String suitable for appending to a where clause that selects for all eas
         * accounts.
         * @return a String in the form "accountKey in (a, b, c...)" that is not to be cached
         */
        public String getAccountKeyWhere() {
            if (mEasAccountSelector == null) {
                StringBuilder sb = new StringBuilder(ACCOUNT_KEY_IN);
                boolean first = true;
                synchronized (mAccountList) {
                    for (Account account : mAccountList) {
                        if (!first) {
                            sb.append(',');
                        } else {
                            first = false;
                        }
                        sb.append(account.mId);
                    }
                }
                sb.append(')');
                mEasAccountSelector = sb.toString();
            }
            return mEasAccountSelector;
        }

        private void onAccountChanged() {
            try {
                maybeStartExchangeServiceThread();
                Context context = getContext();

                // A change to the list requires us to scan for deletions (stop running syncs)
                // At startup, we want to see what accounts exist and cache them
                AccountList currentAccounts = new AccountList();
                try {
                    collectEasAccounts(context, currentAccounts);
                } catch (ProviderUnavailableException e) {
                    // Just leave if EmailProvider is unavailable
                    return;
                }
                synchronized (mAccountList) {
                    for (Account account : mAccountList) {
                        boolean accountIncomplete =
                            (account.mFlags & Account.FLAGS_INCOMPLETE) != 0;
                        // If the current list doesn't include this account and the account wasn't
                        // incomplete, then this is a deletion
                        if (!currentAccounts.contains(account.mId) && !accountIncomplete) {
                            // The implication is that the account has been deleted; let's find out
                            alwaysLog("Observer found deleted account: " + account.mDisplayName);
                            // Run the reconciler (the reconciliation itself runs in the Email app)
                            runAccountReconcilerSync(ExchangeService.this);
                            // See if the account is still around
                            Account deletedAccount =
                                Account.restoreAccountWithId(context, account.mId);
                            if (deletedAccount != null) {
                                // It is; add it to our account list
                                alwaysLog("Account still in provider: " + account.mDisplayName);
                                currentAccounts.add(account);
                            } else {
                                // It isn't; stop syncs and clear our selectors
                                alwaysLog("Account deletion confirmed: " + account.mDisplayName);
                                stopAccountSyncs(account.mId, true);
                                mSyncableEasMailboxSelector = null;
                                mEasAccountSelector = null;
                            }
                        } else {
                            // Get the newest version of this account
                            Account updatedAccount =
                                Account.restoreAccountWithId(context, account.mId);
                            if (updatedAccount == null) continue;
                            if (account.mSyncInterval != updatedAccount.mSyncInterval
                                    || account.mSyncLookback != updatedAccount.mSyncLookback) {
                                // Set the inbox interval to the interval of the Account
                                // This setting should NOT affect other boxes
                                ContentValues cv = new ContentValues();
                                cv.put(MailboxColumns.SYNC_INTERVAL, updatedAccount.mSyncInterval);
                                getContentResolver().update(Mailbox.CONTENT_URI, cv,
                                        WHERE_IN_ACCOUNT_AND_TYPE_INBOX, new String[] {
                                        Long.toString(account.mId)
                                });
                                // Stop all current syncs; the appropriate ones will restart
                                log("Account " + account.mDisplayName + " changed; stop syncs");
                                stopAccountSyncs(account.mId, true);
                            }

                            // See if this account is no longer on security hold
                            if (onSecurityHold(account) && !onSecurityHold(updatedAccount)) {
                                releaseSyncHolds(ExchangeService.this,
                                        AbstractSyncService.EXIT_SECURITY_FAILURE, account);
                            }

                            // Put current values into our cached account
                            account.mSyncInterval = updatedAccount.mSyncInterval;
                            account.mSyncLookback = updatedAccount.mSyncLookback;
                            account.mFlags = updatedAccount.mFlags;
                        }
                    }
                    // Look for new accounts
                    for (Account account : currentAccounts) {
                        if (!mAccountList.contains(account.mId)) {
                            // Don't forget to cache the HostAuth
                            HostAuth ha = HostAuth.restoreHostAuthWithId(getContext(),
                                    account.mHostAuthKeyRecv);
                            if (ha == null) continue;
                            account.mHostAuthRecv = ha;
                            // This is an addition; create our magic hidden mailbox...
                            log("Account observer found new account: " + account.mDisplayName);
                            addAccountMailbox(account.mId);
                            mAccountList.add(account);
                            mSyncableEasMailboxSelector = null;
                            mEasAccountSelector = null;
                        }
                    }
                    // Finally, make sure our account list is up to date
                    mAccountList.clear();
                    mAccountList.addAll(currentAccounts);
                }

                // See if there's anything to do...
                kick("account changed");
            } catch (ProviderUnavailableException e) {
                alwaysLog("Observer failed; provider unavailable");
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            new Thread(new Runnable() {
               public void run() {
                   onAccountChanged();
                }}, "Account Observer").start();
        }

        private void addAccountMailbox(long acctId) {
            Account acct = Account.restoreAccountWithId(getContext(), acctId);
            Mailbox main = new Mailbox();
            main.mDisplayName = Eas.ACCOUNT_MAILBOX_PREFIX;
            main.mServerId = Eas.ACCOUNT_MAILBOX_PREFIX + System.nanoTime();
            main.mAccountKey = acct.mId;
            main.mType = Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;
            main.mSyncInterval = Mailbox.CHECK_INTERVAL_PUSH;
            main.mFlagVisible = false;
            main.save(getContext());
            log("Initializing account: " + acct.mDisplayName);
        }

    }

    /**
     * Register a specific Calendar's data observer; we need to recognize when the SYNC_EVENTS
     * column has changed (when sync has turned off or on)
     * @param account the Account whose Calendar we're observing
     */
    private void registerCalendarObserver(Account account) {
        // Get a new observer
        CalendarObserver observer = new CalendarObserver(mHandler, account);
        if (observer.mCalendarId != 0) {
            // If we find the Calendar (and we'd better) register it and store it in the map
            mCalendarObservers.put(account.mId, observer);
            mResolver.registerContentObserver(
                    ContentUris.withAppendedId(Calendars.CONTENT_URI, observer.mCalendarId), false,
                    observer);
        }
    }

    /**
     * Unregister all CalendarObserver's
     */
    static public void unregisterCalendarObservers() {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        ContentResolver resolver = exchangeService.mResolver;
        for (CalendarObserver observer: exchangeService.mCalendarObservers.values()) {
            resolver.unregisterContentObserver(observer);
        }
        exchangeService.mCalendarObservers.clear();
    }

    /**
     * Return the syncable state of an account's calendar, as determined by the sync_events column
     * of our Calendar (from CalendarProvider2)
     * Note that the current state of sync_events is cached in our CalendarObserver
     * @param accountId the id of the account whose calendar we are checking
     * @return whether or not syncing of events is enabled
     */
    private boolean isCalendarEnabled(long accountId) {
        CalendarObserver observer = mCalendarObservers.get(accountId);
        if (observer != null) {
            return (observer.mSyncEvents == 1);
        }
        // If there's no observer, there's no Calendar in CalendarProvider2, so we return true
        // to allow Calendar creation
        return true;
    }

    private class CalendarObserver extends ContentObserver {
        long mAccountId;
        long mCalendarId;
        long mSyncEvents;
        String mAccountName;

        public CalendarObserver(Handler handler, Account account) {
            super(handler);
            mAccountId = account.mId;
            mAccountName = account.mEmailAddress;

            // Find the Calendar for this account
            Cursor c = mResolver.query(Calendars.CONTENT_URI,
                    new String[] {Calendars._ID, Calendars.SYNC_EVENTS},
                    CalendarSyncAdapter.CALENDAR_SELECTION,
                    new String[] {account.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE},
                    null);
            if (c != null) {
                // Save its id and its sync events status
                try {
                    if (c.moveToFirst()) {
                        mCalendarId = c.getLong(0);
                        mSyncEvents = c.getLong(1);
                    }
                } finally {
                    c.close();
                }
            }
        }

        @Override
        public synchronized void onChange(boolean selfChange) {
            // See if the user has changed syncing of our calendar
            if (!selfChange) {
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            Cursor c = mResolver.query(Calendars.CONTENT_URI,
                                    new String[] {Calendars.SYNC_EVENTS}, Calendars._ID + "=?",
                                    new String[] {Long.toString(mCalendarId)}, null);
                            if (c == null) return;
                            // Get its sync events; if it's changed, we've got work to do
                            try {
                                if (c.moveToFirst()) {
                                    long newSyncEvents = c.getLong(0);
                                    if (newSyncEvents != mSyncEvents) {
                                        log("_sync_events changed for calendar in " + mAccountName);
                                        Mailbox mailbox = Mailbox.restoreMailboxOfType(INSTANCE,
                                                mAccountId, Mailbox.TYPE_CALENDAR);
                                        // Sanity check for mailbox deletion
                                        if (mailbox == null) return;
                                        ContentValues cv = new ContentValues();
                                        if (newSyncEvents == 0) {
                                            // When sync is disabled, we're supposed to delete
                                            // all events in the calendar
                                            log("Deleting events and setting syncKey to 0 for " +
                                                    mAccountName);
                                            // First, stop any sync that's ongoing
                                            stopManualSync(mailbox.mId);
                                            // Set the syncKey to 0 (reset)
                                            EasSyncService service =
                                                new EasSyncService(INSTANCE, mailbox);
                                            CalendarSyncAdapter adapter =
                                                new CalendarSyncAdapter(service);
                                            try {
                                                adapter.setSyncKey("0", false);
                                            } catch (IOException e) {
                                                // The provider can't be reached; nothing to be done
                                            }
                                            // Reset the sync key locally and stop syncing
                                            cv.put(Mailbox.SYNC_KEY, "0");
                                            cv.put(Mailbox.SYNC_INTERVAL,
                                                    Mailbox.CHECK_INTERVAL_NEVER);
                                            mResolver.update(ContentUris.withAppendedId(
                                                    Mailbox.CONTENT_URI, mailbox.mId), cv, null,
                                                    null);
                                            // Delete all events using the sync adapter
                                            // parameter so that the deletion is only local
                                            Uri eventsAsSyncAdapter =
                                                CalendarSyncAdapter.asSyncAdapter(
                                                    Events.CONTENT_URI,
                                                    mAccountName,
                                                    Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                                            mResolver.delete(eventsAsSyncAdapter, WHERE_CALENDAR_ID,
                                                    new String[] {Long.toString(mCalendarId)});
                                        } else {
                                            // Make this a push mailbox and kick; this will start
                                            // a resync of the Calendar; the account mailbox will
                                            // ping on this during the next cycle of the ping loop
                                            cv.put(Mailbox.SYNC_INTERVAL,
                                                    Mailbox.CHECK_INTERVAL_PUSH);
                                            mResolver.update(ContentUris.withAppendedId(
                                                    Mailbox.CONTENT_URI, mailbox.mId), cv, null,
                                                    null);
                                            kick("calendar sync changed");
                                        }

                                        // Save away the new value
                                        mSyncEvents = newSyncEvents;
                                    }
                                }
                            } finally {
                                c.close();
                            }
                        } catch (ProviderUnavailableException e) {
                            Log.w(TAG, "Observer failed; provider unavailable");
                        }
                    }}, "Calendar Observer").start();
            }
        }
    }

    private class MailboxObserver extends ContentObserver {
        public MailboxObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            // See if there's anything to do...
            if (!selfChange) {
                kick("mailbox changed");
            }
        }
    }

    private class SyncedMessageObserver extends ContentObserver {
        Intent syncAlarmIntent = new Intent(INSTANCE, EmailSyncAlarmReceiver.class);
        PendingIntent syncAlarmPendingIntent =
            PendingIntent.getBroadcast(INSTANCE, 0, syncAlarmIntent, 0);
        AlarmManager alarmManager = (AlarmManager)INSTANCE.getSystemService(Context.ALARM_SERVICE);

        public SyncedMessageObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            alarmManager.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 10*SECONDS, syncAlarmPendingIntent);
        }
    }

    static public IEmailServiceCallback callback() {
        return sCallbackProxy;
    }

    static public Account getAccountById(long accountId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            AccountList accountList = exchangeService.mAccountList;
            synchronized (accountList) {
                return accountList.getById(accountId);
            }
        }
        return null;
    }

    static public Account getAccountByName(String accountName) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            AccountList accountList = exchangeService.mAccountList;
            synchronized (accountList) {
                return accountList.getByName(accountName);
            }
        }
        return null;
    }

    static public String getEasAccountSelector() {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null && exchangeService.mAccountObserver != null) {
            return exchangeService.mAccountObserver.getAccountKeyWhere();
        }
        return null;
    }

    public class SyncStatus {
        static public final int NOT_RUNNING = 0;
        static public final int DIED = 1;
        static public final int SYNC = 2;
        static public final int IDLE = 3;
    }

    /*package*/ class SyncError {
        int reason;
        boolean fatal = false;
        long holdDelay = 15*SECONDS;
        long holdEndTime = System.currentTimeMillis() + holdDelay;

        SyncError(int _reason, boolean _fatal) {
            reason = _reason;
            fatal = _fatal;
        }

        /**
         * We double the holdDelay from 15 seconds through 4 mins
         */
        void escalate() {
            if (holdDelay < HOLD_DELAY_MAXIMUM) {
                holdDelay *= 2;
            }
            holdEndTime = System.currentTimeMillis() + holdDelay;
        }
    }

    private void logSyncHolds() {
        if (Eas.USER_LOG) {
            log("Sync holds:");
            long time = System.currentTimeMillis();
            for (long mailboxId : mSyncErrorMap.keySet()) {
                Mailbox m = Mailbox.restoreMailboxWithId(this, mailboxId);
                if (m == null) {
                    log("Mailbox " + mailboxId + " no longer exists");
                } else {
                    SyncError error = mSyncErrorMap.get(mailboxId);
                    if (error != null) {
                        log("Mailbox " + m.mDisplayName + ", error = " + error.reason
                                + ", fatal = " + error.fatal);
                        if (error.holdEndTime > 0) {
                            log("Hold ends in " + ((error.holdEndTime - time) / 1000) + "s");
                        }
                    }
                }
            }
        }
    }

    /**
     * Release security holds for the specified account
     * @param account the account whose Mailboxes should be released from security hold
     */
    static public void releaseSecurityHold(Account account) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.releaseSyncHolds(INSTANCE, AbstractSyncService.EXIT_SECURITY_FAILURE,
                    account);
        }
    }

    /**
     * Release a specific type of hold (the reason) for the specified Account; if the account
     * is null, mailboxes from all accounts with the specified hold will be released
     * @param reason the reason for the SyncError (AbstractSyncService.EXIT_XXX)
     * @param account an Account whose mailboxes should be released (or all if null)
     * @return whether or not any mailboxes were released
     */
    /*package*/ boolean releaseSyncHolds(Context context, int reason, Account account) {
        boolean holdWasReleased = releaseSyncHoldsImpl(context, reason, account);
        kick("security release");
        return holdWasReleased;
    }

    private boolean releaseSyncHoldsImpl(Context context, int reason, Account account) {
        boolean holdWasReleased = false;
        for (long mailboxId: mSyncErrorMap.keySet()) {
            if (account != null) {
                Mailbox m = Mailbox.restoreMailboxWithId(context, mailboxId);
                if (m == null) {
                    mSyncErrorMap.remove(mailboxId);
                } else if (m.mAccountKey != account.mId) {
                    continue;
                }
            }
            SyncError error = mSyncErrorMap.get(mailboxId);
            if (error != null && error.reason == reason) {
                mSyncErrorMap.remove(mailboxId);
                holdWasReleased = true;
            }
        }
        return holdWasReleased;
    }

    /**
     * Reconcile Exchange accounts with AccountManager (asynchronous)
     * @param context the caller's Context
     */
    public static void reconcileAccounts(final Context context) {
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                ExchangeService exchangeService = INSTANCE;
                if (exchangeService != null) {
                    exchangeService.runAccountReconcilerSync(context);
                }
            }});
    }

    /**
     * Blocking call to the account reconciler
     */
    public static void runAccountReconcilerSync(Context context) {
        alwaysLog("Reconciling accounts...");
        new AccountServiceProxy(context).reconcileAccounts(
                HostAuth.SCHEME_EAS, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
    }

    public static void log(String str) {
        log(TAG, str);
    }

    public static void log(String tag, String str) {
        if (Eas.USER_LOG) {
            Log.d(tag, str);
            if (Eas.FILE_LOG) {
                FileLogger.log(tag, str);
            }
        }
    }

    public static void alwaysLog(String str) {
        if (!Eas.USER_LOG) {
            Log.d(TAG, str);
        } else {
            log(str);
        }
    }

    /**
     * EAS requires a unique device id, so that sync is possible from a variety of different
     * devices (e.g. the syncKey is specific to a device)  If we're on an emulator or some other
     * device that doesn't provide one, we can create it as "device".
     * This would work on a real device as well, but it would be better to use the "real" id if
     * it's available
     */
    static public String getDeviceId(Context context) throws IOException {
        if (sDeviceId == null) {
            sDeviceId = new AccountServiceProxy(context).getDeviceId();
            alwaysLog("Received deviceId from Email app: " + sDeviceId);
        }
        return sDeviceId;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    static public ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        public int getMaxForRoute(HttpRoute route) {
            return 8;
        }
    };

    static public synchronized EmailClientConnectionManager getClientConnectionManager() {
        if (sClientConnectionManager == null) {
            // After two tries, kill the process.  Most likely, this will happen in the background
            // The service will restart itself after about 5 seconds
            if (sClientConnectionManagerShutdownCount > MAX_CLIENT_CONNECTION_MANAGER_SHUTDOWNS) {
                alwaysLog("Shutting down process to unblock threads");
                Process.killProcess(Process.myPid());
            }
            HttpParams params = new BasicHttpParams();
            params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 25);
            params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, sConnPerRoute);
            sClientConnectionManager = EmailClientConnectionManager.newInstance(params);
        }
        // Null is a valid return result if we get an exception
        return sClientConnectionManager;
    }

    static private synchronized void shutdownConnectionManager() {
        if (sClientConnectionManager != null) {
            log("Shutting down ClientConnectionManager");
            sClientConnectionManager.shutdown();
            sClientConnectionManagerShutdownCount++;
            sClientConnectionManager = null;
        }
    }

    public static void stopAccountSyncs(long acctId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.stopAccountSyncs(acctId, true);
        }
    }

    private void stopAccountSyncs(long acctId, boolean includeAccountMailbox) {
        synchronized (sSyncLock) {
            List<Long> deletedBoxes = new ArrayList<Long>();
            for (Long mid : mServiceMap.keySet()) {
                Mailbox box = Mailbox.restoreMailboxWithId(this, mid);
                if (box != null) {
                    if (box.mAccountKey == acctId) {
                        if (!includeAccountMailbox &&
                                box.mType == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
                            AbstractSyncService svc = mServiceMap.get(mid);
                            if (svc != null) {
                                svc.stop();
                            }
                            continue;
                        }
                        AbstractSyncService svc = mServiceMap.get(mid);
                        if (svc != null) {
                            svc.stop();
                            Thread t = svc.mThread;
                            if (t != null) {
                                t.interrupt();
                            }
                        }
                        deletedBoxes.add(mid);
                    }
                }
            }
            for (Long mid : deletedBoxes) {
                releaseMailbox(mid);
            }
        }
    }

    static private void reloadFolderListFailed(long accountId) {
        try {
            callback().syncMailboxListStatus(accountId,
                    EmailServiceStatus.ACCOUNT_UNINITIALIZED, 0);
        } catch (RemoteException e1) {
            // Don't care if this fails
        }
    }

    static public void reloadFolderList(Context context, long accountId, boolean force) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION, MailboxColumns.ACCOUNT_KEY + "=? AND " +
                MailboxColumns.TYPE + "=?",
                new String[] {Long.toString(accountId),
                    Long.toString(Mailbox.TYPE_EAS_ACCOUNT_MAILBOX)}, null);
        try {
            if (c.moveToFirst()) {
                synchronized(sSyncLock) {
                    Mailbox mailbox = new Mailbox();
                    mailbox.restore(c);
                    Account acct = Account.restoreAccountWithId(context, accountId);
                    if (acct == null) {
                        reloadFolderListFailed(accountId);
                        return;
                    }
                    String syncKey = acct.mSyncKey;
                    // No need to reload the list if we don't have one
                    if (!force && (syncKey == null || syncKey.equals("0"))) {
                        reloadFolderListFailed(accountId);
                        return;
                    }

                    // Change all ping/push boxes to push/hold
                    ContentValues cv = new ContentValues();
                    cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH_HOLD);
                    context.getContentResolver().update(Mailbox.CONTENT_URI, cv,
                            WHERE_PUSH_OR_PING_NOT_ACCOUNT_MAILBOX,
                            new String[] {Long.toString(accountId)});
                    log("Set push/ping boxes to push/hold");

                    long id = mailbox.mId;
                    AbstractSyncService svc = exchangeService.mServiceMap.get(id);
                    // Tell the service we're done
                    if (svc != null) {
                        synchronized (svc.getSynchronizer()) {
                            svc.stop();
                            // Interrupt the thread so that it can stop
                            Thread thread = svc.mThread;
                            if (thread != null) {
                                thread.setName(thread.getName() + " (Stopped)");
                                thread.interrupt();
                            }
                        }
                        // Abandon the service
                        exchangeService.releaseMailbox(id);
                        // And have it start naturally
                        kick("reload folder list");
                    }
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Informs ExchangeService that an account has a new folder list; as a result, any existing
     * folder might have become invalid.  Therefore, we act as if the account has been deleted, and
     * then we reinitialize it.
     *
     * @param acctId
     */
    static public void stopNonAccountMailboxSyncsForAccount(long acctId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.stopAccountSyncs(acctId, false);
            kick("reload folder list");
        }
    }

    private void acquireWakeLock(long id) {
        synchronized (mWakeLocks) {
            Boolean lock = mWakeLocks.get(id);
            if (lock == null) {
                if (mWakeLock == null) {
                    PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAIL_SERVICE");
                    mWakeLock.acquire();
                    //log("+WAKE LOCK ACQUIRED");
                }
                mWakeLocks.put(id, true);
             }
        }
    }

    private void releaseWakeLock(long id) {
        synchronized (mWakeLocks) {
            Boolean lock = mWakeLocks.get(id);
            if (lock != null) {
                mWakeLocks.remove(id);
                if (mWakeLocks.isEmpty()) {
                    if (mWakeLock != null) {
                        mWakeLock.release();
                    }
                    mWakeLock = null;
                    //log("+WAKE LOCK RELEASED");
                } else {
                }
            }
        }
    }

    static public String alarmOwner(long id) {
        if (id == EXTRA_MAILBOX_ID) {
            return "ExchangeService";
        } else {
            String name = Long.toString(id);
            if (Eas.USER_LOG && INSTANCE != null) {
                Mailbox m = Mailbox.restoreMailboxWithId(INSTANCE, id);
                if (m != null) {
                    name = m.mDisplayName + '(' + m.mAccountKey + ')';
                }
            }
            return "Mailbox " + name;
        }
    }

    private void clearAlarm(long id) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi != null) {
                AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                alarmManager.cancel(pi);
                //log("+Alarm cleared for " + alarmOwner(id));
                mPendingIntents.remove(id);
            }
        }
    }

    private void setAlarm(long id, long millis) {
        synchronized (mPendingIntents) {
            PendingIntent pi = mPendingIntents.get(id);
            if (pi == null) {
                Intent i = new Intent(this, MailboxAlarmReceiver.class);
                i.putExtra("mailbox", id);
                i.setData(Uri.parse("Box" + id));
                pi = PendingIntent.getBroadcast(this, 0, i, 0);
                mPendingIntents.put(id, pi);

                AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + millis, pi);
                //log("+Alarm set for " + alarmOwner(id) + ", " + millis/1000 + "s");
            }
        }
    }

    private void clearAlarms() {
        AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        synchronized (mPendingIntents) {
            for (PendingIntent pi : mPendingIntents.values()) {
                alarmManager.cancel(pi);
            }
            mPendingIntents.clear();
        }
    }

    static public void runAwake(long id) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.acquireWakeLock(id);
            exchangeService.clearAlarm(id);
        }
    }

    static public void runAsleep(long id, long millis) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.setAlarm(id, millis);
            exchangeService.releaseWakeLock(id);
        }
    }

    static public void clearWatchdogAlarm(long id) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.clearAlarm(id);
        }
    }

    static public void setWatchdogAlarm(long id, long millis) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.setAlarm(id, millis);
        }
    }

    static public void alert(Context context, final long id) {
        final ExchangeService exchangeService = INSTANCE;
        checkExchangeServiceServiceRunning();
        if (id < 0) {
            log("ExchangeService alert");
            kick("ping ExchangeService");
        } else if (exchangeService == null) {
            context.startService(new Intent(context, ExchangeService.class));
        } else {
            final AbstractSyncService service = exchangeService.mServiceMap.get(id);
            if (service != null) {
                // Handle alerts in a background thread, as we are typically called from a
                // broadcast receiver, and are therefore running in the UI thread
                String threadName = "ExchangeService Alert: ";
                if (service.mMailbox != null) {
                    threadName += service.mMailbox.mDisplayName;
                }
                new Thread(new Runnable() {
                   public void run() {
                       Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, id);
                       if (m != null) {
                           // We ignore drafts completely (doesn't sync).  Changes in Outbox are
                           // handled in the checkMailboxes loop, so we can ignore these pings.
                           if (Eas.USER_LOG) {
                               Log.d(TAG, "Alert for mailbox " + id + " (" + m.mDisplayName + ")");
                           }
                           if (m.mType == Mailbox.TYPE_DRAFTS || m.mType == Mailbox.TYPE_OUTBOX) {
                               String[] args = new String[] {Long.toString(m.mId)};
                               ContentResolver resolver = INSTANCE.mResolver;
                               resolver.delete(Message.DELETED_CONTENT_URI, WHERE_MAILBOX_KEY,
                                       args);
                               resolver.delete(Message.UPDATED_CONTENT_URI, WHERE_MAILBOX_KEY,
                                       args);
                               return;
                           }
                           service.mAccount = Account.restoreAccountWithId(INSTANCE, m.mAccountKey);
                           service.mMailbox = m;
                           // Send the alarm to the sync service
                           if (!service.alarm()) {
                               // A false return means that we were forced to interrupt the thread
                               // In this case, we release the mailbox so that we can start another
                               // thread to do the work
                               log("Alarm failed; releasing mailbox");
                               synchronized(sSyncLock) {
                                   exchangeService.releaseMailbox(id);
                               }
                               // Shutdown the connection manager; this should close all of our
                               // sockets and generate IOExceptions all around.
                               ExchangeService.shutdownConnectionManager();
                           }
                       }
                    }}, threadName).start();
            }
        }
    }

    public class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Bundle b = intent.getExtras();
                if (b != null) {
                    NetworkInfo a = (NetworkInfo)b.get(ConnectivityManager.EXTRA_NETWORK_INFO);
                    String info = "Connectivity alert for " + a.getTypeName();
                    State state = a.getState();
                    if (state == State.CONNECTED) {
                        info += " CONNECTED";
                        log(info);
                        synchronized (sConnectivityLock) {
                            sConnectivityLock.notifyAll();
                        }
                        kick("connected");
                    } else if (state == State.DISCONNECTED) {
                        info += " DISCONNECTED";
                        log(info);
                        kick("disconnected");
                    }
                }
            } else if (intent.getAction().equals(
                    ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED)) {
                ConnectivityManager cm =
                        (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
                mBackgroundData = cm.getBackgroundDataSetting();
                // If background data is now on, we want to kick ExchangeService
                if (mBackgroundData) {
                    kick("background data on");
                    log("Background data on; restart syncs");
                // Otherwise, stop all syncs
                } else {
                    log("Background data off: stop all syncs");
                    EmailAsyncTask.runAsyncParallel(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (mAccountList) {
                                for (Account account : mAccountList)
                                    ExchangeService.stopAccountSyncs(account.mId);
                            }
                        }});
                }
            }
        }
    }

    /**
     * Starts a service thread and enters it into the service map
     * This is the point of instantiation of all sync threads
     * @param service the service to start
     * @param m the Mailbox on which the service will operate
     */
    private void startServiceThread(AbstractSyncService service, Mailbox m) {
        if (m == null) return;
        synchronized (sSyncLock) {
            String mailboxName = m.mDisplayName;
            String accountName = service.mAccount.mDisplayName;
            Thread thread = new Thread(service, mailboxName + "[" + accountName + "]");
            log("Starting thread for " + mailboxName + " in account " + accountName);
            thread.start();
            mServiceMap.put(m.mId, service);
            runAwake(m.mId);
            if ((m.mServerId != null) && !m.mServerId.startsWith(Eas.ACCOUNT_MAILBOX_PREFIX)) {
                stopPing(m.mAccountKey);
            }
        }
    }

    /**
     * Stop any ping in progress for the given account
     * @param accountId
     */
    private void stopPing(long accountId) {
        // Go through our active mailboxes looking for the right one
        synchronized (sSyncLock) {
            for (long mailboxId: mServiceMap.keySet()) {
                Mailbox m = Mailbox.restoreMailboxWithId(this, mailboxId);
                if (m != null) {
                    String serverId = m.mServerId;
                    if (m.mAccountKey == accountId && serverId != null &&
                            serverId.startsWith(Eas.ACCOUNT_MAILBOX_PREFIX)) {
                        // Here's our account mailbox; reset him (stopping pings)
                        AbstractSyncService svc = mServiceMap.get(mailboxId);
                        svc.reset();
                    }
                }
            }
        }
    }

    private void requestSync(Mailbox m, int reason, Request req) {
        // Don't sync if there's no connectivity
        if (sConnectivityHold || (m == null) || sStop) {
            if (reason >= SYNC_CALLBACK_START) {
                try {
                    sCallbackProxy.syncMailboxStatus(m.mId, EmailServiceStatus.CONNECTION_ERROR, 0);
                } catch (RemoteException e) {
                    // We tried...
                }
            }
            return;
        }
        synchronized (sSyncLock) {
            Account acct = Account.restoreAccountWithId(this, m.mAccountKey);
            if (acct != null) {
                // Always make sure there's not a running instance of this service
                AbstractSyncService service = mServiceMap.get(m.mId);
                if (service == null) {
                    service = new EasSyncService(this, m);
                    if (!((EasSyncService)service).mIsValid) return;
                    service.mSyncReason = reason;
                    if (req != null) {
                        service.addRequest(req);
                    }
                    startServiceThread(service, m);
                }
            }
        }
    }

    private void stopServiceThreads() {
        synchronized (sSyncLock) {
            ArrayList<Long> toStop = new ArrayList<Long>();

            // Keep track of which services to stop
            for (Long mailboxId : mServiceMap.keySet()) {
                toStop.add(mailboxId);
            }

            // Shut down all of those running services
            for (Long mailboxId : toStop) {
                AbstractSyncService svc = mServiceMap.get(mailboxId);
                if (svc != null) {
                    log("Stopping " + svc.mAccount.mDisplayName + '/' + svc.mMailbox.mDisplayName);
                    svc.stop();
                    if (svc.mThread != null) {
                        svc.mThread.interrupt();
                    }
                }
                releaseWakeLock(mailboxId);
            }
        }
    }

    private void waitForConnectivity() {
        boolean waiting = false;
        ConnectivityManager cm =
            (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        while (!sStop) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null) {
                mNetworkInfo = info;
                // We're done if there's an active network
                if (waiting) {
                    // If we've been waiting, release any I/O error holds
                    releaseSyncHolds(this, AbstractSyncService.EXIT_IO_ERROR, null);
                    // And log what's still being held
                    logSyncHolds();
                }
                return;
            } else {
                // If this is our first time through the loop, shut down running service threads
                if (!waiting) {
                    waiting = true;
                    stopServiceThreads();
                }
                // Wait until a network is connected (or 10 mins), but let the device sleep
                // We'll set an alarm just in case we don't get notified (bugs happen)
                synchronized (sConnectivityLock) {
                    runAsleep(EXTRA_MAILBOX_ID, CONNECTIVITY_WAIT_TIME+5*SECONDS);
                    try {
                        log("Connectivity lock...");
                        sConnectivityHold = true;
                        sConnectivityLock.wait(CONNECTIVITY_WAIT_TIME);
                        log("Connectivity lock released...");
                    } catch (InterruptedException e) {
                        // This is fine; we just go around the loop again
                    } finally {
                        sConnectivityHold = false;
                    }
                    runAwake(EXTRA_MAILBOX_ID);
                }
            }
        }
    }

    /**
     * Note that there are two ways the EAS ExchangeService service can be created:
     *
     * 1) as a background service instantiated via startService (which happens on boot, when the
     * first EAS account is created, etc), in which case the service thread is spun up, mailboxes
     * sync, etc. and
     * 2) to execute an RPC call from the UI, in which case the background service will already be
     * running most of the time (unless we're creating a first EAS account)
     *
     * If the running background service detects that there are no EAS accounts (on boot, if none
     * were created, or afterward if the last remaining EAS account is deleted), it will call
     * stopSelf() to terminate operation.
     *
     * The goal is to ensure that the background service is running at all times when there is at
     * least one EAS account in existence
     *
     * Because there are edge cases in which our process can crash (typically, this has been seen
     * in UI crashes, ANR's, etc.), it's possible for the UI to start up again without the
     * background service having been started.  We explicitly try to start the service in Welcome
     * (to handle the case of the app having been reloaded).  We also start the service on any
     * startSync call (if it isn't already running)
     */
    @Override
    public void onCreate() {
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                // Quick checks first, before getting the lock
                if (sStartingUp) return;
                synchronized (sSyncLock) {
                    alwaysLog("!!! EAS ExchangeService, onCreate");
                    // Try to start up properly; we might be coming back from a crash that the Email
                    // application isn't aware of.
                    startService(new Intent(EmailServiceProxy.EXCHANGE_INTENT));
                    if (sStop) {
                        return;
                    }
                }
            }});
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alwaysLog("!!! EAS ExchangeService, onStartCommand, startingUp = " + sStartingUp +
                ", running = " + (INSTANCE != null));
        if (!sStartingUp && INSTANCE == null) {
            sStartingUp = true;
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (sSyncLock) {
                            // ExchangeService cannot start unless we can connect to AccountService
                            if (!new AccountServiceProxy(ExchangeService.this).test()) {
                                alwaysLog("!!! Email application not found; stopping self");
                                stopSelf();
                            }
                            if (sDeviceId == null) {
                                try {
                                    String deviceId = getDeviceId(ExchangeService.this);
                                    if (deviceId != null) {
                                        sDeviceId = deviceId;
                                    }
                                } catch (IOException e) {
                                }
                                if (sDeviceId == null) {
                                    alwaysLog("!!! deviceId unknown; stopping self and retrying");
                                    stopSelf();
                                    // Try to restart ourselves in a few seconds
                                    Utility.runAsync(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Thread.sleep(5000);
                                            } catch (InterruptedException e) {
                                            }
                                            startService(new Intent(
                                                    EmailServiceProxy.EXCHANGE_INTENT));
                                        }});
                                    return;
                                }
                            }
                            // Run the reconciler and clean up mismatched accounts - if we weren't
                            // running when accounts were deleted, it won't have been called.
                            runAccountReconcilerSync(ExchangeService.this);
                            // Update other services depending on final account configuration
                            maybeStartExchangeServiceThread();
                            if (sServiceThread == null) {
                                log("!!! EAS ExchangeService, stopping self");
                                stopSelf();
                            } else if (sStop) {
                                // If we were trying to stop, attempt a restart in 5 secs
                                setAlarm(EXCHANGE_SERVICE_MAILBOX_ID, 5*SECONDS);
                            }
                        }
                    } finally {
                        sStartingUp = false;
                    }
                }});
        }
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        log("!!! EAS ExchangeService, onDestroy");
        // Handle shutting down off the UI thread
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                // Quick checks first, before getting the lock
                if (INSTANCE == null || sServiceThread == null) return;
                synchronized(sSyncLock) {
                    // Stop the sync manager thread and return
                    if (sServiceThread != null) {
                        sStop = true;
                        sServiceThread.interrupt();
                    }
                }
            }});
    }

    void maybeStartExchangeServiceThread() {
        // Start our thread...
        // See if there are any EAS accounts; otherwise, just go away
        if (sServiceThread == null || !sServiceThread.isAlive()) {
            if (EmailContent.count(this, HostAuth.CONTENT_URI, WHERE_PROTOCOL_EAS, null) > 0) {
                log(sServiceThread == null ? "Starting thread..." : "Restarting thread...");
                sServiceThread = new Thread(this, "ExchangeService");
                INSTANCE = this;
                sServiceThread.start();
            }
        }
    }

    /**
     * Start up the ExchangeService service if it's not already running
     * This is a stopgap for cases in which ExchangeService died (due to a crash somewhere in
     * com.android.email) and hasn't been restarted. See the comment for onCreate for details
     */
    static void checkExchangeServiceServiceRunning() {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        if (sServiceThread == null) {
            log("!!! checkExchangeServiceServiceRunning; starting service...");
            exchangeService.startService(new Intent(exchangeService, ExchangeService.class));
        }
    }

    public void run() {
        sStop = false;
        alwaysLog("ExchangeService thread running");
        // If we're really debugging, turn on all logging
        if (Eas.DEBUG) {
            Eas.USER_LOG = true;
            Eas.PARSER_LOG = true;
            Eas.FILE_LOG = true;
        }

        TempDirectory.setTempDirectory(this);

        // If we need to wait for the debugger, do so
        if (Eas.WAIT_DEBUG) {
            Debug.waitForDebugger();
        }

        // Synchronize here to prevent a shutdown from happening while we initialize our observers
        // and receivers
        synchronized (sSyncLock) {
            if (INSTANCE != null) {
                mResolver = getContentResolver();

                // Set up our observers; we need them to know when to start/stop various syncs based
                // on the insert/delete/update of mailboxes and accounts
                // We also observe synced messages to trigger upsyncs at the appropriate time
                mAccountObserver = new AccountObserver(mHandler);
                mResolver.registerContentObserver(Account.NOTIFIER_URI, true, mAccountObserver);
                mMailboxObserver = new MailboxObserver(mHandler);
                mResolver.registerContentObserver(Mailbox.CONTENT_URI, false, mMailboxObserver);
                mSyncedMessageObserver = new SyncedMessageObserver(mHandler);
                mResolver.registerContentObserver(Message.SYNCED_CONTENT_URI, true,
                        mSyncedMessageObserver);

                // Set up receivers for connectivity and background data setting
                mConnectivityReceiver = new ConnectivityReceiver();
                registerReceiver(mConnectivityReceiver, new IntentFilter(
                        ConnectivityManager.CONNECTIVITY_ACTION));

                mBackgroundDataSettingReceiver = new ConnectivityReceiver();
                registerReceiver(mBackgroundDataSettingReceiver, new IntentFilter(
                        ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED));
                // Save away the current background data setting; we'll keep track of it with the
                // receiver we just registered
                ConnectivityManager cm = (ConnectivityManager)getSystemService(
                        Context.CONNECTIVITY_SERVICE);
                mBackgroundData = cm.getBackgroundDataSetting();

                // Do any required work to clean up our Mailboxes (this serves to upgrade
                // mailboxes that existed prior to EmailProvider database version 17)
                MailboxUtilities.fixupUninitializedParentKeys(this, getEasAccountSelector());
            }
        }

        try {
            // Loop indefinitely until we're shut down
            while (!sStop) {
                runAwake(EXTRA_MAILBOX_ID);
                waitForConnectivity();
                mNextWaitReason = null;
                long nextWait = checkMailboxes();
                try {
                    synchronized (this) {
                        if (!mKicked) {
                            if (nextWait < 0) {
                                log("Negative wait? Setting to 1s");
                                nextWait = 1*SECONDS;
                            }
                            if (nextWait > 10*SECONDS) {
                                if (mNextWaitReason != null) {
                                    log("Next awake " + nextWait / 1000 + "s: " + mNextWaitReason);
                                }
                                runAsleep(EXTRA_MAILBOX_ID, nextWait + (3*SECONDS));
                            }
                            wait(nextWait);
                        }
                    }
                } catch (InterruptedException e) {
                    // Needs to be caught, but causes no problem
                    log("ExchangeService interrupted");
                } finally {
                    synchronized (this) {
                        if (mKicked) {
                            //log("Wait deferred due to kick");
                            mKicked = false;
                        }
                    }
                }
            }
            log("Shutdown requested");
        } catch (ProviderUnavailableException pue) {
            // Shutdown cleanly in this case
            // NOTE: Sync adapters will also crash with this error, but that is already handled
            // in the adapters themselves, i.e. they return cleanly via done().  When the Email
            // process starts running again, the Exchange process will be started again in due
            // course, assuming there is at least one existing EAS account.
            Log.e(TAG, "EmailProvider unavailable; shutting down");
            // Ask for our service to be restarted; this should kick-start the Email process as well
            startService(new Intent(this, ExchangeService.class));
        } catch (RuntimeException e) {
            // Crash; this is a completely unexpected runtime error
            Log.e(TAG, "RuntimeException in ExchangeService", e);
            throw e;
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        synchronized (sSyncLock) {
            // If INSTANCE is null, we've already been shut down
            if (INSTANCE != null) {
                log("ExchangeService shutting down...");

                // Stop our running syncs
                stopServiceThreads();

                // Stop receivers
                if (mConnectivityReceiver != null) {
                    unregisterReceiver(mConnectivityReceiver);
                }
                if (mBackgroundDataSettingReceiver != null) {
                    unregisterReceiver(mBackgroundDataSettingReceiver);
                }

                // Unregister observers
                ContentResolver resolver = getContentResolver();
                if (mSyncedMessageObserver != null) {
                    resolver.unregisterContentObserver(mSyncedMessageObserver);
                    mSyncedMessageObserver = null;
                }
                if (mAccountObserver != null) {
                    resolver.unregisterContentObserver(mAccountObserver);
                    mAccountObserver = null;
                }
                if (mMailboxObserver != null) {
                    resolver.unregisterContentObserver(mMailboxObserver);
                    mMailboxObserver = null;
                }
                unregisterCalendarObservers();

                // Clear pending alarms and associated Intents
                clearAlarms();

                // Release our wake lock, if we have one
                synchronized (mWakeLocks) {
                    if (mWakeLock != null) {
                        mWakeLock.release();
                        mWakeLock = null;
                    }
                }

                INSTANCE = null;
                sServiceThread = null;
                sStop = false;
                log("Goodbye");
            }
        }
    }

    /**
     * Release a mailbox from the service map and release its wake lock.
     * NOTE: This method MUST be called while holding sSyncLock!
     *
     * @param mailboxId the id of the mailbox to be released
     */
    private void releaseMailbox(long mailboxId) {
        mServiceMap.remove(mailboxId);
        releaseWakeLock(mailboxId);
    }

    /**
     * Check whether an Outbox (referenced by a Cursor) has any messages that can be sent
     * @param c the cursor to an Outbox
     * @return true if there is mail to be sent
     */
    private boolean hasSendableMessages(Cursor outboxCursor) {
        Cursor c = mResolver.query(Message.CONTENT_URI, Message.ID_COLUMN_PROJECTION,
                EasOutboxService.MAILBOX_KEY_AND_NOT_SEND_FAILED,
                new String[] {Long.toString(outboxCursor.getLong(Mailbox.CONTENT_ID_COLUMN))},
                null);
        try {
            while (c.moveToNext()) {
                if (!Utility.hasUnloadedAttachments(this, c.getLong(Message.CONTENT_ID_COLUMN))) {
                    return true;
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    /**
     * Determine whether the account is allowed to sync automatically, as opposed to manually, based
     * on whether the "require manual sync when roaming" policy is in force and applicable
     * @param account the account
     * @return whether or not the account can sync automatically
     */
    /*package*/ static boolean canAutoSync(Account account) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) {
            return false;
        }
        NetworkInfo networkInfo = exchangeService.mNetworkInfo;

        // Enforce manual sync only while roaming here
        long policyKey = account.mPolicyKey;
        // Quick exit from this check
        if ((policyKey != 0) && (networkInfo != null) &&
                (ConnectivityManager.isNetworkTypeMobile(networkInfo.getType()))) {
            // We'll cache the Policy data here
            Policy policy = account.mPolicy;
            if (policy == null) {
                policy = Policy.restorePolicyWithId(INSTANCE, policyKey);
                account.mPolicy = policy;
            }
            if (policy != null && policy.mRequireManualSyncWhenRoaming && networkInfo.isRoaming()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convenience method to determine whether Email sync is enabled for a given account
     * @param account the Account in question
     * @return whether Email sync is enabled
     */
    private boolean canSyncEmail(android.accounts.Account account) {
        return ContentResolver.getSyncAutomatically(account, EmailContent.AUTHORITY);
    }

    /**
     * Determine whether a mailbox of a given type in a given account can be synced automatically
     * by ExchangeService.  This is an increasingly complex determination, taking into account
     * security policies and user settings (both within the Email application and in the Settings
     * application)
     *
     * @param account the Account that the mailbox is in
     * @param type the type of the Mailbox
     * @return whether or not to start a sync
     */
    private boolean isMailboxSyncable(Account account, int type) {
        // This 'if' statement performs checks to see whether or not a mailbox is a
        // candidate for syncing based on policies, user settings, & other restrictions
        if (type == Mailbox.TYPE_OUTBOX || type == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
            // Outbox and account mailbox are always syncable
            return true;
        } else if (type == Mailbox.TYPE_CONTACTS || type == Mailbox.TYPE_CALENDAR) {
            // Contacts/Calendar obey this setting from ContentResolver
            if (!ContentResolver.getMasterSyncAutomatically()) {
                return false;
            }
            // Get the right authority for the mailbox
            String authority;
            if (type == Mailbox.TYPE_CONTACTS) {
                authority = ContactsContract.AUTHORITY;
            } else {
                authority = CalendarContract.AUTHORITY;
                if (!mCalendarObservers.containsKey(account.mId)){
                    // Make sure we have an observer for this Calendar, as
                    // we need to be able to detect sync state changes, sigh
                    registerCalendarObserver(account);
                }
            }
            // See if "sync automatically" is set; if not, punt
            if (!ContentResolver.getSyncAutomatically(account.mAmAccount, authority)) {
                return false;
            // See if the calendar is enabled from the Calendar app UI; if not, punt
            } else if ((type == Mailbox.TYPE_CALENDAR) && !isCalendarEnabled(account.mId)) {
                return false;
            }
        // Never automatically sync trash
        } else if (type == Mailbox.TYPE_TRASH) {
            return false;
        // For non-outbox, non-account mail, we do three checks:
        // 1) are we restricted by policy (i.e. manual sync only),
        // 2) has the user checked the "Sync Email" box in Account Settings, and
        // 3) does the user have the master "background data" box checked in Settings
        } else if (!canAutoSync(account) || !canSyncEmail(account.mAmAccount) || !mBackgroundData) {
            return false;
        }
        return true;
    }

    private long checkMailboxes () {
        // First, see if any running mailboxes have been deleted
        ArrayList<Long> deletedMailboxes = new ArrayList<Long>();
        synchronized (sSyncLock) {
            for (long mailboxId: mServiceMap.keySet()) {
                Mailbox m = Mailbox.restoreMailboxWithId(this, mailboxId);
                if (m == null) {
                    deletedMailboxes.add(mailboxId);
                }
            }
            // If so, stop them or remove them from the map
            for (Long mailboxId: deletedMailboxes) {
                AbstractSyncService svc = mServiceMap.get(mailboxId);
                if (svc == null || svc.mThread == null) {
                    releaseMailbox(mailboxId);
                    continue;
                } else {
                    boolean alive = svc.mThread.isAlive();
                    log("Deleted mailbox: " + svc.mMailboxName);
                    if (alive) {
                        stopManualSync(mailboxId);
                    } else {
                        log("Removing from serviceMap");
                        releaseMailbox(mailboxId);
                    }
                }
            }
        }

        long nextWait = EXCHANGE_SERVICE_HEARTBEAT_TIME;
        long now = System.currentTimeMillis();

        // Start up threads that need it; use a query which finds eas mailboxes where the
        // the sync interval is not "never".  This is the set of mailboxes that we control
        if (mAccountObserver == null) {
            log("mAccountObserver null; service died??");
            return nextWait;
        }

        Cursor c = getContentResolver().query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                mAccountObserver.getSyncableEasMailboxWhere(), null, null);
        if (c == null) throw new ProviderUnavailableException();
        try {
            while (c.moveToNext()) {
                long mailboxId = c.getLong(Mailbox.CONTENT_ID_COLUMN);
                AbstractSyncService service = null;
                synchronized (sSyncLock) {
                    service = mServiceMap.get(mailboxId);
                }
                if (service == null) {
                    // Get the cached account
                    Account account = getAccountById(c.getInt(Mailbox.CONTENT_ACCOUNT_KEY_COLUMN));
                    if (account == null) continue;

                    // We handle a few types of mailboxes specially
                    int mailboxType = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
                    if (!isMailboxSyncable(account, mailboxType)) {
                        continue;
                    }

                    // Check whether we're in a hold (temporary or permanent)
                    SyncError syncError = mSyncErrorMap.get(mailboxId);
                    if (syncError != null) {
                        // Nothing we can do about fatal errors
                        if (syncError.fatal) continue;
                        if (now < syncError.holdEndTime) {
                            // If release time is earlier than next wait time,
                            // move next wait time up to the release time
                            if (syncError.holdEndTime < now + nextWait) {
                                nextWait = syncError.holdEndTime - now;
                                mNextWaitReason = "Release hold";
                            }
                            continue;
                        } else {
                            // Keep the error around, but clear the end time
                            syncError.holdEndTime = 0;
                        }
                    }

                    // Otherwise, we use the sync interval
                    long syncInterval = c.getInt(Mailbox.CONTENT_SYNC_INTERVAL_COLUMN);
                    if (syncInterval == Mailbox.CHECK_INTERVAL_PUSH) {
                        Mailbox m = EmailContent.getContent(c, Mailbox.class);
                        requestSync(m, SYNC_PUSH, null);
                    } else if (mailboxType == Mailbox.TYPE_OUTBOX) {
                        if (hasSendableMessages(c)) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            startServiceThread(new EasOutboxService(this, m), m);
                        }
                    } else if (syncInterval > 0 && syncInterval <= ONE_DAY_MINUTES) {
                        long lastSync = c.getLong(Mailbox.CONTENT_SYNC_TIME_COLUMN);
                        long sinceLastSync = now - lastSync;
                        long toNextSync = syncInterval*MINUTES - sinceLastSync;
                        String name = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                        if (toNextSync <= 0) {
                            Mailbox m = EmailContent.getContent(c, Mailbox.class);
                            requestSync(m, SYNC_SCHEDULED, null);
                        } else if (toNextSync < nextWait) {
                            nextWait = toNextSync;
                            if (Eas.USER_LOG) {
                                log("Next sync for " + name + " in " + nextWait/1000 + "s");
                            }
                            mNextWaitReason = "Scheduled sync, " + name;
                        } else if (Eas.USER_LOG) {
                            log("Next sync for " + name + " in " + toNextSync/1000 + "s");
                        }
                    }
                } else {
                    Thread thread = service.mThread;
                    // Look for threads that have died and remove them from the map
                    if (thread != null && !thread.isAlive()) {
                        if (Eas.USER_LOG) {
                            log("Dead thread, mailbox released: " +
                                    c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN));
                        }
                        releaseMailbox(mailboxId);
                        // Restart this if necessary
                        if (nextWait > 3*SECONDS) {
                            nextWait = 3*SECONDS;
                            mNextWaitReason = "Clean up dead thread(s)";
                        }
                    } else {
                        long requestTime = service.mRequestTime;
                        if (requestTime > 0) {
                            long timeToRequest = requestTime - now;
                            if (timeToRequest <= 0) {
                                service.mRequestTime = 0;
                                service.alarm();
                            } else if (requestTime > 0 && timeToRequest < nextWait) {
                                if (timeToRequest < 11*MINUTES) {
                                    nextWait = timeToRequest < 250 ? 250 : timeToRequest;
                                    mNextWaitReason = "Sync data change";
                                } else {
                                    log("Illegal timeToRequest: " + timeToRequest);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            c.close();
        }
        return nextWait;
    }

    static public void serviceRequest(long mailboxId, int reason) {
        serviceRequest(mailboxId, 5*SECONDS, reason);
    }

    /**
     * Return a boolean indicating whether the mailbox can be synced
     * @param m the mailbox
     * @return whether or not the mailbox can be synced
     */
    public static boolean isSyncable(Mailbox m) {
        return m.loadsFromServer(HostAuth.SCHEME_EAS);
    }

    static public void serviceRequest(long mailboxId, long ms, int reason) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
        if (m == null || !isSyncable(m)) return;
        try {
            AbstractSyncService service = exchangeService.mServiceMap.get(mailboxId);
            if (service != null) {
                service.mRequestTime = System.currentTimeMillis() + ms;
                kick("service request");
            } else {
                startManualSync(mailboxId, reason, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static public void serviceRequestImmediate(long mailboxId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        AbstractSyncService service = exchangeService.mServiceMap.get(mailboxId);
        if (service != null) {
            service.mRequestTime = System.currentTimeMillis();
            Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
            if (m != null) {
                service.mAccount = Account.restoreAccountWithId(exchangeService, m.mAccountKey);
                service.mMailbox = m;
                kick("service request immediate");
            }
        }
    }

    static public void sendMessageRequest(Request req) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        Message msg = Message.restoreMessageWithId(exchangeService, req.mMessageId);
        if (msg == null) {
            return;
        }
        long mailboxId = msg.mMailboxKey;
        AbstractSyncService service = exchangeService.mServiceMap.get(mailboxId);

        if (service == null) {
            startManualSync(mailboxId, SYNC_SERVICE_PART_REQUEST, req);
            kick("part request");
        } else {
            service.addRequest(req);
        }
    }

    /**
     * Determine whether a given Mailbox can be synced, i.e. is not already syncing and is not in
     * an error state
     *
     * @param mailboxId
     * @return whether or not the Mailbox is available for syncing (i.e. is a valid push target)
     */
    static public int pingStatus(long mailboxId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return PING_STATUS_OK;
        // Already syncing...
        if (exchangeService.mServiceMap.get(mailboxId) != null) {
            return PING_STATUS_RUNNING;
        }
        // No errors or a transient error, don't ping...
        SyncError error = exchangeService.mSyncErrorMap.get(mailboxId);
        if (error != null) {
            if (error.fatal) {
                return PING_STATUS_UNABLE;
            } else if (error.holdEndTime > 0) {
                return PING_STATUS_WAITING;
            }
        }
        return PING_STATUS_OK;
    }

    static public void startManualSync(long mailboxId, int reason, Request req) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        synchronized (sSyncLock) {
            AbstractSyncService svc = exchangeService.mServiceMap.get(mailboxId);
            if (svc == null) {
                exchangeService.mSyncErrorMap.remove(mailboxId);
                Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
                if (m != null) {
                    log("Starting sync for " + m.mDisplayName);
                    exchangeService.requestSync(m, reason, req);
                }
            } else {
                // If this is a ui request, set the sync reason for the service
                if (reason >= SYNC_CALLBACK_START) {
                    svc.mSyncReason = reason;
                }
            }
        }
    }

    // DO NOT CALL THIS IN A LOOP ON THE SERVICEMAP
    static public void stopManualSync(long mailboxId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        synchronized (sSyncLock) {
            AbstractSyncService svc = exchangeService.mServiceMap.get(mailboxId);
            if (svc != null) {
                log("Stopping sync for " + svc.mMailboxName);
                svc.stop();
                svc.mThread.interrupt();
                exchangeService.releaseWakeLock(mailboxId);
            }
        }
    }

    /**
     * Wake up ExchangeService to check for mailboxes needing service
     */
    static public void kick(String reason) {
       ExchangeService exchangeService = INSTANCE;
       if (exchangeService != null) {
            synchronized (exchangeService) {
                //INSTANCE.log("Kick: " + reason);
                exchangeService.mKicked = true;
                exchangeService.notify();
            }
        }
        if (sConnectivityLock != null) {
            synchronized (sConnectivityLock) {
                sConnectivityLock.notify();
            }
        }
    }

    static public void accountUpdated(long acctId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        synchronized (sSyncLock) {
            for (AbstractSyncService svc : exchangeService.mServiceMap.values()) {
                if (svc.mAccount.mId == acctId) {
                    svc.mAccount = Account.restoreAccountWithId(exchangeService, acctId);
                }
            }
        }
    }

    /**
     * Tell ExchangeService to remove the mailbox from the map of mailboxes with sync errors
     * @param mailboxId the id of the mailbox
     */
    static public void removeFromSyncErrorMap(long mailboxId) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.mSyncErrorMap.remove(mailboxId);
        }
    }

    private boolean isRunningInServiceThread(long mailboxId) {
        AbstractSyncService syncService = mServiceMap.get(mailboxId);
        Thread thisThread = Thread.currentThread();
        return syncService != null && syncService.mThread != null &&
            thisThread == syncService.mThread;
    }

    /**
     * Sent by services indicating that their thread is finished; action depends on the exitStatus
     * of the service.
     *
     * @param svc the service that is finished
     */
    static public void done(AbstractSyncService svc) {
        ExchangeService exchangeService = INSTANCE;
        if (exchangeService == null) return;
        synchronized(sSyncLock) {
            long mailboxId = svc.mMailboxId;
            // If we're no longer the syncing thread for the mailbox, just return
            if (!exchangeService.isRunningInServiceThread(mailboxId)) {
                return;
            }
            exchangeService.releaseMailbox(mailboxId);

            ConcurrentHashMap<Long, SyncError> errorMap = exchangeService.mSyncErrorMap;
            SyncError syncError = errorMap.get(mailboxId);

            int exitStatus = svc.mExitStatus;
            Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
            if (m == null) return;

            if (exitStatus != AbstractSyncService.EXIT_LOGIN_FAILURE) {
                long accountId = m.mAccountKey;
                Account account = Account.restoreAccountWithId(exchangeService, accountId);
                if (account == null) return;
                if (exchangeService.releaseSyncHolds(exchangeService,
                        AbstractSyncService.EXIT_LOGIN_FAILURE, account)) {
                    new AccountServiceProxy(exchangeService).notifyLoginSucceeded(accountId);
                }
            }

            switch (exitStatus) {
                case AbstractSyncService.EXIT_DONE:
                    if (svc.hasPendingRequests()) {
                        // TODO Handle this case
                    }
                    errorMap.remove(mailboxId);
                    // If we've had a successful sync, clear the shutdown count
                    synchronized (ExchangeService.class) {
                        sClientConnectionManagerShutdownCount = 0;
                    }
                    break;
                // I/O errors get retried at increasing intervals
                case AbstractSyncService.EXIT_IO_ERROR:
                    if (syncError != null) {
                        syncError.escalate();
                        log(m.mDisplayName + " held for " + syncError.holdDelay + "ms");
                    } else {
                        errorMap.put(mailboxId, exchangeService.new SyncError(exitStatus, false));
                        log(m.mDisplayName + " added to syncErrorMap, hold for 15s");
                    }
                    break;
                // These errors are not retried automatically
                case AbstractSyncService.EXIT_LOGIN_FAILURE:
                    new AccountServiceProxy(exchangeService).notifyLoginFailed(m.mAccountKey);
                    // Fall through
                case AbstractSyncService.EXIT_SECURITY_FAILURE:
                case AbstractSyncService.EXIT_ACCESS_DENIED:
                case AbstractSyncService.EXIT_EXCEPTION:
                    errorMap.put(mailboxId, exchangeService.new SyncError(exitStatus, true));
                    break;
            }
            kick("sync completed");
        }
    }

    /**
     * Given the status string from a Mailbox, return the type code for the last sync
     * @param status the syncStatus column of a Mailbox
     * @return
     */
    static public int getStatusType(String status) {
        if (status == null) {
            return -1;
        } else {
            return status.charAt(STATUS_TYPE_CHAR) - '0';
        }
    }

    /**
     * Given the status string from a Mailbox, return the change count for the last sync
     * The change count is the number of adds + deletes + changes in the last sync
     * @param status the syncStatus column of a Mailbox
     * @return
     */
    static public int getStatusChangeCount(String status) {
        try {
            String s = status.substring(STATUS_CHANGE_COUNT_OFFSET);
            return Integer.parseInt(s);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static public Context getContext() {
        return INSTANCE;
    }
}
