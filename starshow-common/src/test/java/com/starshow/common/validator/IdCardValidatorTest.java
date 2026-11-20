package com.starshow.common.validator;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class IdCardValidatorTest {

    @Test
    void shouldAcceptValid18DigitIdCardsIncludingUpperAndLowerX() {
        String baseDigit = "11010119900101001";
        char digitCheck = IdCardValidator.computeCheckDigit(baseDigit);
        String validDigitCard = baseDigit + digitCheck;
        assertTrue(IdCardValidator.isValid(validDigitCard));

        // 构造末位校验码为 X 的合法虚拟身份证号
        String baseForX = findBaseWithCheckCode('X');
        String upperX = baseForX + "X";
        String lowerX = baseForX + "x";

        assertTrue(IdCardValidator.isValid(upperX));
        assertTrue(IdCardValidator.isValid(lowerX));
        assertEquals(upperX, IdCardValidator.normalize(" " + lowerX + " "));
    }

    @Test
    void shouldRejectInvalidLengthDateFutureDateAndChecksum() {
        assertFalse(IdCardValidator.isValid(null));
        assertFalse(IdCardValidator.isValid(""));
        assertFalse(IdCardValidator.isValid("11010119900101123")); // 17 位
        assertFalse(IdCardValidator.isValid("1101011990010112345")); // 19 位
        assertFalse(IdCardValidator.isValid("010101199001010011")); // 首位为 0

        // 非法日期：2000 年 2 月 30 日
        String invalidFeb30 = "11010120000230001";
        assertFalse(IdCardValidator.isValid(invalidFeb30 + IdCardValidator.computeCheckDigit(invalidFeb30)));

        // 非闰年 2 月 29 日
        String invalidNonLeap = "11010119990229001";
        assertFalse(IdCardValidator.isValid(invalidNonLeap + IdCardValidator.computeCheckDigit(invalidNonLeap)));

        // 未来日期
        String futureBirth = LocalDate.now().plusDays(10).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String futureBase = "110101" + futureBirth + "001";
        assertFalse(IdCardValidator.isValid(futureBase + IdCardValidator.computeCheckDigit(futureBase)));

        // 错误校验码
        String base = "11010119900101001";
        char rightCheck = IdCardValidator.computeCheckDigit(base);
        char wrongCheck = (rightCheck == '1') ? '2' : '1';
        assertFalse(IdCardValidator.isValid(base + wrongCheck));
    }

    private String findBaseWithCheckCode(char expectedCheck) {
        for (int seq = 1; seq < 999; seq++) {
            String candidate = String.format("31010119950505%03d", seq);
            if (IdCardValidator.computeCheckDigit(candidate) == expectedCheck) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to construct sample id card ending with " + expectedCheck);
    }
}
