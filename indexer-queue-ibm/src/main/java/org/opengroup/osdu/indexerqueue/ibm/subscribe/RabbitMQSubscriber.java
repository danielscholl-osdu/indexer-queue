// Copyright 2020 IBM Corp. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.opengroup.osdu.indexerqueue.ibm.subscribe;

import com.google.common.base.Strings;
import com.google.gson.*;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.opengroup.osdu.core.common.Constants;
import org.opengroup.osdu.core.common.http.HttpClient;
import org.opengroup.osdu.core.common.http.HttpRequest;
import org.opengroup.osdu.core.common.http.HttpResponse;
import org.opengroup.osdu.core.common.model.http.AppError;
import org.opengroup.osdu.core.common.model.http.AppException;
import org.opengroup.osdu.core.common.model.http.DpsHeaders;
import org.opengroup.osdu.core.common.model.search.RecordChangedMessages;
import org.opengroup.osdu.core.ibm.messagebus.IMessageFactory;
import org.opengroup.osdu.indexerqueue.ibm.scope.ThreadDpsHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "ibm.queue", name = "manager", havingValue = "rabbitmq")
public class RabbitMQSubscriber {

    @Inject
    IMessageFactory mq;

    @Value("${INDEXER_URL}")
    private String INDEXER_URL;

    @Value("${RETRY_COUNT}")
    private int RETRY_COUNT;

    @Value("${INDEXER_API_KEY}")
    private String INDEXER_API_KEY;

    @Autowired
    ThreadDpsHeaders threadDpsHeaders;

    /*
     * false : Messages will be indexed from Queue
     * true : Messages will be indexed from TOPIC
     */
    @Value("${ibm.topic.enable:false}")
    private String topicFlag;

    private final Gson gson = new Gson();
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQSubscriber.class);

    final String INDEXER_API_KEY_HEADER = "x-api-key";

    @RabbitListener(queues = "${ibm.env.prefix}" +"-"+IMessageFactory.DEFAULT_QUEUE_NAME)
    public void receivedRabbitMessage(String msg) {

        logger.info("received Message: " + msg);

        if (topicFlag.equalsIgnoreCase("true")) {
            logger.info(
                "Indexing api will not be called or disable flag 'ibm.topic.enable'. Indexing will be happen on messages from Topic");
            return;
        }

        try {
            // handles fresh messages from os-storage service - ibm-prefix-record queue
            RecordChangedMessages recordMessage = this.getTaskQueueMessage(msg);
            logger.info("Parsed message: {}", gson.toJson(recordMessage));
            DpsHeaders dpsHeaders = getThreadDpsHeader(recordMessage);
            threadDpsHeaders.setThreadContext(dpsHeaders.getHeaders());
            dpsHeaders.getHeaders().put(DpsHeaders.ACCOUNT_ID, recordMessage.getDataPartitionId());
            if (recordMessage.hasCorrelationId()) {
                dpsHeaders.getHeaders().put(DpsHeaders.CORRELATION_ID, recordMessage.getCorrelationId());
            }
            logger.info(String.format("message headers: %s", dpsHeaders.getHeaders().toString()));

            String url = StringUtils.join(INDEXER_URL, Constants.WORKER_RELATIVE_URL);
            HttpClient httpClient = new HttpClient();
            dpsHeaders.put(INDEXER_API_KEY_HEADER, INDEXER_API_KEY);
            HttpRequest rq = HttpRequest.post(recordMessage).url(url).headers(dpsHeaders.getHeaders()).build();

            int retriesLeft = RETRY_COUNT;
            while (retriesLeft > 0) {
                HttpResponse result = null;
                try {
                    logger.info("Calling indexer API - {}", url);
                    result = httpClient.send(rq);
                    logger.info("Indexer returned with status code: {}", result.getResponseCode());
                    if (result.getResponseCode() == 200 ) {
                        logger.info("Record processed successfully!!");
                        break;
                    } else if (result.hasException() || (result.getBody() != null && !result.getBody().isEmpty())) {
                        AppError error = gson.fromJson(result.getBody(), AppError.class);
                        logger.error("Retrying to index records, indexer-service error: {}", error.getMessage());
                    } else {
                        logger.error("Retrying to index records, indexer-service returned response: {}", result.toString());
                    }
                } catch (Exception e) {
                    logger.info("Exception occurred in indexer-service : {}", e.getMessage());
//                    do not re-throw, retry until retriesLeft becomes 0
//                    throw new AppException(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getCause().getMessage(), "Failed to call Indexer service", e);
                }
                retriesLeft--;
            }

            if(retriesLeft == 0) {
                logger.info("Retry attempt exhausted, sending message to DLQ");
                mq.sendMessageDLQ(msg);
            }
        } catch (Exception ex) {
            logger.error("Sending message to DLQ, as there is error processing request with exception: {}", ex.getMessage());
            mq.sendMessageDLQ(msg);
        }
    }

    private DpsHeaders getThreadDpsHeader(RecordChangedMessages recordMessage) {
        DpsHeaders headers = getHeaders(recordMessage);
        threadDpsHeaders.setThreadContext(headers.getHeaders());
        return headers;
    }

    private RecordChangedMessages getTaskQueueMessage(String msg) {
        try {
            JsonParser jsonParser = new JsonParser();
            JsonElement jsonMessage = jsonParser.parse(msg);

            RecordChangedMessages recordChangedMessages = this.gson.fromJson(jsonMessage.toString(),
                RecordChangedMessages.class);
            String payload = recordChangedMessages.getData();
            if (Strings.isNullOrEmpty(payload)) {
                logger.error("message data not found");
                throw new AppException(HttpStatus.SC_BAD_REQUEST, "Invalid record change message",
                    "message data not found", "'message.data' not found in PubSub message");
            }

            // TODO alanbraz our messages are not encoded
            // String decodedPayload = new String(Base64.getDecoder().decode(payload));
            // recordChangedMessages.setData(decodedPayload);

            Map<String, String> attributes = recordChangedMessages.getAttributes();
            if (attributes == null || attributes.size() == 0) {
                // logger.warn("attribute map not found");
                // throw new AppException(HttpStatus.SC_BAD_REQUEST, "Invalid record change
                // message", "attribute map not found", String.format("PubSub message: %s",
                // recordChangedMessages));
                attributes = new HashMap<String, String>();
                JsonObject jsonObjectMessage = jsonMessage.getAsJsonObject();
                attributes.put(DpsHeaders.DATA_PARTITION_ID,
                    jsonObjectMessage.get(DpsHeaders.DATA_PARTITION_ID).getAsString());
                attributes.put(DpsHeaders.CORRELATION_ID,
                    jsonObjectMessage.get(DpsHeaders.CORRELATION_ID).getAsString());
                recordChangedMessages.setAttributes(attributes);
            }
            Map<String, String> lowerCase = new HashMap<>();
            attributes.forEach((key, value) -> lowerCase.put(key.toLowerCase(), value));
            recordChangedMessages.setAttributes(lowerCase);
            if (recordChangedMessages.missingAccountId()) {
                logger.warn("tenant-id missing");
                throw new AppException(HttpStatus.SC_BAD_REQUEST, "Invalid tenant", "tenant-id missing",
                    String.format("PubSub message: %s", recordChangedMessages));
            }

            return recordChangedMessages;

        } catch (JsonParseException e) {
            logger.warn("Unable to parse request payload.", e);
            throw new AppException(HttpStatus.SC_BAD_REQUEST, "Request payload parsing error",
                "Unable to parse request payload.", e);
        }
    }

    @NotNull
    private DpsHeaders getHeaders(RecordChangedMessages recordMessage) {
        DpsHeaders headers = DpsHeaders.createFromMap(recordMessage.getAttributes());
        return headers;
    }

}
