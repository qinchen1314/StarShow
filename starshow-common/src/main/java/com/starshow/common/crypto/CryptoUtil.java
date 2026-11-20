package com.starshow.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 实名隐私安全工具：
 * 1. HMAC-SHA256(plain, serverPepper)：确定性哈希用于唯一索引防黄牛，同时抵御身份证低熵彩虹表碰撞；
 * 2. AES-256-GCM：带 12 字节随机 IV 与 128 位认证标签的对称加密，用于密文落库；
 * 3. 脱敏展示工具：身份证与手机号展示脱敏。
 */
public final class CryptoUtil {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String AES_GCM_ALG = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BIT_LENGTH = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    public static String hmacSha256Hex(String plaintext, String pepper) {
        if (plaintext == null || pepper == null || pepper.isBlank()) {
            throw new IllegalArgumentException("plaintext and pepper must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] digest = mac.doFinal(plaintext.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 calculation failed", e);
        }
    }

    public static String encryptAesGcm(String plaintext, String secretKey32Bytes) {
        if (plaintext == null) {
            return null;
        }
        byte[] keyBytes = requireAesKey(secretKey32Bytes);
        byte[] iv = new byte[GCM_IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_ALG);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BIT_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    public static String decryptAesGcm(String cipherBase64, String secretKey32Bytes) {
        if (cipherBase64 == null) {
            return null;
        }
        byte[] keyBytes = requireAesKey(secretKey32Bytes);
        byte[] combined = Base64.getDecoder().decode(cipherBase64);
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Invalid AES-GCM ciphertext");
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_ALG);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BIT_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return "****";
        }
        return idCard.substring(0, 4) + "*".repeat(idCard.length() - 8) + idCard.substring(idCard.length() - 4);
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static byte[] requireAesKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("AES key must not be null");
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalArgumentException("AES key length must be 16/24/32 bytes, actual=" + bytes.length);
        }
        return bytes;
    }
}
