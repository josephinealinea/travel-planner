package com.josephinealinea.planner.publish.infra;

import com.josephinealinea.planner.publish.infra.PageStore.Area;
import com.josephinealinea.planner.publish.infra.PageStore.PageFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract against MinIO standing in for R2 — the same S3 API, reachable
 * without an account. Skipped, not failed, on a machine without Docker.
 *
 * MinIO no longer publishes to Docker Hub, so the image comes from quay.io,
 * pinned to a release.
 */
@Testcontainers(disabledWithoutDocker = true)
class R2PageStoreTest extends PageStoreContract {

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                    .asCompatibleSubstituteFor("minio/minio"));

    /** For arranging and inspecting the bucket behind the store's back. */
    private static S3Client admin;

    private String bucket;
    private R2PageStore store;

    @BeforeAll
    static void connect() {
        admin = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .forcePathStyle(true)
                .build();
    }

    @AfterAll
    static void disconnect() {
        if (admin != null) admin.close();
    }

    /** A bucket per test is the cheapest way to an empty store. */
    @BeforeEach
    void setUp() {
        bucket = "pages-" + UUID.randomUUID().toString().substring(0, 8);
        admin.createBucket(create -> create.bucket(bucket));
        store = new R2PageStore(URI.create(MINIO.getS3URL()), bucket,
                MINIO.getUserName(), MINIO.getPassword());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Override
    PageStore store() {
        return store;
    }

    private String contentType(String key) {
        return admin.headObject(head -> head.bucket(bucket).key(key)).contentType();
    }

    /** The Function serves these objects' bodies; the keys are its whole contract. */
    @Test
    void usesThePublishedAndPendingPrefixes() {
        store.write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "trip");
        store.write(Area.PUBLISHED, SLUG, "sam", PageFile.DATA, "{}");
        store.write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged");

        assertThat(admin.listObjectsV2(list -> list.bucket(bucket)).contents())
                .extracting(S3Object::key)
                .containsExactlyInAnyOrder(
                        "published/latam/index.html",
                        "published/latam/m/sam/trip.json",
                        "pending/latam/index.html");
    }

    @Test
    void storesEachFileWithItsContentType() {
        store.write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "trip");
        store.write(Area.PUBLISHED, SLUG, null, PageFile.DATA, "{}");

        assertThat(contentType("published/latam/index.html")).isEqualTo("text/html; charset=utf-8");
        assertThat(contentType("published/latam/trip.json")).isEqualTo("application/json");
    }

    /** A server-side copy must not drop the type, or the Function would serve octet-stream. */
    @Test
    void promotingKeepsTheContentType() {
        store.write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged");
        store.write(Area.PENDING, SLUG, "sam", PageFile.DATA, "{}");

        store.promote(SLUG);

        assertThat(contentType("published/latam/index.html")).isEqualTo("text/html; charset=utf-8");
        assertThat(contentType("published/latam/m/sam/trip.json")).isEqualTo("application/json");
    }

    /** Built on first use, so an instance that never publishes never pays for the SDK. */
    @Test
    void noClientIsBuiltUntilItIsNeeded() throws Exception {
        var fresh = new R2PageStore(URI.create("http://127.0.0.1:1"), "unused", "k", "s");
        var field = R2PageStore.class.getDeclaredField("client");
        field.setAccessible(true);

        assertThat(field.get(fresh)).isNull();
        fresh.close();
    }
}
