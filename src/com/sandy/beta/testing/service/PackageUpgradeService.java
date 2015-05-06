package com.sandy.beta.testing.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.sandy.beta.testing.util.Utils;

public class PackageUpgradeService extends Service {

	public static final String BASE_URL = "http://www.mobile.api/apk/";
	public static final String META_URL = BASE_URL + "meta.json";
	public static final String ACK_URL = BASE_URL + "ack.php";
	public static final int INTERVAL = 5;// in minutes

	public static class SHARED_PREF{
		public static final String BUNDLE_KEY = "shared.pref";
		public static final String KEY_DOWNLOAD_REFERENCE = "download.reference";
		public static final String KEY_DOWNLOAD_FOLDER_PATH = "download.folder.path";
		public static final String KEY_PACKAGE_NAME = "package.name";
		public static final String KEY_APK_NAME = "apk.name";
	}
	
	private static final String PACKAGE_PREFIX = "com.mobile.app"; // change this to the correct package name

	private DownloadManager downloadManager;
	private long mDownloadReference;
	private WeakReference<Context> mContext ;

	private MetaDownloadAsyncTask metadataDownloadAsyncTask;

	private ExecutorService parallelExecutor;
	private ScheduledExecutorService scheduledThreadExecutor;
	private ScheduledFuture<?> schedulerHandle, schedulerAcknowledgementHandle, schedulerProgressHandle;
	

	public static class ACTION{
		public static final String SCHEDULE_DOWNLOAD = PACKAGE_PREFIX + ".SCHEDULE_DOWNLOAD";
		public static final String PACKAGE_UPGRADE = PACKAGE_PREFIX + ".PACKAGE_UPGRADE";
		public static final String DOWNLOAD_PROGRESS = PACKAGE_PREFIX + ".DOWNLOAD_PROGRESS";
		public static final String SEND_ACKNOWLEDGEMENT = PACKAGE_PREFIX + ".SEND_ACKNOWLEDGEMENT";
		public static final String APPLICATION_LAUNCHED = PACKAGE_PREFIX + ".APP_LAUNCHED";
	}

	public static class PARAMS{
		public static final String INTERVAL = "interval";
		public static final String PROGRESS_VALUE = "progress.value";
		public static final String FILE_PATH = "file.path";
		public static final String PACKAGE = "package";
		public static final String NEW_VERSION = "newVersion";
	}

	public PackageUpgradeService() {
	}
	
	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		scheduledThreadExecutor = Executors.newScheduledThreadPool(4);
		parallelExecutor = Executors.newFixedThreadPool(4);
		mContext = new WeakReference<Context>(this);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if (ACTION.SCHEDULE_DOWNLOAD.equals(action)) {
				if (schedulerHandle == null) {
					int interval = intent.getIntExtra(PARAMS.INTERVAL, INTERVAL);
					if (!Utils.isNetworkAvailable(this)) {
						interval = 1; // one minute
					}
					scheduleVersionCheck(interval);
				}
			} else if(ACTION.PACKAGE_UPGRADE.equals(action)){
				String packageName = intent.getStringExtra("package");
				SharedPreferences sp = getApplicationContext().getSharedPreferences(
						PackageUpgradeService.SHARED_PREF.BUNDLE_KEY, 
						Activity.MODE_PRIVATE);
				String filePath = sp.getString(SHARED_PREF.KEY_DOWNLOAD_FOLDER_PATH, "");
				String fileName = sp.getString(SHARED_PREF.KEY_APK_NAME, "");
				if (!"".equals(filePath)) {
					upgradePackage(getApplicationContext(), new File(filePath, fileName));
				}
			} else if(ACTION.SEND_ACKNOWLEDGEMENT.equals(action)){
				scheduleAcknowledgement(INTERVAL);
			} else if (ACTION.APPLICATION_LAUNCHED.equals(action)) {
				saveDownloadReference(SHARED_PREF.KEY_DOWNLOAD_REFERENCE, 0);
				saveDownloadReference(SHARED_PREF.KEY_PACKAGE_NAME, "");
				saveDownloadReference(SHARED_PREF.KEY_APK_NAME, "");
				saveDownloadReference(SHARED_PREF.KEY_DOWNLOAD_FOLDER_PATH, "");
			}
		}
		return START_NOT_STICKY;
	}
	
	@Override
	public void onDestroy() {
		if (schedulerHandle != null) {
			schedulerHandle.cancel(true);
		}
		if (schedulerProgressHandle != null) {
			schedulerProgressHandle.cancel(true);
		}
		if (schedulerAcknowledgementHandle != null) {
			schedulerAcknowledgementHandle.cancel(true);
		}
		if (metadataDownloadAsyncTask != null) {
			metadataDownloadAsyncTask.cancel(true);
		}
		metadataDownloadAsyncTask = null;
		scheduledThreadExecutor.shutdownNow();
		parallelExecutor.shutdownNow();
		if (downloadObserver != null) {
			getContentResolver().unregisterContentObserver(downloadObserver);
		}
		scheduledThreadExecutor = null;
		parallelExecutor = null;
		super.onDestroy();
	}

	private void scheduleVersionCheck(int interval) {
		if (schedulerHandle != null) {
			schedulerHandle.cancel(true);
			schedulerHandle = null;
		}
		schedulerHandle = scheduledThreadExecutor.scheduleAtFixedRate(
				checkAndDownloadAPK,
				0, 
				interval != 0 ? interval : INTERVAL, 
				TimeUnit.MINUTES);
	}

	Runnable checkAndDownloadAPK = new Runnable() {
		@Override
		public void run() {
			if (!Utils.isNetworkAvailable(mContext.get())) {
				return;
			}
			//scheduleVersionCheck(INTERVAL);
			saveDownloadReference(SHARED_PREF.KEY_DOWNLOAD_REFERENCE, 0);
			try {
				URL url = new URL(META_URL);
				if (metadataDownloadAsyncTask == null || metadataDownloadAsyncTask.isCancelled()) {
					metadataDownloadAsyncTask = new MetaDownloadAsyncTask(PackageUpgradeService.this, url);
					metadataDownloadAsyncTask.executeOnExecutor(parallelExecutor, (Void) null);
				}
			} catch (MalformedURLException e) {
				e.printStackTrace();
			}
		}
	};
	

	// json structure
	// {"package":"com.mobile.app", "version":"1.2.100", "apk":"apkname.apk"}
	
	class MetaDownloadAsyncTask extends AsyncTask<Void, Void, String>{
		URL url;
		int readTimeout = 5000; // in milliseconds
		int connectionTimeout = 10000; // in milliseconds
		int flags = DownloadManager.Request.NETWORK_WIFI;// | DownloadManager.Request.NETWORK_MOBILE;
		WeakReference<Context> context ;
		
		public MetaDownloadAsyncTask(Context context, URL url){
			this.context = new WeakReference<Context>(context);
			this.url = url;
		}

		@Override
		protected String doInBackground(Void... params) {
			return fetchMetaData(url, readTimeout, connectionTimeout);
		}
		
		@Override
		protected void onPostExecute(String result) {
			if (isCancelled()) {
				return;
			}
			try {
				Log.d("meta", "meta:" + result);
				JSONObject metaJson = new JSONObject(result);
				String packageName = metaJson.getString("package");
				String version = metaJson.getString("version");
				String apkName = metaJson.getString("apk");

				saveDownloadReference(SHARED_PREF.KEY_PACKAGE_NAME, packageName);
				saveDownloadReference(SHARED_PREF.KEY_APK_NAME, apkName);
				
				Intent nIntent = new Intent(ACTION.PACKAGE_UPGRADE);
				nIntent.putExtra(PARAMS.NEW_VERSION, version);
				LocalBroadcastManager.getInstance(context.get()).sendBroadcast(nIntent);
				
				String currentVersion = getVersion(packageName);
				if(currentVersion.compareTo(version) < 0){
					String uriString = BASE_URL + apkName;
					Uri downloadUri = Uri.parse(uriString);
					File downloadsFolder;
					
					downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
					File mfile = new File(downloadsFolder, apkName);
					String downloadFolderPath = downloadsFolder.getPath();

					Uri destUri = Uri.fromFile(mfile);

					mDownloadReference = enqueDownload(downloadUri, flags, destUri );

					saveDownloadReference(SHARED_PREF.KEY_DOWNLOAD_REFERENCE, mDownloadReference);
					saveDownloadReference(SHARED_PREF.KEY_DOWNLOAD_FOLDER_PATH, downloadFolderPath);

					if (context.get() != null) {
						// Content observer seems not to be working
						startObservingDownload(context.get(), mDownloadReference);
					}
					// alternate way to check progress
					scheduleDownloadProgressCheck();
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
			metadataDownloadAsyncTask = null;
		}
	}

	@SuppressLint("NewApi")
	private long enqueDownload(Uri uri, int flags, Uri destUri){
		downloadManager = (DownloadManager) getSystemService(android.content.Context.DOWNLOAD_SERVICE);
		DownloadManager.Request request = new DownloadManager.Request(uri);

		request.setAllowedNetworkTypes(flags);
		request.setAllowedOverRoaming(false);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			request.setAllowedOverMetered(false);
			request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		}
		request.setDestinationUri(destUri);
		 
		return downloadManager.enqueue(request);
	}
	
	private void saveDownloadReference(String key, long value){
		SharedPreferences sp = getApplicationContext().getSharedPreferences(SHARED_PREF.BUNDLE_KEY, Activity.MODE_PRIVATE);
		SharedPreferences.Editor ed = sp.edit();
		ed.putLong(key, value);
		ed.commit();
	}

	private void saveDownloadReference(String key, String value){
		SharedPreferences sp = getApplicationContext().getSharedPreferences(SHARED_PREF.BUNDLE_KEY, Activity.MODE_PRIVATE);
		SharedPreferences.Editor ed = sp.edit();
		ed.putString(key, value);
		ed.commit();
	}

	private String getVersion(String packageName) {
	    String version = "";
	    try {
	        PackageInfo pInfo = getPackageManager().getPackageInfo(packageName, PackageManager.GET_META_DATA);
	        version = pInfo.versionName;
	    } catch (NameNotFoundException e1) {
	        Log.e(this.getClass().getSimpleName(), "Name not found");
	    }
	    return version;
	}
	
	private String fetchMetaData(URL url, int readTimeout, int connectionTimeout) {
		String result = "";
		try {
			HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setReadTimeout(readTimeout);
			con.setDefaultUseCaches(false);
			con.setConnectTimeout(connectionTimeout);
			result = readStream(con.getInputStream());
		} catch (IOException e) {
			e.printStackTrace();
		}
		return result;
	}
	
	
	private String readStream(InputStream in) {
		BufferedReader reader = null;
		StringBuilder sb = new StringBuilder();
		try {
			reader = new BufferedReader(new InputStreamReader(in));
			String line = "";
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
		} catch (IOException e) {
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
				}
			}
		}
		
		return sb.toString();
	}
		
	private void upgradePackage(Context context, File apkLocation) {
		final Intent mIntent = new Intent(Intent.ACTION_VIEW);
		mIntent.setDataAndType(Uri.fromFile(apkLocation),
				"application/vnd.android.package-archive");
		mIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(mIntent);
	}

	private void scheduleAcknowledgement(int interval) {
		if (schedulerAcknowledgementHandle != null) {
			schedulerAcknowledgementHandle.cancel(true);
			schedulerAcknowledgementHandle = null;
		}
		schedulerAcknowledgementHandle = scheduledThreadExecutor.scheduleAtFixedRate(
				acknowledgeInstallCompleteRunnable,
				0, 
				interval, 
				TimeUnit.MINUTES);
	}

	Runnable acknowledgeInstallCompleteRunnable = new Runnable() {
		
		@Override
		public void run() {
			if (!Utils.isNetworkAvailable(mContext.get())) {
				return;
			}
			
			URL url;
			try {
				url = new URL(ACK_URL + "?androidID=" + Settings.Secure.ANDROID_ID);
				if(sendRequest(url, 1000, 2000) == 200){
					schedulerAcknowledgementHandle.cancel(true);
					schedulerAcknowledgementHandle = null;
				}
			} catch (MalformedURLException e) {
			}
		}
	};

	private int sendRequest(URL url, int readTimeout, int connectionTimeout) {
		int responseCode = 0;
		try {
			HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setReadTimeout(readTimeout);
			con.setDefaultUseCaches(false);
			con.setConnectTimeout(connectionTimeout);
			responseCode = con.getResponseCode();
			con.disconnect();
		} catch (IOException e) {
		}
		return responseCode;
	}
	
	
	private void startObservingDownload(Context context, long downloadReference){
		SharedPreferences sp = context.getSharedPreferences(PackageUpgradeService.SHARED_PREF.BUNDLE_KEY, Activity.MODE_PRIVATE);
		String filePath = sp.getString(PackageUpgradeService.SHARED_PREF.KEY_DOWNLOAD_FOLDER_PATH, "");
		String fileName = sp.getString(PackageUpgradeService.SHARED_PREF.KEY_APK_NAME, "");

		if (!TextUtils.isEmpty(filePath)) {
			File downloadFile = new File(filePath, fileName);
			Uri downloadFolderUri = Uri.fromFile(downloadFile);
			// downloadFolderUri = FileProvider.getUriForFile(context, "com.sandy.beta.testing.fileprovider", downloadFile);
			//context.grantUriPermission(packageName, downloadFolderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
			downloadObserver = new DownloadObserver(new Handler());
			context.getApplicationContext().getContentResolver().registerContentObserver(downloadFolderUri, true, downloadObserver);
		}
	}
	
	DownloadObserver downloadObserver;

	public class DownloadObserver extends ContentObserver {

		WeakReference<Context> context;
		
		public DownloadObserver(Handler handler) {
			super(handler);
		}

		public void setContext(Context context){
			this.context = new WeakReference<Context>(context);
		}

		@Override
		public void onChange(boolean selfChange) {
			this.onChange(selfChange, null);
		}

		@Override
		public void onChange(boolean selfChange, Uri uri) {
			Log.d( "DownloadObserver", "Download " + uri + " updated");
			if(context.get() != null){
				updateDownloadProgress(context.get());
			}
		}
	}
	
	private void scheduleDownloadProgressCheck() {
		if (schedulerProgressHandle != null) {
			schedulerProgressHandle.cancel(true);
			schedulerProgressHandle = null;
		}
		schedulerProgressHandle = scheduledThreadExecutor.scheduleWithFixedDelay(
				updateDownloadProgressRunnable,
				0, 
				1, 
				TimeUnit.SECONDS);
	}
	
	Runnable updateDownloadProgressRunnable = new Runnable() {
		@Override
		public void run() {
			updateDownloadProgress(PackageUpgradeService.this);
		}
	};
	
	private void updateDownloadProgress(Context context){
		
		SharedPreferences sp = context.getSharedPreferences(SHARED_PREF.BUNDLE_KEY, Activity.MODE_PRIVATE);
		long downloadId = sp.getLong(SHARED_PREF.KEY_DOWNLOAD_REFERENCE, 0);

		Query query = new Query();
		query.setFilterById(downloadId);
		Cursor c = downloadManager.query(query);
		if (c.moveToFirst()) {
			int sizeIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
			int downloadedIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
			long size = c.getInt(sizeIndex);
			long downloaded = c.getInt(downloadedIndex);
			double progress = 0.0;
			if (size != -1) {
				progress = downloaded * 100.0 / size;
				Intent intent = new Intent(ACTION.DOWNLOAD_PROGRESS);
				intent.putExtra(PARAMS.PROGRESS_VALUE, (int) progress);
				LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
				if(progress >=100.0){
					context.getContentResolver().unregisterContentObserver(downloadObserver);
					if (schedulerProgressHandle != null) {
						schedulerProgressHandle.cancel(true);
						schedulerProgressHandle = null;
					}
				}
			}
		}
		c.close();
	}
}
