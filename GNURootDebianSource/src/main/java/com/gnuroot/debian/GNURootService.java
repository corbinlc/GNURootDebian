/*
Copyright (c) 2014 Teradyne

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */

/* Author(s): Corbin Leigh Champion */
package com.gnuroot.debian;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GNURootService extends Service {
	boolean sshLaunch;
	boolean termLaunch;
	boolean vncLaunch;
	boolean graphicalLaunch;

	//ArrayList<Process> sshServerList = new ArrayList<Process>();

	@Override
	public void onCreate() { Log.i("Service", "OnCreate() called"); }

	@Override
	public IBinder onBind(Intent intent) {
		// We don't provide binding, so return null
		return null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startID) {
		// This avoids service restarting in cases where the kill signal
		// has been sent.
		if(intent == null) {
			stopSelf();
		}
		String intentType = intent.getStringExtra("type");

		// In the case of kill, don't try to start servers as this can cause
		// the app to continue trying to do things.
		if("kill".equals(intentType)) {
			killServers();
		}
		else if ("VNC".equals(intentType)) {
			startVNCServer();
		}
		else {
			startServers();
		}

		return Service.START_STICKY;
	}

	private void startServers() {
		SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
		sshLaunch = prefs.getBoolean("sshLaunch", true);
		termLaunch = prefs.getBoolean("termLaunch", true);
		vncLaunch = prefs.getBoolean("vncLaunch", false);
		graphicalLaunch = prefs.getBoolean("graphicalLaunch", false);

		List<String> command = new ArrayList<String>();
		command.add(getInstallDir().getAbsolutePath() + "/support/startServers");

		// TODO we need to guarantee vnc support is installed instead of just adding vnc to script arguments
		if(sshLaunch)
			command.add("dropbear");
		if(vncLaunch)
			command.add("vnc");
		try {
			String[] cmd = command.toArray(new String[command.size()]);
			Runtime.getRuntime().exec(cmd);
			Intent notifIntent = new Intent(this, GNURootNotificationService.class);
			notifIntent.putExtra("type", "GNURoot");
			startService(notifIntent);
		} catch (IOException e) {
			Log.e("GNURootService", "Could not start servers: " + e);
		}
	}

	private void startVNCServer() {
		// First, if VNC support isn't installed yet, we have to install it.
		// This needs to be done in a way that's visible to the user, so it
		// must be done in an ATE terminal.
		final File checkXSupport = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_support_passed");
		final File checkXPackages = new File(getInstallDir().getAbsolutePath() + "support/.gnuroot_x_package_passed");
		if(!checkXSupport.exists() || !checkXPackages.exists()) {
			Intent xInstallIntent = new Intent(this, GNURootMain.class);
			xInstallIntent.setAction("com.gnuroot.debian.LAUNCH");
			xInstallIntent.putExtra("launchType", "launchTerm");
			xInstallIntent.putExtra("command",
					getInstallDir().getAbsolutePath() + "/support/installXSupport");
			startActivity(xInstallIntent);
		}

		// This executor will wait for the installation to finish before moving on.
		final String[] command = { getInstallDir().getAbsolutePath() + "/support/startServers",
									"dropbear", "vnc" };
		final ScheduledExecutorService xInstallScheduler =
				Executors.newSingleThreadScheduledExecutor();

		xInstallScheduler.scheduleAtFixedRate
				(new Runnable() {
					public void run() {
						if(checkXPackages.exists() && checkXSupport.exists()) {
							// Start VNC
							try {
								Runtime.getRuntime().exec(command);
							} catch (IOException e) {
								Log.e("GNURootService", "Failed to start VNC server: " + e);
							}
							xInstallScheduler.shutdown();
						}
					}
				}, 100, 100, TimeUnit.MILLISECONDS);
	}

	/**
	 * Kill all processes related to GNURoot Debian.
	 */
	private void killServers() {
		android.os.Process.killProcess(android.os.Process.myPid());
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