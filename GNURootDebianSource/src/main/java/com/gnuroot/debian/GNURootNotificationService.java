package com.gnuroot.debian;

import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.iiordanov.bVNC.Constants;

import java.io.File;
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
    public int onStartCommand(Intent intent, int flags, int startID) {
        Log.i("NotifService", "NotificationService started with onStartCommand");
        String type = intent.getStringExtra("type");
        if(type.equals("VNC")) {
            startVNCServerNotification();
            startVNCServer(intent.getBooleanExtra("newXterm", false), intent.getStringExtra("command"));
        }
        if(type.equals("VNCReconnect")) {
            reconnectVNC();
        }
        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    private void startVNCServerNotification() {
        int id = Integer.parseInt(new SimpleDateFormat("ddHHmmss", Locale.US).format(new Date()));
        Intent VNCIntent = new Intent(this, GNURootNotificationService.class);
        VNCIntent.putExtra("type", "VNCReconnect");
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, VNCIntent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());
        builder.setContentIntent(pendingIntent);
        builder.setAutoCancel(false);
        builder.setContentTitle("VNC Service Running");
        builder.setOngoing(true);
        builder.setPriority(Notification.PRIORITY_HIGH);

        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        startForeground(id, notification);
    }

    private void startVNCServer(boolean createNewXTerm, String command) {
        Intent termIntent = new Intent(this, jackpal.androidterm.RunScript.class);
        termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        termIntent.addCategory(Intent.CATEGORY_DEFAULT);
        termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
        if(command == null)
            command = "/bin/bash";
        if (createNewXTerm)
            termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                    getInstallDir().getAbsolutePath() + "/support/launchXterm " + command);
        else
            // Button presses will not open a new xterm is one is alrady running.
            termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                    getInstallDir().getAbsolutePath() + "/support/launchXterm  button_pressed " + command);

        startActivity(termIntent);
    }

    private void reconnectVNC() {
        Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
        bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=gnuroot"));
        bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(bvncIntent);
    }

    public File getInstallDir() {
        try {
            return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
