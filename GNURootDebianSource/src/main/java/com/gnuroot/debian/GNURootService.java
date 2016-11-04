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

import android.app.IntentService;
import android.content.Intent;

public class GNURootService extends IntentService {
	boolean shown = false;
	public GNURootService() {
		super("GNURootService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {

		if(intent.getAction() == "com.gnuroot.debian.CHECK_STATUS") {
			Intent resultIntent = new Intent("com.gnuroot.debian.GNURootService.status");
			resultIntent.putExtra("packageName", intent.getStringExtra("packageName"));
			resultIntent.putExtra("requestCode", 5);    //Indicate that a CHECK_STATUS has PASSed
			resultIntent.putExtra("resultCode", 1);
			sendBroadcast(resultIntent);
		}

		if(!shown) {
			shown = true;
			Intent errorIntent = new Intent("com.gnuroot.debian.UPDATE_ERROR");
			errorIntent.addCategory(Intent.CATEGORY_DEFAULT);
			errorIntent.putExtra("packageName", intent.getStringExtra("packageName"));
			startActivity(errorIntent);
		}
    }
}