/**
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
package org.apache.pulsar.client.impl;

import static com.scurrilous.circe.checksum.Crc32cIntChecksum.computeChecksum;
import static com.scurrilous.circe.checksum.Crc32cIntChecksum.resumeChecksum;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.api.Commands;
import org.apache.pulsar.common.api.proto.PulsarApi.CompressionType;
import org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata;
import org.apache.pulsar.common.compression.CompressionCodec;
import org.apache.pulsar.common.util.protobuf.ByteBufCodedOutputStream;

/**
 * Test utils for accessing pulsar client classes.
 */
@Slf4j
public class PulsarClientTestUtils {

    public static MessageImpl<byte[]> newSingleMessage(byte[] payload) {
        MessageMetadata.Builder metadataBuilder = MessageMetadata.newBuilder();
        return MessageImpl.create(
            metadataBuilder,
            ByteBuffer.wrap(payload),
            Schema.BYTES
        );
    }

    public static ByteBuf serializeSingleMessage(MessageMetadata.Builder metadataBuilder,
                                                 ByteBuf payload,
                                                 CompressionCodec compressor) {
        ByteBuf compressedPayload = compressor.encode(payload);
        payload.release();
        try {
            return serializeMessage(metadataBuilder.build(), compressedPayload);
        } finally {
            metadataBuilder.recycle();
        }
    }

    private static ByteBuf serializeMessage(MessageMetadata metadata,
                                            ByteBuf compressedPayload) {
        int metadataSize = metadata.getSerializedSize();
        int magicAndChecksumSize = 2 + 4; /* magic + checksum_length */
        int headerSize = magicAndChecksumSize + 4 + metadataSize;

        int checksumReaderIndex = -1;
        ByteBuf header = PooledByteBufAllocator.DEFAULT.buffer(headerSize, headerSize);
        header.writeShort(Commands.magicCrc32c);
        checksumReaderIndex = header.writerIndex();
        header.writerIndex(header.writerIndex() + 4); // skip 4-bytes checksum
        header.writeInt(metadataSize);

        ByteBufCodedOutputStream outStream = ByteBufCodedOutputStream.get(header);
        try {
            metadata.writeTo(outStream);
        } catch (IOException re) {
            log.error("Failed to serialize message header", re);
            throw new RuntimeException(re);
        }

        header.markReaderIndex();
        header.readerIndex(checksumReaderIndex + 4);
        int metadataChecksum = computeChecksum(header);
        int computedChecksum = resumeChecksum(metadataChecksum, compressedPayload);
        // set computed checksum
        header.setInt(checksumReaderIndex, computedChecksum);
        header.resetReaderIndex();

        metadata.recycle();

        CompositeByteBuf command = PooledByteBufAllocator.DEFAULT.compositeBuffer(2);
        command.addComponent(header);
        command.addComponent(compressedPayload);
        command.writerIndex(header.readableBytes() + compressedPayload.readableBytes());

        return command;
    }

    public static ByteBuf serializeBatchedMessage(int numMessages,
                                                  CompressionType compressionType,
                                                  String topicName,
                                                  String producerName,
                                                  String payloadPrefix,
                                                  MessageMetadata.Builder metadataBuilder) {
        BatchMessageContainer container = new BatchMessageContainer(
            numMessages, compressionType, topicName, producerName);

        for (int i = 0; i < numMessages; i++) {
            MessageImpl<byte[]> msg = MessageImpl.create(
                MessageMetadata.newBuilder(metadataBuilder.build())
                    .setSequenceId(i)
                    .setCompression(compressionType),
                ByteBuffer.wrap((payloadPrefix + "-" + i).getBytes(UTF_8)),
                Schema.BYTES);

            container.add(msg, new SendCallback() {
                @Override
                public void sendComplete(Exception e) {
                }

                @Override
                public void addCallback(MessageImpl<?> msg, SendCallback scb) {
                }

                @Override
                public SendCallback getNextSendCallback() {
                    return null;
                }

                @Override
                public MessageImpl<?> getNextMessage() {
                    return null;
                }

                @Override
                public CompletableFuture<MessageId> getFuture() {
                    return null;
                }
            });
        }

        // build the batched message buffer
        ByteBuf compressedPayload = container.getCompressedBatchMetadataAndPayload();

        // set batch
        MessageMetadata metadata = container.setBatchAndBuild();
        return serializeMessage(metadata, compressedPayload);
    }

}