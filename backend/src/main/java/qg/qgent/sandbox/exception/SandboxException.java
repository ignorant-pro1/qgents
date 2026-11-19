package qg.qgent.sandbox.exception;

import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;

/**
 * Sandbox 文件和容器操作的业务异常，使用应用统一的错误处理。
 */
public class SandboxException extends ApiException {
    public SandboxException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public SandboxException(HttpStatus status, String code, String message, Throwable cause) {
        super(status, code, message);
        initCause(cause);
    }

    public String getCode() {
        return code();
    }

    public HttpStatus getStatus() {
        return status();
    }
}
