package com.celica.dash.media

import android.service.notification.NotificationListenerService

/**
 * Exists only so the system will hand us active media sessions - MediaSessionManager checks that
 * the calling app has a granted notification listener. It reads no notifications.
 */
class DashNotificationListener : NotificationListenerService()
