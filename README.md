# app-updater
Auto app updater tool for testing interim builds.

If you need to frequently download, deploy & test your intermediate builds in multiple devices and you don't want to manually download and shoot emails to let testers know of a newly available build then perhaps this companion tool will help you.

Compile this app with the correct package name of the apk you want to check for new versions every X minutes (customizable) and let it automatically download, deploy/upgrade and launch it.

All you have to do it is to provide an API endpoint that responds with a JSON that contains the package name, version and apk file name:

For example:
```json
{
"package":"com.mobile.app", 
"version":"1.2.100", 
"apk":"apkname.apk"
}
```

You would like to have the JSON created automatically during builds. So add logic to your build scripts to update the JSON file automatically upon every build runs.
