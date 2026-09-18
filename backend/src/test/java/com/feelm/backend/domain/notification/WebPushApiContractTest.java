package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.interaso.webpush.VapidKeys;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-075~077: 공개 VAPID 설정과 사용자별 브라우저 구독 계약. */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "feelm.notification.web-push.poll-interval=24h"
        })
@AutoConfigureMockMvc
class WebPushApiContractTest extends SharedPostgresContractTest {

    private static final VapidKeys VAPID_KEYS = VapidKeys.generate();
    private static final String PUBLIC_KEY =
            Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(VAPID_KEYS.getApplicationServerKey());
    private static final String PRIVATE_KEY =
            Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(unsigned32(VAPID_KEYS.getPrivateKey().getS()));
    private static final String AUTH =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

    @DynamicPropertySource
    static void webPushProperties(DynamicPropertyRegistry registry) {
        registry.add("feelm.notification.web-push.mode", () -> "vapid");
        registry.add("feelm.notification.web-push.public-key", () -> PUBLIC_KEY);
        registry.add("feelm.notification.web-push.private-key", () -> PRIVATE_KEY);
        registry.add("feelm.notification.web-push.subject", () -> "mailto:test@feelm.local");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;
    @Autowired WebPushProperties properties;
    @Autowired WebPushDeliveryRepository deliveries;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        user(10);
        user(20);
    }

    @Test
    void getConfigReturnsOnlyPublicVapidKey() throws Exception {
        mvc.perform(get("/api/v1/notifications/push/config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/push/config").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publicKey").value(PUBLIC_KEY))
                .andExpect(jsonPath("$.privateKey").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist());

        jdbc.update("UPDATE users SET status='WITHDRAWN',withdrawn_at=now() WHERE id=?", 10L);
        mvc.perform(get("/api/v1/notifications/push/config").header("Authorization", bearer(10)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerTransfersEndpointWithoutCrossAccountDelivery() throws Exception {
        assertThat(properties.allowedEndpointHostSuffixes()).contains("fcm.googleapis.com");
        assertThat(properties.allows(java.net.URI.create("https://fcm.googleapis.com/fcm/send/x")))
                .isTrue();
        long subscriptionId = register(10, "https://fcm.googleapis.com/fcm/send/device-1");
        long partyNotification = partyNotification(10);
        assertThat(deliveries.enqueue(partyNotification)).isOne();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM web_push_deliveries"
                                        + " WHERE notification_id=? AND status='PENDING'",
                                Long.class,
                                partyNotification))
                .isOne();
        assertThat(register(10, "https://fcm.googleapis.com/fcm/send/device-1"))
                .isEqualTo(subscriptionId);

        assertThat(register(20, "https://fcm.googleapis.com/fcm/send/device-1"))
                .isEqualTo(subscriptionId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM web_push_deliveries"
                                        + " WHERE notification_id=?",
                                String.class,
                                partyNotification))
                .isEqualTo("CANCELED");

        mvc.perform(
                        delete(
                                        "/api/v1/notifications/push/subscriptions/{subscriptionId}",
                                        subscriptionId)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        delete(
                                        "/api/v1/notifications/push/subscriptions/{subscriptionId}",
                                        subscriptionId)
                                .header("Authorization", bearer(20)))
                .andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForMap(
                                "SELECT active,endpoint,p256dh_key,auth_secret"
                                        + " FROM web_push_subscriptions WHERE id=?",
                                subscriptionId))
                .containsEntry("active", false)
                .containsEntry("endpoint", null)
                .containsEntry("p256dh_key", null)
                .containsEntry("auth_secret", null);
    }

    @Test
    void registerCreatesOwnedSubscription() throws Exception {
        long expirationTime = Instant.now().plusSeconds(3_600).toEpochMilli();
        long subscriptionId =
                register(
                        10,
                        "https://fcm.googleapis.com/fcm/send/owned",
                        PUBLIC_KEY,
                        AUTH,
                        expirationTime);

        assertThat(
                        jdbc.queryForMap(
                                "SELECT user_id,active,expiration_at,capability_version"
                                        + " FROM web_push_subscriptions WHERE id=?",
                                subscriptionId))
                .containsEntry("user_id", 10L)
                .containsEntry("active", true)
                .containsEntry("capability_version", 1L);
        assertThat(
                        ((Timestamp)
                                        jdbc.queryForMap(
                                                        "SELECT expiration_at FROM"
                                                                + " web_push_subscriptions WHERE id=?",
                                                        subscriptionId)
                                                .get("expiration_at"))
                                .toInstant())
                .isEqualTo(Instant.ofEpochMilli(expirationTime));
    }

    @Test
    void registerIsIdempotentAndRefreshesKeys() throws Exception {
        String endpoint = "https://fcm.googleapis.com/fcm/send/refresh";
        long subscriptionId = register(10, endpoint);
        VapidKeys refreshedKeys = VapidKeys.generate();
        String refreshedPublicKey =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(refreshedKeys.getApplicationServerKey());
        byte[] refreshedAuthBytes = new byte[16];
        refreshedAuthBytes[0] = 1;
        String refreshedAuth =
                Base64.getUrlEncoder().withoutPadding().encodeToString(refreshedAuthBytes);

        long refreshedId = register(10, endpoint, refreshedPublicKey, refreshedAuth);

        assertThat(refreshedId).isEqualTo(subscriptionId);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT p256dh_key,auth_secret,capability_version"
                                        + " FROM web_push_subscriptions WHERE id=?",
                                subscriptionId))
                .containsEntry("p256dh_key", refreshedPublicKey)
                .containsEntry("auth_secret", refreshedAuth)
                .containsEntry("capability_version", 2L);
    }

    @Test
    void registerRejectsMalformedOrOversizedSubscription() throws Exception {
        mvc.perform(
                        put("/api/v1/notifications/push/subscriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request("https://fcm.googleapis.com/fcm/send/device")))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        put("/api/v1/notifications/push/subscriptions")
                                .header("Authorization", bearer(10))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request("https://127.0.0.1/push")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(
                        put("/api/v1/notifications/push/subscriptions")
                                .header("Authorization", bearer(10))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        request("https://fcm.googleapis.com/" + "x".repeat(2_100))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String endpoint :
                java.util.List.of(
                        "http://fcm.googleapis.com/fcm/send/device",
                        "/fcm/send/device",
                        "https://fcm.googleapis.com/fcm/send/device#fragment")) {
            mvc.perform(
                            put("/api/v1/notifications/push/subscriptions")
                                    .header("Authorization", bearer(10))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(request(endpoint)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(
                        put("/api/v1/notifications/push/subscriptions")
                                .header("Authorization", bearer(10))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(
                        put("/api/v1/notifications/push/subscriptions")
                                .header("Authorization", bearer(10))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        request(
                                                "https://fcm.googleapis.com/fcm/send/expired",
                                                PUBLIC_KEY,
                                                AUTH,
                                                0L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;
        mvc.perform(
                        put("/api/v1/notifications/push/subscriptions")
                                .header("Authorization", bearer(10))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        request(
                                                "https://fcm.googleapis.com/fcm/send/device",
                                                Base64.getUrlEncoder()
                                                        .withoutPadding()
                                                        .encodeToString(offCurve),
                                                AUTH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_push_subscriptions", Long.class))
                .isZero();
    }

    @Test
    void canonicalizesEquivalentEndpointAndRejectsFragment() throws Exception {
        long subscriptionId = register(10, "https://fcm.googleapis.com/fcm/send/canonical");

        long sameSubscription = register(10, "HTTPS://FCM.GOOGLEAPIS.COM:443/fcm/send/canonical");

        assertThat(sameSubscription).isEqualTo(subscriptionId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT endpoint FROM web_push_subscriptions WHERE id=?",
                                String.class,
                                subscriptionId))
                .isEqualTo("https://fcm.googleapis.com/fcm/send/canonical");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_push_subscriptions", Long.class))
                .isOne();
    }

    @Test
    void unregisterOnlyOwnsCurrentUsersSubscription() throws Exception {
        long owned = register(10, "https://fcm.googleapis.com/fcm/send/owned-delete");
        long foreign = register(20, "https://fcm.googleapis.com/fcm/send/foreign-delete");

        mvc.perform(
                        delete("/api/v1/notifications/push/subscriptions/{subscriptionId}", owned)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNoContent());
        mvc.perform(
                        delete("/api/v1/notifications/push/subscriptions/{subscriptionId}", foreign)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active FROM web_push_subscriptions WHERE id=?",
                                Boolean.class,
                                foreign))
                .isTrue();
        mvc.perform(
                        delete("/api/v1/notifications/push/subscriptions/{subscriptionId}", owned)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(
                        put("/api/v1/notifications/push/subscriptions")
                                .header("Authorization", bearer(10))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "endpoint":"https://fcm.googleapis.com/fcm/send/device",
                                          "expirationTime":null,
                                          "keys":{"p256dh":"bad","auth":"bad"}
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private long register(long userId, String endpoint) throws Exception {
        return register(userId, endpoint, PUBLIC_KEY, AUTH);
    }

    private long register(long userId, String endpoint, String p256dh, String auth)
            throws Exception {
        return register(userId, endpoint, p256dh, auth, null);
    }

    private long register(
            long userId, String endpoint, String p256dh, String auth, Long expirationTime)
            throws Exception {
        JsonNode response =
                json.readTree(
                        mvc.perform(
                                        put("/api/v1/notifications/push/subscriptions")
                                                .header("Authorization", bearer(userId))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        request(
                                                                endpoint,
                                                                p256dh,
                                                                auth,
                                                                expirationTime)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        return response.path("subscriptionId").asLong();
    }

    private static String request(String endpoint) {
        return request(endpoint, PUBLIC_KEY, AUTH);
    }

    private static String request(String endpoint, String p256dh, String auth) {
        return request(endpoint, p256dh, auth, null);
    }

    private static String request(
            String endpoint, String p256dh, String auth, Long expirationTime) {
        return """
                {
                  "endpoint":"%s",
                  "expirationTime":%s,
                  "keys":{"p256dh":"%s","auth":"%s"}
                }
                """
                .formatted(
                        endpoint,
                        expirationTime == null ? "null" : expirationTime.toString(),
                        p256dh,
                        auth);
    }

    private long partyNotification(long userId) {
        Instant now = Instant.now();
        return jdbc.queryForObject(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES (?, 'PARTY_INVITE', 9001,"
                        + " 'PARTY_INVITATION', 9001, '제목', '본문', ?, ?) RETURNING id",
                Long.class,
                userId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1_200)));
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "push" + id + "@example.test",
                "push" + id);
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] encoded = value.toByteArray();
        if (encoded.length == 32) {
            return encoded;
        }
        if (encoded.length > 32) {
            return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        }
        byte[] padded = new byte[32];
        System.arraycopy(encoded, 0, padded, 32 - encoded.length, encoded.length);
        return padded;
    }
}
