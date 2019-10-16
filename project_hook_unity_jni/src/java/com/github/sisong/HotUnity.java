package com.github.sisong;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.util.Log;
import java.io.File;

/*
 customize edit for Unity export android project:
 1. add this file HotUnity.java to project;
 2. add libhotunity.so to project jniLibs;
 3. edit file UnityPlayerActivity.java in project;
 add code: `import com.github.sisong.HotUnity;`
 add code: `HotUnity.hotUnity(this);` before `mUnityPlayer = new UnityPlayer(this);`
 */

public class HotUnity{
    private static native void doHot(String baseApk,String baseSoDir,String hotApk,String hotSoDir);
    private static native int  virtualApkPatch(String baseApk,String baseSoDir,
                                               String hotApk,String hotSoDir,
                                               String out_newApk,String out_newSoDir,//if need install(out_newApk), out_newSoDir set ""
                                               String zipDiffPath,int threadNum);
    private static native int  virtualApkMerge(String baseApk,String baseSoDir,
                                               String hotApk,String hotSoDir,
                                               String newApk,String newSoDir);
    
    private static final String kHotUnityLib ="hotunity";
    private static final String kLogTag ="HotUnity";
    private static Context app=null;
    private static String baseApk=null;
    private static String baseSoDir=null;
    private static String hotApk=null;
    private static String hotSoDir=null;
    private static String newApk=null;
    private static String newSoDir=null;
    public static void hotUnity(Context _app){
        app=_app;
        baseApk=app.getPackageResourcePath();
        baseSoDir=app.getApplicationInfo().nativeLibraryDir;
        String updateDir=app.getFilesDir().getPath() + "/HotUpdate";
        if (!makeDir(updateDir)) { runByBaseApk(); return; }
        hotApk=updateDir+"/update.apk";
        hotSoDir=hotApk+"_lib";
        newApk=updateDir+"/new_update.apk";//ApkPatch temp out
        newSoDir=newApk+"_lib";
        //for DEBUG test
        testHotUpdate(app, baseApk,baseSoDir,hotApk,hotSoDir,newApk,newSoDir);
        //merge new to hot for patch result
        if (!mergeNewUpdate(baseApk,baseSoDir,hotApk,hotSoDir,newApk,newSoDir)){
            revertToBaseApk();
        }
        
        if (pathIsExists(hotApk)&&pathIsExists(hotSoDir)){ //run by hotApk
            mapPathLoadLib(hotSoDir,"main");
            mapPathLoadLib(hotSoDir,"unity");
            mapPathLoadLib(hotSoDir,kHotUnityLib);
            //note: You can load other your lib(not unity's) by mapPathLoadLib, can use newVersion lib;
            
            doHot(baseApk,baseSoDir,hotApk,hotSoDir);
        }else{
            runByBaseApk();
        }
    }
    private  static void  runByBaseApk(){
        System.loadLibrary("main");
        System.loadLibrary(kHotUnityLib);
    }
    
    //public funcs for call by C#
    
    public static int apkPatch(String zipDiffPath,int threadNum,String installApkPath){
        //kHotUnityLib is loaded, not need: mapPathLoadLib(hotSoDir,kHotUnityLib);
        boolean isHotUpdate=(installApkPath==null)||(installApkPath.isEmpty());
        int  ret=virtualApkPatch(baseApk,baseSoDir,hotApk,hotSoDir,
                                 isHotUpdate?newApk:installApkPath,isHotUpdate?newSoDir:"",zipDiffPath,threadNum);
        Log.w(kLogTag, "virtualApkPatch() result " +String.valueOf(ret));
        return ret;
    }
    public static void restartApp() {
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(app.getApplicationContext(),
                                                                0, intent, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager mgr = (AlarmManager)app.getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC,System.currentTimeMillis() + 200, contentIntent);
        System.exit(0);
    }
    public static void exitApp(int errCode) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_HOME);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(mainIntent);
        System.exit(errCode);
    }
    public static void revertToBaseApk(){
        removeFile(newApk);
        removeFile(hotApk);
        restartApp();
    }
    
    private static void testHotUpdate(Context app,
                                      String baseApk,String baseSoDir,
                                      String hotApk,String hotSoDir,
                                      String newApk,String newSoDir){
        if (pathIsExists(newApk)||(pathIsExists(newSoDir))) return;
        String testDir=app.getExternalFilesDir("").getAbsolutePath()+"/testHotUpdate";
        //default: testDir=="/sdcard/Android/data/<your-app-id>/files/testHotUpdate";
        //NOTE: put the files you need test into the testDir directory
        String testPatFile=testDir+"/new.pat"; //test pat file
        if (!pathIsExists(testPatFile)) return;
        Log.w(kLogTag, "testHotUpdate() with "+testPatFile);
        mapPathLoadLib(hotSoDir,kHotUnityLib); //for native function: virtualApkPatch()
        
        String testBase=testDir+"/base.apk";
        if (pathIsExists(testBase)) {
            baseApk  = testBase;
            baseSoDir= testBase + "_lib";
        }
        String testHot=testDir+"/update.apk";
        if (pathIsExists(testBase)||pathIsExists(testHot)) {
            hotApk  = testHot;
            hotSoDir= testHot + "_lib";
        }
        int  ret=virtualApkPatch(baseApk,baseSoDir,hotApk,hotSoDir,
                                 newApk,newSoDir,testPatFile,3);
        Log.w(kLogTag, "virtualApkPatch() result " +String.valueOf(ret));
        if ((ret==0) && removeFile(testPatFile)){ //update ok
            Log.w(kLogTag, "testHotUpdate() ok, restartApp");
            restartApp();
        }else{
            Log.w(kLogTag, "testHotUpdate() ERROR, exitApp");
            exitApp(ret);
        }
    }
    
    // merge new to hot
    private static  boolean mergeNewUpdate(String baseApk,String baseSoDir,
                                           String hotApk,String hotSoDir,
                                           String newApk,String newSoDir){
        if (!pathIsExists(hotApk)) {
            if (pathIsExists(hotSoDir))
                removeLibDirWithLibs(hotSoDir);
        }
        if (!pathIsExists(newApk)) {
            if (pathIsExists(newSoDir))
                removeLibDirWithLibs(newSoDir);
            return true; //not need merge, continue run app
        }
        
        if (!mergeHotUnityLib(newSoDir,hotSoDir)){
            Log.w(kLogTag,"mergeHotUnityLib() error! "+newSoDir);
            return false;
        }
        mapPathLoadLib(hotSoDir,kHotUnityLib); //for native function: virtualApkMerge()
        int rt=virtualApkMerge(baseApk,baseSoDir,hotApk,hotSoDir,newApk,newSoDir);
        if (rt!=0){
            Log.w(kLogTag,"virtualApkMerge() error code "+String.valueOf(rt)+"! "+newApk);
            return false;
        }
        return true;
    }
    
    private static boolean mergeHotUnityLib(String newSoDir,String hotSoDir){
        String newLibHotUnity=getLibPath(newSoDir,kHotUnityLib);
        if  (!pathIsExists(newLibHotUnity)) return true;
        if (!makeDir(hotSoDir)) return false;
        String hotLibHotUnity=getLibPath(hotSoDir,kHotUnityLib);
        if (!removeFile(hotLibHotUnity)) return false;
        return moveFileTo(newLibHotUnity,hotLibHotUnity);
    }
    
    private static void removeLibDirWithLibs(String libDir) {
        File dir=new File(libDir);
        String[] files=dir.list();
        for (int i=0;i<files.length;++i) {
            String fileName=files[i];
            if ((fileName=="."||(fileName==".."))) continue;
            removeFile(fileName);
        }
        dir.delete();
    }
    
    private static boolean moveFileTo(String oldFilePath,String newFilePath) {
        File df=new File(oldFilePath);
        File newdf=new File(newFilePath);
        return df.renameTo(newdf);
    }
    private static boolean removeFile(String fileName) {
        File df=new File(fileName);
        if (!df.exists()) return true;
        return df.delete();
    }
    
    private static boolean makeDir(String dirPath) {
        File df=new File(dirPath);
        if (df.exists()) return true;
        if (!df.mkdir()) return false;
        return true;
    }
    
    private static void mapPathLoadLib(String hotSoDir, String libName){
        String cachedLibPath=getLibPath(hotSoDir,libName);
        if (pathIsExists(cachedLibPath)) {
            Log.w(kLogTag,"java map_path() to "+cachedLibPath);
            System.load(cachedLibPath);
        } else {
            Log.w(kLogTag,"java map_path() not found "+cachedLibPath);
            System.loadLibrary(libName);
        }
    }
    private static String getLibPath(String dir,String libName){
        return dir+"/"+System.mapLibraryName(libName);
    }
    private static boolean pathIsExists(String path) {
        File file = new File(path);
        return file.exists();
    }
}
