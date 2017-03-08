package com.gnuroot.debian;

import android.app.IntentService;
import android.content.Intent;

public class GNURootOldService extends IntentService {
    boolean shown = false;
    public GNURootOldService() {
        super("GNURootOldService");
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
            errorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(errorIntent);
        }
    }
}
