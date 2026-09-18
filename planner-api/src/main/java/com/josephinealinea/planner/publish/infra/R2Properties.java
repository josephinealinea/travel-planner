package com.josephinealinea.planner.publish.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * The Cloudflare R2 bucket published pages go to when app.publish.store is
 * {@code r2}. Read only in that mode — see R2PageStoreConfig — so a filesystem
 * install needs none of these keys.
 *
 * Its own record under {@code app.r2} rather than components on
 * AppProperties.Publish: that record is constructed positionally across the
 * test suite, and one more component would break every one of those lines.
 *
 * @param accountId the Cloudflare account id, which is what the S3 endpoint is
 *   named after
 * @param bucket one private bucket holding both {@code published/} and
 *   {@code pending/}; only the Pages Function decides what is public
 */
@ConfigurationProperties(prefix = "app.r2")
public record R2Properties(String accountId,
                           String bucket,
                           String accessKeyId,
                           String secretAccessKey) {

    /** R2's S3-compatible endpoint for this account. */
    public URI endpoint() {
        return URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
    }
}
