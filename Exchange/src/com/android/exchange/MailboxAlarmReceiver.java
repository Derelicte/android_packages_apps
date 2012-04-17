/*
 *  Copyright (C) 2008-2009 Marc Blank
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * MailboxAlarmReceiver is used to "wake up" the ExchangeService at the appropriate time(s).  It may
 * also be used for individual sync adapters, but this isn't implemented at the present time.
 *
 */
public class MailboxAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        long mailboxId = intent.getLongExtra("mailbox", ExchangeService.EXTRA_MAILBOX_ID);
        // EXCHANGE_SERVICE_MAILBOX_ID tells us that the service is asking to be started
        if (mailboxId == ExchangeService.EXCHANGE_SERVICE_MAILBOX_ID) {
            context.startService(new Intent(context, ExchangeService.class));
        } else {
            ExchangeService.alert(context, mailboxId);
        }
    }
}

