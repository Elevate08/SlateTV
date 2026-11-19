package com.stasisstate.slatetv.plugin.api;

import android.os.Bundle;
import com.stasisstate.slatetv.plugin.api.PluginInfo;
import com.stasisstate.slatetv.plugin.api.PluginMediaItem;

/**
 * AIDL contract implemented by external APK background plugins for Slate TV.
 *
 * Plugins declare a Service with intent action:
 *   com.stasisstate.slatetv.plugin.BACKGROUND_PROVIDER
 *
 * Unlike older TV launcher plugin APIs, PluginMediaItem carries optional HTTP
 * request headers in a Bundle so self-hosted services (Immich, Jellyfin, Plex,
 * Home Assistant) can authenticate cleanly via headers instead of leaking keys
 * in query strings.
 */
interface ISlateBackgroundPlugin {
    int getApiVersion();
    PluginInfo getPluginInfo();
    PluginMediaItem getNextMedia(in Bundle options);
    List<PluginMediaItem> getMediaBatch(int count, in Bundle options);
}
