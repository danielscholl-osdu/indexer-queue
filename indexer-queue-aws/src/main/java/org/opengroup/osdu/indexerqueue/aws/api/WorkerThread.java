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


import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import org.opengroup.osdu.core.common.logging.JaxRsDpsLog;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class WorkerThread implements Runnable {
    private final BlockingQueue<Message> incomingMessages;
    private final BlockingQueue<Message> retryMessages;
    private final BlockingQueue<Message> deleteMessage;
    private final BlockingQueue<Message> changeVisibilityMessage;
    private final ExecutorService workerPool;
    private final String newIndexURL;
    private final String reIndexURL;
    private final int maxWaitForMessage;
    private final int maxWaitForProcessing;
    private static final JaxRsDpsLog logger = LogProvider.getLogger();
    static final String REINDEX_URL_PATH = "api/indexer/v2/reindex?force_clean=false";
    static final String NEWINDEX_URL_PATH = "api/indexer/v2/_dps/task-handlers/index-worker";
    public WorkerThread(BlockingQueue<Message> incomingMessages, BlockingQueue<Message> retryMessages, BlockingQueue<Message> deleteMessage, BlockingQueue<Message> changeVisibilityMessage,
                        ExecutorService workerPool, EnvironmentVariables environmentVariables) {
        this(incomingMessages, retryMessages, deleteMessage, changeVisibilityMessage, workerPool, environmentVariables.getTargetURL(), environmentVariables.getMaxWorkerThreadWaitTime(), environmentVariables.getMaxIndexTime());
    }
    public WorkerThread(BlockingQueue<Message> incomingMessages, BlockingQueue<Message> retryMessages, BlockingQueue<Message> deleteMessage, BlockingQueue<Message> changeVisibilityMessage,
                        ExecutorService workerPool, String targetURL, int maxWaitForMessage, int maxWaitForProcessing) {
        this.incomingMessages = incomingMessages;
        this.retryMessages = retryMessages;
        this.deleteMessage = deleteMessage;
        this.changeVisibilityMessage = changeVisibilityMessage;
        this.workerPool = workerPool;
        this.maxWaitForMessage = maxWaitForMessage;
        this.maxWaitForProcessing = maxWaitForProcessing;
        this.newIndexURL = String.format("%s/%s", targetURL, NEWINDEX_URL_PATH);
        this.reIndexURL = String.format("%s/%s", targetURL, REINDEX_URL_PATH);
    }

    private void processMessage(Message incomingMessage) throws InterruptedException {
        long startTime = Instant.now().toEpochMilli();
        Map<String, MessageAttributeValue> attributes = incomingMessage.getMessageAttributes();
        MessageAttributeValue authorization = attributes.get("authorization");
        if (authorization == null) {
            this.retryMessages.put(incomingMessage);
            this.deleteMessage.put(incomingMessage);
            return;
        }

        String authorizationJWT = authorization.getStringValue();
        MessageAttributeValue reIndexCursor = attributes.get("ReIndexCursor");
        IndexProcessor processor;
        if (reIndexCursor == null) {
            processor = new NewIndexProcessor(incomingMessage, newIndexURL, authorizationJWT);
        } else {
            processor = new ReIndexProcessor(incomingMessage, reIndexURL, authorizationJWT);
        }

        CallableResult result;
        try {
            Future<IndexProcessor> future = workerPool.submit(processor);
            processor = future.get(this.maxWaitForProcessing, TimeUnit.SECONDS);
            result = processor.getResult();
        } catch (TimeoutException | ExecutionException e) {
            result = CallableResult.FAIL;
        }

        double timeDelta = (Instant.now().toEpochMilli() - startTime) / 1000.0;
        if (result == CallableResult.PASS) {
            logger.info(String.format("Message %s processed successfully after %.3f seconds.", processor.getMessageId(), timeDelta));
            deleteMessage.put(incomingMessage);
        } else {
            logger.info(String.format("Message %s could not be processed after %.3f seconds. Setting timeout and waiting.", processor.getMessageId(), timeDelta));
            changeVisibilityMessage.put(incomingMessage);
        }
    }

    @Override
    public void run() {
        boolean shouldLoop = true;
        while (shouldLoop) {
            try {
                long waitStart = Instant.now().toEpochMilli();
                Message message = incomingMessages.poll(this.maxWaitForMessage, TimeUnit.SECONDS);
                double timeDelta = (Instant.now().toEpochMilli() - waitStart) / 1000.0;
                if (message != null) {
                    logger.debug(String.format("Waited %.3f seconds to get the message.", timeDelta));
                    processMessage(message);
                } else {
                    logger.debug(String.format("Timed out waiting for message. Timed out after %.3f seconds.", timeDelta));
                }
            } catch (InterruptedException e) {
                logger.error("Unknown error occured when trying to delete messages.", e);
                shouldLoop = false;
            }
        }
    }
}
