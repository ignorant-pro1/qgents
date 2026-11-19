package qg.qgent.common.web;

import qg.qgent.common.dto.PageInfo;

import java.util.List;

public record PagedApiResponse<T>(List<T> data, PageInfo page, String requestId) {
}
