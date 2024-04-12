/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.housekeeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.org.apache.commons.lang3.RandomStringUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.metadata.TbGetAttributesNode;
import org.thingsboard.rule.engine.metadata.TbGetAttributesNodeConfiguration;
import org.thingsboard.server.common.data.ApiUsageState;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EventInfo;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.alarm.EntityAlarm;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.event.EventType;
import org.thingsboard.server.common.data.event.LifecycleEvent;
import org.thingsboard.server.common.data.housekeeper.HousekeeperTask;
import org.thingsboard.server.common.data.housekeeper.HousekeeperTaskType;
import org.thingsboard.server.common.data.id.AlarmId;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.page.TimePageLink;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.msg.housekeeper.HousekeeperClient;
import org.thingsboard.server.controller.AbstractControllerTest;
import org.thingsboard.server.dao.alarm.AlarmDao;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.entity.EntityServiceRegistry;
import org.thingsboard.server.dao.event.EventService;
import org.thingsboard.server.dao.relation.RelationService;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.dao.usagerecord.ApiUsageStateDao;
import org.thingsboard.server.gen.transport.TransportProtos.HousekeeperTaskProto;
import org.thingsboard.server.gen.transport.TransportProtos.ToHousekeeperServiceMsg;
import org.thingsboard.server.service.housekeeper.processor.TsHistoryDeletionTaskProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
@TestPropertySource(properties = {
        "transport.http.enabled=true",
        "queue.core.housekeeper.task-reprocessing-delay-ms=2000",
        "queue.core.housekeeper.poll-interval-ms=1000",
        "queue.core.housekeeper.max-reprocessing-attempts=5"
})
public class HousekeeperServiceTest extends AbstractControllerTest {

    @SpyBean
    private HousekeeperService housekeeperService;
    @SpyBean
    private HousekeeperClient housekeeperClient;
    @SpyBean
    private HousekeeperReprocessingService housekeeperReprocessingService;
    @Autowired
    private EventService eventService;
    @Autowired
    private TimeseriesService timeseriesService;
    @Autowired
    private AttributesService attributesService;
    @Autowired
    private RuleChainService ruleChainService;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private AlarmDao alarmDao;
    @Autowired
    private RelationService relationService;
    @Autowired
    private ApiUsageStateDao apiUsageStateDao;
    @Autowired
    private EntityServiceRegistry entityServiceRegistry;
    @SpyBean
    private TsHistoryDeletionTaskProcessor tsHistoryDeletionTaskProcessor;

    private TenantId tenantId;

    private static final String TELEMETRY_KEY = "telemetry1";
    private static final String ATTRIBUTE_KEY = "_attribute1";
    private static final String KV_VALUE = "ewfewfwef";

    @Before
    public void setUp() throws Exception {
        loginTenantAdmin();
        this.tenantId = super.tenantId;
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void whenDeviceIsDeleted_thenCleanUpRelatedData() throws Exception {
        Device device = createDevice("test", "test");
        createRelatedData(device.getId());

        doDelete("/api/device/" + device.getId()).andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoRelatedData(device.getId());
        });
    }

    @Test
    public void whenRuleChainIsDeleted_thenCleanUpRelatedData() throws Exception {
        RuleChainMetaData ruleChainMetaData = createRuleChain();
        RuleChainId ruleChainId = ruleChainMetaData.getRuleChainId();
        RuleNodeId ruleNode1Id = ruleChainMetaData.getNodes().get(0).getId();
        RuleNodeId ruleNode2Id = ruleChainMetaData.getNodes().get(1).getId();
        createRelatedData(ruleChainId);
        createRelatedData(ruleNode1Id);
        createRelatedData(ruleNode2Id);

        doDelete("/api/ruleChain/" + ruleChainId).andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoRelatedData(ruleNode1Id);
            verifyNoRelatedData(ruleNode2Id);
            verifyNoRelatedData(ruleChainId);
        });
    }

    @Test
    public void whenUserIsDeleted_thenCleanUpRelatedData() throws Exception {
        Device device = createDevice("test", "test");
        UserId userId = customerUserId;
        createRelatedData(userId);
        Alarm alarm = Alarm.builder()
                .type("test")
                .tenantId(tenantId)
                .originator(device.getId())
                .severity(AlarmSeverity.MAJOR)
                .build();
        alarm = doPost("/api/alarm", alarm, Alarm.class);
        AlarmId alarmId = alarm.getId();
        alarm = doPost("/api/alarm/" + alarmId + "/assign/" + userId, "", Alarm.class);
        assertThat(alarm.getAssigneeId()).isEqualTo(userId);
        assertThat(alarmService.findAlarmIdsByAssigneeId(tenantId, userId, new PageLink(100)).getData()).isNotEmpty();

        doDelete("/api/user/" + userId).andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoRelatedData(userId);
            assertThat(alarmService.findAlarmById(tenantId, alarmId).getAssigneeId()).isNull();
        });
    }

    @Test
    public void whenDeviceIsDeleted_thenDeleteAllAlarms() throws Exception {
        Device device = createDevice("test", "test");
        for (int i = 1; i <= 1000; i++) {
            createAlarm(device.getId());
        }

        doDelete("/api/device/" + device.getId()).andExpect(status().isOk());

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoAlarms(device.getId());
        });
    }

    @Test
    public void whenTenantIsDeleted_thenDeleteAllEntitiesAndCleanUpRelatedData() throws Exception {
        loginDifferentTenant();
        tenantId = differentTenantId;

        createRelatedData(tenantId);
        createDifferentTenantCustomer();
        createRelatedData(differentTenantCustomerId);
        loginDifferentTenant();

        List<DeviceId> devices = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            Device device = createDevice("test" + i, "test" + i);
            createRelatedData(device.getId());
            devices.add(device.getId());
        }

        Asset asset = createAsset();
        createRelatedData(asset.getId());
        createRelation(devices.get(0), asset.getId());
        createAlarm(devices.get(0), asset.getId());

        RuleChainMetaData ruleChainMetaData = createRuleChain();
        RuleChainId ruleChainId = ruleChainMetaData.getRuleChainId();
        RuleNodeId ruleNode1Id = ruleChainMetaData.getNodes().get(0).getId();
        RuleNodeId ruleNode2Id = ruleChainMetaData.getNodes().get(1).getId();
        createRelatedData(ruleChainId);
        createRelatedData(ruleNode1Id);
        createRelatedData(ruleNode2Id);

        UserId userId = savedDifferentTenantUser.getId();
        createRelatedData(userId);

        ApiUsageState tenantApiUsageState = apiUsageStateDao.findApiUsageStateByEntityId(differentTenantId);

        loginSysAdmin();
        deleteDifferentTenant();

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            for (DeviceId deviceId : devices) {
                verifyNoRelatedData(deviceId);
            }
            verifyNoRelatedData(asset.getId());
            verifyNoRelatedData(ruleNode1Id);
            verifyNoRelatedData(ruleNode2Id);
            verifyNoRelatedData(ruleChainId);
            verifyNoRelatedData(userId);
            verifyNoRelatedData(differentTenantCustomerId);
            verifyNoRelatedData(tenantApiUsageState.getId());
            verifyNoRelatedData(tenantId);
        });
    }

    @Test
    @Ignore // FIXME !!!
    public void whenTaskProcessingFails_thenReprocess() throws Exception {
        TimeoutException error = new TimeoutException("Test timeout");
        doThrow(error).when(tsHistoryDeletionTaskProcessor).process(any());

        Device device = createDevice("test", "test");
        createRelatedData(device.getId());

        doDelete("/api/device/" + device.getId()).andExpect(status().isOk());

        int attempts = 2;
        await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyTaskProcessing(device.getId(), HousekeeperTaskType.DELETE_TS_HISTORY, 0);
            for (int i = 1; i <= attempts; i++) {
                int attempt = i;
                verify(housekeeperReprocessingService).submitForReprocessing(argThat(getTaskMatcher(device.getId(), HousekeeperTaskType.DELETE_TS_HISTORY,
                        task -> task.getErrorsCount() > 0 && task.getAttempt() == attempt)), argThat(e -> e.getMessage().equals(error.getMessage())));
                verifyTaskProcessing(device.getId(), HousekeeperTaskType.DELETE_TS_HISTORY, attempt);
            }
        });

        assertThat(getTimeseriesHistory(device.getId())).isNotEmpty();
        doCallRealMethod().when(tsHistoryDeletionTaskProcessor).process(any());
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(getTimeseriesHistory(device.getId())).isEmpty();
        });
    }

    @Test
    @Ignore // FIXME !!!
    public void whenReprocessingAttemptsExceeded_thenReprocessOnNextStartUp() throws Exception {
        TimeoutException error = new TimeoutException("Test timeout");
        doThrow(error).when(tsHistoryDeletionTaskProcessor).process(any());

        Device device = createDevice("test", "test");
        createRelatedData(device.getId());

        doDelete("/api/device/" + device.getId()).andExpect(status().isOk());

        int maxAttempts = 5;
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            for (int i = 1; i <= maxAttempts; i++) {
                verifyTaskProcessing(device.getId(), HousekeeperTaskType.DELETE_TS_HISTORY, i);
            }
        });

        Mockito.clearInvocations(housekeeperService);
        doCallRealMethod().when(tsHistoryDeletionTaskProcessor).process(any());
        TimeUnit.SECONDS.sleep(2);
        verify(housekeeperService, never()).processTask(argThat(getTaskMatcher(device.getId(), HousekeeperTaskType.DELETE_TS_HISTORY, null)));

        housekeeperReprocessingService.cycle.set(0); // imitating start-up
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyTaskProcessing(device.getId(), HousekeeperTaskType.DELETE_TS_HISTORY, 6);
        });
    }

    private void verifyTaskProcessing(EntityId entityId, HousekeeperTaskType taskType, int expectedAttempt) throws Exception {
        try {
            verify(housekeeperService).processTask(argThat(getTaskMatcher(entityId, taskType, task -> task.getAttempt() == expectedAttempt)));
        } catch (Throwable e) {
            e.printStackTrace();
            throw e;
        }
    }

    private ArgumentMatcher<ToHousekeeperServiceMsg> getTaskMatcher(EntityId entityId, HousekeeperTaskType taskType,
                                                                    Predicate<HousekeeperTaskProto> additionalCheck) {
        return msg -> {
            HousekeeperTask task = JacksonUtil.fromString(msg.getTask().getValue(), HousekeeperTask.class);
            return task.getEntityId().equals(entityId) && task.getTaskType() == taskType && (additionalCheck == null || additionalCheck.test(msg.getTask()));
        };
    }

    private void createRelatedData(EntityId entityId) throws Exception {
        createTelemetry(entityId);
        for (AttributeScope scope : AttributeScope.values()) {
            createAttribute(entityId, scope, scope + ATTRIBUTE_KEY);
        }
        createEvent(entityId);
    }

    private void verifyNoRelatedData(EntityId entityId) throws Exception {
        assertThat(entityServiceRegistry.getServiceByEntityType(entityId.getEntityType()).findEntity(tenantId, entityId)).isEmpty();

        assertThat(getLatestTelemetry(entityId)).isNull();
        assertThat(getTimeseriesHistory(entityId)).isEmpty();
        for (AttributeScope scope : AttributeScope.values()) {
            assertThat(attributesService.findAll(tenantId, entityId, scope).get()).isEmpty();
        }
        assertThat(getEvents(entityId)).isEmpty();
        assertThat(alarmDao.findEntityAlarmRecordsByEntityId(tenantId, entityId)).isEmpty();
        verifyNoAlarms(entityId);
        assertThat(relationService.findByTo(tenantId, entityId, RelationTypeGroup.COMMON)).isEmpty();
        assertThat(relationService.findByFrom(tenantId, entityId, RelationTypeGroup.COMMON)).isEmpty();
    }

    private void verifyNoAlarms(EntityId entityId) {
        assertThat(alarmService.findAlarmIdsByOriginatorIdAndIdOffset(tenantId, entityId, null, 10)).isEmpty();
    }

    private void createAttribute(EntityId entityId, AttributeScope scope, String key) throws Exception {
        attributesService.save(tenantId, entityId, scope, new BaseAttributeKvEntry(System.currentTimeMillis(), new StringDataEntry(key, KV_VALUE))).get();
    }

    private void createTelemetry(EntityId entityId) throws Exception {
        timeseriesService.save(tenantId, entityId, new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry(TELEMETRY_KEY, KV_VALUE))).get();
    }

    private void createEvent(EntityId entityId) {
        LifecycleEvent event = LifecycleEvent.builder()
                .tenantId(tenantId)
                .entityId(entityId.getId())
                .serviceId("test")
                .lcEventType("test")
                .success(true)
                .build();
        eventService.saveAsync(event);
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> !getEvents(entityId).isEmpty());

    }

    private void createRelation(DeviceId to, AssetId from) {
        EntityRelation relation = new EntityRelation(from, to, EntityRelation.CONTAINS_TYPE, RelationTypeGroup.COMMON);
        relationService.saveRelation(tenantId, relation);
    }

    private void createAlarm(DeviceId deviceId, EntityId propagatedEntityId) {
        Alarm alarm = doPost("/api/alarm", Alarm.builder()
                .tenantId(tenantId)
                .originator(deviceId)
                .severity(AlarmSeverity.CRITICAL)
                .type("test alarm for " + deviceId)
                .propagate(true)
                .build(), Alarm.class);

        List<EntityAlarm> entityAlarms = alarmDao.findEntityAlarmRecords(tenantId, alarm.getId());
        assertThat(entityAlarms).anyMatch(entityAlarm -> entityAlarm.getEntityId().equals(deviceId) && entityAlarm.getAlarmType().equals(alarm.getType()));
        assertThat(entityAlarms).anyMatch(entityAlarm -> entityAlarm.getEntityId().equals(propagatedEntityId) && entityAlarm.getAlarmType().equals(alarm.getType()));
        assertThat(alarmService.findAlarmIdsByOriginatorIdAndIdOffset(tenantId, deviceId, null, 10)).isNotEmpty();
    }

    private void createAlarm(DeviceId deviceId) {
        Alarm alarm = doPost("/api/alarm", Alarm.builder()
                .tenantId(tenantId)
                .originator(deviceId)
                .severity(AlarmSeverity.CRITICAL)
                .type("test alarm for " + deviceId + " " + RandomStringUtils.randomAlphabetic(10))
                .build(), Alarm.class);
        assertThat(alarmService.findAlarmIdsByOriginatorIdAndIdOffset(tenantId, deviceId, null, 10)).isNotEmpty();
    }

    private TsKvEntry getLatestTelemetry(EntityId entityId) throws Exception {
        return timeseriesService.findLatest(tenantId, entityId, HousekeeperServiceTest.TELEMETRY_KEY).get().orElse(null);
    }

    private List<TsKvEntry> getTimeseriesHistory(EntityId entityId) throws Exception {
        return timeseriesService.findAll(tenantId, entityId, List.of(new BaseReadTsKvQuery(HousekeeperServiceTest.TELEMETRY_KEY, 0, System.currentTimeMillis(), 10, "DESC"))).get();
    }

    private List<EventInfo> getEvents(EntityId entityId) {
        return eventService.findEvents(tenantId, entityId, EventType.LC_EVENT, new TimePageLink(100)).getData()
                .stream().filter(event -> Optional.ofNullable(event.getBody()).map(body -> body.get("event"))
                        .map(JsonNode::asText).orElse("").equals("test"))
                .collect(Collectors.toList());
    }

    private Asset createAsset() {
        Asset asset = new Asset();
        asset.setName("test");
        asset.setType("test");
        return doPost("/api/asset", asset, Asset.class);
    }

    private RuleChainMetaData createRuleChain() {
        RuleChain ruleChain = new RuleChain();
        ruleChain.setTenantId(tenantId);
        ruleChain.setName("Test");
        ruleChain.setType(RuleChainType.CORE);
        ruleChain.setDebugMode(true);
        ruleChain.setConfiguration(JacksonUtil.newObjectNode().set("a", new TextNode("b")));
        ruleChain = ruleChainService.saveRuleChain(ruleChain);
        RuleChainId ruleChainId = ruleChain.getId();

        RuleChainMetaData metaData = new RuleChainMetaData();
        metaData.setRuleChainId(ruleChainId);

        RuleNode ruleNode1 = new RuleNode();
        ruleNode1.setName("Simple Rule Node 1");
        ruleNode1.setType(org.thingsboard.rule.engine.metadata.TbGetAttributesNode.class.getName());
        ruleNode1.setConfigurationVersion(TbGetAttributesNode.class.getAnnotation(org.thingsboard.rule.engine.api.RuleNode.class).version());
        ruleNode1.setDebugMode(true);
        TbGetAttributesNodeConfiguration configuration1 = new TbGetAttributesNodeConfiguration();
        configuration1.setServerAttributeNames(Collections.singletonList("serverAttributeKey1"));
        ruleNode1.setConfiguration(JacksonUtil.valueToTree(configuration1));

        RuleNode ruleNode2 = new RuleNode();
        ruleNode2.setName("Simple Rule Node 2");
        ruleNode2.setType(org.thingsboard.rule.engine.metadata.TbGetAttributesNode.class.getName());
        ruleNode2.setConfigurationVersion(TbGetAttributesNode.class.getAnnotation(org.thingsboard.rule.engine.api.RuleNode.class).version());
        ruleNode2.setDebugMode(true);
        TbGetAttributesNodeConfiguration configuration2 = new TbGetAttributesNodeConfiguration();
        configuration2.setServerAttributeNames(Collections.singletonList("serverAttributeKey2"));
        ruleNode2.setConfiguration(JacksonUtil.valueToTree(configuration2));

        metaData.setNodes(Arrays.asList(ruleNode1, ruleNode2));
        metaData.setFirstNodeIndex(0);
        metaData.addConnectionInfo(0, 1, TbNodeConnectionType.SUCCESS);
        ruleChainService.saveRuleChainMetaData(tenantId, metaData, Function.identity());
        return ruleChainService.loadRuleChainMetaData(tenantId, ruleChainId);
    }

}
