package com.josephinealinea.planner.publish.infra;

import com.josephinealinea.planner.shared.Slugs;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pages in one private Cloudflare R2 bucket, over R2's S3-compatible API:
 * {@code published/<slug>/…} and {@code pending/<slug>/…}. The bucket is never
 * public — the Pages Function for /p/* reads {@code published/} and nothing
 * else, and the members-only preview still goes through the API.
 *
 * Mirrors FileSystemPageStore key for path, so the layout under each prefix is
 * the directory tree the filesystem store writes. Two differences follow from
 * object storage having no directories:
 * <ul>
 *   <li>promote is copy-then-delete rather than a rename, keeping the
 *       filesystem's ordering — the old live page goes first, so a failure
 *       part-way leaves no page rather than a half-merged one;</li>
 *   <li>removing a "directory" is listing a prefix and deleting what is under
 *       it. The prefix always ends in "/", so removing {@code latam} can never
 *       touch {@code latam-2026}.</li>
 * </ul>
 *
 * The S3 client is built on first use, not at startup: most requests to a
 * cold instance never publish, and the SDK's initialisation is not free.
 */
@Component
@ConditionalOnProperty(name = "app.publish.store", havingValue = "r2")
public class R2PageStore implements PageStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(R2PageStore.class);

    private final URI endpoint;
    private final String bucket;
    private final String accessKeyId;
    private final String secretAccessKey;

    private volatile S3Client client;

    @Autowired
    public R2PageStore(R2Properties props) {
        this(requireSet(props).endpoint(), props.bucket(),
                props.accessKeyId(), props.secretAccessKey());
    }

    /**
     * Any S3-compatible endpoint. The contract test points this at MinIO,
     * which is why path-style addressing is on — R2 accepts either.
     */
    public R2PageStore(URI endpoint, String bucket, String accessKeyId, String secretAccessKey) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
    }

    /**
     * Fails at startup, with the keys named, rather than on somebody's first
     * publish with an SDK error about a malformed host.
     */
    private static R2Properties requireSet(R2Properties props) {
        List<String> missing = new ArrayList<>();
        if (blank(props.accountId())) missing.add("app.r2.account-id");
        if (blank(props.bucket())) missing.add("app.r2.bucket");
        if (blank(props.accessKeyId())) missing.add("app.r2.access-key-id");
        if (blank(props.secretAccessKey())) missing.add("app.r2.secret-access-key");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("app.publish.store is r2, but "
                    + String.join(", ", missing) + " is not set");
        }
        return props;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ── PageStore ───────────────────────────────────────────────────────────

    @Override
    public void write(Area area, String slug, String member, PageFile file, String contents) {
        String key = key(area, slug, member, file);
        s3().putObject(put -> put.bucket(bucket).key(key).contentType(file.contentType()),
                RequestBody.fromString(contents, StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> read(Area area, String slug, String member, PageFile file) {
        String key = key(area, slug, member, file);
        try {
            return Optional.of(s3().getObjectAsBytes(get -> get.bucket(bucket).key(key))
                    .asUtf8String());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void clearMemberPages(Area area, String slug) {
        deleteAll(keysUnder(tripPrefix(area, slug) + "m/"));
    }

    @Override
    public void remove(Area area, String slug) {
        deleteAll(keysUnder(tripPrefix(area, slug)));
    }

    @Override
    public boolean promote(String slug) {
        String stagedPrefix = tripPrefix(Area.PENDING, slug);
        String livePrefix = tripPrefix(Area.PUBLISHED, slug);
        if (!exists(stagedPrefix + PageFile.INDEX.filename())) return false;

        // The old page goes first, as on the filesystem: a failure between
        // here and the last copy leaves no page rather than a half-merged one.
        remove(Area.PUBLISHED, slug);

        List<String> staged = keysUnder(stagedPrefix);
        for (String key : staged) {
            // A server-side copy keeps each object's content type.
            s3().copyObject(copy -> copy.sourceBucket(bucket).sourceKey(key)
                    .destinationBucket(bucket)
                    .destinationKey(livePrefix + key.substring(stagedPrefix.length())));
        }
        // Only once everything is live, so a failed copy can be retried from
        // a staged page that is still whole.
        deleteAll(staged);
        return true;
    }

    /**
     * One ListObjectsV2 call for the whole trip. A page is two objects, so
     * the 1000-key page only runs out past ~500 members; the loop is there
     * for correctness, not because it is expected to turn.
     */
    @Override
    public Set<String> publishedMemberPages(String slug) {
        String prefix = tripPrefix(Area.PUBLISHED, slug) + "m/";
        String suffix = "/" + PageFile.INDEX.filename();
        Set<String> members = new TreeSet<>();
        for (String key : keysUnder(prefix)) {
            String rest = key.substring(prefix.length());
            // Exactly <member>/index.html — nothing nested deeper counts.
            if (rest.endsWith(suffix) && rest.indexOf('/') == rest.length() - suffix.length()) {
                members.add(rest.substring(0, rest.length() - suffix.length()));
            }
        }
        return members;
    }

    // ── keys ────────────────────────────────────────────────────────────────

    /** Always ends in "/", so a prefix never matches a longer slug. */
    private static String tripPrefix(Area area, String slug) {
        String root = area == Area.PUBLISHED ? "published/" : "pending/";
        return root + Slugs.requireSafe(slug) + "/";
    }

    private static String key(Area area, String slug, String member, PageFile file) {
        String dir = member == null
                ? tripPrefix(area, slug)
                : tripPrefix(area, slug) + "m/" + Slugs.requireSafe(member) + "/";
        return dir + file.filename();
    }

    // ── S3 plumbing ─────────────────────────────────────────────────────────

    private boolean exists(String key) {
        try {
            s3().headObject(head -> head.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    private List<String> keysUnder(String prefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            String continuation = token;
            var page = s3().listObjectsV2(list -> list.bucket(bucket).prefix(prefix)
                    .continuationToken(continuation));
            page.contents().stream().map(S3Object::key).forEach(keys::add);
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
        return keys;
    }

    /**
     * One DeleteObject per key rather than a batch DeleteObjects. A trip is a
     * handful of objects, and the batch call is the one S3 operation that
     * requires a request checksum — exactly what R2 has historically been
     * particular about.
     */
    private void deleteAll(List<String> keys) {
        for (String key : keys) {
            s3().deleteObject(delete -> delete.bucket(bucket).key(key));
        }
    }

    private S3Client s3() {
        S3Client existing = client;
        if (existing != null) return existing;
        synchronized (this) {
            if (client == null) {
                client = S3Client.builder()
                        .httpClient(UrlConnectionHttpClient.create())
                        .endpointOverride(endpoint)
                        // R2 has one region, spelled "auto".
                        .region(Region.of("auto"))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                        .forcePathStyle(true)
                        // Recent SDKs checksum every request and validate every
                        // response by default, sending headers R2 has rejected.
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .build();
                log.info("Published pages go to bucket \"{}\" at {}", bucket, endpoint);
            }
            return client;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        S3Client existing = client;
        if (existing != null) existing.close();
    }
}
