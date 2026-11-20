package com.starshow.common.crypto;

import com.starshow.common.constant.RedisKeys;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CryptoAndRedisKeysTest {

    @Test
    void shouldComputeDeterministicHmacAndRoundTripAesGcm() {
        String sampleId = "110101199001011234";
        String pepper = "unit-test-pepper-secret";
        String aesKey = "0123456789abcdef0123456789abcdef";

        String h1 = CryptoUtil.hmacSha256Hex(sampleId, pepper);
        String h2 = CryptoUtil.hmacSha256Hex(" " + sampleId + " ", pepper);
        String hDifferentPepper = CryptoUtil.hmacSha256Hex(sampleId, "another-pepper");

        assertEquals(64, h1.length());
        assertEquals(h1, h2);
        assertNotEquals(h1, hDifferentPepper);

        String cipher1 = CryptoUtil.encryptAesGcm(sampleId, aesKey);
        String cipher2 = CryptoUtil.encryptAesGcm(sampleId, aesKey);
        assertNotEquals(cipher1, cipher2, "Random GCM IV must produce different ciphertexts");
        assertEquals(sampleId, CryptoUtil.decryptAesGcm(cipher1, aesKey));
        assertEquals(sampleId, CryptoUtil.decryptAesGcm(cipher2, aesKey));
        assertEquals("1101**********1234", CryptoUtil.maskIdCard(sampleId));
    }

    @Test
    void shouldShareSameShowIdHashTagAcrossAllSeckillLuaKeys() {
        long showId = 10001L;
        long tierId = 20001L;
        long orderNo = 999888777L;

        List<String> keys = List.of(
                RedisKeys.stock(showId, tierId),
                RedisKeys.boughtUsers(showId),
                RedisKeys.deductLog(showId, orderNo),
                RedisKeys.deductPendingZset(showId),
                RedisKeys.rollbackDone(showId, orderNo),
                RedisKeys.seatBitmap(showId, "HALL_A"),
                RedisKeys.seatLayout(showId, "HALL_A"),
                RedisKeys.showReady(showId),
                RedisKeys.showOpLock(showId)
        );

        String expectedTag = "{" + showId + "}";
        for (String key : keys) {
            int firstOpen = key.indexOf('{');
            int firstClose = key.indexOf('}', firstOpen + 1);
            assertTrue(firstOpen >= 0 && firstClose > firstOpen, "Missing Hash Tag in key: " + key);
            assertEquals(expectedTag, key.substring(firstOpen, firstClose + 1), "Mismatched Hash Tag in key: " + key);
            assertEquals(firstOpen, key.lastIndexOf('{'), "Key must contain only one Hash Tag: " + key);
        }
    }
}
