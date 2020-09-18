/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.session.remoting.handler;

import com.alipay.sofa.registry.common.model.Node.NodeType;
import com.alipay.sofa.registry.common.model.metaserver.GetLoadbalanceMetricsRequest;
import com.alipay.sofa.registry.common.model.metaserver.LoadbalanceMetrics;
import com.alipay.sofa.registry.remoting.Channel;
import com.alipay.sofa.registry.server.session.store.DataStore;
import com.alipay.sofa.registry.server.session.store.Interests;
import com.alipay.sofa.registry.server.session.store.Watchers;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Set;

/**
 * @author xiangxu
 * @version : LoadbalanceMetricsHandler.java, v 0.1 2020年05月27日 2:56 下午 xiangxu Exp $
 */
public class LoadbalanceMetricsHandler extends AbstractClientHandler {

    @Autowired
    private DataStore sessionDataStore;

    @Autowired
    private Interests sessionInterests;

    @Autowired
    private Watchers  sessionWatchers;

    @Override
    protected NodeType getConnectNodeType() {
        return NodeType.SESSION;
    }

    @Override
    public HandlerType getType() {
        return HandlerType.PROCESSER;
    }

    @Override
    public Object reply(Channel channel, Object message) {
        Set<String> connectionIds = new HashSet<>();
        connectionIds.addAll(sessionDataStore.getConnectPublishers().keySet());
        connectionIds.addAll(sessionInterests.getConnectSubscribers().keySet());
        connectionIds.addAll(sessionWatchers.getConnectWatchers().keySet());
        LoadbalanceMetrics m = new LoadbalanceMetrics();
        m.setConnectionCount(connectionIds.size());
        return m;
    }

    @Override
    public Class interest() {
        return GetLoadbalanceMetricsRequest.class;
    }
}