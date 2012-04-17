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

package com.android.email.activity.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email.activity.ActivityHelper;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.Utility;

/**
 * Psuedo-activity (no UI) to bootstrap the user up to a higher desired security level.  This
 * bootstrap requires the following steps.
 *
 * 1.  Confirm the account of interest has any security policies defined - exit early if not
 * 2.  If not actively administrating the device, ask Device Policy Manager to start that
 * 3.  When we are actively administrating, check current policies and see if they're sufficient
 * 4.  If not, set policies
 * 5.  If necessary, request for user to update device password
 * 6.  If necessary, request for user to activate device encryption
 */
public class AccountSecurity extends Activity {
    private static final String TAG = "Email/AccountSecurity";

    private static final String EXTRA_ACCOUNT_ID = "ACCOUNT_ID";
    private static final String EXTRA_SHOW_DIALOG = "SHOW_DIALOG";
    private static final String EXTRA_PASSWORD_EXPIRING = "EXPIRING";
    private static final String EXTRA_PASSWORD_EXPIRED = "EXPIRED";

    private static final int REQUEST_ENABLE = 1;
    private static final int REQUEST_PASSWORD = 2;
    private static final int REQUEST_ENCRYPTION = 3;

    private boolean mTriedAddAdministrator = false;
    private boolean mTriedSetPassword = false;
    private boolean mTriedSetEncryption = false;
    private Account mAccount;

    /**
     * Used for generating intent for this activity (which is intended to be launched
     * from a notification.)
     *
     * @param context Calling context for building the intent
     * @param accountId The account of interest
     * @param showDialog If true, a simple warning dialog will be shown before kicking off
     * the necessary system settings.  Should be true anywhere the context of the security settings
     * is not clear (e.g. any time after the account has been set up).
     * @return an Intent which can be used to view that account
     */
    public static Intent actionUpdateSecurityIntent(Context context, long accountId,
            boolean showDialog) {
        Intent intent = new Intent(context, AccountSecurity.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_SHOW_DIALOG, showDialog);
        return intent;
    }

    /**
     * Used for generating intent for this activity (which is intended to be launched
     * from a notification.)  This is a special mode of this activity which exists only
     * to give the user a dialog (for context) about a device pin/password expiration event.
     */
    public static Intent actionDevicePasswordExpirationIntent(Context context, long accountId,
            boolean expired) {
        Intent intent = new Intent(context, AccountSecurity.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(expired ? EXTRA_PASSWORD_EXPIRED : EXTRA_PASSWORD_EXPIRING, true);
        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        final boolean showDialog = i.getBooleanExtra(EXTRA_SHOW_DIALOG, false);
        final boolean passwordExpiring = i.getBooleanExtra(EXTRA_PASSWORD_EXPIRING, false);
        final boolean passwordExpired = i.getBooleanExtra(EXTRA_PASSWORD_EXPIRED, false);
        SecurityPolicy security = SecurityPolicy.getInstance(this);
        security.clearNotification();
        if (accountId == -1) {
            finish();
            return;
        }

        mAccount = Account.restoreAccountWithId(AccountSecurity.this, accountId);
        if (mAccount == null) {
            finish();
            return;
        }
        // Special handling for password expiration events
        if (passwordExpiring || passwordExpired) {
            FragmentManager fm = getFragmentManager();
            if (fm.findFragmentByTag("password_expiration") == null) {
                PasswordExpirationDialog dialog =
                    PasswordExpirationDialog.newInstance(mAccount.getDisplayName(),
                            passwordExpired);
                dialog.show(fm, "password_expiration");
            }
            return;
        }
        // Otherwise, handle normal security settings flow
        if (mAccount.mPolicyKey != 0) {
            // This account wants to control security
            if (showDialog) {
                // Show dialog first, unless already showing (e.g. after rotation)
                FragmentManager fm = getFragmentManager();
                if (fm.findFragmentByTag("security_needed") == null) {
                    SecurityNeededDialog dialog =
                        SecurityNeededDialog.newInstance(mAccount.getDisplayName());
                    dialog.show(fm, "security_needed");
                }
            } else {
                // Go directly to security settings
                tryAdvanceSecurity(mAccount);
            }
            return;
        }
        finish();
    }

    /**
     * After any of the activities return, try to advance to the "next step"
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        tryAdvanceSecurity(mAccount);
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Walk the user through the required steps to become an active administrator and with
     * the requisite security settings for the given account.
     *
     * These steps will be repeated each time we return from a given attempt (e.g. asking the
     * user to choose a device pin/password).  In a typical activation, we may repeat these
     * steps a few times.  It may go as far as step 5 (password) or step 6 (encryption), but it
     * will terminate when step 2 (isActive()) succeeds.
     *
     * If at any point we do not advance beyond a given user step, (e.g. the user cancels
     * instead of setting a password) we simply repost the security notification, and exit.
     * We never want to loop here.
     */
    private void tryAdvanceSecurity(Account account) {
        SecurityPolicy security = SecurityPolicy.getInstance(this);
        // Step 1.  Check if we are an active device administrator, and stop here to activate
        if (!security.isActiveAdmin()) {
            if (mTriedAddAdministrator) {
                if (Email.DEBUG) {
                    Log.d(TAG, "Not active admin: repost notification");
                }
                repostNotification(account, security);
                finish();
            } else {
                mTriedAddAdministrator = true;
                // retrieve name of server for the format string
                HostAuth hostAuth = HostAuth.restoreHostAuthWithId(this, account.mHostAuthKeyRecv);
                if (hostAuth == null) {
                    if (Email.DEBUG) {
                        Log.d(TAG, "No HostAuth: repost notification");
                    }
                    repostNotification(account, security);
                    finish();
                } else {
                    if (Email.DEBUG) {
                        Log.d(TAG, "Not active admin: post initial notification");
                    }
                    // try to become active - must happen here in activity, to get result
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            security.getAdminComponent());
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            this.getString(R.string.account_security_policy_explanation_fmt,
                                    hostAuth.mAddress));
                    startActivityForResult(intent, REQUEST_ENABLE);
                }
            }
            return;
        }

        // Step 2.  Check if the current aggregate security policy is being satisfied by the
        // DevicePolicyManager (the current system security level).
        if (security.isActive(null)) {
            if (Email.DEBUG) {
                Log.d(TAG, "Security active; clear holds");
            }
            Account.clearSecurityHoldOnAllAccounts(this);
            security.clearNotification();
            finish();
            return;
        }

        // Step 3.  Try to assert the current aggregate security requirements with the system.
        security.setActivePolicies();

        // Step 4.  Recheck the security policy, and determine what changes are needed (if any)
        // to satisfy the requirements.
        int inactiveReasons = security.getInactiveReasons(null);

        // Step 5.  If password is needed, try to have the user set it
        if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_PASSWORD) != 0) {
            if (mTriedSetPassword) {
                if (Email.DEBUG) {
                    Log.d(TAG, "Password needed; repost notification");
                }
                repostNotification(account, security);
                finish();
            } else {
                if (Email.DEBUG) {
                    Log.d(TAG, "Password needed; request it via DPM");
                }
                mTriedSetPassword = true;
                // launch the activity to have the user set a new password.
                Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
                startActivityForResult(intent, REQUEST_PASSWORD);
            }
            return;
        }

        // Step 6.  If encryption is needed, try to have the user set it
        if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_ENCRYPTION) != 0) {
            if (mTriedSetEncryption) {
                if (Email.DEBUG) {
                    Log.d(TAG, "Encryption needed; repost notification");
                }
                repostNotification(account, security);
                finish();
            } else {
                if (Email.DEBUG) {
                    Log.d(TAG, "Encryption needed; request it via DPM");
                }
                mTriedSetEncryption = true;
                // launch the activity to start up encryption.
                Intent intent = new Intent(DevicePolicyManager.ACTION_START_ENCRYPTION);
                startActivityForResult(intent, REQUEST_ENCRYPTION);
            }
            return;
        }

        // Step 7.  No problems were found, so clear holds and exit
        if (Email.DEBUG) {
            Log.d(TAG, "Policies enforced; clear holds");
        }
        Account.clearSecurityHoldOnAllAccounts(this);
        security.clearNotification();
        finish();
    }

    /**
     * Mark an account as not-ready-for-sync and post a notification to bring the user back here
     * eventually.
     */
    private void repostNotification(final Account account, final SecurityPolicy security) {
        if (account == null) return;
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                security.policiesRequired(account.mId);
            }
        });
    }

    /**
     * Dialog briefly shown in some cases, to indicate the user that a security update is needed.
     * If the user clicks OK, we proceed into the "tryAdvanceSecurity" flow.  If the user cancels,
     * we repost the notification and finish() the activity.
     */
    public static class SecurityNeededDialog extends DialogFragment
            implements DialogInterface.OnClickListener {
        private static final String BUNDLE_KEY_ACCOUNT_NAME = "account_name";

        /**
         * Create a new dialog.
         */
        public static SecurityNeededDialog newInstance(String accountName) {
            final SecurityNeededDialog dialog = new SecurityNeededDialog();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_ACCOUNT_NAME, accountName);
            dialog.setArguments(b);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String accountName = getArguments().getString(BUNDLE_KEY_ACCOUNT_NAME);

            final Context context = getActivity();
            final Resources res = context.getResources();
            final AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle(R.string.account_security_dialog_title);
            b.setIconAttribute(android.R.attr.alertDialogIcon);
            b.setMessage(res.getString(R.string.account_security_dialog_content_fmt, accountName));
            b.setPositiveButton(R.string.okay_action, this);
            b.setNegativeButton(R.string.cancel_action, this);
            if (Email.DEBUG) {
                Log.d(TAG, "Posting security needed dialog");
            }
            return b.create();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            dismiss();
            AccountSecurity activity = (AccountSecurity) getActivity();
            if (activity.mAccount == null) {
                // Clicked before activity fully restored - probably just monkey - exit quickly
                activity.finish();
                return;
            }
            switch (which) {
                case DialogInterface.BUTTON_POSITIVE:
                    if (Email.DEBUG) {
                        Log.d(TAG, "User accepts; advance to next step");
                    }
                    activity.tryAdvanceSecurity(activity.mAccount);
                    break;
                case DialogInterface.BUTTON_NEGATIVE:
                    if (Email.DEBUG) {
                        Log.d(TAG, "User declines; repost notification");
                    }
                    activity.repostNotification(
                            activity.mAccount, SecurityPolicy.getInstance(activity));
                    activity.finish();
                    break;
            }
        }
    }

    /**
     * Dialog briefly shown in some cases, to indicate the user that the PIN/Password is expiring
     * or has expired.  If the user clicks OK, we launch the password settings screen.
     */
    public static class PasswordExpirationDialog extends DialogFragment
            implements DialogInterface.OnClickListener {
        private static final String BUNDLE_KEY_ACCOUNT_NAME = "account_name";
        private static final String BUNDLE_KEY_EXPIRED = "expired";

        /**
         * Create a new dialog.
         */
        public static PasswordExpirationDialog newInstance(String accountName, boolean expired) {
            final PasswordExpirationDialog dialog = new PasswordExpirationDialog();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_ACCOUNT_NAME, accountName);
            b.putBoolean(BUNDLE_KEY_EXPIRED, expired);
            dialog.setArguments(b);
            return dialog;
        }

        /**
         * Note, this actually creates two slightly different dialogs (for expiring vs. expired)
         */
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String accountName = getArguments().getString(BUNDLE_KEY_ACCOUNT_NAME);
            final boolean expired = getArguments().getBoolean(BUNDLE_KEY_EXPIRED);
            final int titleId = expired
                    ? R.string.password_expired_dialog_title
                    : R.string.password_expire_warning_dialog_title;
            final int contentId = expired
                    ? R.string.password_expired_dialog_content_fmt
                    : R.string.password_expire_warning_dialog_content_fmt;

            final Context context = getActivity();
            final Resources res = context.getResources();
            final AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle(titleId);
            b.setIconAttribute(android.R.attr.alertDialogIcon);
            b.setMessage(res.getString(contentId, accountName));
            b.setPositiveButton(R.string.okay_action, this);
            b.setNegativeButton(R.string.cancel_action, this);
            return b.create();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            dismiss();
            AccountSecurity activity = (AccountSecurity) getActivity();
            if (which == DialogInterface.BUTTON_POSITIVE) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
                activity.startActivity(intent);
            }
            activity.finish();
        }
    }
}
