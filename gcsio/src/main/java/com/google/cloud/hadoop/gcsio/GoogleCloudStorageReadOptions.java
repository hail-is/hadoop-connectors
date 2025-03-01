/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.cloud.hadoop.gcsio;

import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;

/**
 * Advanced options for reading GoogleCloudStorage objects. Immutable; callers must use the inner
 * {@link Builder} class to construct instances.
 */
@AutoValue
public abstract class GoogleCloudStorageReadOptions {

  /** Operational modes of fadvise feature. */
  public enum Fadvise {
    AUTO,
    RANDOM,
    SEQUENTIAL
  }

  public static final int DEFAULT_BACKOFF_INITIAL_INTERVAL_MILLIS = 200;
  public static final double DEFAULT_BACKOFF_RANDOMIZATION_FACTOR = 0.5;
  public static final double DEFAULT_BACKOFF_MULTIPLIER = 1.5;
  public static final int DEFAULT_BACKOFF_MAX_INTERVAL_MILLIS = 10 * 1000;
  public static final int DEFAULT_BACKOFF_MAX_ELAPSED_TIME_MILLIS = 2 * 60 * 1000;
  public static final boolean DEFAULT_FAST_FAIL_ON_NOT_FOUND = true;
  public static final boolean DEFAULT_SUPPORT_GZIP_ENCODING = true;
  public static final long DEFAULT_INPLACE_SEEK_LIMIT = 8 * 1024 * 1024;
  public static final Fadvise DEFAULT_FADVISE = Fadvise.SEQUENTIAL;
  public static final int DEFAULT_MIN_RANGE_REQUEST_SIZE = 2 * 1024 * 1024;
  public static final boolean GRPC_CHECKSUMS_ENABLED_DEFAULT = false;
  public static final long DEFAULT_GRPC_READ_TIMEOUT_MILLIS = 20 * 60 * 1000;
  public static final long DEFAULT_GRPC_READ_METADATA_TIMEOUT_MILLIS = 60 * 1000;
  public static final boolean DEFAULT_GRPC_READ_ZEROCOPY_ENABLED = true;
  public static final long DEFAULT_GRPC_READ_MESSAGE_TIMEOUT_MILLIS = 5 * 1000;

  // Default builder should be initialized after default values,
  // otherwise it will access not initialized default values.
  public static final GoogleCloudStorageReadOptions DEFAULT = builder().build();

  public static Builder builder() {
    return new AutoValue_GoogleCloudStorageReadOptions.Builder()
        .setBackoffInitialIntervalMillis(DEFAULT_BACKOFF_INITIAL_INTERVAL_MILLIS)
        .setBackoffRandomizationFactor(DEFAULT_BACKOFF_RANDOMIZATION_FACTOR)
        .setBackoffMultiplier(DEFAULT_BACKOFF_MULTIPLIER)
        .setBackoffMaxIntervalMillis(DEFAULT_BACKOFF_MAX_INTERVAL_MILLIS)
        .setBackoffMaxElapsedTimeMillis(DEFAULT_BACKOFF_MAX_ELAPSED_TIME_MILLIS)
        .setFastFailOnNotFound(DEFAULT_FAST_FAIL_ON_NOT_FOUND)
        .setSupportGzipEncoding(DEFAULT_SUPPORT_GZIP_ENCODING)
        .setInplaceSeekLimit(DEFAULT_INPLACE_SEEK_LIMIT)
        .setFadvise(DEFAULT_FADVISE)
        .setMinRangeRequestSize(DEFAULT_MIN_RANGE_REQUEST_SIZE)
        .setGrpcChecksumsEnabled(GRPC_CHECKSUMS_ENABLED_DEFAULT)
        .setGrpcReadTimeoutMillis(DEFAULT_GRPC_READ_TIMEOUT_MILLIS)
        .setGrpcReadMetadataTimeoutMillis(DEFAULT_GRPC_READ_METADATA_TIMEOUT_MILLIS)
        .setGrpcReadZeroCopyEnabled(DEFAULT_GRPC_READ_ZEROCOPY_ENABLED)
        .setGrpcReadMessageTimeoutMillis(DEFAULT_GRPC_READ_MESSAGE_TIMEOUT_MILLIS);
  }

  public abstract Builder toBuilder();

  /** See {@link Builder#setBackoffInitialIntervalMillis}. */
  public abstract int getBackoffInitialIntervalMillis();

  /** See {@link Builder#setBackoffRandomizationFactor}. */
  public abstract double getBackoffRandomizationFactor();

  /** See {@link Builder#setBackoffMultiplier}. */
  public abstract double getBackoffMultiplier();

  /** See {@link Builder#setBackoffMaxIntervalMillis}. */
  public abstract int getBackoffMaxIntervalMillis();

  /** See {@link Builder#setBackoffMaxElapsedTimeMillis}. */
  public abstract int getBackoffMaxElapsedTimeMillis();

  /** See {@link Builder#setFastFailOnNotFound}. */
  public abstract boolean getFastFailOnNotFound();

  /** See {@link Builder#setSupportGzipEncoding}. */
  public abstract boolean getSupportGzipEncoding();

  /** See {@link Builder#setInplaceSeekLimit}. */
  public abstract long getInplaceSeekLimit();

  /** See {@link Builder#setFadvise}. */
  public abstract Fadvise getFadvise();

  /** See {@link Builder#setMinRangeRequestSize}. */
  public abstract int getMinRangeRequestSize();

  /** See {@link Builder#setGrpcChecksumsEnabled}. */
  public abstract boolean isGrpcChecksumsEnabled();

  /** See {@link Builder#setGrpcReadTimeoutMillis}. */
  public abstract long getGrpcReadTimeoutMillis();

  /** See {@link Builder#setGrpcReadMetadataTimeoutMillis}. */
  public abstract long getGrpcReadMetadataTimeoutMillis();

  /** See {@link Builder#setGrpcReadZeroCopyEnabled}. */
  public abstract boolean isGrpcReadZeroCopyEnabled();

  /** See {@link Builder#setGrpcReadTimeoutMillis(long)}. */
  public abstract long getGrpcReadMessageTimeoutMillis();

  /** Mutable builder for GoogleCloudStorageReadOptions. */
  @AutoValue.Builder
  public abstract static class Builder {
    /**
     * On exponential back-off, the initial delay before the first retry; subsequent retries then
     * grow as an exponential function of the current delay interval.
     */
    public abstract Builder setBackoffInitialIntervalMillis(int backoffInitialIntervalMillis);

    /**
     * The amount of jitter introduced when computing the next retry sleep interval so that when
     * many clients are retrying, they don't all retry at the same time.
     */
    public abstract Builder setBackoffRandomizationFactor(double backoffRandomizationFactor);

    /**
     * The base of the exponent used for exponential back-off; each subsequent sleep interval is
     * roughly this many times the previous interval.
     */
    public abstract Builder setBackoffMultiplier(double backoffMultiplier);

    /**
     * The maximum amount of sleep between retries; at this point, there will be no further
     * exponential back-off. This prevents intervals from growing unreasonably large.
     */
    public abstract Builder setBackoffMaxIntervalMillis(int backoffMaxIntervalMillis);

    /**
     * The maximum total time elapsed since the first retry over the course of a series of retries.
     * This makes it easier to bound the maximum time it takes to respond to a permanent failure
     * without having to calculate the summation of a series of exponentiated intervals while
     * accounting for the randomization of back-off intervals.
     */
    public abstract Builder setBackoffMaxElapsedTimeMillis(int backoffMaxElapsedTimeMillis);

    /**
     * True if attempts to open a new channel on a nonexistent object are required to immediately
     * throw an IOException. If false, then channels may not throw exceptions for such cases until
     * attempting to call read(). Performance can be improved if this is set to false and the caller
     * is equipped to deal with delayed failures for not-found objects. Or if the caller is already
     * sure the object being opened exists, it is recommended to set this to false to avoid doing
     * extraneous checks on open().
     */
    public abstract Builder setFastFailOnNotFound(boolean fastFailOnNotFound);

    /**
     * If false then reading a file with GZIP content encoding (HTTP header "Content-Encoding:
     * gzip") will result in failure (IOException is thrown). If true then GZIP-encoded files will
     * be read successfully.
     */
    public abstract Builder setSupportGzipEncoding(boolean supportGzipEncoding);

    /**
     * If seeking to a new position which is within this number of bytes in front of the current
     * position, then we will skip forward by reading and discarding the necessary amount of bytes
     * rather than trying to open a brand-new underlying stream.
     */
    public abstract Builder setInplaceSeekLimit(long inplaceSeekLimit);

    /**
     * Sets fadvise mode that tunes behavior to optimize HTTP GET requests for various use cases.
     *
     * <p>Supported modes:
     *
     * <ul>
     *   <li>{@code AUTO} - automatically switches to {@code RANDOM} mode if backward read or
     *       forward read for more than {@link #setInplaceSeekLimit} bytes is detected.
     *   <li>{@code RANDOM} - sends HTTP requests with {@code Range} header set to greater of
     *       provided reade buffer by user.
     *   <li>{@code SEQUENTIAL} - sends HTTP requests with unbounded {@code Range} header.
     * </ul>
     */
    public abstract Builder setFadvise(Fadvise fadvise);

    /**
     * Sets the minimum size of the HTTP Range header that could be set in GCS request when opening
     * new stream to read an object.
     */
    public abstract Builder setMinRangeRequestSize(int size);

    /**
     * Sets whether to validate checksums when doing gRPC reads. If enabled, for sequential reads of
     * a whole object, the object checksums will be validated.
     *
     * <p>TODO(b/134521856): Update this to discuss per-request checksums once the server supplies
     * them and we're validating them.
     */
    public abstract Builder setGrpcChecksumsEnabled(boolean grpcChecksumsEnabled);

    /** Sets the property to override the default GCS gRPC read stream timeout. */
    public abstract Builder setGrpcReadTimeoutMillis(long grpcReadTimeoutMillis);

    /** Sets the property to override the default timeout for GCS metadata reads from gRPC. */
    public abstract Builder setGrpcReadMetadataTimeoutMillis(long grpcReadMetadataTimeoutMillis);

    /** Sets the property to use the zero-copy deserializer for gRPC read. */
    public abstract Builder setGrpcReadZeroCopyEnabled(boolean grpcReadZeroCopyEnabled);

    /** Sets the property for gRPC read message timeout in milliseconds. */
    public abstract Builder setGrpcReadMessageTimeoutMillis(long grpcMessageTimeout);

    abstract GoogleCloudStorageReadOptions autoBuild();

    public GoogleCloudStorageReadOptions build() {
      GoogleCloudStorageReadOptions options = autoBuild();
      checkState(
          options.getInplaceSeekLimit() >= 0,
          "inplaceSeekLimit must be non-negative! Got %s",
          options.getInplaceSeekLimit());
      return options;
    }
  }
}
