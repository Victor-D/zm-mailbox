/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2010, 2011 Zimbra, Inc.
 *
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.milter;

import java.io.IOException;
import java.net.Socket;

import com.zimbra.common.util.ZimbraLog;
import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.mina.MinaHandler;
import com.zimbra.cs.mina.MinaSession;

final class MinaMilterHandler extends MilterHandler implements MinaHandler {
    private final MinaSession session;

    MinaMilterHandler(MinaMilterServer server, MinaSession session) {
        super(server);
        this.session = session;
    }

    @Override
    public void connectionClosed() throws IOException {
        ZimbraLog.milter.info(sessPrefix + "Connection closed");
        session.close();
    }

    @Override
    public void connectionIdle() throws IOException {
        notifyIdleConnection();
    }

    @Override
    public void connectionOpened() throws IOException {
        newSession();
        ZimbraLog.milter.info(sessPrefix + "Connection opened");
    }

    @Override
    public void messageReceived(Object msg) throws IOException {
        MilterPacket command = (MilterPacket) msg;

        try {
            MilterPacket response = processCommand(command);
            if (response != null) {
                session.send(response);
            }
        } catch (ServiceException e) {
            ZimbraLog.milter.error(sessPrefix + "Server error: " + e.getMessage());
            dropConnection(); // aborting the session
        }
    }

    @Override
    protected boolean setupConnection(Socket connection) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean authenticate() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean processCommand() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropConnection() {
        if (!session.isClosed()) {
            session.close();
        }
    }

    @Override
    protected void notifyIdleConnection() {
        ZimbraLog.milter.debug(sessPrefix + "Dropping connection for inactivity");
        dropConnection();
    }
}
