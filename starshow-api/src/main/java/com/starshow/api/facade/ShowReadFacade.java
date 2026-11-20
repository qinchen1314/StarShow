package com.starshow.api.facade;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;

/**
 * 演出与票档只读元数据内部调用契约
 */
public interface ShowReadFacade {

    /**
     * 查询演出基础只读元数据（开票时间、售卖状态等）
     */
    ShowBasicDTO getShowBasic(long showId);

    /**
     * 查询票档只读元数据快照（价格、所属区域、演出开票状态等，走 L1+L2 缓存）
     */
    TierSnapshotDTO getTierSnapshot(long showId, long tierId);
}
