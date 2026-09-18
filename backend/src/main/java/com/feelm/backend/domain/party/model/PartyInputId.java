package com.feelm.backend.domain.party.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 현재 파티 구성과 멤버별 추천 생성 세대를 동일한 규칙으로 식별한다. */
public final class PartyInputId {
    private PartyInputId() {}

    public static String from(long partyId, PartyInputSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(partyId).array());
            update(digest, snapshot.modelVersion());
            for (PartyInputSnapshot.Member member : snapshot.members()) {
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(member.userId()).array());
                update(digest, member.generationId());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
