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

package com.android.email.mail.store;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64DataException;
import android.util.Log;

import com.android.email.Email;
import com.android.email.mail.store.ImapStore.ImapException;
import com.android.email.mail.store.ImapStore.ImapMessage;
import com.android.email.mail.store.imap.ImapConstants;
import com.android.email.mail.store.imap.ImapElement;
import com.android.email.mail.store.imap.ImapList;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapString;
import com.android.email.mail.store.imap.ImapUtility;
import com.android.email.mail.transport.CountingOutputStream;
import com.android.email.mail.transport.EOLConvertingOutputStream;
import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.BinaryTempFileBody;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeHeader;
import com.android.emailcommon.internet.MimeMultipart;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.Body;
import com.android.emailcommon.mail.FetchProfile;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

class ImapFolder extends Folder {
    private final static Flag[] PERMANENT_FLAGS =
        { Flag.DELETED, Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED };
    private static final int COPY_BUFFER_SIZE = 16*1024;

    private final ImapStore mStore;
    private final String mName;
    private int mMessageCount = -1;
    private ImapConnection mConnection;
    private OpenMode mMode;
    private boolean mExists;
    /** The local mailbox associated with this remote folder */
    Mailbox mMailbox;
    /** A set of hashes that can be used to track dirtiness */
    Object mHash[];

    /*package*/ ImapFolder(ImapStore store, String name) {
        mStore = store;
        mName = name;
    }

    private void destroyResponses() {
        if (mConnection != null) {
            mConnection.destroyResponses();
        }
    }

    @Override
    public void open(OpenMode mode)
            throws MessagingException {
        try {
            if (isOpen()) {
                if (mMode == mode) {
                    // Make sure the connection is valid.
                    // If it's not we'll close it down and continue on to get a new one.
                    try {
                        mConnection.executeSimpleCommand(ImapConstants.NOOP);
                        return;

                    } catch (IOException ioe) {
                        ioExceptionHandler(mConnection, ioe);
                    } finally {
                        destroyResponses();
                    }
                } else {
                    // Return the connection to the pool, if exists.
                    close(false);
                }
            }
            synchronized (this) {
                mConnection = mStore.getConnection();
            }
            // * FLAGS (\Answered \Flagged \Deleted \Seen \Draft NonJunk
            // $MDNSent)
            // * OK [PERMANENTFLAGS (\Answered \Flagged \Deleted \Seen \Draft
            // NonJunk $MDNSent \*)] Flags permitted.
            // * 23 EXISTS
            // * 0 RECENT
            // * OK [UIDVALIDITY 1125022061] UIDs valid
            // * OK [UIDNEXT 57576] Predicted next UID
            // 2 OK [READ-WRITE] Select completed.
            try {
                doSelect();
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            } finally {
                destroyResponses();
            }
        } catch (AuthenticationFailedException e) {
            // Don't cache this connection, so we're forced to try connecting/login again
            mConnection = null;
            close(false);
            throw e;
        } catch (MessagingException e) {
            mExists = false;
            close(false);
            throw e;
        }
    }

    @Override
    @VisibleForTesting
    public boolean isOpen() {
        return mExists && mConnection != null;
    }

    @Override
    public OpenMode getMode() {
        return mMode;
    }

    @Override
    public void close(boolean expunge) {
        // TODO implement expunge
        mMessageCount = -1;
        synchronized (this) {
            mStore.poolConnection(mConnection);
            mConnection = null;
        }
    }

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public boolean exists() throws MessagingException {
        if (mExists) {
            return true;
        }
        /*
         * This method needs to operate in the unselected mode as well as the selected mode
         * so we must get the connection ourselves if it's not there. We are specifically
         * not calling checkOpen() since we don't care if the folder is open.
         */
        ImapConnection connection = null;
        synchronized(this) {
            if (mConnection == null) {
                connection = mStore.getConnection();
            } else {
                connection = mConnection;
            }
        }
        try {
            connection.executeSimpleCommand(String.format(
                    ImapConstants.STATUS + " \"%s\" (" + ImapConstants.UIDVALIDITY + ")",
                    ImapStore.encodeFolderName(mName, mStore.mPathPrefix)));
            mExists = true;
            return true;

        } catch (MessagingException me) {
            // Treat IOERROR messaging exception as IOException
            if (me.getExceptionType() == MessagingException.IOERROR) {
                throw me;
            }
            return false;

        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);

        } finally {
            connection.destroyResponses();
            if (mConnection == null) {
                mStore.poolConnection(connection);
            }
        }
    }

    // IMAP supports folder creation
    @Override
    public boolean canCreate(FolderType type) {
        return true;
    }

    @Override
    public boolean create(FolderType type) throws MessagingException {
        /*
         * This method needs to operate in the unselected mode as well as the selected mode
         * so we must get the connection ourselves if it's not there. We are specifically
         * not calling checkOpen() since we don't care if the folder is open.
         */
        ImapConnection connection = null;
        synchronized(this) {
            if (mConnection == null) {
                connection = mStore.getConnection();
            } else {
                connection = mConnection;
            }
        }
        try {
            connection.executeSimpleCommand(String.format(ImapConstants.CREATE + " \"%s\"",
                    ImapStore.encodeFolderName(mName, mStore.mPathPrefix)));
            return true;

        } catch (MessagingException me) {
            return false;

        } catch (IOException ioe) {
            throw ioExceptionHandler(connection, ioe);

        } finally {
            connection.destroyResponses();
            if (mConnection == null) {
                mStore.poolConnection(connection);
            }
        }
    }

    @Override
    public void copyMessages(Message[] messages, Folder folder,
            MessageUpdateCallbacks callbacks) throws MessagingException {
        checkOpen();
        try {
            List<ImapResponse> responseList = mConnection.executeSimpleCommand(
                    String.format(ImapConstants.UID_COPY + " %s \"%s\"",
                            ImapStore.joinMessageUids(messages),
                            ImapStore.encodeFolderName(folder.getName(), mStore.mPathPrefix)));
            // Build a message map for faster UID matching
            HashMap<String, Message> messageMap = new HashMap<String, Message>();
            boolean handledUidPlus = false;
            for (Message m : messages) {
                messageMap.put(m.getUid(), m);
            }
            // Process response to get the new UIDs
            for (ImapResponse response : responseList) {
                // All "BAD" responses are bad. Only "NO", tagged responses are bad.
                if (response.isBad() || (response.isNo() && response.isTagged())) {
                    String responseText = response.getStatusResponseTextOrEmpty().getString();
                    throw new MessagingException(responseText);
                }
                // Skip untagged responses; they're just status
                if (!response.isTagged()) {
                    continue;
                }
                // No callback provided to report of UID changes; nothing more to do here
                // NOTE: We check this here to catch any server errors
                if (callbacks == null) {
                    continue;
                }
                ImapList copyResponse = response.getListOrEmpty(1);
                String responseCode = copyResponse.getStringOrEmpty(0).getString();
                if (ImapConstants.COPYUID.equals(responseCode)) {
                    handledUidPlus = true;
                    String origIdSet = copyResponse.getStringOrEmpty(2).getString();
                    String newIdSet = copyResponse.getStringOrEmpty(3).getString();
                    String[] origIdArray = ImapUtility.getImapSequenceValues(origIdSet);
                    String[] newIdArray = ImapUtility.getImapSequenceValues(newIdSet);
                    // There has to be a 1:1 mapping between old and new IDs
                    if (origIdArray.length != newIdArray.length) {
                        throw new MessagingException("Set length mis-match; orig IDs \"" +
                                origIdSet + "\"  new IDs \"" + newIdSet + "\"");
                    }
                    for (int i = 0; i < origIdArray.length; i++) {
                        final String id = origIdArray[i];
                        final Message m = messageMap.get(id);
                        if (m != null) {
                            callbacks.onMessageUidChange(m, newIdArray[i]);
                        }
                    }
                }
            }
            // If the server doesn't support UIDPLUS, try a different way to get the new UID(s)
            if (callbacks != null && !handledUidPlus) {
                ImapFolder newFolder = (ImapFolder)folder;
                try {
                    // Temporarily select the destination folder
                    newFolder.open(OpenMode.READ_WRITE);
                    // Do the search(es) ...
                    for (Message m : messages) {
                        String searchString = "HEADER Message-Id \"" + m.getMessageId() + "\"";
                        String[] newIdArray = newFolder.searchForUids(searchString);
                        if (newIdArray.length == 1) {
                            callbacks.onMessageUidChange(m, newIdArray[0]);
                        }
                    }
                } catch (MessagingException e) {
                    // Log, but, don't abort; failures here don't need to be propagated
                    Log.d(Logging.LOG_TAG, "Failed to find message", e);
                } finally {
                    newFolder.close(false);
                }
                // Re-select the original folder
                doSelect();
            }
        } catch (IOException ioe) {
            throw ioExceptionHandler(mConnection, ioe);
        } finally {
            destroyResponses();
        }
    }

    @Override
    public int getMessageCount() {
        return mMessageCount;
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        checkOpen();
        try {
            int unreadMessageCount = 0;
            List<ImapResponse> responses = mConnection.executeSimpleCommand(String.format(
                    ImapConstants.STATUS + " \"%s\" (" + ImapConstants.UNSEEN + ")",
                    ImapStore.encodeFolderName(mName, mStore.mPathPrefix)));
            // S: * STATUS mboxname (MESSAGES 231 UIDNEXT 44292)
            for (ImapResponse response : responses) {
                if (response.isDataResponse(0, ImapConstants.STATUS)) {
                    unreadMessageCount = response.getListOrEmpty(2)
                            .getKeyedStringOrEmpty(ImapConstants.UNSEEN).getNumberOrZero();
                }
            }
            return unreadMessageCount;
        } catch (IOException ioe) {
            throw ioExceptionHandler(mConnection, ioe);
        } finally {
            destroyResponses();
        }
    }

    @Override
    public void delete(boolean recurse) {
        throw new Error("ImapStore.delete() not yet implemented");
    }

    String[] getSearchUids(List<ImapResponse> responses) {
        // S: * SEARCH 2 3 6
        final ArrayList<String> uids = new ArrayList<String>();
        for (ImapResponse response : responses) {
            if (!response.isDataResponse(0, ImapConstants.SEARCH)) {
                continue;
            }
            // Found SEARCH response data
            for (int i = 1; i < response.size(); i++) {
                ImapString s = response.getStringOrEmpty(i);
                if (s.isString()) {
                    uids.add(s.getString());
                }
            }
        }
        return uids.toArray(Utility.EMPTY_STRINGS);
    }

    @VisibleForTesting
    String[] searchForUids(String searchCriteria) throws MessagingException {
        checkOpen();
        try {
            try {
                String command = ImapConstants.UID_SEARCH + " " + searchCriteria;
                return getSearchUids(mConnection.executeSimpleCommand(command));
            } catch (ImapException e) {
                Log.d(Logging.LOG_TAG, "ImapException in search: " + searchCriteria);
                return Utility.EMPTY_STRINGS; // not found;
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            }
        } finally {
            destroyResponses();
        }
    }

    @Override
    @VisibleForTesting
    public Message getMessage(String uid) throws MessagingException {
        checkOpen();

        String[] uids = searchForUids(ImapConstants.UID + " " + uid);
        for (int i = 0; i < uids.length; i++) {
            if (uids[i].equals(uid)) {
                return new ImapMessage(uid, this);
            }
        }
        return null;
    }

    @VisibleForTesting
    protected static boolean isAsciiString(String str) {
        int len = str.length();
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (c >= 128) return false;
        }
        return true;
    }

    /**
     * Retrieve messages based on search parameters.  We search FROM, TO, CC, SUBJECT, and BODY
     * We send: SEARCH OR FROM "foo" (OR TO "foo" (OR CC "foo" (OR SUBJECT "foo" BODY "foo"))), but
     * with the additional CHARSET argument and sending "foo" as a literal (e.g. {3}<CRLF>foo}
     */
    @Override
    @VisibleForTesting
    public Message[] getMessages(SearchParams params, MessageRetrievalListener listener)
            throws MessagingException {
        List<String> commands = new ArrayList<String>();
        String filter = params.mFilter;
        // All servers MUST accept US-ASCII, so we'll send this as the CHARSET unless we're really
        // dealing with a string that contains non-ascii characters
        String charset = "US-ASCII";
        if (!isAsciiString(filter)) {
            charset = "UTF-8";
        }
        // This is the length of the string in octets (bytes), formatted as a string literal {n}
        String octetLength = "{" + filter.getBytes().length + "}";
        // Break the command up into pieces ending with the string literal length
        commands.add(ImapConstants.UID_SEARCH + " CHARSET " + charset + " OR FROM " + octetLength);
        commands.add(filter + " (OR TO " + octetLength);
        commands.add(filter + " (OR CC " + octetLength);
        commands.add(filter + " (OR SUBJECT " + octetLength);
        commands.add(filter + " BODY " + octetLength);
        commands.add(filter + ")))");
        return getMessagesInternal(complexSearchForUids(commands), listener);
    }

    /* package */ String[] complexSearchForUids(List<String> commands) throws MessagingException {
        checkOpen();
        try {
            try {
                return getSearchUids(mConnection.executeComplexCommand(commands, false));
            } catch (ImapException e) {
                return Utility.EMPTY_STRINGS; // not found;
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            }
        } finally {
            destroyResponses();
        }
    }

    @Override
    @VisibleForTesting
    public Message[] getMessages(int start, int end, MessageRetrievalListener listener)
            throws MessagingException {
        if (start < 1 || end < 1 || end < start) {
            throw new MessagingException(String.format("Invalid range: %d %d", start, end));
        }
        return getMessagesInternal(
                searchForUids(String.format("%d:%d NOT DELETED", start, end)), listener);
    }

    @Override
    @VisibleForTesting
    public Message[] getMessages(String[] uids, MessageRetrievalListener listener)
            throws MessagingException {
        if (uids == null) {
            uids = searchForUids("1:* NOT DELETED");
        }
        return getMessagesInternal(uids, listener);
    }

    public Message[] getMessagesInternal(String[] uids, MessageRetrievalListener listener) {
        final ArrayList<Message> messages = new ArrayList<Message>(uids.length);
        for (int i = 0; i < uids.length; i++) {
            final String uid = uids[i];
            final ImapMessage message = new ImapMessage(uid, this);
            messages.add(message);
            if (listener != null) {
                listener.messageRetrieved(message);
            }
        }
        return messages.toArray(Message.EMPTY_ARRAY);
    }

    @Override
    public void fetch(Message[] messages, FetchProfile fp, MessageRetrievalListener listener)
            throws MessagingException {
        try {
            fetchInternal(messages, fp, listener);
        } catch (RuntimeException e) { // Probably a parser error.
            Log.w(Logging.LOG_TAG, "Exception detected: " + e.getMessage());
            if (mConnection != null) {
                mConnection.logLastDiscourse();
            }
            throw e;
        }
    }

    public void fetchInternal(Message[] messages, FetchProfile fp,
            MessageRetrievalListener listener) throws MessagingException {
        if (messages.length == 0) {
            return;
        }
        checkOpen();
        HashMap<String, Message> messageMap = new HashMap<String, Message>();
        for (Message m : messages) {
            messageMap.put(m.getUid(), m);
        }

        /*
         * Figure out what command we are going to run:
         * FLAGS     - UID FETCH (FLAGS)
         * ENVELOPE  - UID FETCH (INTERNALDATE UID RFC822.SIZE FLAGS BODY.PEEK[
         *                            HEADER.FIELDS (date subject from content-type to cc)])
         * STRUCTURE - UID FETCH (BODYSTRUCTURE)
         * BODY_SANE - UID FETCH (BODY.PEEK[]<0.N>) where N = max bytes returned
         * BODY      - UID FETCH (BODY.PEEK[])
         * Part      - UID FETCH (BODY.PEEK[ID]) where ID = mime part ID
         */

        final LinkedHashSet<String> fetchFields = new LinkedHashSet<String>();

        fetchFields.add(ImapConstants.UID);
        if (fp.contains(FetchProfile.Item.FLAGS)) {
            fetchFields.add(ImapConstants.FLAGS);
        }
        if (fp.contains(FetchProfile.Item.ENVELOPE)) {
            fetchFields.add(ImapConstants.INTERNALDATE);
            fetchFields.add(ImapConstants.RFC822_SIZE);
            fetchFields.add(ImapConstants.FETCH_FIELD_HEADERS);
        }
        if (fp.contains(FetchProfile.Item.STRUCTURE)) {
            fetchFields.add(ImapConstants.BODYSTRUCTURE);
        }

        if (fp.contains(FetchProfile.Item.BODY_SANE)) {
            fetchFields.add(ImapConstants.FETCH_FIELD_BODY_PEEK_SANE);
        }
        if (fp.contains(FetchProfile.Item.BODY)) {
            fetchFields.add(ImapConstants.FETCH_FIELD_BODY_PEEK);
        }

        final Part fetchPart = fp.getFirstPart();
        if (fetchPart != null) {
            String[] partIds =
                    fetchPart.getHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA);
            if (partIds != null) {
                fetchFields.add(ImapConstants.FETCH_FIELD_BODY_PEEK_BARE
                        + "[" + partIds[0] + "]");
            }
        }

        try {
            mConnection.sendCommand(String.format(
                    ImapConstants.UID_FETCH + " %s (%s)", ImapStore.joinMessageUids(messages),
                    Utility.combine(fetchFields.toArray(new String[fetchFields.size()]), ' ')
                    ), false);
            ImapResponse response;
            int messageNumber = 0;
            do {
                response = null;
                try {
                    response = mConnection.readResponse();

                    if (!response.isDataResponse(1, ImapConstants.FETCH)) {
                        continue; // Ignore
                    }
                    final ImapList fetchList = response.getListOrEmpty(2);
                    final String uid = fetchList.getKeyedStringOrEmpty(ImapConstants.UID)
                            .getString();
                    if (TextUtils.isEmpty(uid)) continue;

                    ImapMessage message = (ImapMessage) messageMap.get(uid);
                    if (message == null) continue;

                    if (fp.contains(FetchProfile.Item.FLAGS)) {
                        final ImapList flags =
                            fetchList.getKeyedListOrEmpty(ImapConstants.FLAGS);
                        for (int i = 0, count = flags.size(); i < count; i++) {
                            final ImapString flag = flags.getStringOrEmpty(i);
                            if (flag.is(ImapConstants.FLAG_DELETED)) {
                                message.setFlagInternal(Flag.DELETED, true);
                            } else if (flag.is(ImapConstants.FLAG_ANSWERED)) {
                                message.setFlagInternal(Flag.ANSWERED, true);
                            } else if (flag.is(ImapConstants.FLAG_SEEN)) {
                                message.setFlagInternal(Flag.SEEN, true);
                            } else if (flag.is(ImapConstants.FLAG_FLAGGED)) {
                                message.setFlagInternal(Flag.FLAGGED, true);
                            }
                        }
                    }
                    if (fp.contains(FetchProfile.Item.ENVELOPE)) {
                        final Date internalDate = fetchList.getKeyedStringOrEmpty(
                                ImapConstants.INTERNALDATE).getDateOrNull();
                        final int size = fetchList.getKeyedStringOrEmpty(
                                ImapConstants.RFC822_SIZE).getNumberOrZero();
                        final String header = fetchList.getKeyedStringOrEmpty(
                                ImapConstants.BODY_BRACKET_HEADER, true).getString();

                        message.setInternalDate(internalDate);
                        message.setSize(size);
                        message.parse(Utility.streamFromAsciiString(header));
                    }
                    if (fp.contains(FetchProfile.Item.STRUCTURE)) {
                        ImapList bs = fetchList.getKeyedListOrEmpty(
                                ImapConstants.BODYSTRUCTURE);
                        if (!bs.isEmpty()) {
                            try {
                                parseBodyStructure(bs, message, ImapConstants.TEXT);
                            } catch (MessagingException e) {
                                if (Logging.LOGD) {
                                    Log.v(Logging.LOG_TAG, "Error handling message", e);
                                }
                                message.setBody(null);
                            }
                        }
                    }
                    if (fp.contains(FetchProfile.Item.BODY)
                            || fp.contains(FetchProfile.Item.BODY_SANE)) {
                        // Body is keyed by "BODY[]...".
                        // Previously used "BODY[..." but this can be confused with "BODY[HEADER..."
                        // TODO Should we accept "RFC822" as well??
                        ImapString body = fetchList.getKeyedStringOrEmpty("BODY[]", true);
                        String bodyText = body.getString();
                        InputStream bodyStream = body.getAsStream();
                        message.parse(bodyStream);
                    }
                    if (fetchPart != null && fetchPart.getSize() > 0) {
                        InputStream bodyStream =
                                fetchList.getKeyedStringOrEmpty("BODY[", true).getAsStream();
                        String contentType = fetchPart.getContentType();
                        String contentTransferEncoding = fetchPart.getHeader(
                                MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING)[0];

                        // TODO Don't create 2 temp files.
                        // decodeBody creates BinaryTempFileBody, but we could avoid this
                        // if we implement ImapStringBody.
                        // (We'll need to share a temp file.  Protect it with a ref-count.)
                        fetchPart.setBody(decodeBody(bodyStream, contentTransferEncoding,
                                fetchPart.getSize(), listener));
                    }

                    if (listener != null) {
                        listener.messageRetrieved(message);
                    }
                } finally {
                    destroyResponses();
                }
            } while (!response.isTagged());
        } catch (IOException ioe) {
            throw ioExceptionHandler(mConnection, ioe);
        }
    }

    /**
     * Removes any content transfer encoding from the stream and returns a Body.
     * This code is taken/condensed from MimeUtility.decodeBody
     */
    private Body decodeBody(InputStream in, String contentTransferEncoding, int size,
            MessageRetrievalListener listener) throws IOException {
        // Get a properly wrapped input stream
        in = MimeUtility.getInputStreamForContentTransferEncoding(in, contentTransferEncoding);
        BinaryTempFileBody tempBody = new BinaryTempFileBody();
        OutputStream out = tempBody.getOutputStream();
        try {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int n = 0;
            int count = 0;
            while (-1 != (n = in.read(buffer))) {
                out.write(buffer, 0, n);
                count += n;
                if (listener != null) {
                    listener.loadAttachmentProgress(count * 100 / size);
                }
            }
        } catch (Base64DataException bde) {
            String warning = "\n\n" + Email.getMessageDecodeErrorString();
            out.write(warning.getBytes());
        } finally {
            out.close();
        }
        return tempBody;
    }

    @Override
    public Flag[] getPermanentFlags() {
        return PERMANENT_FLAGS;
    }

    /**
     * Handle any untagged responses that the caller doesn't care to handle themselves.
     * @param responses
     */
    private void handleUntaggedResponses(List<ImapResponse> responses) {
        for (ImapResponse response : responses) {
            handleUntaggedResponse(response);
        }
    }

    /**
     * Handle an untagged response that the caller doesn't care to handle themselves.
     * @param response
     */
    private void handleUntaggedResponse(ImapResponse response) {
        if (response.isDataResponse(1, ImapConstants.EXISTS)) {
            mMessageCount = response.getStringOrEmpty(0).getNumberOrZero();
        }
    }

    private static void parseBodyStructure(ImapList bs, Part part, String id)
            throws MessagingException {
        if (bs.getElementOrNone(0).isList()) {
            /*
             * This is a multipart/*
             */
            MimeMultipart mp = new MimeMultipart();
            for (int i = 0, count = bs.size(); i < count; i++) {
                ImapElement e = bs.getElementOrNone(i);
                if (e.isList()) {
                    /*
                     * For each part in the message we're going to add a new BodyPart and parse
                     * into it.
                     */
                    MimeBodyPart bp = new MimeBodyPart();
                    if (id.equals(ImapConstants.TEXT)) {
                        parseBodyStructure(bs.getListOrEmpty(i), bp, Integer.toString(i + 1));

                    } else {
                        parseBodyStructure(bs.getListOrEmpty(i), bp, id + "." + (i + 1));
                    }
                    mp.addBodyPart(bp);

                } else {
                    if (e.isString()) {
                        mp.setSubType(bs.getStringOrEmpty(i).getString().toLowerCase());
                    }
                    break; // Ignore the rest of the list.
                }
            }
            part.setBody(mp);
        } else {
            /*
             * This is a body. We need to add as much information as we can find out about
             * it to the Part.
             */

            /*
             body type
             body subtype
             body parameter parenthesized list
             body id
             body description
             body encoding
             body size
             */

            final ImapString type = bs.getStringOrEmpty(0);
            final ImapString subType = bs.getStringOrEmpty(1);
            final String mimeType =
                    (type.getString() + "/" + subType.getString()).toLowerCase();

            final ImapList bodyParams = bs.getListOrEmpty(2);
            final ImapString cid = bs.getStringOrEmpty(3);
            final ImapString encoding = bs.getStringOrEmpty(5);
            final int size = bs.getStringOrEmpty(6).getNumberOrZero();

            if (MimeUtility.mimeTypeMatches(mimeType, MimeUtility.MIME_TYPE_RFC822)) {
                // A body type of type MESSAGE and subtype RFC822
                // contains, immediately after the basic fields, the
                // envelope structure, body structure, and size in
                // text lines of the encapsulated message.
                // [MESSAGE, RFC822, [NAME, filename.eml], NIL, NIL, 7BIT, 5974, NIL,
                //     [INLINE, [FILENAME*0, Fwd: Xxx..., FILENAME*1, filename.eml]], NIL]
                /*
                 * This will be caught by fetch and handled appropriately.
                 */
                throw new MessagingException("BODYSTRUCTURE " + MimeUtility.MIME_TYPE_RFC822
                        + " not yet supported.");
            }

            /*
             * Set the content type with as much information as we know right now.
             */
            final StringBuilder contentType = new StringBuilder(mimeType);

            /*
             * If there are body params we might be able to get some more information out
             * of them.
             */
            for (int i = 1, count = bodyParams.size(); i < count; i += 2) {

                // TODO We need to convert " into %22, but
                // because MimeUtility.getHeaderParameter doesn't recognize it,
                // we can't fix it for now.
                contentType.append(String.format(";\n %s=\"%s\"",
                        bodyParams.getStringOrEmpty(i - 1).getString(),
                        bodyParams.getStringOrEmpty(i).getString()));
            }

            part.setHeader(MimeHeader.HEADER_CONTENT_TYPE, contentType.toString());

            // Extension items
            final ImapList bodyDisposition;

            if (type.is(ImapConstants.TEXT) && bs.getElementOrNone(9).isList()) {
                // If media-type is TEXT, 9th element might be: [body-fld-lines] := number
                // So, if it's not a list, use 10th element.
                // (Couldn't find evidence in the RFC if it's ALWAYS 10th element.)
                bodyDisposition = bs.getListOrEmpty(9);
            } else {
                bodyDisposition = bs.getListOrEmpty(8);
            }

            final StringBuilder contentDisposition = new StringBuilder();

            if (bodyDisposition.size() > 0) {
                final String bodyDisposition0Str =
                        bodyDisposition.getStringOrEmpty(0).getString().toLowerCase();
                if (!TextUtils.isEmpty(bodyDisposition0Str)) {
                    contentDisposition.append(bodyDisposition0Str);
                }

                final ImapList bodyDispositionParams = bodyDisposition.getListOrEmpty(1);
                if (!bodyDispositionParams.isEmpty()) {
                    /*
                     * If there is body disposition information we can pull some more
                     * information about the attachment out.
                     */
                    for (int i = 1, count = bodyDispositionParams.size(); i < count; i += 2) {

                        // TODO We need to convert " into %22.  See above.
                        contentDisposition.append(String.format(";\n %s=\"%s\"",
                                bodyDispositionParams.getStringOrEmpty(i - 1)
                                        .getString().toLowerCase(),
                                bodyDispositionParams.getStringOrEmpty(i).getString()));
                    }
                }
            }

            if ((size > 0)
                    && (MimeUtility.getHeaderParameter(contentDisposition.toString(), "size")
                            == null)) {
                contentDisposition.append(String.format(";\n size=%d", size));
            }

            if (contentDisposition.length() > 0) {
                /*
                 * Set the content disposition containing at least the size. Attachment
                 * handling code will use this down the road.
                 */
                part.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                        contentDisposition.toString());
            }

            /*
             * Set the Content-Transfer-Encoding header. Attachment code will use this
             * to parse the body.
             */
            if (!encoding.isEmpty()) {
                part.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING,
                        encoding.getString());
            }

            /*
             * Set the Content-ID header.
             */
            if (!cid.isEmpty()) {
                part.setHeader(MimeHeader.HEADER_CONTENT_ID, cid.getString());
            }

            if (size > 0) {
                if (part instanceof ImapMessage) {
                    ((ImapMessage) part).setSize(size);
                } else if (part instanceof MimeBodyPart) {
                    ((MimeBodyPart) part).setSize(size);
                } else {
                    throw new MessagingException("Unknown part type " + part.toString());
                }
            }
            part.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, id);
        }

    }

    /**
     * Appends the given messages to the selected folder. This implementation also determines
     * the new UID of the given message on the IMAP server and sets the Message's UID to the
     * new server UID.
     */
    @Override
    public void appendMessages(Message[] messages) throws MessagingException {
        checkOpen();
        try {
            for (Message message : messages) {
                // Create output count
                CountingOutputStream out = new CountingOutputStream();
                EOLConvertingOutputStream eolOut = new EOLConvertingOutputStream(out);
                message.writeTo(eolOut);
                eolOut.flush();
                // Create flag list (most often this will be "\SEEN")
                String flagList = "";
                Flag[] flags = message.getFlags();
                if (flags.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0, count = flags.length; i < count; i++) {
                        Flag flag = flags[i];
                        if (flag == Flag.SEEN) {
                            sb.append(" " + ImapConstants.FLAG_SEEN);
                        } else if (flag == Flag.FLAGGED) {
                            sb.append(" " + ImapConstants.FLAG_FLAGGED);
                        }
                    }
                    if (sb.length() > 0) {
                        flagList = sb.substring(1);
                    }
                }

                mConnection.sendCommand(
                        String.format(ImapConstants.APPEND + " \"%s\" (%s) {%d}",
                                ImapStore.encodeFolderName(mName, mStore.mPathPrefix),
                                flagList,
                                out.getCount()), false);
                ImapResponse response;
                do {
                    response = mConnection.readResponse();
                    if (response.isContinuationRequest()) {
                        eolOut = new EOLConvertingOutputStream(
                                mConnection.mTransport.getOutputStream());
                        message.writeTo(eolOut);
                        eolOut.write('\r');
                        eolOut.write('\n');
                        eolOut.flush();
                    } else if (!response.isTagged()) {
                        handleUntaggedResponse(response);
                    }
                } while (!response.isTagged());

                // TODO Why not check the response?

                /*
                 * Try to recover the UID of the message from an APPENDUID response.
                 * e.g. 11 OK [APPENDUID 2 238268] APPEND completed
                 */
                final ImapList appendList = response.getListOrEmpty(1);
                if ((appendList.size() >= 3) && appendList.is(0, ImapConstants.APPENDUID)) {
                    String serverUid = appendList.getStringOrEmpty(2).getString();
                    if (!TextUtils.isEmpty(serverUid)) {
                        message.setUid(serverUid);
                        continue;
                    }
                }

                /*
                 * Try to find the UID of the message we just appended using the
                 * Message-ID header.  If there are more than one response, take the
                 * last one, as it's most likely the newest (the one we just uploaded).
                 */
                String messageId = message.getMessageId();
                if (messageId == null || messageId.length() == 0) {
                    continue;
                }
                // Most servers don't care about parenthesis in the search query [and, some
                // fail to work if they are used]
                String[] uids = searchForUids(String.format("HEADER MESSAGE-ID %s", messageId));
                if (uids.length > 0) {
                    message.setUid(uids[0]);
                }
                // However, there's at least one server [AOL] that fails to work unless there
                // are parenthesis, so, try this as a last resort
                uids = searchForUids(String.format("(HEADER MESSAGE-ID %s)", messageId));
                if (uids.length > 0) {
                    message.setUid(uids[0]);
                }
            }
        } catch (IOException ioe) {
            throw ioExceptionHandler(mConnection, ioe);
        } finally {
            destroyResponses();
        }
    }

    @Override
    public Message[] expunge() throws MessagingException {
        checkOpen();
        try {
            handleUntaggedResponses(mConnection.executeSimpleCommand(ImapConstants.EXPUNGE));
        } catch (IOException ioe) {
            throw ioExceptionHandler(mConnection, ioe);
        } finally {
            destroyResponses();
        }
        return null;
    }

    @Override
    public void setFlags(Message[] messages, Flag[] flags, boolean value)
            throws MessagingException {
        checkOpen();

        String allFlags = "";
        if (flags.length > 0) {
            StringBuilder flagList = new StringBuilder();
            for (int i = 0, count = flags.length; i < count; i++) {
                Flag flag = flags[i];
                if (flag == Flag.SEEN) {
                    flagList.append(" " + ImapConstants.FLAG_SEEN);
                } else if (flag == Flag.DELETED) {
                    flagList.append(" " + ImapConstants.FLAG_DELETED);
                } else if (flag == Flag.FLAGGED) {
                    flagList.append(" " + ImapConstants.FLAG_FLAGGED);
                } else if (flag == Flag.ANSWERED) {
                    flagList.append(" " + ImapConstants.FLAG_ANSWERED);
                }
            }
            allFlags = flagList.substring(1);
        }
        try {
            mConnection.executeSimpleCommand(String.format(
                    ImapConstants.UID_STORE + " %s %s" + ImapConstants.FLAGS_SILENT + " (%s)",
                    ImapStore.joinMessageUids(messages),
                    value ? "+" : "-",
                    allFlags));

        } catch (IOException ioe) {
            throw ioExceptionHandler(mConnection, ioe);
        } finally {
            destroyResponses();
        }
    }

    /**
     * Persists this folder. We will always perform the proper database operation (e.g.
     * 'save' or 'update'). As an optimization, if a folder has not been modified, no
     * database operations are performed.
     */
    void save(Context context) {
        final Mailbox mailbox = mMailbox;
        if (!mailbox.isSaved()) {
            mailbox.save(context);
            mHash = mailbox.getHashes();
        } else {
            Object[] hash = mailbox.getHashes();
            if (!Arrays.equals(mHash, hash)) {
                mailbox.update(context, mailbox.toContentValues());
                mHash = hash;  // Save updated hash
            }
        }
    }

    /**
     * Selects the folder for use. Before performing any operations on this folder, it
     * must be selected.
     */
    private void doSelect() throws IOException, MessagingException {
        List<ImapResponse> responses = mConnection.executeSimpleCommand(
                String.format(ImapConstants.SELECT + " \"%s\"",
                        ImapStore.encodeFolderName(mName, mStore.mPathPrefix)));

        // Assume the folder is opened read-write; unless we are notified otherwise
        mMode = OpenMode.READ_WRITE;
        int messageCount = -1;
        for (ImapResponse response : responses) {
            if (response.isDataResponse(1, ImapConstants.EXISTS)) {
                messageCount = response.getStringOrEmpty(0).getNumberOrZero();
            } else if (response.isOk()) {
                final ImapString responseCode = response.getResponseCodeOrEmpty();
                if (responseCode.is(ImapConstants.READ_ONLY)) {
                    mMode = OpenMode.READ_ONLY;
                } else if (responseCode.is(ImapConstants.READ_WRITE)) {
                    mMode = OpenMode.READ_WRITE;
                }
            } else if (response.isTagged()) { // Not OK
                throw new MessagingException("Can't open mailbox: "
                        + response.getStatusResponseTextOrEmpty());
            }
        }
        if (messageCount == -1) {
            throw new MessagingException("Did not find message count during select");
        }
        mMessageCount = messageCount;
        mExists = true;
    }

    private void checkOpen() throws MessagingException {
        if (!isOpen()) {
            throw new MessagingException("Folder " + mName + " is not open.");
        }
    }

    private MessagingException ioExceptionHandler(ImapConnection connection, IOException ioe) {
        if (Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "IO Exception detected: ", ioe);
        }
        connection.close();
        if (connection == mConnection) {
            mConnection = null; // To prevent close() from returning the connection to the pool.
            close(false);
        }
        return new MessagingException("IO Error", ioe);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ImapFolder) {
            return ((ImapFolder)o).mName.equals(mName);
        }
        return super.equals(o);
    }

    @Override
    public Message createMessage(String uid) {
        return new ImapMessage(uid, this);
    }
}
