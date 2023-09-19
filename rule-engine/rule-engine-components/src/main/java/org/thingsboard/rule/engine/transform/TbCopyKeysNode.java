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

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RuleNode(
        type = ComponentType.TRANSFORMATION,
        name = "copy key-values",
        version = 1,
        configClazz = TbCopyKeysNodeConfiguration.class,
        nodeDescription = "Copies key-values from message to message metadata or vice-versa.",
        nodeDetails = "Fetches key-values from message or message metadata based on the keys list specified in the configuration " +
                "and copies them into message metadata or into message itself in accordance with the fetch source. " +
                "Keys that are absent in the fetch source will be ignored. " +
                "Use regular expression(s) as a key(s) to copy keys by pattern.<br><br>" +
                "Output connections: <code>Success</code>, <code>Failure</code>.",
        uiResources = {"static/rulenode/rulenode-core-config.js"},
        configDirective = "tbTransformationNodeCopyKeysConfig",
        icon = "content_copy"
)
public class TbCopyKeysNode extends TbAbstractTransformNodeWithTbMsgSource {

    private TbCopyKeysNodeConfiguration config;
    private List<Pattern> patternKeys;
    private TbMsgSource copyFrom;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbCopyKeysNodeConfiguration.class);
        this.copyFrom = config.getCopyFrom();
        if (copyFrom == null) {
            throw new TbNodeException("CopyFrom can't be null! Allowed values: " + Arrays.toString(TbMsgSource.values()));
        }
        this.patternKeys = config.getKeys().stream().map(Pattern::compile).collect(Collectors.toList());
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) throws ExecutionException, InterruptedException, TbNodeException {
        var metaDataCopy = msg.getMetaData().copy();
        String msgData = msg.getData();
        boolean msgChanged = false;
        JsonNode dataNode = JacksonUtil.toJsonNode(msgData);
        if (dataNode.isObject()) {
            switch (copyFrom) {
                case METADATA:
                    ObjectNode msgDataNode = (ObjectNode) dataNode;
                    Map<String, String> metaDataMap = metaDataCopy.getData();
                    for (Map.Entry<String, String> entry : metaDataMap.entrySet()) {
                        String keyData = entry.getKey();
                        if (checkKey(keyData)) {
                            msgChanged = true;
                            msgDataNode.put(keyData, entry.getValue());
                        }
                    }
                    msgData = JacksonUtil.toString(msgDataNode);
                    break;
                case DATA:
                    Iterator<Map.Entry<String, JsonNode>> iteratorNode = dataNode.fields();
                    while (iteratorNode.hasNext()) {
                        Map.Entry<String, JsonNode> entry = iteratorNode.next();
                        String keyData = entry.getKey();
                        if (checkKey(keyData)) {
                            msgChanged = true;
                            metaDataCopy.putValue(keyData, JacksonUtil.toString(entry.getValue()));
                        }
                    }
                    break;
                default:
                    log.debug("Unexpected CopyFrom value: {}. Allowed values: {}", copyFrom, TbMsgSource.values());
                    break;
            }
        }
        ctx.tellSuccess(msgChanged ? TbMsg.transformMsg(msg, metaDataCopy, msgData) : msg);
    }

    @Override
    protected String getKeyToUpgradeFromVersionZero() {
        return "copyFrom";
    }

    boolean checkKey(String key) {
        return patternKeys.stream().anyMatch(pattern -> pattern.matcher(key).matches());
    }

}
