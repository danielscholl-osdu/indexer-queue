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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RunWith(MockitoJUnitRunner.class)
public class WorkerThreadTest {
    private static final int MAX_MESSAGES = 10;
    private final BlockingQueue<Message> incomingMessages = new ArrayBlockingQueue<>(MAX_MESSAGES);
    private final BlockingQueue<Message> deleteMessages = new ArrayBlockingQueue<>(MAX_MESSAGES);
    private final BlockingQueue<Message> retryMessages = new ArrayBlockingQueue<>(MAX_MESSAGES);
    private final BlockingQueue<Message> visibilityMessages = new ArrayBlockingQueue<>(MAX_MESSAGES);
    private final ExecutorService executorService = Executors.newFixedThreadPool(MAX_MESSAGES);

    private static final int MAX_WAIT_TIME = 1000;
    private static final String TARGET_URL = "someTargetURL";
    private static final String NEWINDEX_URL = String.format("%s/%s", TARGET_URL, WorkerThread.NEWINDEX_URL_PATH);
    private static final String REINDEX_URL = String.format("%s/%s", TARGET_URL, WorkerThread.REINDEX_URL_PATH);
    private static final String AUTHORIZED_TOKEN = "someAuthorizedToken";
    private static final String AUTHORIZED_KEY = "authorization";
    private static final String REINDEX_KEY = "ReIndexCursor";

    private void cancelHandler(Future<?> handlerFuture) throws InterruptedException {
        handlerFuture.cancel(true);
        // Should take a small amount of time to wait for the thread to loop.
        Thread.sleep(2 * MAX_WAIT_TIME);
        assertTrue(handlerFuture.isDone());
    }

    private WorkerThread getWorker() {
        return new WorkerThread(incomingMessages, retryMessages, deleteMessages, visibilityMessages, executorService, TARGET_URL, MAX_WAIT_TIME, MAX_WAIT_TIME);
    }

    @Test
    public void should_sendUnauthorizedMessages_to_retryQueue() throws InterruptedException {
        String unauthorizedId = "Unauthorized Message ID";
        Message unauthorizedMessage = Message.builder().messageId(unauthorizedId).build();
        WorkerThread testingImplementation = getWorker();
        Future<?> workerThreadExecutor = executorService.submit(testingImplementation);
        incomingMessages.add(unauthorizedMessage);

        Thread.sleep(MAX_WAIT_TIME);
        cancelHandler(workerThreadExecutor);

        assertEquals(0, incomingMessages.size());
        assertEquals(1, deleteMessages.size());
        assertEquals(1, retryMessages.size());
        assertEquals(0, visibilityMessages.size());

        Message receivedMessage = deleteMessages.take();
        assertEquals(unauthorizedId, receivedMessage.messageId());
        receivedMessage = retryMessages.take();
        assertEquals(unauthorizedId, receivedMessage.messageId());
    }

    private Message getAuthorizedMessage(String messageId) {
        return getAuthorizedMessage(messageId, null);
    }

    private Message getAuthorizedMessage(String messageId, String reIndexCursor) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        messageAttributes.put(AUTHORIZED_KEY, MessageAttributeValue.builder().stringValue(AUTHORIZED_TOKEN).build());
        if (reIndexCursor != null) {
            messageAttributes.put(REINDEX_KEY, MessageAttributeValue.builder().stringValue(reIndexCursor).build());
        }
        return Message.builder().messageAttributes(messageAttributes).messageId(messageId).build();
    }

    @Test
    public void should_processNewMessage_correctly() throws InterruptedException {
        String newIndexMessageId = "newIndexMessageId";
        Message message = getAuthorizedMessage(newIndexMessageId);
        Future<?> workerThreadExecutor = executorService.submit(() -> {
            try (MockedConstruction<NewIndexProcessor> newIndexProcessor = Mockito.mockConstruction(NewIndexProcessor.class, (mock, context) -> {
                when(mock.getResult()).thenReturn(CallableResult.PASS);
                when(mock.call()).thenReturn(mock);
            })) {
                WorkerThread testingImplementation = getWorker();
                testingImplementation.run();
            }
        });
        incomingMessages.add(message);

        Thread.sleep(MAX_WAIT_TIME);
        cancelHandler(workerThreadExecutor);

        assertEquals(0, incomingMessages.size());
        assertEquals(1, deleteMessages.size());
        assertEquals(0, retryMessages.size());
        assertEquals(0, visibilityMessages.size());

        Message receivedMessage = deleteMessages.take();
        assertEquals(AUTHORIZED_TOKEN, receivedMessage.messageAttributes().get(AUTHORIZED_KEY).stringValue());
        assertNull(receivedMessage.messageAttributes().get(REINDEX_KEY));
        assertEquals(newIndexMessageId, receivedMessage.messageId());
    }

    @Test
    public void should_processReIndexMessage_correctly() throws Throwable {
        String reIndexMessageId = "reIndexMessageId";
        String reIndexCursor = "reIndexCursor";
        Message message = getAuthorizedMessage(reIndexMessageId, reIndexCursor);
        Future<?> workerThreadExecutor = executorService.submit(() -> {
            try (MockedConstruction<ReIndexProcessor> reIndexProcessor = Mockito.mockConstruction(ReIndexProcessor.class, (mock, context) -> {
                when(mock.getResult()).thenReturn(CallableResult.PASS);
                when(mock.call()).thenReturn(mock);
                assertEquals(REINDEX_URL, context.arguments().get(1));
            })) {
                WorkerThread testingImplementation = getWorker();
                testingImplementation.run();
            }
        });
        incomingMessages.add(message);
        Thread.sleep(MAX_WAIT_TIME);
        cancelHandler(workerThreadExecutor);

        assertEquals(0, incomingMessages.size());
        assertEquals(1, deleteMessages.size());
        assertEquals(0, retryMessages.size());
        assertEquals(0, visibilityMessages.size());

        Message receivedMessage = deleteMessages.take();
        assertEquals(AUTHORIZED_TOKEN, receivedMessage.messageAttributes().get(AUTHORIZED_KEY).stringValue());
        assertEquals(reIndexCursor, receivedMessage.messageAttributes().get(REINDEX_KEY).stringValue());
        assertEquals(reIndexMessageId, receivedMessage.messageId());
    }

    @Test
    public void should_processFailedMessage_correctly() throws InterruptedException {
        String failedIndexMessageId = "failedIndexMessageId";
        Message message = getAuthorizedMessage(failedIndexMessageId);
        try (MockedConstruction<NewIndexProcessor> newIndexProcessor = Mockito.mockConstruction(NewIndexProcessor.class, (mock, context) -> {
            Message messageArgument = ((Message)context.arguments().get(0));
            assertEquals(failedIndexMessageId, messageArgument.messageId());
            assertEquals(AUTHORIZED_TOKEN, messageArgument.messageAttributes().get(AUTHORIZED_KEY).stringValue());
            assertNull(messageArgument.messageAttributes().get(REINDEX_KEY));
            assertEquals(NEWINDEX_URL, context.arguments().get(1));
            assertEquals(AUTHORIZED_TOKEN, context.arguments().get(2));
            when(mock.getResult()).thenReturn(CallableResult.FAIL);
            when(mock.call()).thenReturn(mock);
        })) {
            WorkerThread testingImplementation = getWorker();
            Future<?> workerThreadExecutor = executorService.submit(testingImplementation);
            incomingMessages.add(message);

            Thread.sleep(MAX_WAIT_TIME);
            cancelHandler(workerThreadExecutor);

            assertEquals(0, incomingMessages.size());
            assertEquals(0, deleteMessages.size());
            assertEquals(0, retryMessages.size());
            assertEquals(1, visibilityMessages.size());

            Message receivedMessage = visibilityMessages.take();
            assertEquals(failedIndexMessageId, receivedMessage.messageId());
        }
    }


    @Test
    public void should_processHungMessage_correctly() throws InterruptedException {
        String hungIndexMessageId = "hungIndexMessageId";
        Message message = getAuthorizedMessage(hungIndexMessageId);
        try (MockedConstruction<NewIndexProcessor> newIndexProcessor = Mockito.mockConstruction(NewIndexProcessor.class, (mock, context) -> {
            Message messageArgument = ((Message)context.arguments().get(0));
            assertEquals(hungIndexMessageId, messageArgument.messageId());
            assertEquals(AUTHORIZED_TOKEN, messageArgument.messageAttributes().get(AUTHORIZED_KEY).stringValue());
            assertNull(messageArgument.messageAttributes().get(REINDEX_KEY));
            assertEquals(NEWINDEX_URL, context.arguments().get(1));
            assertEquals(AUTHORIZED_TOKEN, context.arguments().get(2));
            when(mock.getResult()).thenAnswer(new Answer<Object>() {
                @Override
                public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                    Thread.sleep(2 * MAX_WAIT_TIME);
                    return new Object();
                }
            });
        })) {
            WorkerThread testingImplementation = getWorker();
            Future<?> workerThreadExecutor = executorService.submit(testingImplementation);
            incomingMessages.add(message);

            Thread.sleep(MAX_WAIT_TIME);
            cancelHandler(workerThreadExecutor);

            assertEquals(0, incomingMessages.size());
            assertEquals(0, deleteMessages.size());
            assertEquals(0, retryMessages.size());
            assertEquals(1, visibilityMessages.size());

            Message receivedMessage = visibilityMessages.take();
            assertEquals(hungIndexMessageId, receivedMessage.messageId());
        }
    }
}
