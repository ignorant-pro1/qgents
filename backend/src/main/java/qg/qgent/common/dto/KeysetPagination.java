package qg.qgent.common.dto;

import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * 团队与项目列表共用的游标分页规则：页大小、scope 绑定游标和 keyset 结果组装。
 */
public final class KeysetPagination {

    private KeysetPagination() {
    }

    /**
     * 默认 30 条，取值范围 1–100。
     */
    public static int pageSize(Integer requested) {
        int limit = requested == null ? 30 : requested;
        if (limit < 1 || limit > 100) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_PAGE_LIMIT", "limit 必须在 1 到 100 之间");
        }
        return limit;
    }

    /**
     * 解析绑定 scope 的游标；空游标返回 null，超过 512 字符按无效处理。
     */
    public static Long decodeCursor(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            if (cursor.length() > 512) {
                throw new IllegalArgumentException();
            }
            String decoded =
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator <= 0 || !expectedScope.equals(decoded.substring(0, separator))) {
                throw new IllegalArgumentException();
            }
            return Long.valueOf(decoded.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor 无效");
        }
    }

    /**
     * 多取一条判断是否还有下一页，按最后一条可见行的 id 生成下一页游标。
     */
    public static <S, T> PageSlice<T> keysetPage(
        List<S> rows, int pageSize, String scope, Function<S, Long> id, Function<S, T> mapper) {
        boolean hasMore = rows.size() > pageSize;
        List<S> visible = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor =
            hasMore ? encodeCursor(scope, id.apply(visible.get(visible.size() - 1))) : null;
        return new PageSlice<>(
            visible.stream().map(mapper).toList(), new PageInfo(nextCursor, hasMore));
    }

    private static String encodeCursor(String scope, Long anchor) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString((scope + "|" + anchor).getBytes(StandardCharsets.UTF_8));
    }
}
