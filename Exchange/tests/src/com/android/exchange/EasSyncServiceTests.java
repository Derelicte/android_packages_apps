/*
 * Copyright (C) 2009 Marc Blank
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

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Base64;

import com.android.emailcommon.provider.Account;

import org.apache.http.Header;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;

import java.io.IOException;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.EasSyncServiceTests exchange
 */
@SmallTest
public class EasSyncServiceTests extends AndroidTestCase {
    static private final String USER = "user";
    static private final String PASSWORD = "password";
    static private final String HOST = "xxx.host.zzz";
    static private final String ID = "id";

    Context mMockContext;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getContext();
    }

    public void testAddHeaders() {
        HttpRequestBase method = new HttpPost();
        EasSyncService svc = new EasSyncService();
        svc.mAuthString = "auth";
        svc.mProtocolVersion = "12.1";
        svc.mAccount = null;
        // With second argument false, there should be no header
        svc.setHeaders(method, false);
        Header[] headers = method.getHeaders("X-MS-PolicyKey");
        assertEquals(0, headers.length);
        // With second argument true, there should always be a header
        // The value will be "0" without an account
        method.removeHeaders("X-MS-PolicyKey");
        svc.setHeaders(method, true);
        headers = method.getHeaders("X-MS-PolicyKey");
        assertEquals(1, headers.length);
        assertEquals("0", headers[0].getValue());
        // With an account, but null security key, the header's value should be "0"
        Account account = new Account();
        account.mSecuritySyncKey = null;
        svc.mAccount = account;
        method.removeHeaders("X-MS-PolicyKey");
        svc.setHeaders(method, true);
        headers = method.getHeaders("X-MS-PolicyKey");
        assertEquals(1, headers.length);
        assertEquals("0", headers[0].getValue());
        // With an account and security key, the header's value should be the security key
        account.mSecuritySyncKey = "key";
        svc.mAccount = account;
        method.removeHeaders("X-MS-PolicyKey");
        svc.setHeaders(method, true);
        headers = method.getHeaders("X-MS-PolicyKey");
        assertEquals(1, headers.length);
        assertEquals("key", headers[0].getValue());
    }

    public void testGetProtocolVersionDouble() {
        assertEquals(Eas.SUPPORTED_PROTOCOL_EX2003_DOUBLE,
                Eas.getProtocolVersionDouble(Eas.SUPPORTED_PROTOCOL_EX2003));
        assertEquals(Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE,
                Eas.getProtocolVersionDouble(Eas.SUPPORTED_PROTOCOL_EX2007));
        assertEquals(Eas.SUPPORTED_PROTOCOL_EX2007_SP1_DOUBLE,
                Eas.getProtocolVersionDouble(Eas.SUPPORTED_PROTOCOL_EX2007_SP1));
    }

    private EasSyncService setupService(String user) {
        EasSyncService svc = new EasSyncService();
        svc.mUserName = user;
        svc.mPassword = PASSWORD;
        svc.mDeviceId = ID;
        svc.mHostAddress = HOST;
        return svc;
    }

    public void testMakeUriString() throws IOException {
        // Simple user name and command
        EasSyncService svc = setupService(USER);
        String uriString = svc.makeUriString("Sync", null);
        // These next two should now be cached
        assertNotNull(svc.mAuthString);
        assertNotNull(svc.mUserString);
        assertEquals("Basic " + Base64.encodeToString((USER+":"+PASSWORD).getBytes(),
                Base64.NO_WRAP), svc.mAuthString);
        assertEquals("&User=" + USER + "&DeviceId=" + ID + "&DeviceType=" +
                EasSyncService.DEVICE_TYPE, svc.mUserString);
        assertEquals("https://" + HOST + "/Microsoft-Server-ActiveSync?Cmd=Sync" +
                svc.mUserString, uriString);
        // User name that requires encoding
        String user = "name_with_underscore@foo%bar.com";
        svc = setupService(user);
        uriString = svc.makeUriString("Sync", null);
        assertEquals("Basic " + Base64.encodeToString((user+":"+PASSWORD).getBytes(),
                Base64.NO_WRAP), svc.mAuthString);
        String safeUserName = "name_with_underscore%40foo%25bar.com";
        assertEquals("&User=" + safeUserName + "&DeviceId=" + ID + "&DeviceType=" +
                EasSyncService.DEVICE_TYPE, svc.mUserString);
        assertEquals("https://" + HOST + "/Microsoft-Server-ActiveSync?Cmd=Sync" +
                svc.mUserString, uriString);
    }

    public void testResetHeartbeats() {
        EasSyncService svc = new EasSyncService();
        // Test case in which the minimum and force heartbeats need to come up
        svc.mPingMaxHeartbeat = 1000;
        svc.mPingMinHeartbeat = 200;
        svc.mPingHeartbeat = 300;
        svc.mPingForceHeartbeat = 100;
        svc.mPingHeartbeatDropped = true;
        svc.resetHeartbeats(400);
        assertEquals(400, svc.mPingMinHeartbeat);
        assertEquals(1000, svc.mPingMaxHeartbeat);
        assertEquals(400, svc.mPingHeartbeat);
        assertEquals(400, svc.mPingForceHeartbeat);
        assertFalse(svc.mPingHeartbeatDropped);

        // Test case in which the force heartbeat needs to come up
        svc.mPingMaxHeartbeat = 1000;
        svc.mPingMinHeartbeat = 200;
        svc.mPingHeartbeat = 100;
        svc.mPingForceHeartbeat = 100;
        svc.mPingHeartbeatDropped = true;
        svc.resetHeartbeats(150);
        assertEquals(200, svc.mPingMinHeartbeat);
        assertEquals(1000, svc.mPingMaxHeartbeat);
        assertEquals(150, svc.mPingHeartbeat);
        assertEquals(150, svc.mPingForceHeartbeat);
        assertFalse(svc.mPingHeartbeatDropped);

        // Test case in which the maximum needs to come down
        svc.mPingMaxHeartbeat = 1000;
        svc.mPingMinHeartbeat = 200;
        svc.mPingHeartbeat = 800;
        svc.mPingForceHeartbeat = 100;
        svc.mPingHeartbeatDropped = true;
        svc.resetHeartbeats(600);
        assertEquals(200, svc.mPingMinHeartbeat);
        assertEquals(600, svc.mPingMaxHeartbeat);
        assertEquals(600, svc.mPingHeartbeat);
        assertEquals(100, svc.mPingForceHeartbeat);
        assertFalse(svc.mPingHeartbeatDropped);
    }
}
