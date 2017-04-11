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
        SharedPreferences prefs = getSharedPreferences("MAIN", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        switch(buttonView.getId()) {
            case R.id.launch_ssh_checkbox:
                Toast.makeText(this, "ssh", Toast.LENGTH_LONG).show();
                if(!isChecked && !check_vnc.isChecked())
                    check_vnc.setChecked(true);
                editor.putBoolean("sshLaunch", isChecked);
                break;
            case R.id.launch_term_checkbox:
                editor.putBoolean("termLaunch", isChecked);
                break;
            case R.id.launch_vnc_checkbox:
                Toast.makeText(this, "vnc", Toast.LENGTH_LONG).show();
                if(!isChecked && !check_ssh.isChecked())
                    check_ssh.setChecked(true);
                editor.putBoolean("vncLaunch", isChecked);
                break;
            case R.id.launch_graphical_checkbox:
                editor.putBoolean("graphicalLaunch", isChecked);
                break;
        }
        editor.commit();
    }
}
