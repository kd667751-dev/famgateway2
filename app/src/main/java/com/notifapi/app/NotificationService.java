package com.notifapi.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!"com.fampay.in".equals(sbn.getPackageName())) return;
        String title   = sbn.getNotification().extras.getString("android.title", "");
        String content = sbn.getNotification().extras.getString("android.text", "");
        String key     = sbn.getKey();
        long   when    = sbn.getPostTime();
        GatewayLogic.getInstance().onNotification(key, title, content, when);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}
