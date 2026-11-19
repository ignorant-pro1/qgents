package qg.qgent.auth.mail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class VerificationCodeMailerTest {

    @Test
    void registrationMailContainsCodeAndRegistrationCopy() throws Exception {
        String html =
                capture(mailer ->
                        mailer.sendRegistration(
                                "member@example.com", "483920", Duration.ofMinutes(10)));

        assertTrue(html.contains("验证你的 Qgents 邮箱"));
        assertTrue(html.contains("你正在注册 Qgents 账号"));
        assertTrue(html.contains("验证码 10 分钟内有效"));
        assertTrue(html.contains("483920"));
    }

    @Test
    void passwordResetMailContainsCodeAndResetCopy() throws Exception {
        String html =
                capture(mailer ->
                        mailer.sendPasswordReset(
                                "member@example.com", "483920", Duration.ofMinutes(30)));

        // 验证码邮件：展示 6 位数字，不包含重置深链
        assertTrue(html.contains("重置你的 Qgents 密码"));
        assertTrue(html.contains("验证码 30 分钟内有效"));
        assertTrue(html.contains("483920"));
        assertFalse(html.contains("reset-password?token="));
        assertFalse(html.contains("<script"));
    }

    private String capture(Consumer<VerificationCodeMailer> send) throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);

        send.accept(new VerificationCodeMailer(sender, "noreply@example.com"));

        ArgumentCaptor<MimeMessage> captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        return (String) captured.getValue().getContent();
    }
}
