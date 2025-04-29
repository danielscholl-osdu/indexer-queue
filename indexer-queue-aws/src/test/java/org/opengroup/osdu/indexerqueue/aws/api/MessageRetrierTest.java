/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengroup.osdu.indexerqueue.aws.api;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

@RunWith(MockitoJUnitRunner.class)
public class MessageRetrierTest {


    private SqsClient sqsClient = Mockito.mock(SqsClient.class);

    private Message sqsMessage;
    private static final String MESSAGE_BODY = "someMessageBody";
    private static final Map<String, MessageAttributeValue> MESSAGE_ATTRIBUTES = new HashMap<>();
    private static final String SQS_URL = "someURL";
    private MessageRetrier testingInstance;

    @Before
    public void setup() {
        MESSAGE_ATTRIBUTES.put("someAttribute", MessageAttributeValue.builder().stringValue("someValue").build());
        sqsMessage = Message.builder().body(MESSAGE_BODY).messageAttributes(MESSAGE_ATTRIBUTES).build();
        testingInstance = new MessageRetrier(new ArrayBlockingQueue<>(5), 5, sqsClient, SQS_URL);
    }

    @Test
    public void should_createRequestBatchEntry_successfully() {
        SendMessageBatchRequestEntry entry = testingInstance.generateHandleRequest(sqsMessage);
        String body = entry.messageBody();
        Map<String, MessageAttributeValue> attributes = entry.messageAttributes();
        assertEquals(MESSAGE_BODY, body);
        assertEquals(MESSAGE_ATTRIBUTES.size(), attributes.size());
        for (String key : MESSAGE_ATTRIBUTES.keySet()) {
            assertEquals(MESSAGE_ATTRIBUTES.get(key).stringValue(), attributes.get(key).stringValue());
        }
    }

    @Test
    public void should_sendMessageBatchOver_successfully() {
        SendMessageBatchRequestEntry entry = Mockito.mock(SendMessageBatchRequestEntry.class);
        List<SendMessageBatchRequestEntry> entries = Collections.singletonList(entry);
        SendMessageBatchResponse batchResult = Mockito.mock(SendMessageBatchResponse.class);
        List<BatchResultErrorEntry> emptyList = Collections.emptyList();
        List<BatchResultErrorEntry> nonEmptyList = Collections.singletonList(Mockito.mock(BatchResultErrorEntry.class));
        when(batchResult.failed()).thenReturn(emptyList).thenReturn(nonEmptyList);
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class))).thenReturn(batchResult);
        testingInstance.handleRequestBatch(entries, sqsClient);
        testingInstance.handleRequestBatch(entries, sqsClient);
        verify(sqsClient, times(2)).sendMessageBatch(any(SendMessageBatchRequest.class));
    }
}
