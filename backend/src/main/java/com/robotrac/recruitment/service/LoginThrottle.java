package com.robotrac.recruitment.service;

import com.robotrac.recruitment.core.FixedWindowRateLimiter;
import com.robotrac.recruitment.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Brute-force protection for logins. Only FAILED attempts are counted:
 * <ul>
 *   <li>per account (candidate email + drive code, or admin username): {@code login-max-attempts} failures
 *       per window;</li>
 *   <li>per IP address: a higher limit, because a whole campus lab may share one public IP.</li>
 * </ul>
 * A successful login clears the per-account counter.
 */
@Component
public class LoginThrottle {

    private final FixedWindowRateLimiter limiter;
    private final int maxPerAccount;
    private final int maxPerIp;
    private final Duration window;

    public LoginThrottle(FixedWindowRateLimiter limiter,
                         @Value("${app.rate-limit.login-max-attempts:10}") int maxPerAccount,
                         @Value("${app.rate-limit.login-max-failures-per-ip:100}") int maxPerIp,
                         @Value("${app.rate-limit.login-window-minutes:15}") int windowMinutes) {
        this.limiter = limiter;
        this.maxPerAccount = maxPerAccount;
        this.maxPerIp = maxPerIp;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    public void checkAllowed(String scope, String account, String ip) {
        if (limiter.isBlocked(accountKey(scope, account), maxPerAccount, window)
                || limiter.isBlocked(ipKey(scope, ip), maxPerIp, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many unsuccessful login attempts. Please wait " + window.toMinutes()
                            + " minutes and try again, or contact HR.");
        }
    }

    public void recordFailure(String scope, String account, String ip) {
        limiter.tryAcquire(accountKey(scope, account), maxPerAccount, window);
        limiter.tryAcquire(ipKey(scope, ip), maxPerIp, window);
    }

    public void recordSuccess(String scope, String account) {
        limiter.reset(accountKey(scope, account));
    }

    private static String accountKey(String scope, String account) {
        return scope + ":acct:" + (account == null ? "" : account.toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String scope, String ip) {
        return scope + ":ip:" + ip;
    }
}
