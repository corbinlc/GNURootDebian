package com.gnuroot.debian;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Toast;

public class GNURootServerSettings extends Activity implements CompoundButton.OnCheckedChangeListener {
    CheckBox check_ssh;
    CheckBox check_term;
    CheckBox check_vnc;
    CheckBox check_graphical;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // TODO add strings activity_server_settings.xml
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);
        initUI();
        getPrefs();
        startListeners();
    }

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

    private void startListeners() {
        check_ssh.setOnCheckedChangeListener(this);
        check_term.setOnCheckedChangeListener(this);
        check_vnc.setOnCheckedChangeListener(this);
        check_graphical.setOnCheckedChangeListener(this);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch(buttonView.getId()) {
            case R.id.launch_ssh_checkbox:
                Toast.makeText(this, "ssh", Toast.LENGTH_LONG).show();
                if(!isChecked && !check_vnc.isChecked())
                    check_vnc.setChecked(true);
                break;
            case R.id.launch_term_checkbox:

                break;
            case R.id.launch_vnc_checkbox:
                Toast.makeText(this, "vnc", Toast.LENGTH_LONG).show();
                if(!isChecked && !check_ssh.isChecked())
                    check_ssh.setChecked(true);
                break;
            case R.id.launch_graphical_checkbox:

                break;
        }
    }

    /*
    private void sshBox(boolean isChecked, boolean vncChecked) {
        if(isChecked) {
            // TODO shared preferences blabla
            Toast.makeText(this, "ssh checked", Toast.LENGTH_LONG).show();
        }
        else {
            if(!vncChecked) {
                vncBox(true, false);
                // TODO shared preferences blabla
                Toast.makeText(this, "ssh unchecked, vnc to be checked", Toast.LENGTH_LONG).show();
            }
            else {
                // TODO shared preferences blabla
                Toast.makeText(this, "ssh unchecked", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void termBox(boolean isChecked) {
        if(isChecked)
            Toast.makeText(this, "term checked", Toast.LENGTH_LONG).show();
        else
            Toast.makeText(this, "term unchecked", Toast.LENGTH_LONG).show();
    }

    private void vncBox(boolean isChecked, boolean sshChecked) {
        if(isChecked) {
            // TODO shared preferences blabla
            Toast.makeText(this, "vnc checked", Toast.LENGTH_LONG).show();
        }
        else {
            if(!sshChecked) {
                vncBox(true, false);
                // TODO shared preferences blabla
                Toast.makeText(this, "vnc unchecked, ssh to be checked", Toast.LENGTH_LONG).show();
            }
            else {
                // TODO shared preferences blabla
                Toast.makeText(this, "vnc unchecked", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void graphBox(boolean isChecked) {
        if(isChecked)
            Toast.makeText(this, "graph checked", Toast.LENGTH_LONG).show();
        else
            Toast.makeText(this, "graph unchecked", Toast.LENGTH_LONG).show();
    }
    */
}
