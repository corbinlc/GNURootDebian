package com.gnuroot.debian;


import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class GNURootNotificationService extends Service {

    @Override
    //TODO find out if anything needs to be done here
    public void onCreate() {
        Log.i("NotifService", "Started with onCreate");
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startID) {
        //Either of these may be null depending on source
        String type = intent.getStringExtra("type");

        if("GNURoot".equals(type)) {
            startGNURootServerNotification();
        }
        if("VNC".equals(type)) {
            startVNCServerNotification();
        }
        if("cancelVNCNotif".equals(type)) {
            cancelVNCServerNotification();
        }
        return Service.START_STICKY;
    }

    private void startGNURootServerNotification() {
        int id = Integer.parseInt(new SimpleDateFormat("ddHHmmss", Locale.US).format(new Date()));

        //TODO Define strings

        // These intents actions are set that way to separate their relative pending intents.
        Intent killIntent = new Intent(this, GNURootService.class);
        killIntent.setAction(Long.toString(System.currentTimeMillis()));
        killIntent.putExtra("type", "kill");
        PendingIntent killPending = PendingIntent.getService(this, 0, killIntent, PendingIntent.FLAG_ONE_SHOT);

        Intent settingsIntent = new Intent(this, GNURootServerSettings.class);
        settingsIntent.setAction((Long.toString(System.currentTimeMillis())));
        PendingIntent settingsPending = PendingIntent.getActivity(this, 0, settingsIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());
        builder.setSmallIcon(R.drawable.ic_launcher);
        builder.setContentTitle("GNURoot");
        builder.setContentText("Server Control");
        builder.addAction(R.drawable.ic_exit, "Exit", killPending);
        builder.addAction(R.drawable.ic_launcher, "Settings", settingsPending);
        builder.setAutoCancel(false);
        builder.setPriority(Notification.PRIORITY_MAX);

        // TODO check these flags
        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        startForeground(id, notification);
    }

    private void startVNCServerNotification() {
        int id = Integer.parseInt(new SimpleDateFormat("ddHHmmss", Locale.US).format(new Date()));
        Intent VNCIntent = new Intent(this, GNURootService.class);
        VNCIntent.putExtra("type", "VNCReconnect");
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, VNCIntent, 0);

        /* This was to be used for cancelling the notification and disconnect bVNC.
        Intent cancelIntent = new Intent(this, GNURootNotificationService.class);
        cancelIntent.putExtra("type", "cancelVNCNotif");
        PendingIntent pendingCancel = PendingIntent.getService(this, 1, cancelIntent, 0);
        */

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());
        builder.setContentIntent(pendingIntent);
        builder.setAutoCancel(false);
        builder.setContentTitle("VNC Service Running");
        builder.setOngoing(true);
        builder.setSmallIcon(R.drawable.xterm_transparent);
        builder.setPriority(Notification.PRIORITY_HIGH);
        //builder.addAction(R.drawable.ic_exit, "Clear", pendingCancel);

        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        startForeground(id, notification);
    }

    private void cancelVNCServerNotification() {
        /* TODO Find a way to kill bVNC from outside
        Intent exitIntent = new Intent(this, com.iiordanov.bVNC.RemoteCanvasActivity.class);
        exitIntent.putExtra("disconnect", true);
        startActivity(exitIntent);
        */

        stopForeground(true);
        stopSelf();
    }
}