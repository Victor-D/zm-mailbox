/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2014 Zimbra Software, LLC.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.mailbox;

import net.spy.memcached.HashAlgorithm;

import org.springframework.context.annotation.Configuration;

import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Server;
import com.zimbra.cs.memcached.ZimbraMemcachedClientConfigurer;
import com.zimbra.cs.util.ZimbraConfig;

/**
 * Spring Configuration used by Memcached-based unit tests that will use a localhost-based memcached.
 */
@Configuration
public class MemcachedOnLocalhostZimbraConfig extends ZimbraConfig {

    @Override
    public boolean isRedisAvailable() throws ServiceException {
        return false;
    }

    @Override
    public ZimbraMemcachedClientConfigurer memcachedClientConfigurerBean() throws Exception {
        return new ZimbraMemcachedClientConfigurer() {
            @Override
            public void reconfigure() throws ServiceException {
                Server server = Provisioning.getInstance().getLocalServer();
                String[] serverList = {"localhost"};
                boolean useBinaryProtocol = server.getBooleanAttr(Provisioning.A_zimbraMemcachedClientBinaryProtocolEnabled, false);
                String hashAlgorithm = server.getAttr(Provisioning.A_zimbraMemcachedClientHashAlgorithm, HashAlgorithm.KETAMA_HASH.toString());
                int expirySeconds = 10;
                long timeoutMillis = 20;
                client.connect(serverList, useBinaryProtocol, hashAlgorithm, expirySeconds, timeoutMillis);
            }
        };
    }
}
