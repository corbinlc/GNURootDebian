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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.gnuroot.library.GNURootCoreActivity;

import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.preference.PreferenceManager;
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
import com.iiordanov.bVNC.Constants;

public class GNURootMain extends GNURootCoreActivity {

	private ActionBar actionBar;
	String rootfsName = "Debian";
	Boolean errOcc;
	Boolean expectingResult = false;
    Boolean installingXStep1 = false;
	ProgressDialog pdRing;
	Integer downloadResultCode;
	File mainFile;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		SharedPreferences.Editor editor = prefs.edit();
        if(!prefs.getBoolean("firstTime", false)) {
            try {
                setupSupportFiles(true);
            }
            catch (NameNotFoundException e) {
                Toast.makeText(getApplicationContext(), "Installing GNURoot " + rootfsName + " failed.  Something went wrong.", Toast.LENGTH_LONG).show();
            }
            setupFirstHalf();
            editor.putBoolean("firstTime", true);
            editor.commit();
        }
        else if(getIntent().getAction() == "com.gnuroot.debian.NEW_XTERM")
			launchXTerm(false);
		else if(getIntent().getAction() == "com.gnuroot.debian.NEW_XWINDOW")
            launchXTerm(true);
		else
			launchTerm();
	}
    /*
	@Override
	protected void onNewIntent(Intent intent) {
		String action = intent.getAction();
		if (action.equals("com.gnuroot.debian.NEW_WINDOW"))
			launchTerm();
		else if (action.equals("com.gnuroot.debian.NEW_XWINDOW"))
			launchXTerm();
	}
    */
	/*
    //first install packages
    //then apply custom tar file that provides a simple xstartup adn passwd file
	public void installX() {
        installingXStep1 = true;
		ArrayList<String> packageNameArrayList = new ArrayList<String>();
		packageNameArrayList.add("libgl1-mesa-swx11");
		packageNameArrayList.add("tightvncserver");
		packageNameArrayList.add("xterm");
		packageNameArrayList.add("xfonts-base");
		packageNameArrayList.add("twm");
		ArrayList<String> prerequisitesArrayList = new ArrayList<String>();
		prerequisitesArrayList.add("gnuroot_rootfs");
		installPackages(packageNameArrayList, "gnuroot_x_support_step1", prerequisitesArrayList);
	}
	*/

	public void setupSupportFiles(boolean deleteFirst) throws NameNotFoundException {
		File installDir = getInstallDir();
		File sdcardInstallDir = getSdcardInstallDir();

        if (deleteFirst)
            deleteRecursive(new File(installDir.getAbsolutePath() + "/support"));
		
		//create directory for support executables and scripts
		File tempFile = new File(installDir.getAbsolutePath() + "/support");
		if (!tempFile.exists()) {
			tempFile.mkdir();
		}
		
		//copy bare necessities to the now created support directory
		copyAssets("com.gnuroot.debian");

		String shadowOption = " ";

		//create a script for running a command in proot
		tempFile = new File(installDir.getAbsolutePath() + "/support/launchProot");
		if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.KITKAT) {
			shadowOption = ((CheckBox) findViewById(R.id.sdcard_checkbox)).isChecked() ? " -n " : " ";
		}

        //create a script for starting a xterm
        //start vncserver if not already running
        //start new xterm
		/*
        tempFile = new File(installDir.getAbsolutePath() + "/support/startX");
        writeToFile("#!/bin/bash\n" +
				"cp /root/.Xauthority /home/.Xauthority\n" +
                "DISPLAY=localhost:51 xterm -geometry 80x24+0+0 -e exit\n" +
                "if [[ $? == 0 ]]; then\n" +
				"cp /root/.Xauthority /home/.Xauthority\n" +
                "DISPLAY=localhost:51 xterm -geometry 80x24+0+0 -e $@ &\n" +
                "\nblue='\\033[0;34m'; NC='\\033[0m'; echo -e \"${blue}Killing this terminal will kill your xterm\"\n" +
                "echo -e \"${NC}\"\n" +
				"touch /support/.gnuroot_x_started\n" +
                "else\n" +
                "rm /tmp/.X51-lock\n" +
                "rm /tmp/.X11-unix/X51\n" +
                "HOME=/root tightvncserver -geometry 1024x768 :51\n" +
				"cp /root/.Xauthority /home/.Xauthority\n" +
                "DISPLAY=localhost:51 xterm -geometry 80x24+0+0 -e $@ &\n" +
                "\nblue='\\033[0;34m'; NC='\\033[0m'; echo -e \"${blue}Killing this terminal will kill your vnc server and xterm\"\n" +
                "echo -e \"${NC}\"\n" +
				"touch /support/.gnuroot_x_started\n" +
                "fi",tempFile);
        exec("chmod 0777 " + tempFile.getAbsolutePath(), true);
		*/
        SharedPreferences.Editor editor = getSharedPreferences("MAIN", MODE_PRIVATE).edit();
        PackageInfo pi = getPackageManager().getPackageInfo("com.gnuroot.debian", 0);
        editor.putString("patchVersion", pi.versionName);
        editor.commit();
	}

	private void setupFirstHalf() {
		errOcc = false;

		expectingResult = true;
		Intent downloadIntent = new Intent();
		downloadIntent.addCategory(Intent.CATEGORY_DEFAULT);
		downloadIntent.setClassName("com.gnuroot.debian","com.gnuroot.debian.GNURootDownloaderActivity");
		startActivityForResult(downloadIntent, 0);
	}

	// Since the Downloader Activity was no longer returning to an interactive
	// UI, the notification would persist even after the download completed.
	public static void cancelNotifications(Context ctx) {
		NotificationManager notifManager= (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
		notifManager.cancelAll();
	}

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
                                        Toast.makeText(getApplicationContext(), "Installing GNURoot Debian failed.  Something went wrong.", Toast.LENGTH_LONG).show();
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
                                    Toast.makeText(getApplicationContext(), "Installing GNURoot Debian failed.  Couldn't get necessary .obb files.", Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
				}
			}
		}
	//	if(requestCode == 1)
	//		launchXTerm();
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


		tempFile = new File(installDir.getAbsolutePath()+"/debian/sys");
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

        //exec(getInstallDir().getAbsolutePath() + "/support/launchProot", true);

		//unpack the .obb file
		/*
		GNURootMain.this.runOnUiThread(new Runnable() {
			public void run() {
				installTar(Uri.fromFile(mainFile),"gnuroot_rootfs", null);
			}
		});
		*/

		launchTerm();

		//finish();

		//pdRing.dismiss();
	}
    /*
	void deleteRecursive(File fileOrDirectory) {
		if (fileOrDirectory.isDirectory())
			for (File child : fileOrDirectory.listFiles())
				deleteRecursive(child);

		fileOrDirectory.delete();

	}
	*/
    void deleteRecursive(File fileOrDirectory) {
        exec(getInstallDir().getAbsolutePath() + "/support/busybox rm -rf " + fileOrDirectory.getAbsolutePath(), true);
    }

	private void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while((read = in.read(buffer)) != -1){
			out.write(buffer, 0, read);
		}
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
                //if (filename.contains(".tar.gz"))
                //    out = openFileOutput(filename,MODE_PRIVATE);
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
            } catch(IOException e) {
                Log.e("tag", "Failed to copy asset file: " + filename, e);
            }
        }
    }

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
                line = line.replace("SDCARD_ROOT_PATH", sdcardInstallDir.getAbsolutePath());
                line = line.replace("SDCARD_PATH", sdcardPath);
                line = line.replace("EXTRA_BINDINGS", "");
                line = line.replace("MAIN_FILE_PATH", mainFilePath);
                bw.write(line+"\n");
            }
        } catch (Exception e) {
            //todo, don't just return
            return;
        } finally {
            try {
                if(br != null)
                    br.close();
            } catch (IOException e) {
                //
            }
            try {
                if(bw != null)
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

            if (installingXStep1 && ((requestCode == CHECK_STATUS) && (resultCode != GNURootCoreActivity.STATUS_FILE_NOT_FOUND))) {
                installingXStep1 = false;
                if (resultCode == GNURootCoreActivity.PASS) {
                    File fileHandle = new File(getFilesDir() + "/xsupport.tar.gz");
                    ArrayList<String> prerequisitesArrayList = new ArrayList<String>();
                    prerequisitesArrayList.add("gnuroot_rootfs");
                    prerequisitesArrayList.add("gnuroot_x_support_step1");
                    installTar(FileProvider.getUriForFile(this, "com.gnuroot.debian.fileprovider", fileHandle), "gnuroot_x_support", prerequisitesArrayList);
                }
            } else if (((requestCode == CHECK_STATUS) && (resultCode != GNURootCoreActivity.STATUS_FILE_NOT_FOUND)) || (requestCode == RUN_SCRIPT)) {
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
		//finish();
	}

    public void launchTerm() {
		/*
        ArrayList<String> prerequisitesArrayList = new ArrayList<String>();
        prerequisitesArrayList.add("gnuroot_rootfs");
        runCommand("/bin/bash", prerequisitesArrayList);
        //((GNURootCoreActivity)getActivity()).runCommand("/data/data/com.gnuroot.debian/support/busybox sh", prerequisitesArrayList);
        */
		Intent termIntent = new Intent(this,jackpal.androidterm.RunScript.class);
		termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		termIntent.addCategory(Intent.CATEGORY_DEFAULT);
		termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
		termIntent.putExtra("jackpal.androidterm.iInitialCommand", getInstallDir().getAbsolutePath() + "/support/launchProot");
		startActivity(termIntent);
		finish();
    }

	public void installXTerm() {

	}

    public void launchXTerm(Boolean createNewXTerm) {
		/*
        ArrayList<String> prerequisitesArrayList = new ArrayList<String>();
        prerequisitesArrayList.add("gnuroot_rootfs");
        prerequisitesArrayList.add("gnuroot_x_support");
        runXCommand("/bin/bash", prerequisitesArrayList);
        */

		File deleteStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
		if(deleteStarted.exists())
			deleteStarted.delete();

        Intent termIntent = new Intent(this, jackpal.androidterm.RunScript.class);
        termIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        termIntent.addCategory(Intent.CATEGORY_DEFAULT);
        termIntent.setAction("jackpal.androidterm.RUN_SCRIPT");
        if(createNewXTerm)
            termIntent.putExtra("jackpal.androidterm.iInitialCommand", getInstallDir().getAbsolutePath() + "/support/execInProot /support/newXterm");
        else
            termIntent.putExtra("jackpal.androidterm.iInitialCommand", getInstallDir().getAbsolutePath() + "/support/launchXterm");
        startActivity(termIntent);


		final ScheduledExecutorService scheduler =
				Executors.newSingleThreadScheduledExecutor();

		scheduler.scheduleAtFixedRate
				(new Runnable() {
					public void run() {
						File checkStarted = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_started");
						File checkRunning = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_running");
						if(checkStarted.exists() || checkRunning.exists()) {
							Intent bvncIntent = new Intent(getBaseContext(), com.iiordanov.bVNC.RemoteCanvasActivity.class);
							bvncIntent.setData(Uri.parse("vnc://127.0.0.1:5951/?"+ Constants.PARAM_VNC_PWD+"=gnuroot"));
							bvncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
							startActivity(bvncIntent);
							scheduler.shutdown();
						}
					}
				}, 3, 2, TimeUnit.SECONDS); //Avoid race case in which tightvnc needs to be restarted


		finish();
    }

	public void reconnectX() {
		Intent vncIntent = new Intent("com.gnuroot.debian.CONNECT_VNC_VIEWER");
		vncIntent.addCategory(Intent.CATEGORY_DEFAULT);
		vncIntent.putExtra("packageName", getPackageName());
		startService(vncIntent);
	}
}
