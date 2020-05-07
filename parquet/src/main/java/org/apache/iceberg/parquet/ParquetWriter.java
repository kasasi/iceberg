/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.parquet;

import com.google.common.collect.ImmutableMap;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.Schema;
import org.apache.iceberg.common.DynConstructors;
import org.apache.iceberg.common.DynMethods;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.OutputFile;
import org.apache.parquet.bytes.ByteBufferAllocator;
import org.apache.parquet.column.ColumnWriteStore;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.column.page.PageWriteStore;
import org.apache.parquet.hadoop.CodecFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.MessageType;

class ParquetWriter<T> implements FileAppender<T>, Closeable {

  // We have one for Parquet 1.10 and one for 1.11. The signature changed, but we still want
  // to be compatible with both of them
  private static DynConstructors.Ctor<PageWriteStore> pageStoreCtorParquet = DynConstructors
          .builder(PageWriteStore.class)

          // Parquet 1.11
          .hiddenImpl("org.apache.parquet.hadoop.ColumnChunkPageWriteStore",
              CodecFactory.BytesCompressor.class,
              MessageType.class,
              ByteBufferAllocator.class,
              int.class)

          // Parquet 1.10
          .hiddenImpl("org.apache.parquet.hadoop.ColumnChunkPageWriteStore",
              CodecFactory.BytesCompressor.class,
              MessageType.class,
              ByteBufferAllocator.class)

          .build();

  private static final DynMethods.UnboundMethod flushToWriter = DynMethods
      .builder("flushToFileWriter")
      .hiddenImpl("org.apache.parquet.hadoop.ColumnChunkPageWriteStore", ParquetFileWriter.class)
      .build();

  private final long targetRowGroupSize;
  private final Map<String, String> metadata;
  private final ParquetProperties props;
  private final CodecFactory.BytesCompressor compressor;
  private final MessageType parquetSchema;
  private final ParquetValueWriter<T> model;
  private final ParquetFileWriter writer;
  private final MetricsConfig metricsConfig;
  private final int columnIndexTruncateLength;

  private DynMethods.BoundMethod flushPageStoreToWriter;
  private ColumnWriteStore writeStore;
  private long nextRowGroupSize = 0;
  private long recordCount = 0;
  private long nextCheckRecordCount = 10;

  // Copied from Parquet 1.11.0 to keep support for 1.10.0
  private static final String COLUMN_INDEX_TRUNCATE_LENGTH = "parquet.columnindex.truncate.length";
  private static final int DEFAULT_COLUMN_INDEX_TRUNCATE_LENGTH = 64;

  @SuppressWarnings("unchecked")
  ParquetWriter(Configuration conf, OutputFile output, Schema schema, long rowGroupSize,
                Map<String, String> metadata,
                Function<MessageType, ParquetValueWriter<?>> createWriterFunc,
                CompressionCodecName codec,
                ParquetProperties properties,
                MetricsConfig metricsConfig,
                ParquetFileWriter.Mode writeMode) {
    this.targetRowGroupSize = rowGroupSize;
    this.props = properties;
    this.metadata = ImmutableMap.copyOf(metadata);
    this.compressor = new CodecFactory(conf, props.getPageSizeThreshold()).getCompressor(codec);
    this.parquetSchema = ParquetSchemaUtil.convert(schema, "table");
    this.model = (ParquetValueWriter<T>) createWriterFunc.apply(parquetSchema);
    this.metricsConfig = metricsConfig;
    this.columnIndexTruncateLength = conf.getInt(COLUMN_INDEX_TRUNCATE_LENGTH, DEFAULT_COLUMN_INDEX_TRUNCATE_LENGTH);

    try {
      this.writer = new ParquetFileWriter(ParquetIO.file(output, conf), parquetSchema,
         writeMode, rowGroupSize, 0);
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to create Parquet file");
    }

    try {
      writer.start();
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to start Parquet file writer");
    }

    startRowGroup();
  }

  @Override
  public void add(T value) {
    recordCount += 1;
    model.write(0, value);
    writeStore.endRecord();
    checkSize();
  }

  @Override
  public Metrics metrics() {
    return ParquetUtil.footerMetrics(writer.getFooter(), metricsConfig);
  }

  @Override
  public long length() {
    try {
      return writer.getPos() + (writeStore.isColumnFlushNeeded() ? writeStore.getBufferedSize() : 0);
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to get file length");
    }
  }

  @Override
  public List<Long> splitOffsets() {
    return ParquetUtil.getSplitOffsets(writer.getFooter());
  }

  private void checkSize() {
    if (recordCount >= nextCheckRecordCount) {
      long bufferedSize = writeStore.getBufferedSize();
      double avgRecordSize = ((double) bufferedSize) / recordCount;

      if (bufferedSize > (nextRowGroupSize - 2 * avgRecordSize)) {
        flushRowGroup(false);
      } else {
        long remainingSpace = nextRowGroupSize - bufferedSize;
        long remainingRecords = (long) (remainingSpace / avgRecordSize);
        this.nextCheckRecordCount = recordCount + Math.min(Math.max(remainingRecords / 2, 100), 10000);
      }
    }
  }

  private void flushRowGroup(boolean finished) {
    try {
      if (recordCount > 0) {
        writer.startBlock(recordCount);
        writeStore.flush();
        flushPageStoreToWriter.invoke(writer);
        writer.endBlock();
        if (!finished) {
          startRowGroup();
        }
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to flush row group");
    }
  }

  private void startRowGroup() {
    try {
      this.nextRowGroupSize = Math.min(writer.getNextRowGroupSize(), targetRowGroupSize);
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
    this.nextCheckRecordCount = Math.min(Math.max(recordCount / 2, 100), 10000);
    this.recordCount = 0;

    PageWriteStore pageStore = pageStoreCtorParquet.newInstance(
        compressor, parquetSchema, props.getAllocator(), this.columnIndexTruncateLength);

    this.flushPageStoreToWriter = flushToWriter.bind(pageStore);
    this.writeStore = props.newColumnWriteStore(parquetSchema, pageStore);

    model.setColumnStore(writeStore);
  }

  @Override
  public void close() throws IOException {
    flushRowGroup(true);
    writeStore.close();
    writer.end(metadata);
  }
}