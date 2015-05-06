package com.sandy.beta.testing.receiver;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

import com.sandy.beta.testing.service.PackageUpgradeService;

public class BootServiceReceiver extends BroadcastReceiver {

	DownloadManager downloadManager;

	public BootServiceReceiver() {
	}
	
	@Override
	public void onReceive(Context context, Intent intent) {
		downloadManager = (DownloadManager) context.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
		
		String action = intent.getAction();

		SharedPreferences sp = context.getSharedPreferences(PackageUpgradeService.SHARED_PREF.BUNDLE_KEY, Activity.MODE_PRIVATE);
		
		if (android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {

			long downloadReference = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, 0);
			long savedDownloadReference = sp.getLong(PackageUpgradeService.SHARED_PREF.KEY_DOWNLOAD_REFERENCE, 0);
			String filePath = sp.getString(PackageUpgradeService.SHARED_PREF.KEY_DOWNLOAD_FOLDER_PATH, "");
			
			if(downloadReference == savedDownloadReference){
				// query download manager for successful download
				Query query = new Query();
                query.setFilterById(downloadReference);
                Cursor c = downloadManager.query(query);
                if (c.moveToFirst()) {
                    int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(columnIndex)) {
                    	// download successful
                    	// try upgrading the package
        				upgradePackage(context, filePath);
                    }
                }
                c.close();
			}
		} else if (Intent.ACTION_PACKAGE_ADDED.equals(action)
				|| Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
			String packageName = sp.getString(PackageUpgradeService.SHARED_PREF.KEY_PACKAGE_NAME, "");
			Uri data = intent.getData();
			if(data != null){
				// extract package name from Uri
				String packageInfo = data.toString().replaceAll("package:", "");
				Toast.makeText(context, "packageInfo:" + packageInfo, Toast.LENGTH_LONG).show();

				if(packageName.equals(packageInfo)){
					requestAcknowledgement(context);
					
					// try getting the launch intent from package manager
					Intent mIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
					if (mIntent != null) {
						mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						context.startActivity(mIntent);
						acknowledgeAppLaunchedService(context);
					} else {
						// couldn't find the launch intent
						// try implicit intent
						mIntent = new Intent();
						mIntent.setPackage(packageName);
						mIntent.addCategory(Intent.CATEGORY_LAUNCHER);
						mIntent.addCategory(Intent.CATEGORY_DEFAULT);
						mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						try{
							context.startActivity(mIntent);
						}catch(Exception ex){
							
						}
						
						// acknowledge install complete to API server
						acknowledgeAppLaunchedService(context);
					}
				}
			}
		}
	}

	private void startFileDownloadService(Context context){
		Intent intent = new Intent(context, PackageUpgradeService.class);
		intent.setAction(PackageUpgradeService.ACTION.SCHEDULE_DOWNLOAD);
		intent.putExtra(PackageUpgradeService.PARAMS.INTERVAL, 1);
		context.startService(intent);
	}

	// upgrade the app using service
	private void upgradePackage(Context context, String filePath) {
		Intent intent = new Intent(context, PackageUpgradeService.class);
		intent.setAction(PackageUpgradeService.ACTION.PACKAGE_UPGRADE);
		intent.putExtra(PackageUpgradeService.PARAMS.FILE_PATH, filePath);
		context.startService(intent);
	}
	
	// notify service of app launch
	private void acknowledgeAppLaunchedService(Context context){
		Intent intent = new Intent(context, PackageUpgradeService.class);
		intent.setAction(PackageUpgradeService.ACTION.APPLICATION_LAUNCHED);
		context.startService(intent);
	}


	// send install completed acknowledge to server
	private void requestAcknowledgement(Context context) {
		Intent intent = new Intent(context, PackageUpgradeService.class);
		intent.setAction(PackageUpgradeService.ACTION.SEND_ACKNOWLEDGEMENT);
		context.startService(intent);
	}
	
}