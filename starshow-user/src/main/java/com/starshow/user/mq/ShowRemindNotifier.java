package com.starshow.user.mq;

import com.starshow.api.event.ShowRemindEvent;

/**
 * 开票提醒通知发送 SPI（默认日志模拟实现，便于故障恢复与去重测试精确断言通知次数）
 */
public interface ShowRemindNotifier {

    void notifyRemind(ShowRemindEvent event);
}
