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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

@RunWith(MockitoJUnitRunner.class)
public class MessageDeleterTest {

    private final SqsClient sqsClient = Mockito.mock(SqsClient.class);

    @Captor
    private ArgumentCaptor<DeleteMessageBatchRequest> requestCaptor;

    private Message sqsMessage;
    private static final String HANDLER = "someHandlerURL";
    private static final String MESSAGE_ID = "someMessageId";
    private static final String SQS_URL = "someSQSURL";
    private MessageDeleter testingInstance;

    @Before
    public void setup() {
        sqsMessage = Message.builder().messageId(MESSAGE_ID).receiptHandle(HANDLER).build();
        testingInstance = new MessageDeleter(new ArrayBlockingQueue<>(5), 5, sqsClient, SQS_URL);
    }

    @Test
    public void should_createMessageDeleteBatchEntry_correctly() {
        DeleteMessageBatchRequestEntry deleteEntry = testingInstance.generateHandleRequest(sqsMessage);
        assertEquals(HANDLER, deleteEntry.receiptHandle());
        assertEquals(MESSAGE_ID, deleteEntry.id());
    }

    @Test
    public void should_deleteMessageBatchEntry_correctly() {
        DeleteMessageBatchRequestEntry entry = Mockito.mock(DeleteMessageBatchRequestEntry.class);
        testingInstance.handleRequestBatch(Collections.singletonList(entry), sqsClient);
        verify(sqsClient, times(1)).deleteMessageBatch(requestCaptor.capture());
        DeleteMessageBatchRequest request = requestCaptor.getValue();
        assertEquals(1, request.entries().size());
        assertEquals(entry, request.entries().get(0));
    }
}
