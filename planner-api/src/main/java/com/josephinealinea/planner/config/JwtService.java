package com.josephinealinea.planner.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.storage.YamlPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * Signs and verifies the session JWT with HS256 via Nimbus (which ships with
 * Spring Security's JOSE support).
 *
 * If no secret is configured one is generated and persisted to data/secret.yml,
 * so restarting the API in local development does not sign everybody out.
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "travel-planner";

    private final byte[] secret;
    private final java.time.Duration ttl;

    public JwtService(AppProperties props, YamlStore store, YamlPaths paths) {
        this.ttl = props.security().sessionTtl();
        this.secret = resolveSecret(props, store, paths);
    }

    public String issue(String userId) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(userId)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ttl)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not issue a session token", e);
        }
    }

    /** The subject (user id) if the token is well-formed, signed and unexpired. */
    public Optional<String> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secret))) return Optional.empty();
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) return Optional.empty();
            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.toInstant().isBefore(Instant.now())) return Optional.empty();
            return Optional.ofNullable(claims.getSubject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public java.time.Duration ttl() {
        return ttl;
    }

    private static byte[] resolveSecret(AppProperties props, YamlStore store, YamlPaths paths) {
        String configured = props.security().jwtSecret();
        if (configured != null && !configured.isBlank()) {
            if (configured.getBytes().length < 32) {
                throw new IllegalStateException(
                        "app.security.jwt-secret must be at least 32 bytes for HS256.");
            }
            return configured.getBytes();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> stored = store.read(paths.secret(), Map.class, null);
        if (stored != null && stored.get("jwtSecret") != null) {
            return Base64.getDecoder().decode(stored.get("jwtSecret"));
        }

        byte[] generated = new byte[48];
        new SecureRandom().nextBytes(generated);
        store.write(paths.secret(), Map.of("jwtSecret", Base64.getEncoder().encodeToString(generated)));
        log.warn("No app.security.jwt-secret set — generated one and saved it to {}. "
                + "Set JWT_SECRET explicitly before deploying.", paths.secret());
        return generated;
    }

    public static ApiException expired() {
        return ApiException.unauthorized("error.session.expired");
    }
}
