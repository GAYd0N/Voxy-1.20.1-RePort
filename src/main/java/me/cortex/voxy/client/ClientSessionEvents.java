package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

public class ClientSessionEvents {
    public static boolean inSession = false;

    public static void sessionStart() {
        if (inSession) {
            Logger.warn("Ignored duplicate voxy session start");
            return;
        }
        inSession = true;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) {
            Logger.warn("Voxy instance already exists on session start, recreating instance");
            try {
                VoxyCommon.shutdownInstance();
            } catch (Throwable t) {
                Logger.error("Error while shutting down stale voxy instance before session start", t);
            }
        }

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                try {
                    VoxyCommon.createInstance();
                } catch (Throwable t) {
                    Logger.error("Failed to initialize voxy session, continuing without voxy for this session", t);
                    try {
                        VoxyCommon.shutdownInstance();
                    } catch (Throwable shutdownErr) {
                        Logger.error("Failed to cleanup voxy after session init error", shutdownErr);
                    }
                }
            }
        }
    }

    public static void sessionEnd() {
        if (!inSession) {
            Logger.warn("Ignored duplicate voxy session end");
            return;
        }
        inSession = false;

        try {
            VoxyCommon.shutdownInstance();
        } catch (Throwable t) {
            Logger.error("Failed shutting down voxy session", t);
        }
    }
}
