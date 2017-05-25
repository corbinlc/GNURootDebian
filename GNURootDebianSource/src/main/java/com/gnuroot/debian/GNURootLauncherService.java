package com.gnuroot.debian;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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

    private String userPassword;

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startID) {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        userPassword = prefs.getString("userPassword", "gnuroot");
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
        else if("installXSupport".equals(type)) {
            installXSupport();
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

        // The rootfs needs to be installed before dropbear can set up a new user, which would prevent
        // connecting to that user.
        if(installStep) {


            // Do installation in a visible terminal, but exit once complete.
            // Command is either null, or to installXSupport.
            Intent pollIntent = new Intent(this, jackpal.androidterm.RunScript.class);
            pollIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            pollIntent.addCategory(Intent.CATEGORY_DEFAULT);
            pollIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
            pollIntent.putExtra("jackpal.androidterm.iInitialCommand",
                    getInstallDir().getAbsolutePath() + "/support/waitForInstall " + userPassword);
            startActivity(pollIntent);

            final ScheduledExecutorService waitScheduler =
                    Executors.newSingleThreadScheduledExecutor();
            final File waitingStatus = new File(getInstallDir().getAbsolutePath() + "/support/.waiting");
            waitScheduler.scheduleAtFixedRate
                    (new Runnable() {
                        public void run() {
                            if(waitingStatus.exists()) {
                                termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                                        getInstallDir().getAbsolutePath() + "/support/launchProot " + command + " exit");
                                startActivity(termIntent);

                                waitScheduler.shutdown();
                            }
                        }
                    }, 1, 1, TimeUnit.MILLISECONDS);


            /*
            Toast.makeText(getApplicationContext(),
                    "GNURoot will briefly close after installation while it creates an ssh sessioin.",
                    Toast.LENGTH_LONG).show();
                    */

            // After installation completes, call recursively so that an ssh client is the visible
            // terminal.
            final ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor();
            final File installationStatus = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_patch_passed");
            scheduler.scheduleAtFixedRate
                    (new Runnable() {
                        public void run() {
                            if(installationStatus.exists()) {
                                // Start the server before trying to connect to it.
                                Intent serverIntent = new Intent(getApplicationContext(), GNURootService.class);
                                startService(serverIntent);

                                scheduler.shutdown();
                            }
                        }
                    }, 500, 5, TimeUnit.MILLISECONDS);
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
                                            getInstallDir().getAbsolutePath() + "/support/startDBClient " + userPassword + " /bin/bash");
                                else
                                    termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                                            getInstallDir().getAbsolutePath() + "/support/startDBClient " + userPassword + " " + command);

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
        cmdBuilder.add(getInstallDir().getAbsolutePath() + "/support/launchXterm");
        cmdBuilder.add(userPassword);
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
        final File vncStatus = new File(getInstallDir().getAbsolutePath() + "/support/.vnc_running");
        final File xSupportStatus = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_support_passed");
        final File xPackagesStatus = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_package_passed");
        scheduler.scheduleAtFixedRate
                (new Runnable() {
                    public void run() {
                        if (dropbearStatus.exists() && vncStatus.exists() && xSupportStatus.exists() && xPackagesStatus.exists()) {
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
                            bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=" + userPassword));
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

    /**
     * Untar the xsupport directory and install necessary packages in a terminal visible to the
     * user.
     */
    public void installXSupport() {
        Intent termIntent = new Intent(this, jackpal.androidterm.RunScript.class);
        termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        termIntent.addCategory(Intent.CATEGORY_DEFAULT);
        termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
        termIntent.putExtra("jackpal.androidterm.iInitialCommand",
                getInstallDir().getAbsolutePath() + "/support/launchProot " + "/support/installXSupport");
        startActivity(termIntent);
    }

    public File getInstallDir() {
        try {
            return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
