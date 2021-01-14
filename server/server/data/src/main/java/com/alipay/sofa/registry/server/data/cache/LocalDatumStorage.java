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
package com.alipay.sofa.registry.server.data.cache;

import com.alipay.sofa.registry.common.model.ConnectId;
import com.alipay.sofa.registry.common.model.ProcessId;
import com.alipay.sofa.registry.common.model.PublisherVersion;
import com.alipay.sofa.registry.common.model.dataserver.Datum;
import com.alipay.sofa.registry.common.model.dataserver.DatumSummary;
import com.alipay.sofa.registry.common.model.dataserver.DatumVersion;
import com.alipay.sofa.registry.common.model.slot.Slot;
import com.alipay.sofa.registry.common.model.slot.func.SlotFunction;
import com.alipay.sofa.registry.common.model.slot.func.SlotFunctionRegistry;
import com.alipay.sofa.registry.common.model.store.Publisher;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.server.data.bootstrap.DataServerConfig;
import com.alipay.sofa.registry.server.data.slot.SlotChangeListener;
import com.alipay.sofa.registry.util.ParaCheckUtil;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

/**
 * @author yuzhi.lyz
 * @version v 0.1 2020-12-02 19:40 yuzhi.lyz Exp $
 */
public final class LocalDatumStorage implements DatumStorage {
    private static final Logger                 LOGGER             = LoggerFactory
                                                                       .getLogger(LocalDatumStorage.class);

    private final SlotFunction                  slotFunction       = SlotFunctionRegistry.getFunc();
    private final Map<Integer, PublisherGroups> publisherGroupsMap = Maps.newConcurrentMap();

    @Autowired
    private DataServerConfig                    dataServerConfig;

    private PublisherGroups getPublisherGroupsOfSlot(String dataInfoId) {
        final Integer slotId = slotFunction.slotOf(dataInfoId);
        return publisherGroupsMap.get(slotId);
    }

    @Override
    public Datum get(String dataInfoId) {
        PublisherGroups groups = getPublisherGroupsOfSlot(dataInfoId);
        return groups == null ? null : groups.getDatum(dataInfoId);
    }

    @Override
    public DatumVersion getVersion(String dataInfoId) {
        PublisherGroups groups = getPublisherGroupsOfSlot(dataInfoId);
        return groups == null ? null : groups.getVersion(dataInfoId);
    }

    @Override
    public Map<String, DatumVersion> getVersions(int slotId) {
        PublisherGroups groups = publisherGroupsMap.get(slotId);
        return groups == null ? Collections.emptyMap() : groups.getVersions();
    }

    @Override
    public Map<String, Datum> getAll() {
        Map<String, Datum> m = new HashMap<>(64);
        publisherGroupsMap.values().forEach(g -> m.putAll(g.getAllDatum()));
        return m;
    }

    @Override
    public Map<String, Publisher> getByConnectId(ConnectId connectId) {
        Map<String, Publisher> m = new HashMap<>(64);
        publisherGroupsMap.values().forEach(g -> m.putAll(g.getByConnectId(connectId)));
        return m;
    }

    @Override
    public Map<String, Map<String, Publisher>> getPublishers(int slot) {
        PublisherGroups groups = publisherGroupsMap.get(slot);
        if (groups == null) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, Publisher>> map = Maps.newHashMap();
        Map<String, Datum> datumMap = groups.getAllDatum();
        datumMap.values().forEach(d -> map.put(d.getDataInfoId(), d.getPubMap()));
        return map;
    }

    @Override
    public DatumVersion putPublisher(Publisher publisher) {
        PublisherGroups groups = getPublisherGroupsOfSlot(publisher.getDataInfoId());
        return groups == null ? null : groups.putPublisher(publisher,
            dataServerConfig.getLocalDataCenter());
    }

    @Override
    public DatumVersion createEmptyDatumIfAbsent(Publisher publisher) {
        PublisherGroups groups = getPublisherGroupsOfSlot(publisher.getDataInfoId());
        return groups == null ? null : groups.createGroupIfAbsent(publisher,
            dataServerConfig.getLocalDataCenter()).getVersion();
    }

    @Override
    public Map<String, DatumVersion> clean(ProcessId sessionProcessId) {
        // clean by sessionProcessId, the sessionProcessId could not be null
        ParaCheckUtil.checkNotNull(sessionProcessId, "sessionProcessId");
        Map<String, DatumVersion> versionMap = new HashMap<>(32);
        publisherGroupsMap.values().forEach(g -> versionMap.putAll(g.clean(sessionProcessId)));
        return versionMap;
    }

    @Override
    public Map<String, DatumVersion> remove(ConnectId connectId, ProcessId sessionProcessId, long registerTimestamp) {
        // remove by client off, the sessionProcessId could not be null
        ParaCheckUtil.checkNotNull(sessionProcessId, "sessionProcessId");
        ParaCheckUtil.checkNotNull(connectId, "connectId");
        Map<String, DatumVersion> versionMap = new HashMap<>(32);
        publisherGroupsMap.values()
                .forEach(g -> versionMap.putAll(g.remove(connectId, sessionProcessId, registerTimestamp)));
        return versionMap;
    }

    // only for http testapi
    @Override
    public DatumVersion remove(String dataInfoId, ProcessId sessionProcessId) {
        // the sessionProcessId is null when the call from sync leader
        PublisherGroups groups = getPublisherGroupsOfSlot(dataInfoId);
        return groups == null ? null : groups.remove(dataInfoId, sessionProcessId);
    }

    @Override
    public DatumVersion update(String dataInfoId, List<Publisher> updatedPublishers) {
        PublisherGroups groups = getPublisherGroupsOfSlot(dataInfoId);
        return groups == null ? null : groups.update(dataInfoId, updatedPublishers,
            dataServerConfig.getLocalDataCenter());
    }

    @Override
    public DatumVersion remove(String dataInfoId, ProcessId sessionProcessId,
                               Map<String, PublisherVersion> removedPublishers) {
        // the sessionProcessId is null when the call from sync leader
        PublisherGroups groups = getPublisherGroupsOfSlot(dataInfoId);
        return groups == null ? null : groups.remove(dataInfoId, sessionProcessId,
            removedPublishers);
    }

    @Override
    public Map<String, DatumSummary> getDatumSummary(int slotId, String sessionIpAddress) {
        final PublisherGroups groups = publisherGroupsMap.get(slotId);
        return groups != null ? groups.getSummary(sessionIpAddress) : Collections.emptyMap();
    }

    @Override
    public SlotChangeListener getSlotChanngeListener() {
        return new SlotListener();
    }

    @Override
    public Set<ProcessId> getSessionProcessIds() {
        Set<ProcessId> ids = Sets.newHashSet();
        publisherGroupsMap.values().forEach(g -> ids.addAll(g.getSessionProcessIds()));
        return ids;
    }

    @Override
    public Map<String, Integer> compact(long tombstoneTimestamp) {
        Map<String, Integer> compacts = Maps.newHashMap();
        publisherGroupsMap.values().forEach(g -> compacts.putAll(g.compact(tombstoneTimestamp)));
        return compacts;
    }

    @Override
    public void updateVersion(int slotId) {
        PublisherGroups groups = publisherGroupsMap.get(slotId);
        if (groups == null) {
            return;
        }
        groups.updateVersion();
    }

    private final class SlotListener implements SlotChangeListener {

        @Override
        public void onSlotAdd(int slotId, Slot.Role role) {
            publisherGroupsMap.computeIfAbsent(slotId, k -> {
                PublisherGroups groups = new PublisherGroups();
                LOGGER.info("{} add publisherGroup {}", dataServerConfig.getLocalDataCenter(), slotId);
                return groups;
            });
        }

        @Override
        public void onSlotRemove(int slotId, Slot.Role role) {
            boolean removed = publisherGroupsMap.remove(slotId) != null;
            LOGGER.info("{}, remove publisherGroup {}, removed={}",
                dataServerConfig.getLocalDataCenter(), slotId, removed);
        }
    }

}
