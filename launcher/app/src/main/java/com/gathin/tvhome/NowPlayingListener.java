package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the launcher can read the TV's media sessions for Now playing: Android ties that access to a
 * notification listener. It overrides nothing, so it never looks at notifications. From KIDŌ (MIT, Glidian).
 */
public class NowPlayingListener extends NotificationListenerService {
}
