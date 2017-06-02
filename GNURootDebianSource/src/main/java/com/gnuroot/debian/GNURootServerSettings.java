package com.gnuroot.debian;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

public class GNURootServerSettings extends Activity implements CompoundButton.OnCheckedChangeListener {
    CheckBox check_ssh;
    CheckBox check_term;
    CheckBox check_vnc;
    CheckBox check_graphical;

    Button updateSSHPassword;
    Button updateVNCPassword;

    private String sshPassword;

    /**
     * Initialize UI, get shared preferences, and start listeners when created.
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);
        initUI();
        getPrefs();
        startListeners();
    }

    /**
     * Initialize global variables to their respective elements.
     */
    private void initUI() {
        check_ssh = (CheckBox) findViewById(R.id.launch_ssh_checkbox);
        check_term = (CheckBox) findViewById(R.id.launch_term_checkbox);
        check_vnc = (CheckBox) findViewById(R.id.launch_vnc_checkbox);
        check_graphical = (CheckBox) findViewById(R.id.launch_graphical_checkbox);

        updateSSHPassword = (Button) findViewById(R.id.updateSSHPassword);
        updateVNCPassword = (Button) findViewById(R.id.updateVNCPassword);
    }

    /**
     * Initialize preferences to values stored in shared preferences.
     */
    private void getPrefs() {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        check_ssh.setChecked(prefs.getBoolean("sshLaunch", true));
        check_term.setChecked(prefs.getBoolean("termLaunch", true));
        check_vnc.setChecked(prefs.getBoolean("vncLaunch", false));
        check_graphical.setChecked(prefs.getBoolean("graphicalLaunch", false));

        sshPassword = prefs.getString("sshPassword", "gnuroot");
    }

    /**
     * Start listeners for each of the settings elements.
     */
    private void startListeners() {
        check_ssh.setOnCheckedChangeListener(this);
        check_term.setOnCheckedChangeListener(this);
        check_vnc.setOnCheckedChangeListener(this);
        check_graphical.setOnCheckedChangeListener(this);

        updateSSHPassword.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                getNewUserPassword("sshPassword");
            }
        });

        updateVNCPassword.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                getNewUserPassword("vncPassword");
            }
        });

    }

    /**
     * Changes shared preferences based on checkboxes being
     * checked and unchecked.
     * There are several dependencies that are automatically enforced.
     * 1. At least one server must be launched, so VNC or SSH launch must be checked.
     * 2. SSH currently must be active at all times.
     * 3. Terminals require ssh servers.
     * 4. Graphical launches require vnc servers.
     * @param buttonView Determines which checkbox's state changed.
     * @param isChecked Indicates whether the checkbox has become checked
     *                  or unchecked.
     */
    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        switch(buttonView.getId()) {
            case R.id.launch_ssh_checkbox:
                if(!isChecked && !check_vnc.isChecked())
                    check_vnc.setChecked(true);
                if(!isChecked && check_term.isChecked())
                    check_term.setChecked(false);
                editor.putBoolean("sshLaunch", isChecked);
                break;
            case R.id.launch_term_checkbox:
                if(isChecked && !check_ssh.isChecked())
                    check_ssh.setChecked(true);
                editor.putBoolean("termLaunch", isChecked);
                break;
            case R.id.launch_vnc_checkbox:
                if(!isChecked && !check_ssh.isChecked())
                    check_ssh.setChecked(true);
                if(!isChecked && check_graphical.isChecked())
                    check_graphical.setChecked(false);
                editor.putBoolean("vncLaunch", isChecked);
                break;
            case R.id.launch_graphical_checkbox:
                if(isChecked && !check_vnc.isChecked())
                    check_vnc.setChecked(true);
                editor.putBoolean("graphicalLaunch", isChecked);
                break;
        }
        editor.commit();
    }

    /**
     * Get a new user password and set it on the Android side through shared preferences.
     * @param type Designates whether ssh or vnc password is being set.
     */
    private void getNewUserPassword(final String type) {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        final SharedPreferences.Editor editor = prefs.edit();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.password_prompt);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton(R.string.server_setting_positive_button, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String userPassword = input.getText().toString();
                // Passwords that are too long are truncated automatically by VNC, but passwords
                // that are too short must be handled.
                if(userPassword.length() < 6) {
                    Toast.makeText(getApplicationContext(), R.string.short_password, Toast.LENGTH_LONG).show();
                    dialog.cancel();
                    return;
                }
                editor.putString(type, userPassword);
                editor.commit();

                if("sshPassword".equals(type))
                    setNewSSHPasswordInRootFS(userPassword);
                else if("vncPassword".equals(type)) {
                    setNewVNCPasswordInRootFS(userPassword);
                }
                else {
                    Log.e("GNURootServerSettings", "getNewUserPassword received an unexpected @type.");
                }

                dialog.cancel();
            }
        });

        builder.setNegativeButton(R.string.server_setting_negative_button,
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.create().show();
    }

    /**
     * Send @pw to chpasswd for "user" user in a background proot session.
     */
    private void setNewSSHPasswordInRootFS(String pw) {
        String[] command = { getInstallDir().getAbsolutePath() + "/support/execInProot",
                "/support/changeSSHPasswd", pw };

        try {
            Runtime.getRuntime().exec(command);
        } catch (IOException e) {
            Log.e("GNURootServerSettings", "Failed to set new SSH password: " + e);
        }
    }


    /**
     * Send @pw to the changeVNCPasswd script in a background proot session.
     * changeVNCPasswd must be executed by the user whose password is changing, in this case "user".
     * The background argument is sent to imply that dropbear should not attempt opening a terminal.
     */
    private void setNewVNCPasswordInRootFS(String pw) {
        // If packages haven't been updated, the password cannot be changed until expect is installed.
        // installXSupport will use the password set on the Android side after expect has been installed.
        File xPackagesStatus = new File(getInstallDir().getAbsolutePath() + "/support/.gnuroot_x_package_passed");
        if(!xPackagesStatus.exists())
            return;

        String[] command = { getInstallDir().getAbsolutePath() + "/support/startDBClient", sshPassword,
                "background", "/support/changeVNCPasswd", pw };

        try {
            Runtime.getRuntime().exec(command);
        } catch (IOException e) {
            Log.e("GNURootService", "Failed to set new VNC password: " + e);
        }
    }

    public File getInstallDir() {
        try {
            return new File(getPackageManager().getApplicationInfo("com.gnuroot.debian", 0).dataDir);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
