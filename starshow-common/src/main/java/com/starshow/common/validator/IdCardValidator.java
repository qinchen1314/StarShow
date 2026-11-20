package com.starshow.common.validator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * 中国大陆 18 位居民身份证号校验器（严格遵循 GB 11643-1999 / ISO 7064:1983.MOD 11-2）
 */
public final class IdCardValidator {

    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^[1-9]\\d{16}[0-9Xx]$");
    private static final DateTimeFormatter BIRTH_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

    private IdCardValidator() {
    }

    /**
     * 规范化身份证号：去除首尾空白并统一将末位 x 转为大写 X
     */
    public static String normalize(String idCard) {
        if (idCard == null) {
            return null;
        }
        return idCard.trim().toUpperCase();
    }

    /**
     * 校验 18 位身份证号格式、严格出生日期与第 18 位校验码
     */
    public static boolean isValid(String rawIdCard) {
        if (rawIdCard == null) {
            return false;
        }
        String idCard = normalize(rawIdCard);
        if (!ID_CARD_PATTERN.matcher(idCard).matches()) {
            return false;
        }
        String birthStr = idCard.substring(6, 14);
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(birthStr, BIRTH_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if (birthDate.isBefore(MIN_BIRTH_DATE) || birthDate.isAfter(today)) {
            return false;
        }
        return idCard.charAt(17) == computeCheckDigit(idCard.substring(0, 17));
    }

    /**
     * 根据前 17 位数字计算第 18 位 GB 11643 校验码
     */
    public static char computeCheckDigit(String first17Digits) {
        if (first17Digits == null || first17Digits.length() != 17) {
            throw new IllegalArgumentException("first17Digits must have length 17");
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char ch = first17Digits.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException("first17Digits must contain only ASCII digits");
            }
            sum += (ch - '0') * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11];
    }
}
