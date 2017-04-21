package com.gnuroot.debian;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.iiordanov.bVNC.Constants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GNURootLauncherService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startID) {
        String type = intent.getStringExtra("type");
        String command = intent.getStringExtra("command");
        if("launchTerm".equals(type)) {
            boolean installStep = intent.getBooleanExtra("installStep", false);
            launchTerm(installStep, command);
        }
        else if ("launchXTerm".equals(type)) {
            boolean newXTerm = intent.getBooleanExtra("newXTerm", false);
            launchXTerm(newXTerm, command);
        }
        else if("launchVNC".equals(type)) {
            launchVNC();
        }
        else {
            // TODO make this a string resource
            Toast.makeText(getApplicationContext(),
                    "GNURoot received a launch type it does not know how to handle.",
                    Toast.LENGTH_LONG).show();
        }
        return START_NOT_STICKY;
    }

    public void launchTerm(boolean installStep, final String command) {
        final Intent termIntent = new Intent(this, jackpal.androidterm.RunScript.class);
        termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        termIntent.addCategory(Intent.CATEGORY_DEFAULT);
        termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");

        // Some aspects want to be run as "root", or in the case of dropbear,
        // we won't have necessities set up yet.
        if(installStep) {
            if (command == null)
                termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                        getInstallDir().getAbsolutePath() + "/support/launchProot /bin/bash");
            else
                termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                        getInstallDir().getAbsolutePath() + "/support/launchProot " + command);

            startActivity(termIntent);
            stopSelf();
        }

        // Otherwise, connect through a dbclient.
        else {

            final ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor();
            final File dropbearStatus = new File(getInstallDir().getAbsolutePath() + "/support/.dropbear_running");
            scheduler.scheduleAtFixedRate
                    (new Runnable() {
                        public void run() {
                            if (dropbearStatus.exists()) {
                                if (command == null)
                                    termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                                            getInstallDir().getAbsolutePath() + "/support/startDBClient /bin/bash");
                                    //getInstallDir().getAbsolutePath() + "/support/busybox sh");
                                else
                                    termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                                            getInstallDir().getAbsolutePath() + "/support/startDBClient " + command);

                                startActivity(termIntent);
                                stopSelf();
                                scheduler.shutdown();
                            }
                        }
                    }, 100, 100, TimeUnit.MILLISECONDS);
        }
    }

    public void launchXTerm(boolean newXTerm, String command) {
        // Delete status files that signify whether x is started, in case they're left over from a
        // previous run.
        final File checkStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
        final File checkRunning = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_running");
        if (checkStarted.exists())
            checkStarted.delete();
        if (checkRunning.exists())
            checkRunning.delete();

        // Build the command to be run in the xterm
        ArrayList<String> cmdBuilder = new ArrayList<>();
        cmdBuilder.add(getInstallDir().getAbsolutePath() + "support/launchXterm");
        if (!newXTerm)
            cmdBuilder.add("button_pressed");
        if (command == null)
            cmdBuilder.add("/bin/bash");
        else
            cmdBuilder.add(command);
        final String[] cmd = cmdBuilder.toArray(new String[cmdBuilder.size()]);

        final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();

        final File dropbearStatus = new File(getInstallDir().getAbsolutePath() + "/support/.dropbear_running");
        scheduler.scheduleAtFixedRate
                (new Runnable() {
                    public void run() {
                        if (dropbearStatus.exists() && (checkStarted.exists() || checkRunning.exists())) {
                            try {
                                Runtime.getRuntime().exec(cmd);
                            } catch (IOException e) {
                                Log.e("GNURootService", "Couldn't execute x commands: " + e);
                            }
                            scheduler.shutdown();
                        }
                    }
                }, 100, 100, TimeUnit.MILLISECONDS);

        launchVNC();
    }

    public void launchVNC() {
        // Once the VNC server is running, connect to it and start the notification.
        final ScheduledExecutorService vncScheduler =
                Executors.newSingleThreadScheduledExecutor();

        final File vncStatus = new File(getInstallDir().getAbsolutePath() + "/support/.vnc_running");
        vncScheduler.scheduleAtFixedRate
                (new Runnable() {
                    public void run() {
                        if (vncStatus.exists()) {
                            Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
                            bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=gnuroot"));
                            bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                            Intent notifIntent = new Intent(getBaseContext(), GNURootNotificationService.class);
                            notifIntent.putExtra("type", "VNC");

                            startService(notifIntent);
                            startActivity(bvncIntent);
                            vncScheduler.shutdown();
                        }
                    }
                }, 3, 2, TimeUnit.SECONDS); //Avoid race case in which tightvnc needs to be restarted
    }

    public File getInstallDir() {
        try {
            return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
