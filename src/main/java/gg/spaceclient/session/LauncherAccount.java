package gg.spaceclient.session;

/** One account as the Space Client launcher stores it. */
public record LauncherAccount(
        String username,
        String uuid,
        String accessToken,
        String refreshToken,
        long expiresAt,
        boolean offline
) {
    public boolean isExpired() {
        return System.currentTimeMillis() / 1000L >= expiresAt - 60;
    }
}
