/*
 * Copyright 2019 Google LLC. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration.GCS_OUTPUT_STREAM_PIPE_TYPE;
import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration.GCS_OUTPUT_STREAM_TYPE;
import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem.OutputStreamType;
import com.google.cloud.hadoop.gcsio.CreateFileOptions;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystemIntegrationHelper;
import com.google.cloud.hadoop.util.AsyncWriteChannelOptions;
import com.google.cloud.hadoop.util.AsyncWriteChannelOptions.PipeType;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class GoogleHadoopOutputStreamIntegrationTest {

  private static GoogleCloudStorageFileSystemIntegrationHelper gcsFsIHelper;

  @BeforeClass
  public static void beforeClass() throws Exception {
    gcsFsIHelper = GoogleCloudStorageFileSystemIntegrationHelper.create();
    gcsFsIHelper.beforeAllTests();
  }

  @AfterClass
  public static void afterClass() {
    gcsFsIHelper.afterAllTests();
  }

  @Parameterized.Parameters
  public static Collection<Object[]> getConstructorArguments() {
    return Arrays.asList(
        new Object[] {OutputStreamType.BASIC, PipeType.IO_STREAM_PIPE},
        new Object[] {OutputStreamType.BASIC, PipeType.NIO_CHANNEL_PIPE},
        new Object[] {OutputStreamType.FLUSHABLE_COMPOSITE, PipeType.IO_STREAM_PIPE},
        new Object[] {OutputStreamType.SYNCABLE_COMPOSITE, PipeType.IO_STREAM_PIPE});
  }

  private final OutputStreamType outputStreamType;
  private final PipeType pipeType;

  public GoogleHadoopOutputStreamIntegrationTest(
      OutputStreamType outputStreamType, PipeType pipeType) {
    this.outputStreamType = outputStreamType;
    this.pipeType = pipeType;
  }

  private Configuration getTestConfig() {
    Configuration conf = GoogleHadoopFileSystemIntegrationHelper.getTestConfig();
    conf.setEnum(GCS_OUTPUT_STREAM_TYPE.getKey(), outputStreamType);
    conf.setEnum(GCS_OUTPUT_STREAM_PIPE_TYPE.getKey(), pipeType);
    return conf;
  }

  @Test
  public void write_withZeroBufferSize() throws Exception {
    URI testFile = gcsFsIHelper.getUniqueObjectUri("GHFSOutputStream_write_withZeroBufferSize");

    Configuration config = getTestConfig();
    config.setInt(GoogleHadoopFileSystemConfiguration.GCS_OUTPUT_STREAM_BUFFER_SIZE.getKey(), 0);

    GoogleHadoopFileSystem ghfs =
        GoogleHadoopFileSystemIntegrationHelper.createGhfs(testFile, config);

    AsyncWriteChannelOptions writeOptions =
        ghfs.getGcsFs().getOptions().getCloudStorageOptions().getWriteChannelOptions();
    assertThat(writeOptions.getBufferSize()).isEqualTo(0);

    try (GoogleHadoopOutputStream out =
        new GoogleHadoopOutputStream(
            ghfs,
            testFile,
            new FileSystem.Statistics(ghfs.getScheme()),
            CreateFileOptions.DEFAULT_OVERWRITE)) {
      out.write(1);
    }

    FileStatus fileStatus = ghfs.getFileStatus(ghfs.getHadoopPath(testFile));

    assertThat(fileStatus.getLen()).isEqualTo(1);
  }
}
