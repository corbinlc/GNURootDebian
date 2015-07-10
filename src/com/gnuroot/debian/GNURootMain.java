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
import java.io.OutputStreamWriter;

import com.gnuroot.library.GNURootCoreActivity;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBar.Tab;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.FileProvider;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.Toast;

import com.gnuroot.debian.R;

public class GNURootMain extends GNURootCoreActivity {

	private ViewPager viewPager;
	private GNURootTabsPagerAdapter mAdapter; 
	private ActionBar actionBar;
	// Tab titles
	private String[] tabs = { "Install/Update", "Launch" };
	String rootfsName = "Debian";
	Boolean errOcc;
	Boolean expectingResult;
	ProgressDialog pdRing;
	Integer downloadResultCode;
	File mainFile;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Initilization
		viewPager = (ViewPager) findViewById(R.id.pager);
		actionBar = getSupportActionBar();
		mAdapter = new GNURootTabsPagerAdapter(getSupportFragmentManager());

		viewPager.setAdapter(mAdapter);
		actionBar.setHomeButtonEnabled(false);
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);		

		// Adding Tabs
		for (String tab_name : tabs) {
			actionBar.addTab(actionBar.newTab().setText(tab_name)
					.setTabListener(this));
		}

		/**
		 * on swiping the viewpager make respective tab selected
		 * */
		viewPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
			@Override
			public void onPageSelected(int position) {
				// on changing the page
				// make respected tab selected
				actionBar.setSelectedNavigationItem(position);
			}

			@Override
			public void onPageScrolled(int arg0, float arg1, int arg2) {
			}

			@Override
			public void onPageScrollStateChanged(int arg0) {
			}
		});

		//TODO: set initial tab based on whether this has been installed previously
	}

	@Override
	public void onTabReselected(Tab tab, FragmentTransaction ft) {
	}

	@Override
	public void onTabSelected(Tab tab, FragmentTransaction ft) {
		// on tab selected
		// show respected fragment view
		viewPager.setCurrentItem(tab.getPosition());
	}

	@Override
	public void onTabUnselected(Tab tab, FragmentTransaction ft) {
	}

	public void installRootfs() {

		//when told to install, will do the following
		//delete old stuff
		//create needed directories
		//unpack proot and busybox and put them in the correct place
		//need to make sure .obb has been downloaded, if not call activity and wait for it to complete
		//launch proot and unpack .obb - really a zip file
		//done

		//inform the user we are unpacking and this will take a while
		pdRing = ProgressDialog.show(GNURootMain.this, "Setting up GNURoot " + rootfsName, "This may take some time.",true);
		pdRing.setCancelable(false);
		Thread t = new Thread() { 
			public void run() {
				try {
					setupFirstHalf();
				} catch (Exception e) {
					pdRing.dismiss();
					Toast.makeText(GNURootMain.this, "Installing GNURoot " + rootfsName + " failed.  Something went wrong.", Toast.LENGTH_LONG).show();
				}
			}
		};
		t.start();
	}

	private void setupFirstHalf() {
		errOcc = false;

		expectingResult = true;
		Intent downloadIntent = new Intent();
		downloadIntent.addCategory(Intent.CATEGORY_DEFAULT);
		downloadIntent.setClassName("com.gnuroot.debian","com.gnuroot.debian.GNURootDownloaderActivity");
		startActivityForResult(downloadIntent, 0);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode,
			Intent data) {
		if (expectingResult) {
			expectingResult = false;
			if (requestCode == 0) {
				if (resultCode > 0) {
					downloadResultCode = resultCode;
					Thread t = new Thread() {
						public void run() {
							try {
								setupSecondHalf(downloadResultCode);
							} catch (Exception e) {
								pdRing.dismiss();
								Toast.makeText(getApplicationContext(), "Installing GNURoot Debian failed.  Something went wrong.", Toast.LENGTH_LONG).show();
							}
						}
					};
					t.start();
				} else {
					pdRing.dismiss();
					Toast.makeText(getApplicationContext(), "Installing GNURoot Debian failed.  Couldn't get necessary .obb files.", Toast.LENGTH_LONG).show();
				}
			}
		}
	}

	private void setupSecondHalf(int obbVersion) {

		String path = Environment.getExternalStorageDirectory() + "/Android/obb/com.gnuroot.debian/";
		mainFile = new File(path + "main." + Integer.toString(obbVersion) + ".com.gnuroot.debian.obb");
		File installDir = getInstallDir();
		File sdcardInstallDir = getSdcardInstallDir();

		//setup some basic directories

		//create internal install directory
		File tempFile = new File(installDir.getAbsolutePath()+"/debian");
		//blow away if it already existed
		if (tempFile.exists()) {
			deleteRecursive(tempFile);
		}
		tempFile.mkdir();

		//create external noexec parent directory
		tempFile = new File(sdcardInstallDir.getAbsolutePath());
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create external noexec directory
		tempFile = new File(sdcardInstallDir.getAbsolutePath()+"/debian");
		//blow it aways if it already existed
		if (tempFile.exists()) {
			deleteRecursive(tempFile);
		}
		tempFile.mkdir();

		//create a directory for binding the android rootfs to
		tempFile = new File(installDir.getAbsolutePath()+"/debian/host-rootfs");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the noexec directory to
		tempFile = new File(installDir.getAbsolutePath()+"/debian/.proot.noexec");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the sdcard to
		tempFile = new File(installDir.getAbsolutePath()+"/debian/sdcard");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		
		tempFile = new File(installDir.getAbsolutePath()+"/debian/dev");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath()+"/debian/proc");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		
		tempFile = new File(installDir.getAbsolutePath()+"/debian/mnt");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		
		tempFile = new File(installDir.getAbsolutePath()+"/debian/data");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the home to
		tempFile = new File(installDir.getAbsolutePath()+"/debian/home");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a home directory on sdcard
		tempFile = new File(sdcardInstallDir.getAbsolutePath() + "/home");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		
		//create a directory for firing intents from
		tempFile = new File(installDir.getAbsolutePath()+"/debian/intents");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create an intents directory on the sdcard
		tempFile = new File(sdcardInstallDir.getAbsolutePath() + "/intents");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create directory for support executables and scripts
		tempFile = new File(installDir.getAbsolutePath() + "/support");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//copy bare necessities to the now created support directory
		copyAssets("com.gnuroot.debian");
		
		String shadowOption = ((CheckBox) findViewById(R.id.sdcard_checkbox)).isChecked() ? " -n " : " ";
		
		//create a script for running a command in proot
		tempFile = new File(installDir.getAbsolutePath() + "/support/launchProot");
		writeToFile("#! " + installDir.getAbsolutePath()+"/support/busybox sh\n" +
					installDir.getAbsolutePath()+"/support/busybox clear\n" +
					"\nblue='\\033[0;34m'; NC='\\033[0m'; echo -e \"${blue}Sponsored by: \"\n" +
					"echo \"                                          \"\n" +
					"echo \" _____                   _                \"\n" +
					"echo \"|_   _| ___  ___  ___  _| | _ _  ___  ___ \"\n" +
					"echo \"  | |  | -_||  _|| . || . || | ||   || -_|\"\n" +
					"echo \"  |_|  |___||_|  |__,||___||_  ||_|_||___|\"\n" +
					"echo \"                           |___|          \"\n" +
					"echo -e \"${NC}\"\n" +
					"LD_PRELOAD= PROOT_TMP_DIR=" + installDir.getAbsolutePath() + "/support/ " + installDir.getAbsolutePath() + "/support/proot -r " + installDir.getAbsolutePath() + "/debian -v -1 -H -l" + shadowOption + "-0 -b /dev -b /proc -b /data -b /mnt -b /proc/mounts:/etc/mtab -b /:/host-rootfs -b " + sdcardInstallDir.getAbsolutePath() + "/intents:/intents -b " + sdcardInstallDir.getAbsolutePath() + "/home:/home -b " + sdcardInstallDir.getAbsolutePath() + "/debian:/.proot.noexec -b " + Environment.getExternalStorageDirectory() + ":/sdcard -b " + installDir.getAbsolutePath() + "/support/:/support $@", tempFile);
					//"LD_PRELOAD= PROOT_TMP_DIR=" + installDir.getAbsolutePath() + "/support/ PROOT_LOADER=" + installDir.getAbsolutePath() + "/support/loader " + installDir.getAbsolutePath() + "/support/proot -r " + installDir.getAbsolutePath() + "/debian -v -1 -H -l" + shadowOption + "-0 -b /dev -b /proc -b /data -b /mnt -b /proc/mounts:/etc/mtab -b /:/host-rootfs -b " + sdcardInstallDir.getAbsolutePath() + "/intents:/intents -b " + sdcardInstallDir.getAbsolutePath() + "/home:/home -b " + sdcardInstallDir.getAbsolutePath() + "/debian:/.proot.noexec -b " + Environment.getExternalStorageDirectory() + ":/sdcard -b " + installDir.getAbsolutePath() + "/support/:/support $@", tempFile);
		exec("chmod 0777 " + tempFile.getAbsolutePath(), true);

		//create a script for untaring a file
		tempFile = new File(installDir.getAbsolutePath() + "/support/untargz");
		writeToFile("#!/support/busybox sh\n" +
				"/support/busybox tar -xzvf /host-rootfs/$2\n" + 
				"if [[ $? == 0 ]]; then touch /support/.$1_passed; else touch /support/.$1_failed; fi", tempFile); 
		exec("chmod 0777 " + tempFile.getAbsolutePath(), true);

		//create a script for installing packages
		tempFile = new File(installDir.getAbsolutePath() + "/support/installPackages");
		writeToFile("#!/bin/bash\napt-get update\n" +
				"DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install ${@:2}\n" + 
				"if [[ $? == 0 ]]; then touch /support/.$1_passed; apt-get clean; else touch /support/.$1_failed; fi", tempFile); 
		exec("chmod 0777 " + tempFile.getAbsolutePath(), true);

		//unpack the .obb file
		GNURootMain.this.runOnUiThread(new Runnable() {
			public void run() {
				installTar(Uri.fromFile(mainFile),"gnuroot_rootfs", null);
				//installTar(mainFile.getAbsolutePath());
			}
		});

		pdRing.dismiss();

	}

	void deleteRecursive(File fileOrDirectory) {
		if (fileOrDirectory.isDirectory())
			for (File child : fileOrDirectory.listFiles())
				deleteRecursive(child);
		
		fileOrDirectory.delete();
		
	}
	

	private void copyAssets(String packageName) {
		Context friendContext = null;
		try {
			friendContext = this.createPackageContext(packageName,Context.CONTEXT_IGNORE_SECURITY);
		} catch (NameNotFoundException e1) {
			return;
		}
		AssetManager assetManager = friendContext.getAssets();
		File tempFile = new File(getInstallDir().getAbsolutePath() + "/support");	
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		String[] files = null;
		try {
			files = assetManager.list("");
		} catch (IOException e) {
			Log.e("tag", "Failed to get asset file list.", e);
		}
		for(String filename : files) {
			InputStream in = null;
			OutputStream out = null;
			try {
				in = assetManager.open(filename);
				filename = filename.replace(".mp2", "");
				File outFile = new File(tempFile, filename);
				out = new FileOutputStream(outFile);
				copyFile(in, out);
				in.close();
				in = null;
				out.flush();
				out.close();
				out = null;
				exec("chmod 0777 " + outFile.getAbsolutePath(), true);
			} catch(IOException e) {
				Log.e("tag", "Failed to copy asset file: " + filename, e);
			}       
		}
	}

	private void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while((read = in.read(buffer)) != -1){
			out.write(buffer, 0, read);
		}
	}

	private void exec(String command, boolean setError) {
		Runtime runtime = Runtime.getRuntime(); 
		Process process;
		try {
			process = runtime.exec(command);
			try {
				String str;
				process.waitFor();
				BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
				while ((str = stdError.readLine()) != null) {
					Log.e("Exec",str);
					if (setError)
						errOcc = true;
				}
				process.getInputStream().close(); 
				process.getOutputStream().close(); 
				process.getErrorStream().close(); 
			} catch (InterruptedException e) {
				if (setError)
					errOcc = true;
			}
		} catch (IOException e1) {
			errOcc = true;
		}
	}

	private void writeToFile(String data, File file) {
		FileOutputStream stream;
		try {
			stream = new FileOutputStream(file);
			try {
				stream.write(data.getBytes());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				try {
					stream.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	//override this with what you want to happen when the GNURoot Debian service completes a task
	public void nextStep(Intent intent) {
		super.nextStep(intent);
		if (intent.getStringExtra("packageName").equals(getPackageName())) {
			int resultCode = intent.getIntExtra("resultCode",0);
			int requestCode = intent.getIntExtra("requestCode",0);
			int found = intent.getIntExtra("found",0);

			if (((requestCode == CHECK_STATUS) && (found == 1)) || (requestCode == RUN_SCRIPT)) {
				Thread thread = new Thread() {

					@Override
					public void run() {

						// Block this thread for 1 second. There is a race case if the progressDialog is dismissed too quickly
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
						}

						// After sleep finished blocking, create a Runnable to run on the UI Thread.
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								progressDialog.dismiss();
							}
						});

					}

				};
				thread.start();
			}
		}

	}
}