package qg.qgent.common.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.common.web.ApiException;

/** 共用游标分页：页大小边界、游标往返、scope 绑定与非法游标。 */
class KeysetPaginationTest {

    @Test
    void pageSizeDefaultsToThirtyAndRejectsOutOfRange() {
        assertEquals(30, KeysetPagination.pageSize(null));
        assertEquals(1, KeysetPagination.pageSize(1));
        assertEquals(100, KeysetPagination.pageSize(100));

        for (int invalid : List.of(0, 101)) {
            ApiException error =
                    assertThrows(ApiException.class, () -> KeysetPagination.pageSize(invalid));
            assertEquals("INVALID_PAGE_LIMIT", error.code());
        }
    }

    @Test
    void pageWithMoreRowsReturnsAnchorCursorAndLastPageStops() {
        PageSlice<String> first =
                KeysetPagination.keysetPage(
                        List.of(1L, 2L, 3L), 2, "teams:list", row -> row, String::valueOf);

        assertTrue(first.getPage().isHasMore());
        assertEquals(List.of("1", "2"), first.getData());
        assertEquals(
                2L, KeysetPagination.decodeCursor(first.getPage().getNextCursor(), "teams:list"));

        PageSlice<String> last =
                KeysetPagination.keysetPage(
                        List.of(3L), 2, "teams:list", row -> row, String::valueOf);

        assertFalse(last.getPage().isHasMore());
        assertNull(last.getPage().getNextCursor());
    }

    @Test
    void cursorFromAnotherScopeIsRejected() {
        String cursor =
                KeysetPagination.keysetPage(
                                List.of(1L, 2L), 1, "teams:list", row -> row, String::valueOf)
                        .getPage()
                        .getNextCursor();

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> KeysetPagination.decodeCursor(cursor, "teams:members"));

        assertEquals("INVALID_CURSOR", error.code());
    }

    @Test
    void blankCursorMeansFirstPageAndInvalidCursorsAreRejected() {
        assertNull(KeysetPagination.decodeCursor("  ", "teams:list"));

        for (String invalid : List.of("not-base64!!", "a".repeat(513), "dGVhbXM6bGlzdA")) {
            ApiException error =
                    assertThrows(
                            ApiException.class,
                            () -> KeysetPagination.decodeCursor(invalid, "teams:list"));
            assertEquals("INVALID_CURSOR", error.code());
        }
    }
}
