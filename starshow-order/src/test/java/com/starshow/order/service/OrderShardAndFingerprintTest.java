package com.starshow.order.service;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.order.config.OrderSecurityValidator;
import com.starshow.order.dto.SeckillOrderRequest;
import com.starshow.order.mapper.OrderShardTableResolver;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrderShardAndFingerprintTest {

    @Test
    void shouldRouteAll16GenesConsistentlyAcross4OrderShardsWithoutCollisions() {
        GeneSnowflakeIdGenerator generator = new GeneSnowflakeIdGenerator(3L);
        Set<Long> generated = new HashSet<>();
        Set<String> tables = new HashSet<>();

        for (long userId = 1000L; userId < 1016L; userId++) {
            for (int i = 0; i < 32; i++) {
                long orderNo = generator.nextOrderId(userId);
                assertTrue(generated.add(orderNo), "OrderNo collision detected");
                assertTrue(OrderShardTableResolver.geneMatchesUserId(orderNo, userId));
                assertEquals(
                        OrderShardTableResolver.tableForUserId(userId),
                        OrderShardTableResolver.tableForOrderNo(orderNo)
                );
                tables.add(OrderShardTableResolver.tableForOrderNo(orderNo));
            }
        }
        assertEquals(Set.of("t_order_0", "t_order_1", "t_order_2", "t_order_3"), tables);
        assertFalse(OrderShardTableResolver.geneMatchesUserId(generator.nextOrderId(1001L), 1002L));
    }

    @Test
    void shouldNormalizeSeatOrderAndDetectParameterTamperingInFingerprint() {
        SeckillOrderRequest r1 = SeckillOrderRequest.builder()
                .showId(10002L)
                .tierId(20004L)
                .qty(2)
                .zoneId("HALL_A")
                .seatIndices(List.of(12, 11))
                .layoutVersion("1")
                .saleConfigVersion(1)
                .expectedUnitPriceCent(88000L)
                .seckillToken("tok1")
                .clientRequestId("req-00000001")
                .build();

        SeckillOrderRequest r2 = SeckillOrderRequest.builder()
                .showId(10002L)
                .tierId(20004L)
                .qty(2)
                .zoneId("HALL_A")
                .seatIndices(List.of(11, 12))
                .layoutVersion("1")
                .saleConfigVersion(1)
                .expectedUnitPriceCent(88000L)
                .seckillToken("tok-different")
                .clientRequestId("req-00000001")
                .build();

        String fp1 = SeckillRequestFingerprint.compute(5001L, r1);
        String fp2 = SeckillRequestFingerprint.compute(5001L, r2);
        assertEquals(64, fp1.length());
        assertEquals(fp1, fp2, "Normalized seat order and token change should not alter request parameter fingerprint");

        r2.setExpectedUnitPriceCent(99000L);
        assertNotEquals(fp1, SeckillRequestFingerprint.compute(5001L, r2), "Price change must alter fingerprint");

        r2.setExpectedUnitPriceCent(88000L);
        r2.setSeatIndices(List.of(11, 13));
        assertNotEquals(fp1, SeckillRequestFingerprint.compute(5001L, r2), "Seat change must alter fingerprint");
    }

    @Test
    void shouldRejectMissingOrPlaceholderInternalSecretInOrderSecurityValidator() {
        StarShowSecurityProperties missing = new StarShowSecurityProperties();
        OrderSecurityValidator v1 = new OrderSecurityValidator(missing);
        assertThrows(IllegalStateException.class, v1::afterPropertiesSet);

        StarShowSecurityProperties placeholder = new StarShowSecurityProperties();
        placeholder.setInternalGatewaySecret("starshow-dev-internal-gateway-secret");
        OrderSecurityValidator v2 = new OrderSecurityValidator(placeholder);
        assertThrows(IllegalStateException.class, v2::afterPropertiesSet);

        StarShowSecurityProperties valid = new StarShowSecurityProperties();
        valid.setInternalGatewaySecret("order-strong-secret-9a8b7c6d5e4f3a2b");
        OrderSecurityValidator v3 = new OrderSecurityValidator(valid);
        assertDoesNotThrow(v3::afterPropertiesSet);
    }
}
