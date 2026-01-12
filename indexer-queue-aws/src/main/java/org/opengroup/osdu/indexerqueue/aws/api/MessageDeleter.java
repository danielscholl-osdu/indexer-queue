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
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.concurrent.BlockingQueue;

public class MessageDeleter extends MessageHandler<DeleteMessageBatchRequestEntry> {

    private final String deleteQueueURL;
    public MessageDeleter(BlockingQueue<Message> messagesToDelete, int maxBatchRequests, SqsClient sqsClient, String deleteQueueURL) {
        super(messagesToDelete, maxBatchRequests, sqsClient);
        this.deleteQueueURL = deleteQueueURL;
    }

    @Override
    protected DeleteMessageBatchRequestEntry generateHandleRequest(Message message) {
        return DeleteMessageBatchRequestEntry.builder().id(message.messageId()).receiptHandle(message.receiptHandle()).build();
    }

    @Override
    protected void handleRequestBatch(List<DeleteMessageBatchRequestEntry> batch, SqsClient sqsClient) {
        logger.info(String.format("Deleting %d messages.", batch.size()));
        sqsClient.deleteMessageBatch(DeleteMessageBatchRequest.builder().queueUrl(deleteQueueURL).entries(batch).build());
    }
}
