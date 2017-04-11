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
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.iiordanov.bVNC.Constants;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GNURootService extends Service {
	boolean sshLaunch;
	boolean termLaunch;
	boolean vncLaunch;
	boolean graphicalLaunch;

	@Override
	public void onCreate() { Log.i("Service", "OnCreate() called"); }

	@Override
	public IBinder onBind(Intent intent) {
		// We don't provide binding, so return null
		return null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startID) {
		String intentType = intent.getStringExtra("type");

		// In the case of kill, don't try to start servers as this can cause
		// the app to continue trying to do things.
		if("kill".equals(intentType)) {
			killServers();
		}
		else {
			startServers();
			//startDropbearServer();
			if ("SSH".equals(intentType)) {
				startDropbearServer();
			}

			if ("VNC".equals(intentType)) {
				startVNCServer(intent.getBooleanExtra("newXterm", false), intent.getStringExtra("command"));
			}

			if ("VNCReconnect".equals(intentType)) {
				reconnectVNC();
			}

			if ("kill".equals(intentType)) {
				killServers();
			}
		}

		return Service.START_STICKY;
	}

	private void startServers() {
		SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
		sshLaunch = prefs.getBoolean("sshLaunch", true);
		termLaunch = prefs.getBoolean("termLaunch", true);
		vncLaunch = prefs.getBoolean("vncLaunch", false);
		graphicalLaunch = prefs.getBoolean("graphicalLaunch", false);

		if(sshLaunch)
			startDropbearServer();
		if(vncLaunch);
		//TODO startVNCServer should just start a server
		if(termLaunch);
		//TODO move launchTerm in here? handle installation here?
		if(graphicalLaunch);
		//TODO connect to bVNC
	}

	private void startDropbearServer() {
		if(!dropbearServerRunning()) {
			try {
				String command = getInstallDir().getAbsolutePath() + "/support/dbserverscript";
				Runtime.getRuntime().exec(command);
				Intent notifServiceIntent = new Intent(this, GNURootNotificationService.class);
				notifServiceIntent.putExtra("type", "dropbear");
				startService(notifServiceIntent);
			} catch (IOException e) {
				Toast.makeText(getApplicationContext(),
						"Error starting dropbear server. Please report this to a developer.",
						Toast.LENGTH_LONG);
				Log.e("GNURootService", "Failed to start DB server: " + e);
			}
		}
	}

	private boolean dropbearServerRunning() {
		try {
			String commands[] = new String[5];
			commands[0] = "ps";
			commands[1] = "ef";
			commands[2] = "|";
			commands[3] = "grep";
			commands[4] = "dropbear";
			Process process = Runtime.getRuntime().exec(commands);
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

			String line;
			while((line = bufferedReader.readLine()) != null) {
				if(line.contains("dropbear") && !line.contains("grep"))
					return true;
			}
		}
		catch (IOException e) {
			//TODO make this a good string
			Log.e("GNURootService", "Failed to check dropbear server: " + e);
		}
		return false;
	}

	private void startVNCServer(final boolean createNewXTerm, String command) {
		// Delete status files that signify whether x is started, in case they're left over from a
		// previous run.
		final File checkStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
		final File checkRunning = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_running");
		if(checkStarted.exists())
			checkStarted.delete();
		if(checkRunning.exists())
			checkRunning.delete();

		// Once the dropbear server is running, start a dbclient in the background to start the VNC
		// server.
		final String cmd;
		if(command == null)
			cmd = "/bin/bash";
		else
			cmd = command;
		final String[] commandArray;
		if(!createNewXTerm) {
			commandArray = new String[3];
			commandArray[0] = getInstallDir().getAbsolutePath() + "/support/launchXterm";
			commandArray[1] = "button_pressed";
			commandArray[2] = cmd;
		}
		else {
			commandArray = new String[2];
			commandArray[0] = getInstallDir().getAbsolutePath() + "/support/launchXterm";
			commandArray[1] = cmd;
		}

		final ScheduledExecutorService dbScheduler =
				Executors.newSingleThreadScheduledExecutor();

		final File dropbearStatus = new File(getInstallDir().getAbsolutePath() + "/support/.dropbear_running");
		dbScheduler.scheduleAtFixedRate
				(new Runnable() {
					public void run() {
						if(dropbearStatus.exists()) {
							try {
								Runtime.getRuntime().exec(commandArray);
							}
							catch (IOException e) {
								Log.e("GNURootService", "Couldn't execute x commands: " + e);
							}
							dbScheduler.shutdown();
						}
					}
				}, 100, 100, TimeUnit.MILLISECONDS);

		// Once the VNC server is running, connect to it and start the notification.
		final Intent notifServiceIntent = new Intent(this, com.gnuroot.debian.GNURootNotificationService.class);
		notifServiceIntent.putExtra("type", "VNC");

		final ScheduledExecutorService scheduler =
				Executors.newSingleThreadScheduledExecutor();

		scheduler.scheduleAtFixedRate
				(new Runnable() {
					public void run() {
						if (checkStarted.exists() || checkRunning.exists()) {
							Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
							bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=gnuroot"));
							bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

							startActivity(bvncIntent);
							startService(notifServiceIntent);
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

	private void killServers() {
		android.os.Process.killProcess(android.os.Process.myPid());
	}

	public File getInstallDir() {
		try {
			return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
		} catch (PackageManager.NameNotFoundException e) {
			return null;
		}
	}
}