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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
public class MessageVisibilityModifierTest {
    private SqsClient sqsClient = Mockito.mock(SqsClient.class);


    private Message.Builder sqsMessageBuilder;
    private static final String HANDLER = "someHandlerURL";
    private static final String MESSAGE_ID = "someMessageId";
    private static final String SQS_URL = "someURL";
    private MessageVisibilityModifier testingInstance;

    @Before
    public void setup() {
        sqsMessageBuilder = Message.builder().messageId(MESSAGE_ID).receiptHandle(HANDLER);
        testingInstance = new MessageVisibilityModifier(new ArrayBlockingQueue<>(5), 10, sqsClient, SQS_URL);
    }

    private void setReceiveCount(int receiveCount) {
        Map<MessageSystemAttributeName, String> attributes = new HashMap<>();
        attributes.put(MessageSystemAttributeName.fromValue("ApproximateReceiveCount"), Integer.toString(receiveCount));
        sqsMessageBuilder.attributes(attributes);
    }

    private int verifyVisibilityEntry(ChangeMessageVisibilityBatchRequestEntry entry, int mustBeGreaterThanOrEqual) {
        assertEquals(MESSAGE_ID, entry.id());
        assertEquals(HANDLER, entry.receiptHandle());
        int visibilityTimeout = entry.visibilityTimeout();
        assertTrue(mustBeGreaterThanOrEqual <= visibilityTimeout);
        return visibilityTimeout;
    }

    @Test
    public void should_correctlySetVisibility_for_messages() {
        ChangeMessageVisibilityBatchRequestEntry entry = testingInstance.generateHandleRequest(sqsMessageBuilder.build());
        int mustBeGreaterThan = verifyVisibilityEntry(entry, 1);
        for (int i = 1; i < 12; ++i) {
            setReceiveCount(i);
            entry = testingInstance.generateHandleRequest(sqsMessageBuilder.build());
            mustBeGreaterThan = verifyVisibilityEntry(entry, mustBeGreaterThan);
        }
    }

    @Test
    public void should_correctlySendMessageBatch() {
        ChangeMessageVisibilityBatchRequestEntry entry = testingInstance.generateHandleRequest(sqsMessageBuilder.build());
        List<ChangeMessageVisibilityBatchRequestEntry> entries = Collections.singletonList(entry);
        testingInstance.handleRequestBatch(entries, sqsClient);
        verify(sqsClient, times(1)).changeMessageVisibilityBatch(any(ChangeMessageVisibilityBatchRequest.class));
    }
}
