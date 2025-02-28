/*
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.hadoop.gcsio.FileInfo;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageReadOptions;
import com.google.common.flogger.GoogleLogger;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.statistics.IOStatistics;
import org.apache.hadoop.fs.statistics.IOStatisticsSource;

class GoogleHadoopFSInputStream extends FSInputStream implements IOStatisticsSource {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  // Statistic tracker of the Input stream
  private final GhfsInputStreamStatistics streamStatistics;
  // IO Statistics of the current input stream
  private final IOStatistics ioStatistics;
  // All store IO access goes through this.
  private final SeekableByteChannel channel;
  // Path of the file to read.
  private final URI gcsPath;
  // Statistics tracker provided by the parent GoogleHadoopFileSystem for recording
  // numbers of bytes read.
  private final FileSystem.Statistics statistics;
  // Used for single-byte reads.
  private final byte[] singleReadBuf = new byte[1];
  // Number of bytes read through this channel.
  private long totalBytesRead;

  GoogleHadoopFSInputStream(
      GoogleHadoopFileSystem ghfs,
      URI gcsPath,
      GoogleCloudStorageReadOptions readOptions,
      FileSystem.Statistics statistics)
      throws IOException {
    logger.atFiner().log(
        "GoogleHadoopFSInputStream(gcsPath: %s, readOptions: %s)", gcsPath, readOptions);
    this.gcsPath = gcsPath;
    this.statistics = statistics;
    this.totalBytesRead = 0;
    this.channel = ghfs.getGcsFs().open(gcsPath, readOptions);
    this.streamStatistics = ghfs.getInstrumentation().newInputStreamStatistics(statistics);
    this.ioStatistics = GoogleHadoopFSInputStream.this.streamStatistics.getIOStatistics();
  }

  GoogleHadoopFSInputStream(
      GoogleHadoopFileSystem ghfs, FileInfo fileInfo, FileSystem.Statistics statistics)
      throws IOException {
    logger.atFiner().log("GoogleHadoopFSInputStream(fileInfo: %s)", fileInfo);
    this.gcsPath = fileInfo.getPath();
    this.statistics = statistics;
    this.totalBytesRead = 0;
    GoogleCloudStorageFileSystem gcsFs = ghfs.getGcsFs();
    this.channel =
        gcsFs.open(fileInfo, gcsFs.getOptions().getCloudStorageOptions().getReadChannelOptions());
    this.streamStatistics = ghfs.getInstrumentation().newInputStreamStatistics(statistics);
    this.ioStatistics = GoogleHadoopFSInputStream.this.streamStatistics.getIOStatistics();
  }

  @Override
  public synchronized int read() throws IOException {
    streamStatistics.readOperationStarted(getPos(), 1);
    int response;
    try {
      int result;
      // TODO(user): Wrap this in a while-loop if we ever introduce a non-blocking mode for the
      // underlying channel.

      synchronized (this) {
        int numRead = channel.read(ByteBuffer.wrap(singleReadBuf));
        if (numRead == -1) {
          result = -1;
        } else {
          if (numRead != 1) {
            throw new IOException(
                String.format(
                    "Somehow read %d bytes using single-byte buffer for path %s ending in position %d!",
                    numRead, gcsPath, channel.position()));
          }
          byte b = singleReadBuf[0];
          totalBytesRead++;
          statistics.incrementBytesRead(1);
          statistics.incrementReadOps(1);
          result = (b & 0xff);
        }
      }
      response = result;
    } catch (IOException e) {
      streamStatistics.readException();
      throw e;
    }
    streamStatistics.bytesRead(1);
    streamStatistics.readOperationCompleted(1, response);
    return response;
  }

  @Override
  public synchronized int read(byte[] buf, int offset, int length) throws IOException {
    streamStatistics.readOperationStarted(getPos(), length);
    int response = 0;
    try {
      int result;

      synchronized (this) {
        checkNotNull(buf, "buf must not be null");
        if (offset < 0 || length < 0 || length > buf.length - offset) {
          throw new IndexOutOfBoundsException();
        }
        int numRead = channel.read(ByteBuffer.wrap(buf, offset, length));
        if (numRead > 0) {
          // -1 means we actually read 0 bytes, but requested at least one byte.
          totalBytesRead += numRead;
          statistics.incrementBytesRead(numRead);
          statistics.incrementReadOps(1);
        }
        result = numRead;
      }
      response = result;
    } catch (IOException e) {
      streamStatistics.readException();
    }
    streamStatistics.bytesRead(response);
    streamStatistics.readOperationCompleted(length, response);
    return response;
  }

  @Override
  public synchronized void seek(long pos) throws IOException {
    long curPos = getPos();
    long diff = pos - curPos;
    if (diff > 0) {
      streamStatistics.seekForwards(diff);
    } else {
      streamStatistics.seekBackwards(diff);
    }
    synchronized (this) {
      logger.atFiner().log("seek(%d)", pos);
      try {
        channel.position(pos);
      } catch (IllegalArgumentException e) {
        throw new IOException(e);
      }
    }
  }

  @Override
  public synchronized void close() throws IOException {
    streamStatistics.close();
    synchronized (this) {
      logger.atFiner().log("close(): %s", gcsPath);
      if (channel != null) {
        logger.atFiner().log("Closing '%s' file with %d total bytes read", gcsPath, totalBytesRead);
        channel.close();
      }
    }
  }

  /**
   * Get the current IOStatistics from input stream
   *
   * @return the iostatistics of the input stream
   */
  @Override
  public IOStatistics getIOStatistics() {
    return ioStatistics;
  }

  /**
   * Reads up to length bytes from the underlying store and stores them starting at the specified
   * offset in the given buffer. Less than length bytes may be returned. Reading starts at the given
   * position.
   *
   * @param position Data is read from the stream starting at this position.
   * @param buf The buffer into which data is returned.
   * @param offset The offset at which data is written.
   * @param length Maximum number of bytes to read.
   * @return Number of bytes read or -1 on EOF.
   * @throws IOException if an IO error occurs.
   */
  @Override
  public synchronized int read(long position, byte[] buf, int offset, int length)
      throws IOException {
    int result = super.read(position, buf, offset, length);

    if (result > 0) {
      // -1 means we actually read 0 bytes, but requested at least one byte.
      statistics.incrementBytesRead(result);
      totalBytesRead += result;
    }
    return result;
  }

  /**
   * Gets the current position within the file being read.
   *
   * @return The current position within the file being read.
   * @throws IOException if an IO error occurs.
   */
  @Override
  public synchronized long getPos() throws IOException {
    long pos = channel.position();
    logger.atFiner().log("getPos(): %d", pos);
    return pos;
  }

  /**
   * Seeks a different copy of the data. Not supported.
   *
   * @return true if a new source is found, false otherwise.
   */
  @Override
  public synchronized boolean seekToNewSource(long targetPos) {
    return false;
  }

  /**
   * Indicates whether this stream supports the 'mark' functionality.
   *
   * @return false (functionality not supported).
   */
  @Override
  public boolean markSupported() {
    // HDFS does not support it either and most Hadoop tools do not expect it.
    return false;
  }

  @Override
  public int available() throws IOException {
    if (!channel.isOpen()) {
      throw new ClosedChannelException();
    }
    return super.available();
  }
}
