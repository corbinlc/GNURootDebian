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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class GNURootNotificationService extends Service {

    @Override
    //TODO find out if anything needs to be done here
    public void onCreate() {
        Log.i("NotifService", "Started with onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startID) {
        //Either of these may be null depending on source
        String type = intent.getStringExtra("type");
        String action = intent.getAction();

        if("VNC".equals(type)) {
            startVNCServerNotification();
            startVNCServer(intent.getBooleanExtra("newXterm", false), intent.getStringExtra("command"));
        }
        if("VNCReconnect".equals(type)) {
            reconnectVNC();
        }
        if("cancelVNCNotif".equals(type)) {
            cancelVNCServerNotification();
        }
        if("com.gnuroot.debian.bVNC_DISCONNECT".equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
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

        Intent cancelIntent = new Intent(this, GNURootNotificationService.class);
        cancelIntent.putExtra("type", "cancelVNCNotif");
        PendingIntent pendingCancel = PendingIntent.getService(this, 1, cancelIntent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());
        builder.setContentIntent(pendingIntent);
        builder.setAutoCancel(false);
        builder.setContentTitle("VNC Service Running");
        builder.setOngoing(true);
        builder.setSmallIcon(R.drawable.xterm_transparent);
        builder.setPriority(Notification.PRIORITY_HIGH);
        builder.addAction(R.drawable.ic_exit, "Clear", pendingCancel);

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

        final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate
                (new Runnable() {
                    public void run() {
                        File checkStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
                        File checkRunning = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_running");
                        if (checkStarted.exists() || checkRunning.exists()) {
                            Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
                            bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=gnuroot"));
                            bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(bvncIntent);
                            scheduler.shutdown();
                        }
                    }
                }, 3, 2, TimeUnit.SECONDS); //Avoid race case in which tightvnc needs to be restarted
    }

    private void reconnectVNC() {
        Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
        bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=gnuroot"));
        bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(bvncIntent);
    }

    private void cancelVNCServerNotification() {
        Intent exitIntent = new Intent(this, com.iiordanov.bVNC.RemoteCanvasActivity.class);
        exitIntent.putExtra("disconnect", true);
        startActivity(exitIntent);

        stopForeground(true);
        stopSelf();
    }

    public File getInstallDir() {
        try {
            return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
