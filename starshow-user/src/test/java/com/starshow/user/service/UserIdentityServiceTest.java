package com.starshow.user.service;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.crypto.CryptoUtil;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.validator.IdCardValidator;
import com.starshow.user.domain.entity.UserIdentityDO;
import com.starshow.user.dto.IdentityVerifyRequest;
import com.starshow.user.mapper.UserIdentityMapper;
import com.starshow.user.vo.IdentityVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

    @Mock
    private UserIdentityMapper userIdentityMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private StarShowSecurityProperties securityProperties;
    private UserIdentityService userIdentityService;

    @BeforeEach
    void setUp() {
        securityProperties = new StarShowSecurityProperties();
        securityProperties.setAesKey("A7f9K2m4P6q8R1t3V5w7X9y0Z2b4D6f8");
        securityProperties.setIdcardPepper("identity-test-pepper-9a8b7c6d5e4f");
        userIdentityService = new UserIdentityService(userIdentityMapper, securityProperties, stringRedisTemplate);
    }

    @Test
    void shouldEncryptWithAesGcmAndStoreHmacWithoutPlaintext() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String validLowerX = buildSampleIdCardEndingWithX().toLowerCase();
        String expectedNormalized = validLowerX.toUpperCase();

        IdentityVerifyRequest req = new IdentityVerifyRequest();
        req.setRealName("张星演");
        req.setIdCard(validLowerX);

        IdentityVO vo = userIdentityService.verifyIdentity(10001L, req);
        assertTrue(vo.isVerified());
        assertEquals(CryptoUtil.maskIdCard(expectedNormalized), vo.getIdCardMasked());
        assertNotNull(vo.getVerifiedTime());

        // 核验落库实体零明文，且 AES-GCM 可逆解密，HMAC 与预期一致
        ArgumentCaptor<UserIdentityDO> captor = ArgumentCaptor.forClass(UserIdentityDO.class);
        verify(userIdentityMapper).insert(captor.capture());
        UserIdentityDO saved = captor.getValue();

        assertFalse(saved.getRealNameCipher().contains("张星演"));
        assertFalse(saved.getIdCardCipher().contains(expectedNormalized));
        assertEquals("张星演", CryptoUtil.decryptAesGcm(saved.getRealNameCipher(), securityProperties.getAesKey()));
        assertEquals(expectedNormalized, CryptoUtil.decryptAesGcm(saved.getIdCardCipher(), securityProperties.getAesKey()));
        assertEquals(
                CryptoUtil.hmacSha256Hex(expectedNormalized, securityProperties.getIdcardPepper()),
                saved.getIdCardHmac()
        );
        verify(valueOperations).set(eq(RedisKeys.userVerified(10001L)), eq("1"), eq(UserIdentityService.VERIFIED_CACHE_TTL));
    }

    @Test
    void shouldRejectSecondAccountBindingSameIdCardWithA0105() {
        String validIdCard = buildSampleIdCardEndingWithX();
        IdentityVerifyRequest req = new IdentityVerifyRequest();
        req.setRealName("黄牛二号");
        req.setIdCard(validIdCard);

        // 模拟 DB 触发 uk_id_card_hmac 唯一冲突，且当前账号(10002)尚未实名
        when(userIdentityMapper.insert(any(UserIdentityDO.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_id_card_hmac'"));
        when(userIdentityMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> userIdentityService.verifyIdentity(10002L, req));
        assertEquals(ErrorCode.IDENTITY_DUPLICATE, ex.getErrorCode());
    }

    @Test
    void shouldRejectSameAccountRepeatedVerificationWithA0110() {
        String validIdCard = buildSampleIdCardEndingWithX();
        IdentityVerifyRequest req = new IdentityVerifyRequest();
        req.setRealName("张星演");
        req.setIdCard(validIdCard);

        // 模拟 DB 触发 uk_user_id 唯一冲突，且当前账号已有实名记录
        when(userIdentityMapper.insert(any(UserIdentityDO.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_user_id'"));
        when(userIdentityMapper.selectOne(any()))
                .thenReturn(UserIdentityDO.builder().userId(10001L).build());

        BizException ex = assertThrows(BizException.class, () -> userIdentityService.verifyIdentity(10001L, req));
        assertEquals(ErrorCode.IDENTITY_ALREADY_VERIFIED, ex.getErrorCode());
    }

    @Test
    void shouldRejectInvalidIdCardWithA0109() {
        IdentityVerifyRequest req = new IdentityVerifyRequest();
        req.setRealName("张星演");
        req.setIdCard("110101200002300011");

        BizException ex = assertThrows(BizException.class, () -> userIdentityService.verifyIdentity(10001L, req));
        assertEquals(ErrorCode.ID_CARD_INVALID, ex.getErrorCode());
        verify(userIdentityMapper, never()).insert(any(UserIdentityDO.class));
    }

    private String buildSampleIdCardEndingWithX() {
        for (int seq = 1; seq < 999; seq++) {
            String candidate = String.format("31010119950505%03d", seq);
            if (IdCardValidator.computeCheckDigit(candidate) == 'X') {
                return candidate + "X";
            }
        }
        throw new IllegalStateException("Could not generate sample ID card");
    }
}
