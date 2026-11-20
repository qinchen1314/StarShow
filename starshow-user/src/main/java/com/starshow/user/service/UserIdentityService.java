package com.starshow.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 链路一（节点 2）：实名认证与一证一账号防黄牛
 * - GB 11643-1999 严格校验 + 末位 X 规范化；
 * - AES-256-GCM 密文落库 + HMAC-SHA256(serverPepper) 唯一索引防脱库彩虹表与并发抢绑；
 * - 直接 INSERT 依赖 DB 唯一约束防 TOCTOU 并发绕过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentityService {

    static final Duration VERIFIED_CACHE_TTL = Duration.ofHours(1);

    private final UserIdentityMapper userIdentityMapper;
    private final StarShowSecurityProperties securityProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public IdentityVO verifyIdentity(long userId, IdentityVerifyRequest request) {
        if (!IdCardValidator.isValid(request.getIdCard())) {
            throw new BizException(ErrorCode.ID_CARD_INVALID);
        }
        String normalizedIdCard = IdCardValidator.normalize(request.getIdCard());
        String realName = request.getRealName().trim();

        String realNameCipher = CryptoUtil.encryptAesGcm(realName, securityProperties.getAesKey());
        String idCardCipher = CryptoUtil.encryptAesGcm(normalizedIdCard, securityProperties.getAesKey());
        String idCardMasked = CryptoUtil.maskIdCard(normalizedIdCard);
        String idCardHmac = CryptoUtil.hmacSha256Hex(normalizedIdCard, securityProperties.getIdcardPepper());
        LocalDateTime now = LocalDateTime.now();

        UserIdentityDO entity = UserIdentityDO.builder()
                .userId(userId)
                .realNameCipher(realNameCipher)
                .idCardCipher(idCardCipher)
                .idCardMasked(idCardMasked)
                .idCardHmac(idCardHmac)
                .verifiedTime(now)
                .deleted(0)
                .build();

        try {
            userIdentityMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            UserIdentityDO existingSelf = findByUserId(userId);
            if (existingSelf != null) {
                throw new BizException(ErrorCode.IDENTITY_ALREADY_VERIFIED);
            }
            throw new BizException(ErrorCode.IDENTITY_DUPLICATE);
        }

        // 实名认证不可逆，写入 Redis 1 小时正缓存加速链路三抢票前置校验
        try {
            stringRedisTemplate.opsForValue().set(RedisKeys.userVerified(userId), "1", VERIFIED_CACHE_TTL);
        } catch (Exception ex) {
            log.warn("Failed to populate user verified cache for userId={}", userId, ex);
        }

        log.info("User identity verified: userId={}, idCardMasked={}", userId, idCardMasked);
        return IdentityVO.builder()
                .verified(true)
                .idCardMasked(idCardMasked)
                .verifiedTime(now)
                .build();
    }

    public IdentityVO getIdentity(long userId) {
        UserIdentityDO identity = findByUserId(userId);
        if (identity == null) {
            return IdentityVO.builder().verified(false).build();
        }
        return IdentityVO.builder()
                .verified(true)
                .idCardMasked(identity.getIdCardMasked())
                .verifiedTime(identity.getVerifiedTime())
                .build();
    }

    private UserIdentityDO findByUserId(long userId) {
        return userIdentityMapper.selectOne(
                new LambdaQueryWrapper<UserIdentityDO>().eq(UserIdentityDO::getUserId, userId)
        );
    }
}
