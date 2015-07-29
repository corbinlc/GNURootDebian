/*
Copyright (c) 2014 Corbin Leigh Champion

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

import com.gnuroot.library.GNURootCoreActivity;
import com.gnuroot.debian.R;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

public class GNURootInstallFragment extends Fragment {

	ListView listView;
	ArrayAdapter<String> adapter;
	Button launchButton;
	Button patchButton;
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		
		View fragmentView = inflater.inflate(R.layout.fragment_install, container, false);
		
		if (getActivity() != null) {
	        launchButton = (Button) fragmentView.findViewById(R.id.install_button);
	        launchButton.setOnClickListener(new OnClickListener()
	        {
	            @Override
	            public void onClick(View view)
	            {
	            	new AlertDialog.Builder((GNURootMain)getActivity())
	                .setIcon(android.R.drawable.ic_dialog_alert)
	                .setTitle("Confirm Install")
	                .setMessage("This will delete any previous installation. Are you sure you want to do this?")
	                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {

	                    @Override
	                    public void onClick(DialogInterface dialog, int which) {
	                    	((GNURootMain)getActivity()).installRootfs();    
	                    }

	                })
	                .setNegativeButton("No", null)
	                .show();
	            }
	        });
	        patchButton = (Button) fragmentView.findViewById(R.id.patch_button);
	        patchButton.setOnClickListener(new OnClickListener()
	        {
	            @Override
	            public void onClick(View view)
	            {
	            	((GNURootMain)getActivity()).installPatches();    
	            }
	        });
		}
	
		return fragmentView;
	}
	
}
