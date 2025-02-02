/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc;

import java.io.IOException;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.orc.impl.MemoryManager;
import org.apache.orc.impl.WriterImpl;

/**
 * Contains factory methods to read or write ORC files.
 */
public class OrcFile {
  public static final String MAGIC = "ORC";

  /**
   * Create a version number for the ORC file format, so that we can add
   * non-forward compatible changes in the future. To make it easier for users
   * to understand the version numbers, we use the Hive release number that
   * first wrote that version of ORC files.
   *
   * Thus, if you add new encodings or other non-forward compatible changes
   * to ORC files, which prevent the old reader from reading the new format,
   * you should change these variable to reflect the next Hive release number.
   * Non-forward compatible changes should never be added in patch releases.
   *
   * Do not make any changes that break backwards compatibility, which would
   * prevent the new reader from reading ORC files generated by any released
   * version of Hive.
   */
  public enum Version {
    V_0_11("0.11", 0, 11),
    V_0_12("0.12", 0, 12);

    public static final Version CURRENT = V_0_12;

    private final String name;
    private final int major;
    private final int minor;

    Version(String name, int major, int minor) {
      this.name = name;
      this.major = major;
      this.minor = minor;
    }

    public static Version byName(String name) {
      for(Version version: values()) {
        if (version.name.equals(name)) {
          return version;
        }
      }
      throw new IllegalArgumentException("Unknown ORC version " + name);
    }

    /**
     * Get the human readable name for the version.
     */
    public String getName() {
      return name;
    }

    /**
     * Get the major version number.
     */
    public int getMajor() {
      return major;
    }

    /**
     * Get the minor version number.
     */
    public int getMinor() {
      return minor;
    }
  }

  /**
   * Records the version of the writer in terms of which bugs have been fixed.
   * For bugs in the writer, but the old readers already read the new data
   * correctly, bump this version instead of the Version.
   */
  public enum WriterVersion {
    ORIGINAL(0),
    HIVE_8732(1), // corrupted stripe/file maximum column statistics
    HIVE_4243(2), // use real column names from Hive tables
    HIVE_12055(3), // vectorized writer

    // Don't use any magic numbers here except for the below:
    FUTURE(Integer.MAX_VALUE); // a version from a future writer

    private final int id;

    public int getId() {
      return id;
    }

    WriterVersion(int id) {
      this.id = id;
    }

    private static final WriterVersion[] values;
    static {
      // Assumes few non-negative values close to zero.
      int max = Integer.MIN_VALUE;
      for (WriterVersion v : WriterVersion.values()) {
        if (v.id < 0) throw new AssertionError();
        if (v.id > max && FUTURE.id != v.id) {
          max = v.id;
        }
      }
      values = new WriterVersion[max + 1];
      for (WriterVersion v : WriterVersion.values()) {
        if (v.id < values.length) {
          values[v.id] = v;
        }
      }
    }

    public static WriterVersion from(int val) {
      if (val == FUTURE.id) return FUTURE; // Special handling for the magic value.
      return values[val];
    }
  }
  public static final WriterVersion CURRENT_WRITER = WriterVersion.HIVE_12055;

  public enum EncodingStrategy {
    SPEED, COMPRESSION
  }

  public enum CompressionStrategy {
    SPEED, COMPRESSION
  }

  // unused
  protected OrcFile() {}

  public static class ReaderOptions {
    private final Configuration conf;
    private FileSystem filesystem;
    private FileMetaInfo fileMetaInfo; // TODO: this comes from some place.
    private long maxLength = Long.MAX_VALUE;
    private FileMetadata fullFileMetadata; // Propagate from LLAP cache.

    public ReaderOptions(Configuration conf) {
      this.conf = conf;
    }

    public ReaderOptions fileMetaInfo(FileMetaInfo info) {
      fileMetaInfo = info;
      return this;
    }

    public ReaderOptions filesystem(FileSystem fs) {
      this.filesystem = fs;
      return this;
    }

    public ReaderOptions maxLength(long val) {
      maxLength = val;
      return this;
    }

    public ReaderOptions fileMetadata(FileMetadata metadata) {
      this.fullFileMetadata = metadata;
      return this;
    }

    public Configuration getConfiguration() {
      return conf;
    }

    public FileSystem getFilesystem() {
      return filesystem;
    }

    public FileMetaInfo getFileMetaInfo() {
      return fileMetaInfo;
    }

    public long getMaxLength() {
      return maxLength;
    }

    public FileMetadata getFileMetadata() {
      return fullFileMetadata;
    }
  }

  public static ReaderOptions readerOptions(Configuration conf) {
    return new ReaderOptions(conf);
  }

  public interface WriterContext {
    Writer getWriter();
  }

  public interface WriterCallback {
    void preStripeWrite(WriterContext context) throws IOException;
    void preFooterWrite(WriterContext context) throws IOException;
  }

  /**
   * Options for creating ORC file writers.
   */
  public static class WriterOptions {
    private final Configuration configuration;
    private FileSystem fileSystemValue = null;
    private TypeDescription schema = null;
    private long stripeSizeValue;
    private long blockSizeValue;
    private int rowIndexStrideValue;
    private int bufferSizeValue;
    private boolean blockPaddingValue;
    private CompressionKind compressValue;
    private MemoryManager memoryManagerValue;
    private Version versionValue;
    private WriterCallback callback;
    private EncodingStrategy encodingStrategy;
    private CompressionStrategy compressionStrategy;
    private double paddingTolerance;
    private String bloomFilterColumns;
    private double bloomFilterFpp;

    protected WriterOptions(Properties tableProperties, Configuration conf) {
      configuration = conf;
      memoryManagerValue = getStaticMemoryManager(conf);
      stripeSizeValue = OrcConf.STRIPE_SIZE.getLong(tableProperties, conf);
      blockSizeValue = OrcConf.BLOCK_SIZE.getLong(tableProperties, conf);
      rowIndexStrideValue =
          (int) OrcConf.ROW_INDEX_STRIDE.getLong(tableProperties, conf);
      bufferSizeValue = (int) OrcConf.BUFFER_SIZE.getLong(tableProperties,
          conf);
      blockPaddingValue =
          OrcConf.BLOCK_PADDING.getBoolean(tableProperties, conf);
      compressValue =
          CompressionKind.valueOf(OrcConf.COMPRESS.getString(tableProperties,
              conf).toUpperCase());
      String versionName = OrcConf.WRITE_FORMAT.getString(tableProperties,
          conf);
      versionValue = Version.byName(versionName);
      String enString = OrcConf.ENCODING_STRATEGY.getString(tableProperties,
          conf);
      encodingStrategy = EncodingStrategy.valueOf(enString);

      String compString =
          OrcConf.COMPRESSION_STRATEGY.getString(tableProperties, conf);
      compressionStrategy = CompressionStrategy.valueOf(compString);

      paddingTolerance =
          OrcConf.BLOCK_PADDING_TOLERANCE.getDouble(tableProperties, conf);

      bloomFilterColumns = OrcConf.BLOOM_FILTER_COLUMNS.getString(tableProperties,
          conf);
      bloomFilterFpp = OrcConf.BLOOM_FILTER_FPP.getDouble(tableProperties,
          conf);
    }

    /**
     * Provide the filesystem for the path, if the client has it available.
     * If it is not provided, it will be found from the path.
     */
    public WriterOptions fileSystem(FileSystem value) {
      fileSystemValue = value;
      return this;
    }

    /**
     * Set the stripe size for the file. The writer stores the contents of the
     * stripe in memory until this memory limit is reached and the stripe
     * is flushed to the HDFS file and the next stripe started.
     */
    public WriterOptions stripeSize(long value) {
      stripeSizeValue = value;
      return this;
    }

    /**
     * Set the file system block size for the file. For optimal performance,
     * set the block size to be multiple factors of stripe size.
     */
    public WriterOptions blockSize(long value) {
      blockSizeValue = value;
      return this;
    }

    /**
     * Set the distance between entries in the row index. The minimum value is
     * 1000 to prevent the index from overwhelming the data. If the stride is
     * set to 0, no indexes will be included in the file.
     */
    public WriterOptions rowIndexStride(int value) {
      rowIndexStrideValue = value;
      return this;
    }

    /**
     * The size of the memory buffers used for compressing and storing the
     * stripe in memory.
     */
    public WriterOptions bufferSize(int value) {
      bufferSizeValue = value;
      return this;
    }

    /**
     * Sets whether the HDFS blocks are padded to prevent stripes from
     * straddling blocks. Padding improves locality and thus the speed of
     * reading, but costs space.
     */
    public WriterOptions blockPadding(boolean value) {
      blockPaddingValue = value;
      return this;
    }

    /**
     * Sets the encoding strategy that is used to encode the data.
     */
    public WriterOptions encodingStrategy(EncodingStrategy strategy) {
      encodingStrategy = strategy;
      return this;
    }

    /**
     * Sets the tolerance for block padding as a percentage of stripe size.
     */
    public WriterOptions paddingTolerance(double value) {
      paddingTolerance = value;
      return this;
    }

    /**
     * Comma separated values of column names for which bloom filter is to be created.
     */
    public WriterOptions bloomFilterColumns(String columns) {
      bloomFilterColumns = columns;
      return this;
    }

    /**
     * Specify the false positive probability for bloom filter.
     * @param fpp - false positive probability
     * @return this
     */
    public WriterOptions bloomFilterFpp(double fpp) {
      bloomFilterFpp = fpp;
      return this;
    }

    /**
     * Sets the generic compression that is used to compress the data.
     */
    public WriterOptions compress(CompressionKind value) {
      compressValue = value;
      return this;
    }

    /**
     * Set the schema for the file. This is a required parameter.
     * @param schema the schema for the file.
     * @return this
     */
    public WriterOptions setSchema(TypeDescription schema) {
      this.schema = schema;
      return this;
    }

    /**
     * Sets the version of the file that will be written.
     */
    public WriterOptions version(Version value) {
      versionValue = value;
      return this;
    }

    /**
     * Add a listener for when the stripe and file are about to be closed.
     * @param callback the object to be called when the stripe is closed
     * @return this
     */
    public WriterOptions callback(WriterCallback callback) {
      this.callback = callback;
      return this;
    }

    /**
     * A package local option to set the memory manager.
     */
    protected WriterOptions memory(MemoryManager value) {
      memoryManagerValue = value;
      return this;
    }

    public boolean getBlockPadding() {
      return blockPaddingValue;
    }

    public long getBlockSize() {
      return blockSizeValue;
    }

    public String getBloomFilterColumns() {
      return bloomFilterColumns;
    }

    public FileSystem getFileSystem() {
      return fileSystemValue;
    }

    public Configuration getConfiguration() {
      return configuration;
    }

    public TypeDescription getSchema() {
      return schema;
    }

    public long getStripeSize() {
      return stripeSizeValue;
    }

    public CompressionKind getCompress() {
      return compressValue;
    }

    public WriterCallback getCallback() {
      return callback;
    }

    public Version getVersion() {
      return versionValue;
    }

    public MemoryManager getMemoryManager() {
      return memoryManagerValue;
    }

    public int getBufferSize() {
      return bufferSizeValue;
    }

    public int getRowIndexStride() {
      return rowIndexStrideValue;
    }

    public CompressionStrategy getCompressionStrategy() {
      return compressionStrategy;
    }

    public EncodingStrategy getEncodingStrategy() {
      return encodingStrategy;
    }

    public double getPaddingTolerance() {
      return paddingTolerance;
    }

    public double getBloomFilterFpp() {
      return bloomFilterFpp;
    }
  }

  /**
   * Create a set of writer options based on a configuration.
   * @param conf the configuration to use for values
   * @return A WriterOptions object that can be modified
   */
  public static WriterOptions writerOptions(Configuration conf) {
    return new WriterOptions(null, conf);
  }

  /**
   * Create a set of write options based on a set of table properties and
   * configuration.
   * @param tableProperties the properties of the table
   * @param conf the configuration of the query
   * @return a WriterOptions object that can be modified
   */
  public static WriterOptions writerOptions(Properties tableProperties,
                                            Configuration conf) {
    return new WriterOptions(tableProperties, conf);
  }

  private static ThreadLocal<MemoryManager> memoryManager = null;

  private static synchronized MemoryManager getStaticMemoryManager(
      final Configuration conf) {
    if (memoryManager == null) {
      memoryManager = new ThreadLocal<MemoryManager>() {
        @Override
        protected MemoryManager initialValue() {
          return new MemoryManager(conf);
        }
      };
    }
    return memoryManager.get();
  }

  /**
   * Create an ORC file writer. This is the public interface for creating
   * writers going forward and new options will only be added to this method.
   * @param path filename to write to
   * @param opts the options
   * @return a new ORC file writer
   * @throws IOException
   */
  public static Writer createWriter(Path path,
                                    WriterOptions opts
                                    ) throws IOException {
    FileSystem fs = opts.getFileSystem() == null ?
        path.getFileSystem(opts.getConfiguration()) : opts.getFileSystem();

    return new WriterImpl(fs, path, opts);
  }

}
