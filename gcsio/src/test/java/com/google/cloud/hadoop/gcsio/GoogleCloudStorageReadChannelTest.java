/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

package com.google.cloud.hadoop.gcsio;

import static com.google.cloud.hadoop.gcsio.GoogleCloudStorageTest.newStorageObject;
import static com.google.cloud.hadoop.gcsio.GoogleCloudStorageTestUtils.BUCKET_NAME;
import static com.google.cloud.hadoop.gcsio.GoogleCloudStorageTestUtils.HTTP_TRANSPORT;
import static com.google.cloud.hadoop.gcsio.GoogleCloudStorageTestUtils.OBJECT_NAME;
import static com.google.cloud.hadoop.gcsio.GoogleCloudStorageTestUtils.createReadChannel;
import static com.google.cloud.hadoop.gcsio.StorageResourceId.UNKNOWN_GENERATION_ID;
import static com.google.cloud.hadoop.util.testing.MockHttpTransportHelper.dataRangeResponse;
import static com.google.cloud.hadoop.util.testing.MockHttpTransportHelper.dataResponse;
import static com.google.cloud.hadoop.util.testing.MockHttpTransportHelper.jsonDataResponse;
import static com.google.cloud.hadoop.util.testing.MockHttpTransportHelper.jsonErrorResponse;
import static com.google.cloud.hadoop.util.testing.MockHttpTransportHelper.mockTransport;
import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertThrows;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageReadOptions.Fadvise;
import com.google.cloud.hadoop.util.testing.MockHttpTransportHelper.ErrorResponses;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GoogleCloudStorageReadChannel} class. */
@RunWith(JUnit4.class)
public class GoogleCloudStorageReadChannelTest {

  @Test
  public void metadataInitialization_eager() throws IOException {
    StorageObject object = newStorageObject(BUCKET_NAME, OBJECT_NAME);
    MockHttpTransport transport = mockTransport(jsonDataResponse(object));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(true).build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);

    assertThat(requests).hasSize(1);
    assertThat(readChannel.size()).isEqualTo(object.getSize().longValue());
    assertThat(requests).hasSize(1);
  }

  @Test
  public void metadataInitialization_lazy() throws IOException {
    StorageObject object = newStorageObject(BUCKET_NAME, OBJECT_NAME);
    MockHttpTransport transport = mockTransport(jsonDataResponse(object));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);

    assertThat(requests).isEmpty();
    assertThat(readChannel.size()).isEqualTo(object.getSize().longValue());
    assertThat(requests).hasSize(1);
  }

  @Test
  public void fadviseAuto_onForwardRead_switchesToRandom() throws IOException {
    int seekPosition = 5;
    byte[] testData = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09};
    byte[] testData2 = Arrays.copyOfRange(testData, seekPosition, testData.length);

    MockHttpTransport transport =
        mockTransport(
            // 1st read request response
            dataRangeResponse(Arrays.copyOfRange(testData, 1, testData.length), 1, testData.length),
            // 2nd read request response
            dataRangeResponse(testData2, seekPosition, testData2.length));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        newLazyReadOptionsBuilder()
            .setFadvise(Fadvise.AUTO)
            .setMinRangeRequestSize(1)
            .setInplaceSeekLimit(2)
            .build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);

    byte[] readBytes = new byte[1];

    readChannel.position(1);
    assertThat(readChannel.read(ByteBuffer.wrap(readBytes))).isEqualTo(1);
    assertThat(readBytes).isEqualTo(new byte[] {testData[1]});

    readChannel.position(seekPosition);
    assertThat(readChannel.randomAccess).isFalse();

    assertThat(readChannel.read(ByteBuffer.wrap(readBytes))).isEqualTo(1);
    assertThat(readBytes).isEqualTo(new byte[] {testData[seekPosition]});
    assertThat(readChannel.randomAccess).isTrue();

    List<String> rangeHeaders =
        requests.stream().map(r -> r.getHeaders().getRange()).collect(toList());

    assertThat(rangeHeaders).containsExactly("bytes=1-", "bytes=5-5").inOrder();
  }

  @Test
  public void fadviseAuto_onBackwardRead_switchesToRandom() throws IOException {
    int seekPosition = 5;
    byte[] testData = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09};
    byte[] testData2 = Arrays.copyOfRange(testData, seekPosition, testData.length);

    MockHttpTransport transport =
        mockTransport(
            // 1st read request response
            dataRangeResponse(testData2, seekPosition, testData2.length),
            // 2nd read request response
            dataRangeResponse(testData, 0, testData.length));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        newLazyReadOptionsBuilder().setFadvise(Fadvise.AUTO).setMinRangeRequestSize(1).build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);

    byte[] readBytes = new byte[1];

    readChannel.position(seekPosition);

    assertThat(readChannel.read(ByteBuffer.wrap(readBytes))).isEqualTo(1);
    assertThat(readBytes).isEqualTo(new byte[] {testData[seekPosition]});

    readChannel.position(0);
    assertThat(readChannel.randomAccess).isFalse();

    assertThat(readChannel.read(ByteBuffer.wrap(readBytes))).isEqualTo(1);
    assertThat(readBytes).isEqualTo(new byte[] {testData[0]});
    assertThat(readChannel.randomAccess).isTrue();

    List<String> rangeHeaders =
        requests.stream().map(r -> r.getHeaders().getRange()).collect(toList());

    assertThat(rangeHeaders).containsExactly("bytes=5-", "bytes=0-0").inOrder();
  }

  @Test
  public void footerPrefetch_reused() throws IOException {
    int footeSize = 2;
    byte[] testData = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09};
    int footerStart = testData.length - footeSize;
    byte[] footer = Arrays.copyOfRange(testData, footerStart, testData.length);

    MockHttpTransport transport =
        mockTransport(
            // Footer prefetch response
            dataRangeResponse(footer, footerStart, testData.length),
            // Footer read miss request response
            dataResponse(new byte[] {testData[footerStart - 1]}));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        newLazyReadOptionsBuilder()
            .setFadvise(Fadvise.RANDOM)
            .setMinRangeRequestSize(footeSize)
            .build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);
    assertThat(requests).isEmpty();

    byte[] readBytes = new byte[2];

    // Force lazy footer prefetch
    readChannel.position(footerStart);
    assertThat(readChannel.read(ByteBuffer.wrap(readBytes))).isEqualTo(2);
    assertThat(readChannel.size()).isEqualTo(testData.length);
    assertThat(readBytes).isEqualTo(Arrays.copyOfRange(testData, footerStart, testData.length));

    readChannel.position(footerStart - 1);

    assertThat(readChannel.read(ByteBuffer.wrap(readBytes))).isEqualTo(2);
    assertThat(readBytes)
        .isEqualTo(Arrays.copyOfRange(testData, footerStart - 1, testData.length - 1));

    List<String> rangeHeaders =
        requests.stream().map(r -> r.getHeaders().getRange()).collect(toList());

    assertThat(rangeHeaders).containsExactly("bytes=8-9", "bytes=7-7").inOrder();
  }

  @Test
  public void read_whenBufferIsEmpty() throws IOException {
    ByteBuffer emptyBuffer = ByteBuffer.wrap(new byte[0]);

    Storage storage = new Storage(HTTP_TRANSPORT, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadChannel readChannel =
        createReadChannel(storage, newLazyReadOptionsBuilder().build());

    assertThat(readChannel.read(emptyBuffer)).isEqualTo(0);
  }

  @Test
  public void read_whenPositionIsEqualToSize() throws IOException {
    ByteBuffer readBuffer = ByteBuffer.wrap(new byte[1]);
    StorageObject object = newStorageObject(BUCKET_NAME, OBJECT_NAME);

    MockHttpTransport transport = mockTransport(jsonDataResponse(object.setSize(BigInteger.ZERO)));

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadChannel readChannel =
        createReadChannel(storage, GoogleCloudStorageReadOptions.DEFAULT);

    assertThat(readChannel.position()).isEqualTo(readChannel.size());
    assertThat(readChannel.read(readBuffer)).isEqualTo(-1);
  }

  @Test
  public void size_whenObjectIsGzipEncoded_shouldBeSetToMaxLongValue() throws IOException {
    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(
                newStorageObject(BUCKET_NAME, OBJECT_NAME).setContentEncoding("gzip")));
    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadChannel readChannel =
        createReadChannel(storage, GoogleCloudStorageReadOptions.DEFAULT);

    assertThat(readChannel.size()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void initMetadata_throwsException_whenReadConsistencyEnabledAndGenerationIsNull()
      throws IOException {
    Storage storage = new Storage(HTTP_TRANSPORT, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadOptions options = newLazyReadOptionsBuilder().build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                readChannel.initMetadata("gzip", /* sizeFromMetadata= */ 1, UNKNOWN_GENERATION_ID));

    assertThat(e).hasMessageThat().contains("Generation parameter of -1 is invalid");
  }

  @Test
  public void initMetadata_succeeds_whenReadConsistencyEnabledAndGenerationIsValid()
      throws IOException {
    Storage storage = new Storage(HTTP_TRANSPORT, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadOptions options = newLazyReadOptionsBuilder().build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);

    readChannel.initMetadata("gzip", /* sizeFromMetadata= */ 1, /* generation= */ 1234L);
  }

  @Test
  public void initGeneration_hasGenerationId() throws IOException {
    StorageObject storageObject = newStorageObject(BUCKET_NAME, OBJECT_NAME);
    MockHttpTransport transport = mockTransport(jsonDataResponse(storageObject));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);
    // initialize metadata
    readChannel.size();
    assertThat(readChannel.generation()).isEqualTo(storageObject.getGeneration());
  }

  @Test
  public void lazyInitGeneration_succeeds_whenReadConsistencyStrict() throws IOException {
    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(newStorageObject(BUCKET_NAME, OBJECT_NAME).setGeneration(5L)));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options);
    // initialize metadata
    readChannel.size();
    assertThat(readChannel.generation()).isEqualTo(5L);
  }

  @Test
  public void lazyReadFileAtSpecificGeneration_fails_ifGenerationChanged() throws IOException {
    long requestedGeneration = 5L;
    long actualGeneration = 342L;

    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(
                newStorageObject(BUCKET_NAME, OBJECT_NAME).setGeneration(actualGeneration)));

    Storage storage =
        new Storage(transport, GsonFactory.getDefaultInstance(), new ArrayList<>()::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();

    GoogleCloudStorageReadChannel readChannel =
        createReadChannel(storage, options, requestedGeneration);

    IllegalStateException exception = assertThrows(IllegalStateException.class, readChannel::size);

    assertThat(exception)
        .hasMessageThat()
        .contains(
            String.format(
                "Provided generation (%d) should be equal to fetched generation (%d)",
                requestedGeneration, actualGeneration));
  }

  @Test
  public void eagerReadFileAtSpecificGeneration_fails_ifGenerationChanged() throws IOException {
    long requestedGeneration = 5L;
    long actualGeneration = 342L;

    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(
                newStorageObject(BUCKET_NAME, OBJECT_NAME).setGeneration(actualGeneration)));

    Storage storage =
        new Storage(transport, GsonFactory.getDefaultInstance(), new ArrayList<>()::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(true).build();

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> createReadChannel(storage, options, requestedGeneration));

    assertThat(exception)
        .hasMessageThat()
        .contains(
            String.format(
                "Provided generation (%d) should be equal to fetched generation (%d)",
                requestedGeneration, actualGeneration));
  }

  @Test
  public void lazyReadFileAtSpecificGeneration_succeeds_whenReadConsistencyStrict()
      throws IOException {
    long generation = 5L;
    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(newStorageObject(BUCKET_NAME, OBJECT_NAME).setGeneration(generation)));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options, generation);
    // initialize metadata
    readChannel.size();
    assertThat(readChannel.generation()).isEqualTo(generation);
  }

  @Test
  public void eagerReadFileAtSpecificGeneration_succeeds_whenReadConsistencyStrict()
      throws IOException {
    long generation = 5L;
    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(newStorageObject(BUCKET_NAME, OBJECT_NAME).setGeneration(generation)));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(true).build();

    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options, generation);
    assertThat(readChannel.generation()).isEqualTo(generation);
  }

  @Test
  public void lazyReadFileAtSpecificGeneration_fails_whenReadConsistencyStrict()
      throws IOException {
    long generation = 5L;
    MockHttpTransport transport = mockTransport(jsonErrorResponse(ErrorResponses.NOT_FOUND));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();
    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options, generation);
    assertThrows(FileNotFoundException.class, readChannel::size);
  }

  @Test
  public void eagerReadFileAtSpecificGeneration_fails_whenReadConsistencyStrict()
      throws IOException {
    long generation = 5L;
    MockHttpTransport transport = mockTransport(jsonErrorResponse(ErrorResponses.NOT_FOUND));

    List<HttpRequest> requests = new ArrayList<>();

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), requests::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(true).build();
    assertThrows(
        FileNotFoundException.class, () -> createReadChannel(storage, options, generation));
  }

  @Test
  public void afterRetry_subsequentReads_succeed() throws IOException {
    long generation = 5L;
    MockHttpTransport transport =
        mockTransport(
            jsonErrorResponse(ErrorResponses.NOT_FOUND),
            jsonDataResponse(newStorageObject(BUCKET_NAME, OBJECT_NAME).setGeneration(generation)));

    Storage storage =
        new Storage(transport, GsonFactory.getDefaultInstance(), new ArrayList<>()::add);

    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();
    GoogleCloudStorageReadChannel readChannel = createReadChannel(storage, options, generation);

    assertThrows(FileNotFoundException.class, readChannel::size);

    assertThat(readChannel.size()).isNotEqualTo(0);
    assertThat(readChannel.generation()).isEqualTo(generation);
  }

  @Test
  public void read_gzipEncoded_shouldReadAllBytes() throws IOException {
    byte[] testData = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(newStorageObject(BUCKET_NAME, OBJECT_NAME).setContentEncoding("gzip")),
            dataRangeResponse(testData, 0, testData.length));

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadChannel readChannel =
        createReadChannel(storage, GoogleCloudStorageReadOptions.DEFAULT);

    assertThat(readChannel.size()).isEqualTo(Long.MAX_VALUE);
    assertThat(readChannel.read(ByteBuffer.wrap(new byte[testData.length + 1])))
        .isEqualTo(testData.length);
    assertThat(readChannel.size()).isEqualTo(testData.length);
  }

  @Test
  public void open_gzipContentEncoding_succeeds_whenContentEncodingSupported() throws Exception {
    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(
                newStorageObject(BUCKET_NAME, OBJECT_NAME).setContentEncoding("gzip")));

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setSupportGzipEncoding(true).build();

    try (GoogleCloudStorageReadChannel channel = createReadChannel(storage, readOptions)) {
      channel.position();
    }
  }

  @Test
  public void open_gzipContentEncoding_throwsIOException_ifContentEncodingNotSupported()
      throws Exception {
    MockHttpTransport transport =
        mockTransport(
            jsonDataResponse(
                newStorageObject(BUCKET_NAME, OBJECT_NAME).setContentEncoding("gzip")));

    Storage storage = new Storage(transport, GsonFactory.getDefaultInstance(), r -> {});

    GoogleCloudStorageReadOptions readOptions =
        GoogleCloudStorageReadOptions.builder().setSupportGzipEncoding(false).build();

    IOException e = assertThrows(IOException.class, () -> createReadChannel(storage, readOptions));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Cannot read GZIP encoded files - content encoding support is disabled.");
  }

  private static GoogleCloudStorageReadOptions.Builder newLazyReadOptionsBuilder() {
    return GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false);
  }
}
