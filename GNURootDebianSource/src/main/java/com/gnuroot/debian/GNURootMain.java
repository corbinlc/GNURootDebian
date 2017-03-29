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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import com.iiordanov.bVNC.Constants;

public class GNURootMain extends Activity {

	//Launch types. GNURoot Debian expects this to be stored in the "launchType" extra in LAUNCH intents.
	public static final String GNUROOT_TERM = "launchTerm";
	public static final String GNUROOT_XTERM = "launchXTerm";

	Boolean errOcc;
	Boolean expectingResult = false;
	ProgressDialog pdRing;
	Integer downloadResultCode;
	File mainFile;
	Intent savedIntent = null;
	boolean noInstallAgain = false;

	/**
	 * GNURoot Debian behaves based on the intent it receives (if it exists), or installs the rootfs if it does not
	 * yet exist, or launches into a prooted terminal.
	 * Possible intent actions:
	 * 1. LAUNCH. See handleLaunchIntent.
	 * 2. GNUROOT_REINSTALL. Reinstalls the rootfs, not the app.
	 * 3. UPDATE_ERROR. An internal intent sent from GNURoot service to handle intents sent from the old ecosystem.
	 * @param savedInstanceState
	 */
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		handleIntent(getIntent());
	}

	private void handleIntent(Intent intent) {
		String intentAction = intent.getAction();

		File installStatus = new File(getInstallDir().getAbsolutePath() +"/support/.gnuroot_rootfs_passed");
		if ((!isSymlink(installStatus) && !installStatus.exists()) && (noInstallAgain == false)) {
			GNURootMain.this.runOnUiThread(new Runnable() {
				public void run() {
					Toast.makeText(getApplicationContext(), R.string.toast_not_installed, Toast.LENGTH_LONG).show();
				}
			});
			savedIntent = intent;
			setupSupportFiles(true, true);
			updateVersion();
			setupFirstHalf();
		} else if ("com.gnuroot.debian.GNUROOT_REINSTALL".equals(intentAction)) {
			setupSupportFiles(true, true);
			updateVersion();
			setupFirstHalf();
		} else if ("com.gnuroot.debian.LAUNCH".equals(intentAction)) {
			noInstallAgain = false;
			handleLaunchIntent(intent);
		}  else if ("com.gnuroot.debian.UPDATE_ERROR".equals(intentAction))
			showUpdateErrorButton(intent.getStringExtra("packageName"));
        /*
        else if(intentAction == "com.gnuroot.debian.TOAST_ALARM")
            makeAlarmToast(intent);
        */
		else
			launchTerm(null);
	}

	/**
	 * Launches a prooted term or xterm depending on the intent. Will also untar a shared file if it exists.
	 * @param intent
	 */
	public void handleLaunchIntent(Intent intent) {
		Uri sharedFile = intent.getData();
		String launchType = intent.getStringExtra("launchType");
		String command = intent.getStringExtra("command");

		if (command != null) {
			File file = new File(getInstallDir().getAbsolutePath() + "/support/newCommand");
			try {
				OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(file));
				outputStreamWriter.write(command);
				outputStreamWriter.close();
			} catch (IOException e) {
				Log.e("Exception", "File write failed: " + e.toString());
			}
			exec("chmod 0777 " + file.getAbsolutePath(), true);
			command = "/support/newCommand";
		}

		if(sharedFile != null) {
			copySharedFile(sharedFile);
		}

		switch (launchType) {
			case GNUROOT_TERM:
				launchTerm(command);
				return;
			case GNUROOT_XTERM:
				launchXTerm(!intent.getBooleanExtra("terminal_button", false), command);
				return;
			default:
				Toast.makeText(this, R.string.toast_bad_launch_type, Toast.LENGTH_LONG).show();
				finish();
		}
	}

	/**
	 * Sends an intent to Android Terminal Emulator to launch PRoot, which installs any missing components.
	 * @param command is the command that will be executed when PRoot launches.
	 */
	public void launchTerm(String command) {
		Intent termIntent = new Intent(this, jackpal.androidterm.RunScript.class);
		termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
		if(command == null)
			termIntent.putExtra("jackpal.androidterm.iInitialCommand",
					getInstallDir().getAbsolutePath() + "/support/launchProot /bin/bash");
		else
			termIntent.putExtra("jackpal.androidterm.iInitialCommand",
					getInstallDir().getAbsolutePath() + "/support/launchProot " + command);

		checkPatches();

		startActivity(termIntent);
		finish();
	}

	/**
	 * Launches a VNC session by sending an intent to create a new xterm to Android Terminal Emulator, waiting for
	 * status files to be created, and then sending an intent to bVNC.
	 * @param createNewXTerm determines if a new xterm should be launched regardless of whether one is already active.
	 */
	public void launchXTerm(Boolean createNewXTerm, String command) {
		File deleteStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
		if (deleteStarted.exists())
			deleteStarted.delete();

		Intent serviceIntent = new Intent(this, GNURootService.class);
		serviceIntent.addCategory(Intent.CATEGORY_DEFAULT);
		serviceIntent.putExtra("type", "VNC");
		serviceIntent.putExtra("command", command);
		serviceIntent.putExtra("newXterm", createNewXTerm);

		checkPatches();
		startService(serviceIntent);

		finish();
	}

	/**
	 * Copy a file from another app. This can be a tar file or a singular script.
	 * @param sharedFile must be a tar.gz file. The intent must include flag FLAG_GRANT_READ_URI_PERMISSION
	 */
	private void copySharedFile(Uri sharedFile) {
		InputStream srcStream;
		String untarCommand;
		boolean status = true;
		File srcFile = new File(sharedFile.getPath());
		File destFolder = getInstallDir();
		File destFile = new File(destFolder.getAbsolutePath() + "/support/" + srcFile.getName());

		try {
			srcStream = getContentResolver().openInputStream(sharedFile);
			try { copyFile(srcStream, new FileOutputStream(destFile)); }
			catch (IOException e) { status = false; }
		} catch (FileNotFoundException e) {
			status = false;
		}

		if (!status) {
			Toast.makeText(this, R.string.toast_file_not_found, Toast.LENGTH_LONG).show();
		}

		return;

	}

	/**
	 * Displays an alert dialog if another app isn't updated to at least the version that included the
	 * new ecosystem changes. Sends the user to the market page for it if not.
	 */
	private void showUpdateErrorButton(final String packageName) {
		AlertDialog.Builder builder = new AlertDialog.Builder(GNURootMain.this);
		builder.setMessage(R.string.update_error_message);
		builder.setPositiveButton(R.string.button_affirmative, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
				if(packageName != null){
					Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setData(Uri.parse("market://details?id=" + packageName));
					startActivity(intent);
				}
				finish();
			}
		});
		builder.create().show();
	}

	/**
	 *  TODO This was going to be used to check status files.
	 *  private void makeAlarmToast(Intent intent) {
	 *	    String alarmName = intent.getStringExtra("alarmName");
	 *	    Toast.makeText(this, alarmName + "... Please wait for completion.", Toast.LENGTH_LONG).show();
	 *  }
	 */

	/**
	 * Creates /support and copies from project assets directory to proper rootfs locations.
	 * @param deleteFirst indicates to blow /support away first.
	 */
	public void setupSupportFiles(boolean deleteFirst, boolean homeStatus) {
		File installDir = getInstallDir();

		if (deleteFirst)
			deleteRecursive(new File(installDir.getAbsolutePath() + "/support"));

		//create directory for support executables and scripts
		File tempFile = new File(installDir.getAbsolutePath() + "/support");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//copy bare necessities to the now created support directory
		copyAssets("com.gnuroot.debian");

        if(homeStatus) {
            tempFile = new File(getInstallDir() + "/support/.home_internal");
            if(!tempFile.exists()) {
                try {
                    tempFile.createNewFile();
                } catch (IOException e) {
                    Log.e("GNURoot", "Failed to create home directory status file.");
                }
            }
        }

		/* TODO This isn't used currently, but will need to be handled somehow eventually.
		String shadowOption = " ";
		//create a script for running a command in proot
		tempFile = new File(installDir.getAbsolutePath() + "/support/launchProot");
		if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.KITKAT) {
			shadowOption = ((CheckBox) findViewById(R.id.sdcard_checkbox)).isChecked() ? " -n " : " ";
		}
		*/
	}

	/**
	 * Launches the GNURootDownloaderActivity.
	 */
	private void setupFirstHalf() {
		errOcc = false;

		expectingResult = true;
		Intent downloadIntent = new Intent();
		downloadIntent.addCategory(Intent.CATEGORY_DEFAULT);
		downloadIntent.setClassName("com.gnuroot.debian", "com.gnuroot.debian.GNURootDownloaderActivity");
		startActivityForResult(downloadIntent, 0);
	}


	/**
	 * Cancels the downloader notification caused by GNURootDownloaderActivity.
	 * @param ctx
	 */
	public static void cancelNotifications(Context ctx) {
		NotificationManager notifManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
		notifManager.cancelAll();
	}

	/**
	 * If GNURootDownloaderActivity is successful, kills the downloader notification and launches setupSecondHalf.
	 * @param requestCode
	 * @param resultCode
	 * @param data
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode,
									Intent data) {
		if (expectingResult) {
			cancelNotifications(this);
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
								GNURootMain.this.runOnUiThread(new Runnable() {
									public void run() {
										Toast.makeText(getApplicationContext(), R.string.toast_bad_install, Toast.LENGTH_LONG).show();
									}
								});
							}
						}
					};
					t.start();
				} else {
					pdRing.dismiss();
					GNURootMain.this.runOnUiThread(new Runnable() {
						public void run() {
							GNURootMain.this.runOnUiThread(new Runnable() {
								public void run() {
									Toast.makeText(getApplicationContext(), R.string.toast_bad_install, Toast.LENGTH_LONG).show();
								}
							});
						}
					});
				}
			}
		}
	}

	/**
	 * Saves the location of the architecture specific obb in @mainFile, then creates
	 * basic directories for the file system and finally launches a term.
	 * @param obbVersion
	 */
	private void setupSecondHalf(int obbVersion) {

		String path = Environment.getExternalStorageDirectory() + "/Android/obb/com.gnuroot.debian/";
		mainFile = new File(path + "main." + Integer.toString(obbVersion) + ".com.gnuroot.debian.obb");
		File installDir = getInstallDir();
		File sdcardInstallDir = getSdcardInstallDir();

		//setup some basic directories

		//if the home directory already exists, move it so it can be restored
        boolean saveHome = false;
		String rootfsHome = getInstallDir().getAbsolutePath() + "/debian/home";
        String dataHome = getInstallDir() + "/home";
        String rootfsShadow = getInstallDir().getAbsolutePath() + "/debian/etc/shadow";
        String dataShadow = getInstallDir().getAbsolutePath() + "/shadow";
        String rootfsPasswd = getInstallDir().getAbsolutePath() + "/debian/etc/passwd";
        String dataPasswd = getInstallDir().getAbsolutePath() + "/passwd";
        String rootfsPasswd2 = getInstallDir().getAbsolutePath() + "/debian/etc/passwd-";
        String dataPasswd2 = getInstallDir().getAbsolutePath() + "/passwd-";
		File from = new File(rootfsHome);
		File to;
		if(from.exists()) {
			to = new File(dataHome);
			from.renameTo(to);

            // Preserve the shadow file so users are saved. Moved back by launchProot
            // after reinstallation.

            from = new File(rootfsShadow);
            to = new File(dataShadow);
            from.renameTo(to);

            from = new File(rootfsPasswd);
            to = new File(dataPasswd);
            from.renameTo(to);

            from = new File(rootfsPasswd2);
            to = new File(dataPasswd2);
            from.renameTo(to);

            saveHome = true;
		}

		//create internal install directory
		File tempFile = new File(installDir.getAbsolutePath() + "/debian");
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
		tempFile = new File(sdcardInstallDir.getAbsolutePath() + "/debian");
		//blow it aways if it already existed
		if (tempFile.exists()) {
			deleteRecursive(tempFile);
		}
		tempFile.mkdir();

		//create a directory for binding the android rootfs to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/host-rootfs");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the noexec directory to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/.proot.noexec");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the sdcard to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/sdcard");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/dev");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/proc");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/mnt");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		tempFile = new File(installDir.getAbsolutePath() + "/debian/data");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}


		tempFile = new File(installDir.getAbsolutePath() + "/debian/sys");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create a directory for binding the home to
		tempFile = new File(installDir.getAbsolutePath() + "/debian/home");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		if(saveHome) {
            // Move home directory and passwd files back.
			to = new File(rootfsHome);
            from = new File(dataHome);
			from.renameTo(to);

            to = new File(rootfsShadow);
            from = new File(dataShadow);
            from.renameTo(to);

            to = new File(rootfsPasswd);
            from = new File(dataPasswd);
            from.renameTo(to);

            to = new File(rootfsPasswd2);
            from = new File(dataPasswd2);
            from.renameTo(to);
		}

		//create a directory for firing intents from
		tempFile = new File(installDir.getAbsolutePath() + "/debian/intents");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		//create an intents directory on the sdcard
		tempFile = new File(sdcardInstallDir.getAbsolutePath() + "/intents");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}

		if ((savedIntent == null) || (!"com.gnuroot.debian.LAUNCH".equals(savedIntent.getAction())))
			launchTerm(null);
		else {
			noInstallAgain = true;
			handleIntent(savedIntent);
		}

	}

	public File getInstallDir() {
		try {
			return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
		} catch (NameNotFoundException e) {
			return null;
		}
	}

	public File getSdcardInstallDir() {
		return new File(Environment.getExternalStorageDirectory() + "/GNURoot");
	}

	/**
	 * Recursively deletes everything at @fileOrDirectory and below.
	 * @param fileOrDirectory
	 */
	void deleteRecursive(File fileOrDirectory) {
		exec(getInstallDir().getAbsolutePath() + "/support/busybox rm -rf " + fileOrDirectory.getAbsolutePath(), true);
	}

	/**
	 * Copies a file from @in to @out.
	 * @param in
	 * @param out
	 * @throws IOException
	 */
	private void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
	}

	public void copyDirectory(File sourceLocation , File targetLocation)
			throws IOException {

		if (sourceLocation.isDirectory()) {
			if (!targetLocation.exists()) {
				targetLocation.mkdir();
			}

			String[] children = sourceLocation.list();
			for (int i=0; i<children.length; i++) {
				copyDirectory(new File(sourceLocation, children[i]),
						new File(targetLocation, children[i]));
			}
		} else {

			InputStream in = new FileInputStream(sourceLocation);
			OutputStream out = new FileOutputStream(targetLocation);

			// Copy the bits from instream to outstream
			byte[] buf = new byte[1024];
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
			in.close();
			out.close();
		}
	}

	/**
	 * Copies assets from the armhf|armhf|i386 /assets directory to /data/user/0/support.
	 * This is bound to ROOTDIR/support when execInProot is ran.
	 * @param packageName
	 */
	private void copyAssets(String packageName) {
		Context friendContext = null;
		try {
			friendContext = this.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY);
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
		for (String filename : files) {
			InputStream in = null;
			OutputStream out = null;
			boolean replaceStrings = false;
			try {
				in = assetManager.open(filename);
				if (filename.contains(".replace.mp2")) {
					replaceStrings = true;
				}
				filename = filename.replace(".replace.mp2", "");
				filename = filename.replace(".mp2", "");
				filename = filename.replace(".mp3", ".tar.gz");
				File outFile = new File(tempFile, filename);
				out = new FileOutputStream(outFile);
				copyFile(in, out);
				in.close();
				in = null;
				out.flush();
				out.close();
				out = null;
				if (replaceStrings) {
					replaceScriptStrings(outFile);
				}
				exec("chmod 0777 " + outFile.getAbsolutePath(), true);
			} catch (IOException e) {
				Log.e("tag", "Failed to copy asset file: " + filename, e);
			}
		}
	}

	/**
	 * Replaces key phrases in scripts copied with the asset manager.
	 * @param myFile
	 */
	private void replaceScriptStrings(File myFile) {
		File installDir = getInstallDir();
		File sdcardInstallDir = getSdcardInstallDir();
		String sdcardPath = Environment.getExternalStorageDirectory().getAbsolutePath();
		String mainFilePath = Environment.getExternalStorageDirectory() + "/Android/obb/com.gnuroot.debian/main." + BuildConfig.OBBVERSION + ".com.gnuroot.debian.obb";

		String oldFileName = myFile.getAbsolutePath();
		String tmpFileName = oldFileName + ".tmp";

		BufferedReader br = null;
		BufferedWriter bw = null;
		try {
			br = new BufferedReader(new FileReader(oldFileName));
			bw = new BufferedWriter(new FileWriter(tmpFileName));
			String line;
			while ((line = br.readLine()) != null) {
				line = line.replace("ROOT_PATH", installDir.getAbsolutePath());
				line = line.replace("SDCARD_GNU_PATH", sdcardInstallDir.getAbsolutePath());
				line = line.replace("SDCARD_PATH", sdcardPath);
				line = line.replace("EXTRA_BINDINGS", "");
				line = line.replace("MAIN_FILE_PATH", mainFilePath);
				bw.write(line + "\n");
			}
		} catch (Exception e) {
			Toast.makeText(this, R.string.toast_bad_install, Toast.LENGTH_LONG).show();
			return;
		} finally {
			try {
				if (br != null)
					br.close();
			} catch (IOException e) {
				//
			}
			try {
				if (bw != null)
					bw.close();
			} catch (IOException e) {
				//
			}
		}
		// Once everything is complete, delete old file..
		File oldFile = new File(oldFileName);
		oldFile.delete();

		// And rename tmp file's name to old file name
		File newFile = new File(tmpFileName);
		newFile.renameTo(oldFile);

	}

	/**
	 * Executes @command and sets @setError if an error occurs.
	 * @param command
	 * @param setError
	 */
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
					Log.e("Exec", str);
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

	/**
	 * Checks whether the home directory has been moved off of the sdcard.
	 */

	private void checkHome() {
		File check = new File(getInstallDir().getAbsolutePath() + "/support/.home_internal");
		if(!check.exists()) {
            File sourceDir = new File(getSdcardInstallDir().getAbsolutePath() + "/home");
            File targetDir = new File(getInstallDir().getAbsolutePath() + "/debian/home");
			try {
				copyDirectory(sourceDir, targetDir);
                if(!check.createNewFile())
                    Log.e("GNURootMain", "Could not create home internal status file.");
                deleteRecursive(sourceDir);
                sourceDir.mkdir();
                File source = new File(getInstallDir().getAbsolutePath() + "/support/WHERE_ARE_MY_FILES.txt");
                File target = new File(getSdcardInstallDir().getAbsolutePath() + "/home/WHERE_ARE_MY_FILES.txt");
                copyDirectory(source, target);
			}
			catch (IOException e) {
				Log.e("GNURootMain", "Couldn't move home directory internal: " + e);
			}
		}
	}

	/**
	 * Checks whether the package.versionName is the same as the the versionName stored in shared preferences. If it
	 * is not, it deletes the patch passed status file so that the launchProot script will apply the new patches.
	 */
	private void checkPatches() {
		SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();

		PackageInfo pi;
		String patchVersion = "notARealPatchVersion";
		String sharedVersion = prefs.getString("patchVersion", null);
		try {
			pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			patchVersion = pi.versionName;
		} catch (NameNotFoundException e) {
			Toast.makeText(getApplicationContext(), R.string.toast_bad_package, Toast.LENGTH_LONG).show();
		}

		if (sharedVersion != null && !sharedVersion.equals(patchVersion)) {
			File patchStatus = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_patch_passed");
			deleteRecursive(patchStatus);
			Toast.makeText(this, R.string.toast_bad_patch, Toast.LENGTH_LONG).show();
		}

		if ((sharedVersion == null) || (!sharedVersion.equals(patchVersion))) {
			setupSupportFiles(false, false);
            checkHome();
		}

		if (!"notARealPatchVersion".equals(patchVersion)) {
			editor.putString("patchVersion", patchVersion);
			editor.commit();
		}
	}

	private void updateVersion() {
		SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();

		PackageInfo pi;
		String patchVersion = "notARealPatchVersion";
		try {
			pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			patchVersion = pi.versionName;
		} catch (NameNotFoundException e) {
			Toast.makeText(getApplicationContext(), R.string.toast_bad_package, Toast.LENGTH_LONG).show();
		}

		if (!"notARealPatchVersion".equals(patchVersion)) {
			editor.putString("patchVersion", patchVersion);
			editor.commit();
		}
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
}