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

package com.gnuroot.library;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import com.gnuroot.library.GNURootCoreBroadcastReceiver.GNURootCoreBroadcastReceiverEventListener;

import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.FileProvider;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar.Tab;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

public class GNURootCoreActivity extends ActionBarActivityWithTabListener implements GNURootCoreBroadcastReceiverEventListener {
	
	public static final int INSTALL_PACKAGES = 1;
	public static final int INSTALL_TAR = 2;
	public static final int RUN_SCRIPT = 3;
	public static final int OPEN_NEW_TERM = 4; 
	public static final int CHECK_STATUS = 5;

	public ProgressDialog progressDialog = null;
	GNURootCoreBroadcastReceiver broadcastReceiver = null;
	MyTimerTask myTimerTask = null;
	
	@Override
	protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        // Instantiates a new DownloadStateReceiver
        broadcastReceiver = new GNURootCoreBroadcastReceiver();
        broadcastReceiver.registerCallback(this);
        // Registers the DownloadStateReceiver and its intent filters
        registerReceiver(broadcastReceiver,new IntentFilter("com.gnuroot.debian.GNURootService.status"));
	}
	
	public File getInstallDir() {
		try {
			return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
		} catch (NameNotFoundException e) {
			fireMarketIntent();
			return null;
		}
	}
	
	public File getSdcardInstallDir() {
		return new File(Environment.getExternalStorageDirectory() + "/GNURoot");
	}
	
	@Override
	protected void onDestroy()
	{
		try {
			unregisterReceiver(broadcastReceiver);
			super.onDestroy();
		} catch (IllegalArgumentException e) {
			super.onDestroy();
		}
	}
	
	//install packages based on package names put into an ArrayList <String>
	public void installPackages(ArrayList <String> packageList, String statusFileName, ArrayList <String> prerequisitesList) {
		if (getInstallDir() == null) { 
			return;
		}
		Intent termIntent = new Intent("com.gnuroot.debian.INSTALL_PACKAGES");
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.putExtra("packageName", getPackageName());
		termIntent.putExtra("prerequisites", prerequisitesList);
		termIntent.putExtra("statusFileName", statusFileName);
		termIntent.putExtra("statusFileDirectory", getInstallDir().getAbsolutePath() + "/support/");
		//turn list into a space sparated list
		String ssv = packageList.toString().replace("[", "").replace("]", "").replace(",", "");
		String scriptStr = getInstallDir().getAbsolutePath() + "/support/launchProot /support/installPackages " + statusFileName + " " + ssv + "";
		termIntent.putExtra("scriptStr", scriptStr);
		try {
			if ((progressDialog != null) && progressDialog.isShowing()) {
				progressDialog.setMessage("Packages being installed in GNURoot Debian.");
			} else {
				progressDialog = ProgressDialog.show(this, "Please wait ...", "Packages being installed in GNURoot Debian.", true);
				progressDialog.setCancelable(false);
			}
			startService(termIntent);	
			pollForCompletion(termIntent);
		} catch (ActivityNotFoundException e) {
			fireMarketIntent();
		}
	}
	
	//install a given .tar.gz based on a URI
	public void installTar(Uri targzUri, String statusFileName, ArrayList <String> prerequisitesList) {
		if (getInstallDir() == null) { 
			return;
		}
		Intent termIntent = new Intent("com.gnuroot.debian.INSTALL_TAR");
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		termIntent.setDataAndType(targzUri,"application/x-tar");
		termIntent.putExtra("packageName", getPackageName());
		termIntent.putExtra("prerequisites", prerequisitesList);
		String scriptStr = getInstallDir().getAbsolutePath() + "/support/launchProot /support/untargz " + statusFileName;
		termIntent.putExtra("scriptStr", scriptStr);
		termIntent.putExtra("statusFileName", statusFileName);
		termIntent.putExtra("statusFileDirectory", getInstallDir().getAbsolutePath() + "/support/");
		try {
			if ((progressDialog != null) && progressDialog.isShowing()) {
				progressDialog.setMessage("Tar file being installed in GNURoot Debian.");
			} else {
				progressDialog = ProgressDialog.show(this, "Please wait ...", "Tar file being installed in GNURoot Debian.", true);
				progressDialog.setCancelable(false);
			}
			startService(termIntent);
			pollForCompletion(termIntent);
		} catch (ActivityNotFoundException e) {
			fireMarketIntent();
		}
	}
	
	//run a command and then exit when it is complete
	public void runCommand(String commandStr, ArrayList <String> prerequisitesList) {
		if (getInstallDir() == null) { 
			return;
		}
		Intent termIntent = new Intent("com.gnuroot.debian.RUN_SCRIPT_STR");
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.putExtra("packageName", getPackageName());
		termIntent.putExtra("prerequisites", prerequisitesList);
		termIntent.putExtra("statusFileDirectory", getInstallDir().getAbsolutePath() + "/support/");
		String scriptStr = getInstallDir().getAbsolutePath() + "/support/launchProot " + commandStr + "";
		//String scriptStr = getInstallDir().getAbsolutePath() + "/support/busybox sh";
		termIntent.putExtra("scriptStr", scriptStr);
		try {
			progressDialog = ProgressDialog.show(this, "Please wait ...", "Command is being run in GNURoot Debian.", true);
			progressDialog.setCancelable(false);
			startService(termIntent);		
		} catch (ActivityNotFoundException e) {
			fireMarketIntent();
		}
	}

	@Override
	public void onTabReselected(Tab arg0, FragmentTransaction arg1) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onTabSelected(Tab arg0, FragmentTransaction arg1) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onTabUnselected(Tab arg0, FragmentTransaction arg1) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void nextStep(Intent intent) {
		if (intent.getStringExtra("packageName").equals(getPackageName())) {
			int resultCode = intent.getIntExtra("resultCode",0);
			int requestCode = intent.getIntExtra("requestCode",0);
			if (requestCode == CHECK_STATUS) {
				int found = intent.getIntExtra("found",0);
				if (found == 1) {
					myTimerTask.cancel();
				}
			}
			if (resultCode != 0) {
				if ((progressDialog != null) && (progressDialog.isShowing()))
						progressDialog.dismiss();
				if (myTimerTask != null)
					myTimerTask.cancel();
			}
				
			if (resultCode == 2)
				Toast.makeText(getApplicationContext(), "GNURoot Debian or this app has not been setup yet.", Toast.LENGTH_LONG).show();
				
		}
	}
	
	
	
	class MyTimerTask extends TimerTask  {
	     Intent intent;

	     public MyTimerTask(Intent intent) {
	         this.intent = intent;
	     }

	     @Override
	     public void run() {
	    	String statusFileDirectory = intent.getStringExtra("statusFileDirectory");
	    	String statusFileName = intent.getStringExtra("statusFileName");
			Intent statusIntent = new Intent("com.gnuroot.debian.CHECK_STATUS");
			statusIntent.addCategory(Intent.CATEGORY_DEFAULT);
			statusIntent.putExtra("packageName", getPackageName());
			statusIntent.putExtra("statusFileName", statusFileName);
			statusIntent.putExtra("statusFileDirectory", statusFileDirectory);
			try {
				startService(statusIntent);
			} catch (ActivityNotFoundException e) {
				fireMarketIntent();
			}
	 		return;
	     }
	}
	
	public void pollForCompletion(Intent intent) {
		myTimerTask = new MyTimerTask(intent);
		new Timer().scheduleAtFixedRate(myTimerTask, 0, 2000);
	}
	
	public void fireMarketIntent() {
		Toast.makeText(getApplicationContext(), "You must install GNURoot Debian for this app to work.", Toast.LENGTH_LONG).show();
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setData(Uri.parse("market://details?id=com.gnuroot.debian"));
		startActivity(intent);
	}
	
}

