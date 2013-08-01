package com.example.netscandemo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;

import android.content.Context;
import android.os.Environment;
import android.provider.Settings.Secure;
import android.util.Log;

public class FileHelper {
	
	private Context _context;
	private String root;
	//private String uuid;
	
	public FileHelper(Context context){
		this._context = context;
		this.root = "";
		//this.uuid = "";
	}
	
	public String getDir(){
		//uuid = Secure.getString(_context.getContentResolver(),Secure.ANDROID_ID);
		root = Environment.getExternalStorageDirectory().getPath() + "/ARPScan";
		File f = new File(root);
		if(!f.exists()){
			buildDir();
		}
		
		return root;
	}
	
	public boolean buildDir(){
		
    	File dir = new File(root);
    	boolean success = dir.mkdir();
		return success;
	}
	
	
	
}
