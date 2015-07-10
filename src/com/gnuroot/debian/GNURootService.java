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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import com.gnuroot.library.GNURootCoreActivity;

import android.app.IntentService;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

public class GNURootService extends IntentService {

	public GNURootService() {
		super("GNURootService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		if (intent.getAction().equals("com.gnuroot.debian.RUN_SCRIPT_STR")) {
			runScriptStr(intent);
		} else if (intent.getAction().equals("com.gnuroot.debian.INSTALL_PACKAGES")) {
			installPackages(intent);
		} else if (intent.getAction().equals("com.gnuroot.debian.INSTALL_TAR")) {
			installTar(intent);
		} else if (intent.getAction().equals("com.gnuroot.debian.CHECK_STATUS")) {
			checkStatus(intent);
		}
	}
	
	public void installPackages(Intent intent) {
		int status = 0;
		Intent resultIntent = new Intent("com.gnuroot.debian.GNURootService.status");
		resultIntent.putExtra("packageName", intent.getStringExtra("packageName"));
		resultIntent.putExtra("requestCode",GNURootCoreActivity.INSTALL_PACKAGES);
		exec("rm " + intent.getStringExtra("statusFileDirectory") + "." + intent.getStringExtra("statusFileName") + "_failed");
		exec("rm " + intent.getStringExtra("statusFileDirectory") + "." + intent.getStringExtra("statusFileName") + "_passed");

		status = checkPrerequisites(intent);
		if (status == 1) {
			resultIntent.putExtra("resultCode", 2);
			sendBroadcast(resultIntent);
			return;
		}
		
		String scriptStr = intent.getStringExtra("scriptStr");
		Intent termIntent = new Intent("jackpal.androidterm.RUN_SCRIPT");
		termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.putExtra("jackpal.androidterm.iInitialCommand", scriptStr);
		startActivity(termIntent);
		resultIntent.putExtra("resultCode", status);
		sendBroadcast(resultIntent);
		return;
	}

	public void runScriptStr(Intent intent) {
		int status = 0;
		Intent resultIntent = new Intent("com.gnuroot.debian.GNURootService.status");
		resultIntent.putExtra("packageName", intent.getStringExtra("packageName"));
		resultIntent.putExtra("requestCode",GNURootCoreActivity.RUN_SCRIPT);

		status = checkPrerequisites(intent);
		if (status == 1) {
			resultIntent.putExtra("resultCode", 2);
			sendBroadcast(resultIntent);
			return;
		}
		
		String scriptStr = intent.getStringExtra("scriptStr");
		Intent termIntent = new Intent("jackpal.androidterm.RUN_SCRIPT");
		termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.putExtra("jackpal.androidterm.iInitialCommand", scriptStr);
		startActivity(termIntent);
		resultIntent.putExtra("resultCode", status);
		sendBroadcast(resultIntent);
		return;
	}

	public void installTar(Intent intent) {
		Uri srcUri = intent.getData();
		String scriptStr = intent.getStringExtra("scriptStr");
		InputStream srcStream;
		int status = 0;
		Intent resultIntent = new Intent("com.gnuroot.debian.GNURootService.status");
		resultIntent.putExtra("packageName", intent.getStringExtra("packageName"));
		resultIntent.putExtra("requestCode",GNURootCoreActivity.INSTALL_TAR);
		status = checkPrerequisites(intent);
		if (status == 1) {
			resultIntent.putExtra("resultCode", 2);
			sendBroadcast(resultIntent);
			return;
		}
		exec("rm " + intent.getStringExtra("statusFileDirectory") + "." + intent.getStringExtra("statusFileName") + "_failed");
		exec("rm " + intent.getStringExtra("statusFileDirectory") + "." + intent.getStringExtra("statusFileName") + "_passed");
		Intent termIntent = new Intent("jackpal.androidterm.RUN_SCRIPT");
		termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		try {
			srcStream = getContentResolver().openInputStream(srcUri);
			File srcFile = new File(srcUri.getPath());
			File destFolder = getFilesDir();
			File destFile = new File(destFolder.getAbsolutePath()+"/"+srcFile.getName());
			try {
				copyFile(srcStream,destFile);
			} catch (IOException e) {
				status = 1;
			}
			termIntent.putExtra("jackpal.androidterm.iInitialCommand", scriptStr + " " + destFile.getAbsolutePath() + "");
			startActivity(termIntent);
		} catch (FileNotFoundException e1) {
			status = 1;
		}
		resultIntent.putExtra("resultCode", status);
		sendBroadcast(resultIntent);
		return;
	}

	public void checkStatus(Intent intent) {
		Intent resultIntent = new Intent("com.gnuroot.debian.GNURootService.status");
		resultIntent.putExtra("packageName", intent.getStringExtra("packageName"));
		resultIntent.putExtra("requestCode",GNURootCoreActivity.CHECK_STATUS);
		String statusFileDirectory = intent.getStringExtra("statusFileDirectory");
		String statusFileName = intent.getStringExtra("statusFileName");
		File tempFile = new File(statusFileDirectory + "." + statusFileName + "_failed");
		if (isSymlink(tempFile) || tempFile.exists()) {
			resultIntent.putExtra("resultCode", 1);
			resultIntent.putExtra("found", 1);
			sendBroadcast(resultIntent);
			return;
		}
		tempFile = new File(statusFileDirectory + "." + statusFileName + "_passed");
		if (isSymlink(tempFile) || tempFile.exists()) {
			resultIntent.putExtra("resultCode", 0);
			resultIntent.putExtra("found", 1);
			sendBroadcast(resultIntent);
			return;
		}
		resultIntent.putExtra("resultCode", 0);
		resultIntent.putExtra("found", 0);
		sendBroadcast(resultIntent);
		return;
	}

	public int checkPrerequisites(Intent intent) {
		ArrayList<String> prerequisites = intent.getStringArrayListExtra("prerequisites");
		if (prerequisites == null)
			return 0;
		String statusFileDirectory = intent.getStringExtra("statusFileDirectory");
		for (int i = 0; i < prerequisites.size(); i++) {
			File tempFile = new File(statusFileDirectory + "." + prerequisites.get(i) + "_passed");
			if (!isSymlink(tempFile) && !tempFile.exists()) {
				return 1;
			}
		}
		return 0;
	}

	public void copyFile(InputStream src, File dst) throws IOException {
		OutputStream out = new FileOutputStream(dst);

		// Transfer bytes from in to out
		byte[] buf = new byte[1024];
		int len;
		while ((len = src.read(buf)) > 0) {
			out.write(buf, 0, len);
		}
		src.close();
		out.close();
	}

	private boolean exec(String command) {
		Runtime runtime = Runtime.getRuntime();
		Process process;
		boolean problemEncountered = false;
		try {
			process = runtime.exec(command);
			try {
				String str;
				process.waitFor();
				BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
				while ((str = stdError.readLine()) != null) {
					Log.e("Exec",str);
					problemEncountered = true;
				}
				process.getInputStream().close();
				process.getOutputStream().close();
				process.getErrorStream().close();
			} catch (InterruptedException e) {
				problemEncountered = true;
			}
		} catch (IOException e1) {
			problemEncountered = true;
		}
		return problemEncountered;
	}

	public static boolean isSymlink(File file) {
		try {
			if (file == null)
				throw new NullPointerException("File must not be null");
			File canon;
			if (file.getParent() == null) {
				canon = file;
			} else {
				File canonDir;

				canonDir = file.getParentFile().getCanonicalFile();

				canon = new File(canonDir, file.getName());
			}
			return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
		} catch (IOException e) {
			return false;
		}
	}
	
	private String mHandle;
    private static final int REQUEST_WINDOW_HANDLE = 1;
    private static final int RESULT_OK = 1;
	
	protected void onActivityResult(int request, int result, Intent data) {
        if (result != RESULT_OK) {
            return;
        }

        if (request == REQUEST_WINDOW_HANDLE && data != null) {
            mHandle = data.getStringExtra("jackpal.androidterm.window_handle");
        }
    }

}
