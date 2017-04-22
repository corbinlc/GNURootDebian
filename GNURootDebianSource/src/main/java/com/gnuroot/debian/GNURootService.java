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
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import com.iiordanov.bVNC.Constants;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GNURootService extends Service {
	boolean shown = false;

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

		if("VNC".equals(intentType)) {
			startVNCServer(intent.getBooleanExtra("newXterm", false), intent.getStringExtra("command"));
		}

		if("VNCReconnect".equals(intentType)) {
			reconnectVNC();
		}

		return Service.START_STICKY;
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

		final Intent notifServiceIntent = new Intent(this, com.gnuroot.debian.GNURootNotificationService.class);
		notifServiceIntent.putExtra("type", "VNC");

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
							//CCXbvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
							startActivity(bvncIntent);

							startService(notifServiceIntent);
							scheduler.shutdown();
						}
					}
				}, 3, 2, TimeUnit.SECONDS); //Avoid race case in which tightvnc needs to be restarted

		stopSelf();
	}

	private void reconnectVNC() {
		Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
		bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?" + Constants.PARAM_VNC_PWD + "=gnuroot"));
		bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(bvncIntent);
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