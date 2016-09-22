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

import com.gnuroot.library.GNURootCoreActivity;

import android.app.IntentService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;

import com.iiordanov.bVNC.Constants;

public class GNURootService extends IntentService {

	public GNURootService() {
		super("GNURootService");
	}
	public boolean errOcc = false;

	@Override
	protected void onHandleIntent(Intent intent) {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo("com.gnuroot.debian", 0);
            String patchVersion = prefs.getString("patchVersion", null);
            if ((patchVersion == null) || (patchVersion.equals(pi.versionName) == false)) {
                sendResponse(intent, GNURootCoreActivity.PATCH_NEEDED);
            } else if (intent.getAction().equals("com.gnuroot.debian.RUN_SCRIPT_STR")) {
                runScriptStr(intent);
            } else if (intent.getAction().equals("com.gnuroot.debian.RUN_XSCRIPT_STR")) {
                runXScriptStr(intent);
            } else if (intent.getAction().equals("com.gnuroot.debian.INSTALL_PACKAGES")) {
                installPackages(intent);
            } else if (intent.getAction().equals("com.gnuroot.debian.INSTALL_TAR")) {
                installTar(intent);
            } else if (intent.getAction().equals("com.gnuroot.debian.CHECK_STATUS")) {
                checkStatus(intent);
            } else if (intent.getAction().equals("com.gnuroot.debian.CHECK_PREREQ")) {
                checkPrerequisites(intent);
            } else if (intent.getAction().equals("com.gnuroot.debian.RUN_BLOCKING_SCRIPT_STR")) {
                runInstallScriptStr(intent);
            } else if (intent.getAction().equals("com.gnuroot.debian.CONNECT_VNC_VIEWER")) {
                connectVncViewer(intent);
            } else {
                sendResponse(intent, GNURootCoreActivity.ERROR);
            }
        } catch (Exception e) {
            sendResponse(intent, GNURootCoreActivity.ERROR);
        }
    }

	public void sendResponse(Intent intent, int resultCode) {
        int requestCode = GNURootCoreActivity.UNKNOWN_ACTION;
		Intent resultIntent = new Intent("com.gnuroot.debian.GNURootService.status");
		resultIntent.putExtra("packageName", intent.getStringExtra("packageName"));
        if (intent.getAction().equals("com.gnuroot.debian.RUN_SCRIPT_STR")) {
            requestCode = GNURootCoreActivity.RUN_SCRIPT;
        } else if (intent.getAction().equals("com.gnuroot.debian.RUN_XSCRIPT_STR")) {
            requestCode = GNURootCoreActivity.RUN_XSCRIPT;
        } else if (intent.getAction().equals("com.gnuroot.debian.INSTALL_PACKAGES")) {
            requestCode = GNURootCoreActivity.INSTALL_PACKAGES;
        } else if (intent.getAction().equals("com.gnuroot.debian.INSTALL_TAR")) {
            requestCode = GNURootCoreActivity.INSTALL_TAR;
        } else if (intent.getAction().equals("com.gnuroot.debian.CHECK_STATUS")) {
            requestCode = GNURootCoreActivity.CHECK_STATUS;
        } else if (intent.getAction().equals("com.gnuroot.debian.CHECK_PREREQ")) {
            requestCode = GNURootCoreActivity.CHECK_PREREQ;
        } else if (intent.getAction().equals("com.gnuroot.debian.RUN_BLOCKING_SCRIPT_STR")) {
            requestCode = GNURootCoreActivity.RUN_BLOCKING_SCRIPT;
        } else if (intent.getAction().equals("com.gnuroot.debian.CONNECT_VNC_VIEWER")) {
            requestCode = GNURootCoreActivity.CONNECT_VNC_VIEWER;
        }
		resultIntent.putExtra("requestCode", requestCode);
		resultIntent.putExtra("resultCode", resultCode);

        String missingPreq = intent.getStringExtra("missingPreq");
        if (missingPreq != null)
            resultIntent.putExtra("missingPreq", missingPreq);
		sendBroadcast(resultIntent);
	}

	public void deleteStatusFiles(Intent intent) {
		exec("rm " + intent.getStringExtra("statusFileDirectory") + "." + intent.getStringExtra("statusFileName") + "_failed");
		exec("rm " + intent.getStringExtra("statusFileDirectory") + "." + intent.getStringExtra("statusFileName") + "_passed");
	}

    public int checkPrerequisitesInternal(Intent intent) {
        ArrayList<String> prerequisites = intent.getStringArrayListExtra("prerequisites");
        if (prerequisites == null)
            return GNURootCoreActivity.PASS;
        String statusFileDirectory = intent.getStringExtra("statusFileDirectory");
        for (int i = 0; i < prerequisites.size(); i++) {
            File tempFile = new File(statusFileDirectory + "." + prerequisites.get(i) + "_passed");
            if (!isSymlink(tempFile) && !tempFile.exists()) {
                intent.putExtra("missingPreq",prerequisites.get(i));
                sendResponse(intent, GNURootCoreActivity.MISSING_PREREQ);
                return GNURootCoreActivity.MISSING_PREREQ;
            }
        }
        return GNURootCoreActivity.PASS;
    }

    public int launchTermInternal(Intent intent) {
        int status = GNURootCoreActivity.PASS;
        String scriptStr = intent.getStringExtra("scriptStr");
        Intent termIntent = new Intent(this,jackpal.androidterm.RunScript.class);
        termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        termIntent.addCategory(Intent.CATEGORY_DEFAULT);
        termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
        termIntent.putExtra("jackpal.androidterm.iInitialCommand", scriptStr);
        startActivity(termIntent);
        return status;
    }

    public void checkPrerequisites(Intent intent) {
        int status = GNURootCoreActivity.PASS;

        status = checkPrerequisitesInternal(intent);
        if (status != GNURootCoreActivity.PASS)
            return;

        sendResponse(intent, status);
    }

	public void installPackages(Intent intent) {
		int status = GNURootCoreActivity.PASS;

		deleteStatusFiles(intent);

		status = checkPrerequisitesInternal(intent);
		if (status != GNURootCoreActivity.PASS)
			return;

        status = launchTermInternal(intent);

		sendResponse(intent, status);
	}

	public void runScriptStr(Intent intent) {
		int status = GNURootCoreActivity.PASS;

		status = checkPrerequisitesInternal(intent);
		if (status != GNURootCoreActivity.PASS)
			return;

        status = launchTermInternal(intent);

		sendResponse(intent, status);
	}

	public void runXScriptStr(Intent intent) {
		deleteStatusFiles(intent);
        runScriptStr(intent);
	}

    public void connectVncViewer(Intent intent) {
        int status = GNURootCoreActivity.PASS;

        Intent bvncIntent = new Intent(this, com.iiordanov.bVNC.RemoteCanvasActivity.class);
        bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?"+Constants.PARAM_VNC_PWD+"=gnuroot"));
        bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(bvncIntent);
        } catch (ActivityNotFoundException e) {
            status = GNURootCoreActivity.ERROR;
        }

        sendResponse(intent, status);
    }

	public void runInstallScriptStr(Intent intent) {
		deleteStatusFiles(intent);
		runScriptStr(intent);
	}

	public void installTar(Intent intent) {
		Uri srcUri = intent.getData();
		String scriptStr = intent.getStringExtra("scriptStr");
		InputStream srcStream;
        int status = GNURootCoreActivity.PASS;

        deleteStatusFiles(intent);

        status = checkPrerequisitesInternal(intent);
        if (status != GNURootCoreActivity.PASS)
            return;

		try {
			srcStream = getContentResolver().openInputStream(srcUri);
			File srcFile = new File(srcUri.getPath());
			File destFolder = getFilesDir();
			File destFile = new File(destFolder.getAbsolutePath()+"/"+srcFile.getName());
			try {
				if ((intent.getStringExtra("packageName").equals(getPackageName()) == false) || (srcFile.getAbsolutePath().startsWith("/my_root") == false))
					copyFile(srcStream,new FileOutputStream(destFile));
			} catch (IOException e) {
				status = GNURootCoreActivity.ERROR;
			}
            intent.putExtra("scriptStr",scriptStr + " " + destFile.getAbsolutePath() + "");
            launchTermInternal(intent);
		} catch (FileNotFoundException e1) {
			status = GNURootCoreActivity.ERROR;
		}

        sendResponse(intent, status);
	}

	public void checkStatus(Intent intent) {
        int status = GNURootCoreActivity.STATUS_FILE_NOT_FOUND;

		String statusFileDirectory = intent.getStringExtra("statusFileDirectory");
		String statusFileName = intent.getStringExtra("statusFileName");
        File tempFile = new File(statusFileDirectory + "." + statusFileName + "_passed");
        if (isSymlink(tempFile) || tempFile.exists()) {
            status = GNURootCoreActivity.PASS;
        }
		tempFile = new File(statusFileDirectory + "." + statusFileName + "_failed");
		if (isSymlink(tempFile) || tempFile.exists()) {
            status = GNURootCoreActivity.ERROR;
        }

        sendResponse(intent, status);
		return;
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

	private void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while((read = in.read(buffer)) != -1){
			out.write(buffer, 0, read);
		}
	}

}
