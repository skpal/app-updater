package com.sandy.beta.testing;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.sandy.beta.testing.service.PackageUpgradeService;


public class MainActivity extends Activity {

	private View btnDownload;
	private ProgressBar progressBar;
	private TextView tvProgress, tvPackageName, tvVersionCurrent, tvVersionNew;
	
	private String versionName = "";
	private String packageName = "com.mobile.app"; // change this to the correct package name
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		btnDownload = findViewById(R.id.btnDownload);
		btnDownload.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(MainActivity.this, PackageUpgradeService.class);
				intent.setAction(PackageUpgradeService.ACTION.SCHEDULE_DOWNLOAD);
				intent.putExtra(PackageUpgradeService.PARAMS.INTERVAL, 5);
				startService(intent);
			}
		});
		
		progressBar = (ProgressBar) findViewById(R.id.progress1);
		tvProgress = (TextView) findViewById(R.id.tvProgress);
		tvPackageName = (TextView) findViewById(R.id.tvPackageName);
		tvVersionCurrent = (TextView) findViewById(R.id.tvVersionCurrent);
		tvVersionNew = (TextView) findViewById(R.id.tvVersionNew);
		if(tvPackageName != null){
			tvPackageName.setText(packageName);
		}

		registerDownloadProgressReceiver();
	}
	
	BroadcastReceiver progressReceiver = new BroadcastReceiver() {
		
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if(PackageUpgradeService.ACTION.DOWNLOAD_PROGRESS.equals(action)){
				int progress = intent.getExtras().getInt(PackageUpgradeService.PARAMS.PROGRESS_VALUE, 0);
				if (progressBar != null) {
					progressBar.setProgress(progress);
				}
				if(tvProgress != null){
					tvProgress.setText(String.format("%d/%d", progress, 100));
				}
				btnDownload.setEnabled(false);
			} else if(PackageUpgradeService.ACTION.PACKAGE_UPGRADE.equals(action)){
				String newVersion = intent.getExtras().getString(PackageUpgradeService.PARAMS.NEW_VERSION);
				if(tvVersionNew != null){
					tvVersionNew.setText("New Version:" + newVersion);
				}
			}
		}
	};

	private void registerDownloadProgressReceiver(){
		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(PackageUpgradeService.ACTION.DOWNLOAD_PROGRESS);
		intentFilter.addAction(PackageUpgradeService.ACTION.PACKAGE_UPGRADE);
		LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, intentFilter);
	}

	private void unregisterDownloadProgressReceiver(){
		LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
	}

	protected void onResume() {
		super.onResume();
		if(tvVersionCurrent != null){
			tvVersionCurrent.setText("Current Version:");
			gatherAppInfo(packageName);
			tvVersionCurrent.setText("Current Version:" + versionName);
		}
	};
	
	@Override
	protected void onDestroy() {
		unregisterDownloadProgressReceiver();
		super.onDestroy();
	}
	
    private void gatherAppInfo(String packageName) {
	    try {
	        PackageInfo pInfo = getPackageManager().getPackageInfo(packageName, PackageManager.GET_META_DATA);
	        versionName = pInfo.versionName;
	        packageName = pInfo.packageName;
	    } catch (NameNotFoundException e1) {
	        Log.e(this.getClass().getSimpleName(), "Package not found");
	    }
	}
}
