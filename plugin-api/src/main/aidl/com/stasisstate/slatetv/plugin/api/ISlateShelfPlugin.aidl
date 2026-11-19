package com.stasisstate.slatetv.plugin.api;

import android.os.Bundle;
import com.stasisstate.slatetv.plugin.api.ShelfPluginInfo;
import com.stasisstate.slatetv.plugin.api.ShelfPayload;

interface ISlateShelfPlugin {
    int getApiVersion();
    ShelfPluginInfo getShelfInfo();
    ShelfPayload getShelfPayload(in Bundle options);
}
