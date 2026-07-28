package com.netgo.mobile;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

/**
 * ExoPlayer's default retry policy gives up on a chunk/manifest load after
 * a handful of attempts, which is tuned for on-demand video — for a live
 * IPTV channel over a flaky mobile-data or budget-router connection, that
 * gives up far too quickly and surfaces as a frozen/dead channel. This is
 * far more patient: many more retries, with a gentle backoff capped at a
 * few seconds, so a brief network hiccup gets absorbed instead of killing
 * playback.
 */
@UnstableApi
public class IptvLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {

    @Override
    public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
        // 500ms, 1s, 1.5s, 2s... capped at 4s between attempts.
        long delay = Math.min(500L * (loadErrorInfo.errorCount), 4000L);
        return delay;
    }

    @Override
    public int getMinimumLoadableRetryCount(int dataType) {
        // Effectively "keep trying" for a live channel — the app's own
        // higher-level stall/error recovery (reload the same channel)
        // kicks in independently after ~9s if this layer can't recover
        // on its own, so this doesn't risk retrying forever with nothing
        // else watching.
        return 50;
    }
}
