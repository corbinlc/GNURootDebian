package com.gnuroot.debian;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.CompoundButton;

public class GNURootServerSettings extends Activity implements CompoundButton.OnCheckedChangeListener {
    CheckBox check_ssh;
    CheckBox check_term;
    CheckBox check_vnc;
    CheckBox check_graphical;

    /**
     * Initialize UI, get shared preferences, and start listeners when created.
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // TODO add strings activity_server_settings.xml
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);
        initUI();
        getPrefs();
        startListeners();
    }

    /**
     * Initialize global variables to their respective checkboxes.
     */
    private void initUI() {
        check_ssh = (CheckBox) findViewById(R.id.launch_ssh_checkbox);
        check_term = (CheckBox) findViewById(R.id.launch_term_checkbox);
        check_vnc = (CheckBox) findViewById(R.id.launch_vnc_checkbox);
        check_graphical = (CheckBox) findViewById(R.id.launch_graphical_checkbox);
    }

    private void getPrefs() {
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        check_ssh.setChecked(prefs.getBoolean("sshLaunch", true));
        check_term.setChecked(prefs.getBoolean("termLaunch", true));
        check_vnc.setChecked(prefs.getBoolean("vncLaunch", false));
        check_graphical.setChecked(prefs.getBoolean("graphicalLaunch", false));
    }

    /**
     * Start listeners for each of the checkboxes.
     */
    private void startListeners() {
        check_ssh.setOnCheckedChangeListener(this);
        check_term.setOnCheckedChangeListener(this);
        check_vnc.setOnCheckedChangeListener(this);
        check_graphical.setOnCheckedChangeListener(this);
    }

    /**
     * Changes shared preferences based on checkboxes being
     * checked and unchecked.
     * There are several dependencies that are automatically enforced.
     * 1. At least one server must be launched, so VNC or SSH launch must be checked.
     * 2. Terminals require ssh servers.
     * 3. Graphical launches require vnc servers.
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
}
