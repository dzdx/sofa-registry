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
package com.alipay.sofa.registry.server.session.scheduler.task;

import com.alipay.sofa.registry.common.model.dataserver.Datum;
import com.alipay.sofa.registry.common.model.sessionserver.DataChangeRequest;
import com.alipay.sofa.registry.common.model.store.BaseInfo.ClientVersion;
import com.alipay.sofa.registry.common.model.store.Subscriber;
import com.alipay.sofa.registry.common.model.store.URL;
import com.alipay.sofa.registry.core.model.AssembleType;
import com.alipay.sofa.registry.core.model.ScopeEnum;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.server.session.assemble.SubscriberAssembleStrategy;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.cache.AppRevisionCacheRegistry;
import com.alipay.sofa.registry.server.session.cache.SessionDatumCacheDecorator;
import com.alipay.sofa.registry.server.session.push.FirePushService;
import com.alipay.sofa.registry.server.session.scheduler.ExecutorManager;
import com.alipay.sofa.registry.server.session.store.Interests;
import com.alipay.sofa.registry.server.session.store.ReSubscribers;
import com.alipay.sofa.registry.task.batcher.TaskProcessor.ProcessingResult;
import com.alipay.sofa.registry.task.listener.TaskEvent;
import com.alipay.sofa.registry.task.listener.TaskListenerManager;
import org.springframework.util.CollectionUtils;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * @author shangyu.wh
 * @version $Id: DataChangeFetchTask.java, v 0.1 2017-12-13 12:25 shangyu.wh Exp $
 */
public class DataChangeFetchTask extends AbstractSessionTask {

    private final static Logger              LOGGER = LoggerFactory
                                                        .getLogger(DataChangeFetchTask.class);

    private final SessionServerConfig        sessionServerConfig;

    /**
     * trigger task com.alipay.sofa.registry.server.meta.listener process
     */
    private final TaskListenerManager        taskListenerManager;

    private final ExecutorManager            executorManager;

    private DataChangeRequest                dataChangeRequest;

    private final Interests                  sessionInterests;

    private final SessionDatumCacheDecorator sessionDatumCacheDecorator;

    private final FirePushService            firePushService;

    private final SubscriberAssembleStrategy subscriberAssembleStrategy;

    private final AppRevisionCacheRegistry   appRevisionCacheRegistry;

    public DataChangeFetchTask(SessionServerConfig sessionServerConfig,
                               TaskListenerManager taskListenerManager,
                               ExecutorManager executorManager, Interests sessionInterests,
                               SubscriberAssembleStrategy subscriberAssembleStrategy,
                               SessionDatumCacheDecorator sessionDatumCacheDecorator,
                               AppRevisionCacheRegistry appRevisionCacheRegistry,
                               FirePushService firePushService) {
        this.sessionServerConfig = sessionServerConfig;
        this.taskListenerManager = taskListenerManager;
        this.executorManager = executorManager;
        this.sessionInterests = sessionInterests;
        this.subscriberAssembleStrategy = subscriberAssembleStrategy;
        this.appRevisionCacheRegistry = appRevisionCacheRegistry;
        this.sessionDatumCacheDecorator = sessionDatumCacheDecorator;
        this.firePushService = firePushService;
    }

    @Override
    public void execute() {
        doExecute(dataChangeRequest.getDataInfoId());
    }

    private void doExecute(String dataInfoId) {
        String localDataCenterID = sessionServerConfig.getSessionServerDataCenter();
        boolean ifLocalDataCenter = localDataCenterID.equals(dataChangeRequest.getDataCenter());

        for (ScopeEnum scopeEnum : ScopeEnum.values()) {
            Map<InetSocketAddress, Map<String, Subscriber>> map = getCache(dataInfoId, scopeEnum);
            if (CollectionUtils.isEmpty(map)) {
                continue;
            }

            LOGGER.info("Get all subscribers to send from cache size:{},which dataInfoId:{} on dataCenter:{},scope:{}",
                    map.size(), dataInfoId, dataChangeRequest.getDataCenter(), scopeEnum);
            for (Entry<InetSocketAddress, Map<String, Subscriber>> entry : map.entrySet()) {
                Map<String, Subscriber> subscriberMap = entry.getValue();

                if (CollectionUtils.isEmpty(subscriberMap)) {
                    continue;
                }

                //check subscriber push version
                Collection<Subscriber> subscribers = subscribersVersionCheck(subscriberMap
                        .values());
                if (subscribers.isEmpty()) {
                    continue;
                }

                //remove stopPush subscriber avoid push duplicate
                evictReSubscribers(subscribers);

                List<String> subscriberRegisterIdList = new ArrayList<>(subscriberMap.keySet());

                for (AssembleType assembleType : AssembleType.values()) {

                    List<Subscriber> subscribersSend = subscribers.stream().filter(
                            subscriber -> subscriber.getAssembleType() == assembleType)
                            .collect(Collectors.toList());

                    if (subscribersSend.isEmpty()) {
                        continue;
                    }
                    Subscriber defaultSubscriber = subscribersSend.stream().findFirst().get();
                    Datum datum = subscriberAssembleStrategy.assembleDatum(assembleType,
                            sessionServerConfig.getSessionServerDataCenter(),
                            defaultSubscriber);

                    if (datum == null) {
                        LOGGER.error("Get publisher data error,which dataInfoId:"
                                + dataInfoId + " on dataCenter:"
                                + dataChangeRequest.getDataCenter());
                        continue;
                    }
                    PushTaskClosure pushTaskClosure = getTaskClosure(dataInfoId, datum.getVersion());

                    switch (scopeEnum) {
                        case zone:
                        case dataCenter:
                            if (!ifLocalDataCenter) {
                                break;
                            }
                            Subscriber subscriber = subscriberMap.values().iterator().next();
                            boolean isOldVersion = !ClientVersion.StoreData.equals(subscriber
                                    .getClientVersion());
                            if (isOldVersion) {
                                firePushService.fireUserDataElementPushTask(new URL(entry.getKey()), datum, subscribersSend, pushTaskClosure, scopeEnum);
                            } else {
                                firePushService.fireReceivedDataMultiPushTask(datum, subscriberRegisterIdList, subscribersSend,
                                        scopeEnum, subscriber, pushTaskClosure);
                            }
                            break;
                        case global:
                            firePushService.fireReceivedDataMultiPushTask(datum, subscriberRegisterIdList,
                                    subscribersSend, scopeEnum, defaultSubscriber, pushTaskClosure);
                            break;
                        default:
                            LOGGER.warn("unknown scope, {}", scopeEnum);
                    }
                    pushTaskClosure.start();
                }
            }
        }
    }

    private Collection<Subscriber> subscribersVersionCheck(Collection<Subscriber> subscribers) {
        Collection<Subscriber> subscribersSend = new ArrayList<>();
        for (Subscriber subscriber : subscribers) {
            if (subscriber.checkVersion(dataChangeRequest.getDataCenter(),
                dataChangeRequest.getVersion())) {
                subscribersSend.add(subscriber);
            }
        }
        return subscribersSend;
    }

    public PushTaskClosure getTaskClosure(String dataInfoId, Long version) {
        //this for all this dataInfoId push result get and call back to change version
        PushTaskClosure pushTaskClosure = new PushTaskClosure(executorManager.getPushTaskCheckAsyncHashedWheelTimer(),
                sessionServerConfig, dataInfoId);
        pushTaskClosure.setTaskClosure((status, task) -> {
            String dataCenter = dataChangeRequest.getDataCenter();
            Long changeVersion = dataChangeRequest.getVersion();
            if (status == ProcessingResult.Success) {

                if (sessionServerConfig.isStopPushSwitch()) {
                    LOGGER.info("Stop Push switch on, dataCenter:{}, dataInfoId:{}, changeVersion:{}, pushVersion:{}, can not be update!",
                            dataCenter, dataInfoId, changeVersion, version);
                    return;
                }
                boolean result = sessionInterests.checkAndUpdateInterestVersions(dataCenter, dataInfoId, version);
                if (result) {
                    LOGGER.info("Push all tasks success, dataCenter:{}, dataInfoId:{}, changeVersion:{}, pushVersion:{}, update!",
                            dataCenter,
                            dataInfoId, changeVersion, version);
                } else {
                    LOGGER.info("Push all tasks success,but dataCenter:{} dataInfoId:{} version:{} need not update!",
                            dataCenter, dataInfoId, version);
                    LOGGER.info(
                            "Push all tasks success, but dataCenter:{}, dataInfoId:{}, changeVersion:{}, pushVersion:{}, need not update!",
                            dataCenter, dataInfoId, changeVersion, version);
                }
            } else {
                LOGGER.warn(
                        "Push tasks found error, subscribers version can not be update! dataCenter:{}, dataInfoId:{}, changeVersion:{}, "
                                + "pushVersion:{}",
                        dataCenter, dataInfoId, changeVersion, version);
            }
        });
        return pushTaskClosure;
    }

    private void evictReSubscribers(Collection<Subscriber> subscribersPush) {
        if (this.sessionInterests instanceof ReSubscribers) {
            ReSubscribers reSubscribers = (ReSubscribers) sessionInterests;
            subscribersPush.forEach(reSubscribers::deleteReSubscriber);
        }
    }

    private Map<InetSocketAddress, Map<String, Subscriber>> getCache(String dataInfoId,
                                                                     ScopeEnum scopeEnum) {
        return sessionInterests.querySubscriberIndex(dataInfoId, scopeEnum);
    }

    @Override
    public long getExpiryTime() {
        return -1;
    }

    @Override
    public void setTaskEvent(TaskEvent taskEvent) {
        //taskId create from event
        if (taskEvent.getTaskId() != null) {
            setTaskId(taskEvent.getTaskId());
        }

        Object obj = taskEvent.getEventObj();

        if (!(obj instanceof DataChangeRequest)) {
            throw new IllegalArgumentException("Input task event object error!");
        }

        this.dataChangeRequest = (DataChangeRequest) obj;
    }

    /**
     * Setter method for property <tt>dataChangeRequest</tt>.
     *
     * @param dataChangeRequest value to be assigned to property dataChangeRequest
     */
    public void setDataChangeRequest(DataChangeRequest dataChangeRequest) {
        this.dataChangeRequest = dataChangeRequest;
    }

    @Override
    public boolean checkRetryTimes() {
        return checkRetryTimes(sessionServerConfig.getDataChangeFetchTaskRetryTimes());
    }

    @Override
    public String toString() {
        return "DATA_CHANGE_FETCH_TASK{" + "taskId='" + getTaskId() + '\'' + ", dataChangeRequest="
               + dataChangeRequest + ", expiryTime='" + getExpiryTime() + '\'' + '}';
    }
}