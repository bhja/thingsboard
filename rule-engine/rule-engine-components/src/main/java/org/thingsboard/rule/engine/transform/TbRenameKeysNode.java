/**
 * Copyright © 2016-2023 The Thingsboard Authors
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
package org.thingsboard.rule.engine.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.util.TbMsgSource;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@RuleNode(
        type = ComponentType.TRANSFORMATION,
        name = "rename keys",
        version = 1,
        configClazz = TbRenameKeysNodeConfiguration.class,
        nodeDescription = "Renames message or message metadata key names.",
        nodeDetails = "Renames key names in the message or message metadata based on the provided key names mapping. " +
                "If key to rename doesn't exists in the specified source(message or message metadata) it will be ignored.<br><br>" +
                "Output connections: <code>Success</code>, <code>Failure</code>.",
        uiResources = {"static/rulenode/rulenode-core-config.js"},
        configDirective = "tbTransformationNodeRenameKeysConfig",
        icon = "find_replace"
)
public class TbRenameKeysNode extends TbAbstractTransformNodeWithTbMsgSource {

    private TbRenameKeysNodeConfiguration config;
    private Map<String, String> renameKeysMapping;
    private TbMsgSource renameIn;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbRenameKeysNodeConfiguration.class);
        this.renameIn = config.getRenameIn();
        this.renameKeysMapping = config.getRenameKeysMapping();
        if (renameIn == null) {
            throw new TbNodeException("RenameIn can't be null! Allowed values: " + Arrays.toString(TbMsgSource.values()));
        }
        if (renameKeysMapping == null || renameKeysMapping.isEmpty()) {
            throw new TbNodeException("At least one mapping entry should be specified!");
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) throws ExecutionException, InterruptedException, TbNodeException {
        TbMsgMetaData metaDataCopy = msg.getMetaData().copy();
        String data = msg.getData();
        boolean msgChanged = false;
        switch (renameIn) {
            case METADATA:
                Map<String, String> metaDataMap = metaDataCopy.getData();
                for (Map.Entry<String, String> entry : renameKeysMapping.entrySet()) {
                    String nameKey = entry.getKey();
                    if (metaDataMap.containsKey(nameKey)) {
                        msgChanged = true;
                        metaDataMap.put(entry.getValue(), metaDataMap.get(nameKey));
                        metaDataMap.remove(nameKey);
                    }
                }
                metaDataCopy = new TbMsgMetaData(metaDataMap);
                break;
            case DATA:
                JsonNode dataNode = JacksonUtil.toJsonNode(data);
                if (dataNode.isObject()) {
                    ObjectNode msgData = (ObjectNode) dataNode;
                    for (Map.Entry<String, String> entry : renameKeysMapping.entrySet()) {
                        String nameKey = entry.getKey();
                        if (msgData.has(nameKey)) {
                            msgChanged = true;
                            msgData.set(entry.getValue(), msgData.get(nameKey));
                            msgData.remove(nameKey);
                        }
                    }
                    data = JacksonUtil.toString(msgData);
                }
                break;
            default:
                log.debug("Unexpected RenameIn value: {}. Allowed values: {}", renameIn, TbMsgSource.values());
                break;
        }
        ctx.tellSuccess(msgChanged ? TbMsg.transformMsg(msg, metaDataCopy, data) : msg);
    }

    @Override
    protected String getKeyToUpgradeFromVersionZero() {
        return "renameIn";
    }

}
