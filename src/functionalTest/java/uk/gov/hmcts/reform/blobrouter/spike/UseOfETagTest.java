package uk.gov.hmcts.reform.blobrouter.spike;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.specialized.BlobInputStream;
import com.google.common.collect.ImmutableMap;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import uk.gov.hmcts.reform.blobrouter.FunctionalTestBase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class UseOfETagTest extends FunctionalTestBase {

    BlobContainerClient containerClient;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        containerClient = blobRouterStorageClient.getBlobContainerClient(BULK_SCAN_CONTAINER + "-rejected");
    }

    @Test
    void should_reject_to_download_when_etag_not_match() {
        var blobName = uploadBlobAndGetName();
        var blockBlobClient = containerClient.getBlobClient(blobName);
        var requestConditions = new BlobRequestConditions().setIfMatch("NONE");

        assertThatCode(() -> {
            try (BlobInputStream blobInputStream = blockBlobClient.openInputStream(new BlobRange(0), requestConditions)) {
                // do smth with blob
                var actualBlobContents = new String(blobInputStream.readAllBytes());
                assertThat(actualBlobContents).isEqualTo(blobName + "-contents");
            }
        })
            .asInstanceOf(InstanceOfAssertFactories.type(IOException.class))
            .satisfies(exception ->
                assertThat(exception)
                    .hasCauseInstanceOf(BlobStorageException.class)
                    .getCause()
                    .asInstanceOf(InstanceOfAssertFactories.type(BlobStorageException.class))
                    .satisfies(storageExcetion -> {
                        assertThat(storageExcetion.getErrorCode()).isEqualTo(BlobErrorCode.CONDITION_NOT_MET);
                        assertThat(storageExcetion.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED.value());
                    })
            );
    }

    @Test
    void should_download_the_blob_when_etag_is_correct() throws IOException {
        var blobName = uploadBlobAndGetName();
        var blockBlobClient = containerClient.getBlobClient(blobName);
        String originalEtag = blockBlobClient.getProperties().getETag();
        assertThat(originalEtag).isNotEmpty();

        var requestConditions = new BlobRequestConditions().setIfMatch(originalEtag);

        try (BlobInputStream blobInputStream = blockBlobClient.openInputStream(new BlobRange(0), requestConditions)) {
            // do smth with blob
            var actualBlobContents = new String(blobInputStream.readAllBytes());
            assertThat(actualBlobContents).isEqualTo(blobName + "-contents");
        }
    }

    /*
INIT: 4
INIT: 2
INIT: 8
INIT: 10
INIT: 1
INIT: 6
INIT: 3
INIT: 5
INIT: 7
METADATA UPDATED: 8
INIT: 9
FINISH METADATA: key=one; value=8
     */
    @Test
    void should_update_metadata() {
        var blobName = uploadBlobAndGetName();

        var blockBlobClients = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> new AbstractMap.SimpleEntry<>(i, containerClient.getBlobClient(blobName)))
            .collect(Collectors.toMap(
                AbstractMap.SimpleEntry::getKey,
                entry -> Tuples.of(entry.getValue(), entry.getValue().getProperties().getETag())
            ));

        System.out.println("SIZE: " + blockBlobClients.size());

        // assert all clients are unique
        assertThat(blockBlobClients
                       .values()
                       .stream()
                       .map(tuple -> tuple.getT1().hashCode())
                       .collect(Collectors.toSet())
        ).hasSize(blockBlobClients.size());
        // assert all etags are the same
        assertThat(blockBlobClients
                       .values()
                       .stream()
                       .map(Tuple2::getT2)
                       .collect(Collectors.toSet())
        ).hasSize(1)
            .first()
            .asString()
            .isNotEmpty();

        blockBlobClients
            .entrySet()
            .parallelStream()
            .forEach(entry -> {
                try {
                    System.out.println("INIT: " + entry.getKey());
                    var blobClient = entry.getValue().getT1();
                    String originalETag = entry.getValue().getT2();

                    blobClient.setMetadataWithResponse(
                        ImmutableMap.of("one", Integer.toString(entry.getKey())),
                        new BlobRequestConditions().setIfMatch(originalETag),
                        null,
                        Context.NONE
                    );
                    String newEtag = blobClient.getProperties().getETag();

                    System.out.println("METADATA UPDATED: " + entry.getKey());

                    assertThat(newEtag).isNotEqualTo(originalETag);
                } catch (BlobStorageException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BlobErrorCode.CONDITION_NOT_MET);
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED.value());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        var lastClient = containerClient.getBlobClient(blobName);
        lastClient.getProperties().getMetadata().forEach(
            (key, value) -> System.out.println("FINISH METADATA: key=" + key + "; value=" + value)
        );
    }

    /*
INIT: 2
INIT: 8
INIT: 10
INIT: 4
INIT: 1
INIT: 6
INIT: 5
INIT: 9
INIT: 7
METADATA UPDATED: 2
INIT: 3
METADATA UPDATED: 5
FINISH METADATA: key=one; value=5
     */
    @Test
    void should_update_metadata_v2() {
        var blobName = uploadBlobAndGetName();

        var blockBlobClients = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> new AbstractMap.SimpleEntry<>(i, containerClient.getBlobClient(blobName)))
            .collect(Collectors.toMap(
                AbstractMap.SimpleEntry::getKey,
                AbstractMap.SimpleEntry::getValue
            ));

        System.out.println("SIZE: " + blockBlobClients.size());

        // assert all clients are unique
        assertThat(blockBlobClients
                       .values()
                       .stream()
                       .map(BlobClient::hashCode)
                       .collect(Collectors.toSet())
        ).hasSize(blockBlobClients.size());

        blockBlobClients
            .entrySet()
            .parallelStream()
            .forEach(entry -> {
                try {
                    System.out.println("INIT: " + entry.getKey());
                    BlobClient blobClient = entry.getValue();
                    String originalETag = blobClient.getProperties().getETag();
                    blobClient.setMetadataWithResponse(
                        ImmutableMap.of("one", Integer.toString(entry.getKey())),
                        new BlobRequestConditions().setIfMatch(originalETag),
                        null,
                        Context.NONE
                    );
                    String newEtag = blobClient.getProperties().getETag();

                    System.out.println("METADATA UPDATED: " + entry.getKey());

                    assertThat(newEtag).isNotEqualTo(originalETag);
                } catch (BlobStorageException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BlobErrorCode.CONDITION_NOT_MET);
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED.value());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        var lastClient = containerClient.getBlobClient(blobName);
        lastClient.getProperties().getMetadata().forEach(
            (key, value) -> System.out.println("FINISH METADATA: key=" + key + "; value=" + value)
        );
    }

    /*
    FIRST RUN:
INIT: 10
INIT: 2
INIT: 1
INIT: 8
INIT: 6
INIT: 4
DOWNLOADED: 8
DOWNLOADED: 4
DOWNLOADED: 2
DOWNLOADED: 6
DOWNLOADED: 1
DOWNLOADED: 10
INIT: 9
INIT: 5
INIT: 7
INIT: 3
DOWNLOADED: 3
DOWNLOADED: 5
DOWNLOADED: 7
DOWNLOADED: 9
     */
    @Test
    void should_download_the_blob_when_etag_is_correct_parallel_case() {
        var blobName = uploadBlobAndGetName();

        var blockBlobClients = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> new AbstractMap.SimpleEntry<>(i, containerClient.getBlobClient(blobName)))
            .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));

        System.out.println("SIZE: " + blockBlobClients.size());

        assertThat(blockBlobClients
                       .values()
                       .stream()
                       .map(BlobClient::hashCode)
                       .collect(Collectors.toSet())
        ).hasSize(blockBlobClients.size());

        blockBlobClients
            .entrySet()
            .parallelStream()
            .forEach(entry -> {
                try {
                    System.out.println("INIT: " + entry.getKey());
                    String originalETag = entry.getValue().getProperties().getETag();
                    var requestConditions = new BlobRequestConditions().setIfMatch(originalETag);

                    try (BlobInputStream blobInputStream = entry.getValue().openInputStream(
                        new BlobRange(0), requestConditions
                    )) {
                        System.out.println("DOWNLOADED: " + entry.getKey());
                        // do smth with blob
                        var actualBlobContents = new String(blobInputStream.readAllBytes());
                        assertThat(actualBlobContents).isEqualTo(blobName + "-contents");
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
    }

    /*
    FIRST RUN:
INIT: 10
INIT: 6
INIT: 4
INIT: 1
INIT: 2
INIT: 8
DOWNLOADED: 8
DOWNLOADED: 4
DOWNLOADED: 2
DOWNLOADED: 6
DOWNLOADED: 1
DOWNLOADED: 10
UPDATED: 10

java.lang.RuntimeException: com.azure.storage.blob.models.BlobStorageException: Status code 412, "<?xml version="1.0" encoding="utf-8"?><Error><Code>ConditionNotMet</Code><Message>The condition specified using HTTP conditional header(s) is not met.
     */
    @Test
    void should_reject_to_download_when_etag_not_match_parallel_case() {
        var blobName = uploadBlobAndGetName();

        var blockBlobClients = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> new AbstractMap.SimpleEntry<>(i, containerClient.getBlobClient(blobName)))
            .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));

        System.out.println("SIZE: " + blockBlobClients.size());

        blockBlobClients
            .entrySet()
            .parallelStream()
            .forEach(entry -> {
                try {
                    System.out.println("INIT: " + entry.getKey());
                    String originalETag = entry.getValue().getProperties().getETag();
                    var requestConditions = new BlobRequestConditions().setIfMatch(originalETag);

                    try (BlobInputStream blobInputStream = entry.getValue().openInputStream(
                        new BlobRange(0),
                        requestConditions
                    )) {
                        System.out.println("DOWNLOADED: " + entry.getKey());
                        // do smth with blob
                        var actualBlobContents = new String(blobInputStream.readAllBytes());
                        assertThat(actualBlobContents).isEqualTo(blobName + "-contents");
                        // update
                        entry.getValue().uploadWithResponse(new BlobParallelUploadOptions(new ByteArrayInputStream((actualBlobContents + "-updated").getBytes()), actualBlobContents.length() + 8).setRequestConditions(requestConditions), Context.NONE);
                        System.out.println("UPDATED: " + entry.getKey());
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
    }

    private String uploadBlobAndGetName() {
        var blobName = "spike-use-etag-%d" + System.currentTimeMillis();
        var blobContents = (blobName + "-contents").getBytes();

        containerClient
            .getBlobClient(blobName)
            .getBlockBlobClient()
            .upload(
                new ByteArrayInputStream(blobContents),
                blobContents.length,
                true
            );

        return blobName;
    }
}
