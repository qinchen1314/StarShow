package com.starshow.common.result;

import lombok.Getter;

/**
 * 全链路统一错误码（A=客户端/业务参数，B=服务端/高并发，C=外部依赖/支付）
 */
@Getter
public enum ErrorCode {

    SUCCESS("00000", "OK"),
    ORDER_QUEUING("00202", "抢票请求已受理，正在异步创建订单"),

    // 鉴权与实名 (A01xx)
    UNAUTHORIZED("A0101", "未登录或令牌已失效"),
    TOKEN_REVOKED("A0102", "登录态已被注销或踢出"),
    INVALID_PARAM("A0103", "请求参数非法"),
    IDENTITY_REQUIRED("A0104", "请先完成实名认证后再参与预约或抢票"),
    IDENTITY_DUPLICATE("A0105", "该身份证已绑定其他账号，请勿重复注册"),
    RESERVATION_DUPLICATE("A0106", "您已预约过该场次"),
    PHONE_ALREADY_EXISTS("A0107", "该手机号已注册"),
    LOGIN_FAILED("A0108", "手机号或密码错误"),
    ID_CARD_INVALID("A0109", "身份证号格式或校验码不合法"),
    IDENTITY_ALREADY_VERIFIED("A0110", "当前账号已完成实名认证，请勿重复提交"),

    // 演出与余票 (A02xx)
    SHOW_NOT_FOUND("A0201", "演出场次不存在"),
    SHOW_NOT_ON_SALE("A0202", "演出尚未开票或已停售"),
    TIER_NOT_FOUND("A0203", "票档不存在"),
    SEAT_CONFLICT("A0204", "所选座位已被占用，请重新选择"),
    SHOW_CONFIG_VERSION_CHANGED("A0205", "开售配置或座位布局版本已变化，请刷新页面"),

    // 抢票与订单 (A03xx)
    STOCK_NOT_ENOUGH("A0301", "手慢了，该票档余票不足"),
    DUPLICATE_PURCHASE("A0302", "每人每场次仅限购买一单，请勿重复抢购"),
    SECKILL_TOKEN_INVALID("A0303", "抢票凭证无效或已使用，请重试"),
    ORDER_NOT_FOUND("A0304", "订单不存在"),
    ORDER_STATE_ILLEGAL("A0305", "订单状态不允许当前操作"),
    DEDUCT_REJECTED_BY_TOMBSTONE("A0306", "该笔预扣请求已超时作废，请重新发起"),

    // 支付与退款 (A04xx)
    PAYMENT_NOT_FOUND("A0401", "支付单不存在"),
    PAYMENT_SIGN_INVALID("A0402", "支付回调验签失败"),
    PAYMENT_AMOUNT_MISMATCH("A0403", "支付回调金额与订单不一致"),
    REFUND_NOT_ALLOWED("A0404", "当前订单状态不允许退款"),

    // 系统与限流 (B01xx / B02xx)
    TOO_MANY_REQUESTS("B0101", "当前抢票人数过多，请稍后再试"),
    LOCK_ACQUIRE_FAILED("B0102", "系统繁忙，请稍后重试"),
    MQ_SEND_FAILED("B0103", "下单消息投递失败，请稍后重试"),
    STOCK_UNAVAILABLE("B0201", "库存暂不可用，请稍后刷新"),
    SYSTEM_ERROR("B9999", "系统内部异常");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
