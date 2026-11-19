package qg.qgent.auth.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

class TokenServiceTest {
    private final TokenService service =
            new TokenService("01234567890123456789012345678901", 15, 30, 30);

    @Test
    void accessTokenRoundTrip() {
        Long id = TestIds.next();
        assertThat(service.verifyAccess(service.access(id))).isEqualTo(id);
    }

    @Test
    void rejectsModifiedToken() {
        String token = service.access(TestIds.next());
        assertThat(service.verifyAccess(token + "x")).isNull();
    }

    @Test
    void opaqueTokensAreRandomAndHashable() {
        String a = service.opaque(), b = service.opaque();
        assertThat(a).isNotEqualTo(b);
        assertThat(service.hash(a)).hasSize(32);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new TokenService("short", 15, 30, 30))
                .isInstanceOf(IllegalStateException.class);
    }
}
