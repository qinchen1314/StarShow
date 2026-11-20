package com.starshow.show.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 演出稳定游标分页响应（GET /api/v1/shows）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowListVO {

    private List<ShowSummaryVO> items;
    private String nextCursor;
}
