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

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class MessageHandlerTest {

    private static final int MAX_BATCH_REQUESTS = 7;
    private static final int MAX_WAIT_FOR_MESSAGE = 100;
    private static final int MAX_WAIT_FOR_MESSAGE_BATCH = 1000;

    private static class MessageHandlerImplTest extends MessageHandler<String> {
        ArrayList<List<String>> requestBatches = new ArrayList<>();
        final SqsClient sqsClient;

        protected MessageHandlerImplTest(BlockingQueue<Message> messagesToHandle, SqsClient sqsClient) {
            super(messagesToHandle, MAX_BATCH_REQUESTS, sqsClient, MAX_WAIT_FOR_MESSAGE, MessageHandlerTest.MAX_WAIT_FOR_MESSAGE_BATCH);
            this.sqsClient = sqsClient;
        }

        @Override
        protected String generateHandleRequest(Message message) {
            return message.body();
        }

        @Override
        protected void handleRequestBatch(List<String> batch, SqsClient sqsClient) {
            assertEquals(this.sqsClient, sqsClient);
            requestBatches.add(new ArrayList<>(batch));
            System.out.println(batch.size());
        }
    }

    private void cancelHandler(Future<?> handlerFuture) throws InterruptedException {
        handlerFuture.cancel(true);
        // Should take a small amount of time to wait for the thread to loop.
        Thread.sleep(2 * MAX_WAIT_FOR_MESSAGE);
        assertTrue(handlerFuture.isDone());
    }

    private int testWithBatchSize(MessageHandlerImplTest impl, int numMessages, BlockingQueue<Message> messagesToHandle, int waitTime, int curBatch) throws InterruptedException {
        List<String> messageBodies = new ArrayList<>();
        for (int i = 0; i < numMessages; ++i) {
            messageBodies.add(String.format("%d test message body.", i));
        }
        List<Message> messages = messageBodies.stream().map(body -> Message.builder().body(body).messageId(body).build()).collect(Collectors.toList());

        messagesToHandle.addAll(messages);

        Thread.sleep(waitTime);

        int numBatches = (numMessages + MAX_BATCH_REQUESTS - 1) / MAX_BATCH_REQUESTS; // Get the number of batches;
        assertEquals(curBatch + numBatches, impl.requestBatches.size());
        for (int batchNum = 0; batchNum < numBatches; ++batchNum) {
            List<String> receivedBodies = impl.requestBatches.get(curBatch + batchNum);
            int messageNum = batchNum * MAX_BATCH_REQUESTS;
            int messagesLeft = numMessages - messageNum;
            int expectedNumForBatch = Math.min(messagesLeft, MAX_BATCH_REQUESTS);
            for (int j = 0; j < expectedNumForBatch; ++j) {
                assertEquals(messageBodies.get(messageNum + j), receivedBodies.get(j));
            }
        }

        return curBatch + numBatches;
    }

    @Test
    public void should_correctlyHandle_whenBatchReachesExpire() throws InterruptedException {
        SqsClient amazonSqsClient = Mockito.mock(SqsClient.class);
        BlockingQueue<Message> messagesToHandle = new ArrayBlockingQueue<>(MAX_BATCH_REQUESTS);

        MessageHandlerImplTest impl = new MessageHandlerImplTest(messagesToHandle, amazonSqsClient);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> threadFuture = executorService.submit(impl);
        testWithBatchSize(impl, 1, messagesToHandle, 2 * MAX_WAIT_FOR_MESSAGE_BATCH, 0);
        cancelHandler(threadFuture);
    }

    @Test
    public void should_correctlyHandle_whenBatchIsReached() throws InterruptedException {
        SqsClient amazonSqsClient = Mockito.mock(SqsClient.class);
        BlockingQueue<Message> messagesToHandle = new ArrayBlockingQueue<>(MAX_BATCH_REQUESTS);

        MessageHandlerImplTest impl = new MessageHandlerImplTest(messagesToHandle, amazonSqsClient);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> threadFuture = executorService.submit(impl);
        testWithBatchSize(impl, MAX_BATCH_REQUESTS, messagesToHandle, MAX_WAIT_FOR_MESSAGE_BATCH, 0);
        cancelHandler(threadFuture);
    }

    @Test
    public void should_correctlyHandle_whenMultipleMessagesWithSameId_areSent() throws InterruptedException {
        SqsClient amazonSqsClient = Mockito.mock(SqsClient.class);
        int maxMessages = 3;
        BlockingQueue<Message> messagesToHandle = new ArrayBlockingQueue<>(maxMessages);

        MessageHandlerImplTest impl = new MessageHandlerImplTest(messagesToHandle, amazonSqsClient);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> threadFuture = executorService.submit(impl);
        String messageId = "testSameId";
        String messageBody = "Message with same identifier";
        Message toAdd = Message.builder().messageId(messageId).body(messageBody).build();
        messagesToHandle.put(toAdd);
        messagesToHandle.put(toAdd);
        messagesToHandle.put(toAdd);
        Thread.sleep(2 * MAX_WAIT_FOR_MESSAGE_BATCH);
        assertEquals(1, impl.requestBatches.size());
        List<String> messageBatch = impl.requestBatches.get(0);
        assertEquals(1, messageBatch.size());
        assertEquals(messageBody, messageBatch.get(0));
    }

    @Test
    public void should_correctlyReset_whenMultipleBatchesAreSent() throws InterruptedException {
        SqsClient amazonSqsClient = Mockito.mock(SqsClient.class);
        int maxMessages = 15;
        BlockingQueue<Message> messagesToHandle = new ArrayBlockingQueue<>(maxMessages);

        MessageHandlerImplTest impl = new MessageHandlerImplTest(messagesToHandle, amazonSqsClient);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> threadFuture = executorService.submit(impl);
        int nextBatch = testWithBatchSize(impl, MAX_BATCH_REQUESTS, messagesToHandle, MAX_WAIT_FOR_MESSAGE_BATCH, 0);

        nextBatch = testWithBatchSize(impl, maxMessages, messagesToHandle, 2 * MAX_WAIT_FOR_MESSAGE_BATCH, nextBatch);

        testWithBatchSize(impl, 3, messagesToHandle, 2 * MAX_WAIT_FOR_MESSAGE_BATCH, nextBatch);

        cancelHandler(threadFuture);
    }
}
