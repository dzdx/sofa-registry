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
package com.alipay.sofa.registry.test.app;

import com.alibaba.fastjson.JSON;
import com.alipay.sofa.registry.client.api.registration.PublisherRegistration;
import com.alipay.sofa.registry.client.api.registration.SubscriberRegistration;
import com.alipay.sofa.registry.common.model.AppRegisterServerDataBox;
import com.alipay.sofa.registry.common.model.constants.ValueConstants;
import com.alipay.sofa.registry.common.model.store.DataInfo;
import com.alipay.sofa.registry.common.model.store.Subscriber;
import com.alipay.sofa.registry.common.model.store.Watcher;
import com.alipay.sofa.registry.core.model.AppRevisionInterface;
import com.alipay.sofa.registry.core.model.AppRevisionRegister;
import com.alipay.sofa.registry.core.model.ScopeEnum;
import com.alipay.sofa.registry.core.model.SubscriberRegister;
import com.alipay.sofa.registry.server.test.AppDiscoveryBuilder;
import com.alipay.sofa.registry.test.BaseIntegrationTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(SpringRunner.class)
public class PubSubTest extends BaseIntegrationTest {
    @Test
    public void reportMeta() throws InterruptedException {
        String appname = "foo";
        String revision = "1111";
        AppDiscoveryBuilder builder = new AppDiscoveryBuilder(appname, revision, "127.0.0.1:12220");
        builder.addService("func1", ValueConstants.DEFAULT_GROUP,
            ValueConstants.DEFAULT_INSTANCE_ID);
        builder.addService("func2", ValueConstants.DEFAULT_GROUP,
            ValueConstants.DEFAULT_INSTANCE_ID);
        builder.addMetaBaseParam("metaParam1", "metaValue1");
        builder.addMetaInterfaceParam("func1", "metaParam2", " metaValue2");
        builder.addMetaInterfaceParam("func2", "metaParam3", " metaValue3");
        builder.addDataBaseParam("dataParam1", "dataValue1");
        builder.addDataInterfaceParam("func1", "dataParam2", "dataValue2");
        builder.addDataInterfaceParam("func2", "dataParam3", "dataValue3");

        PublisherRegistration registration = new PublisherRegistration(appname);

        registration.setPreRequest(builder.buildAppRevision());

        registryClient1.register(registration, JSON.toJSONString(builder.buildData()));

        MySubscriberDataObserver observer = new MySubscriberDataObserver();
        SubscriberRegistration subReg = new SubscriberRegistration("func1", observer);
        subReg.setScopeEnum(ScopeEnum.dataCenter);
        registryClient2.register(subReg);
        Thread.sleep(10000L);

        assertEquals("func1", observer.dataId);
        assertEquals(LOCAL_REGION, observer.userData.getLocalZone());
        assertEquals(1, observer.userData.getZoneData().size());
        assertEquals(1, observer.userData.getZoneData().values().size());
        assertTrue(observer.userData.getZoneData().containsKey(LOCAL_REGION));
        assertEquals(1, observer.userData.getZoneData().get(LOCAL_REGION).size());
    }
}
