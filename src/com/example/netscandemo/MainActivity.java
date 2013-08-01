package com.example.netscandemo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.conn.util.InetAddressUtils;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings.Secure;
import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.Menu;
import android.widget.Toast;

public class MainActivity extends Activity {

	private final String TAG = "netdemo";
	private final static int[] DPORTS = { 139, 445, 22, 80 };
	private final static int TIMEOUT_SCAN = 10; // seconds
	private final static int TIMEOUT_SHUTDOWN = 2; // seconds
	private final static int THREADS = 50; //FIXME: Test, plz set in options again ?
	private final static int SOCKET_TIMEOUT = 500; // socket timeout in ms
	
	private long network_start = 0;
	private long network_end = 0;
	private long network_ip = 0;
	private ExecutorService mPool;
	private Map<String,String> hostmap;
	private Map<String,Long> timemap;
	private long metaTS = 0;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		if(isNetworkOn()){
			Log.i("netdemo", NetInfo.getIPAddress());
			Log.i("netdemo", NetInfo.getMACAddress("wlan0"));
			Log.i("netdemo", NetInfo.getBroadcastAddress());
			Log.i("netdemo", String.valueOf(NetInfo.getCidr()));
			
			String ip_addr = NetInfo.getIPAddress();
			network_ip = NetInfo.getUnsignedLongFromIp(ip_addr);
			short cidr = NetInfo.getCidr();
			int shift = (32 - cidr);
			if (cidr < 31) {
                network_start = (network_ip >> shift << shift) + 1;
                network_end = (network_start | ((1 << shift) - 1)) - 1;
            }
			Log.i("netdemo", NetInfo.getIpFromLongUnsigned(network_start));
			Log.i("netdemo", NetInfo.getIpFromLongUnsigned(network_end));
			
			Comparator<String> comparator = new Comparator<String>() {

				@Override
				public int compare(String arg0, String arg1) {
					// TODO Auto-generated method stub
					long ip0 = NetInfo.getUnsignedLongFromIp(arg0);
					long ip1 = NetInfo.getUnsignedLongFromIp(arg1);
					if(ip0 > ip1)
						return 1;
					else if (ip0 < ip1)
						return -1;
					else
						return 0;
				}
				  
			};
			hostmap = new TreeMap<String,String>(comparator);
			timemap = new TreeMap<String,Long>(comparator);
			metaTS = SystemClock.elapsedRealtime();
			getValidARPCache();
			Discovery mTask = new Discovery(network_ip, network_start, network_end);
			mTask.execute();
		}
		
		
	}

	public class Discovery extends AsyncTask<Void, Void, Void>{
		private long ip = 0;
		private long start = 0;
		private long end = 0;
		private int size = 0;
		private int pt_move = 2; // 1=backward 2=forward
		
		public Discovery(long ip, long start, long end) {
	        super();
	        this.ip = ip;
	        this.start = start;
	        this.end = end;
	        this.size = (int) (end - start + 1);
	        this.pt_move = 2;
	    }
		
		@Override
		protected Void doInBackground(Void... params) {
	        
			mPool = Executors.newFixedThreadPool(THREADS);
            if (ip <= end && ip >= start) {
                Log.i(TAG, "Back and forth scanning");
                // gateway
                launch(start);

                // hosts
                long pt_backward = ip;
                long pt_forward = ip + 1;
                long size_hosts = size - 1;

                for (int i = 0; i < size_hosts; i++) {
                    // Set pointer if of limits
                    if (pt_backward <= start) {
                        pt_move = 2;
                    } else if (pt_forward > end) {
                        pt_move = 1;
                    }
                    // Move back and forth
                    if (pt_move == 1) {
                        launch(pt_backward);
                        pt_backward--;
                        pt_move = 2;
                    } else if (pt_move == 2) {
                        launch(pt_forward);
                        pt_forward++;
                        pt_move = 1;
                    }
                }
            } else {
                Log.i(TAG, "Sequential scanning");
                for (long i = start; i <= end; i++) {
                    launch(i);
                }
            }
            mPool.shutdown();
            try {
                if(!mPool.awaitTermination(TIMEOUT_SCAN, TimeUnit.SECONDS)){
                    mPool.shutdownNow();
                    Log.i(TAG, "Shutting down pool");
                    if(!mPool.awaitTermination(TIMEOUT_SHUTDOWN, TimeUnit.SECONDS)){
                        Log.i(TAG, "Pool did not terminate");
                    }
                }
            } catch (InterruptedException e){
                Log.e(TAG, e.getMessage());
                mPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
	        
	        return null;
	    }

		@Override
		protected void onPostExecute(Void unused) {
			try {
				hostmap.put(NetInfo.getIPAddress(), NetInfo.getMACAddress("wlan0"));
				if(!timemap.containsKey(NetInfo.getIPAddress()))
					timemap.put(NetInfo.getIPAddress(),SystemClock.elapsedRealtime() - metaTS);
				String furi = exportToCSV(MainActivity.this);
	      		  Log.i(TAG, "File exported: "+furi);
	      		  Toast.makeText(MainActivity.this, "File exported: "+furi, Toast.LENGTH_LONG).show();
	      	  } catch (IOException e) {
	      		  // TODO Auto-generated catch block
	      		  e.printStackTrace();
	      	  }
	    }
		
		@Override
		protected void onCancelled() {
	        if (mPool != null) {
	            synchronized (mPool) {
	                mPool.shutdownNow();
	                // FIXME: Prevents some task to end (and close the Save DB)
	            }
	        }
	        try {
	        	hostmap.put(NetInfo.getIPAddress(), NetInfo.getMACAddress("wlan0"));
	        	if(!timemap.containsKey(NetInfo.getIPAddress()))
					timemap.put(NetInfo.getIPAddress(),SystemClock.elapsedRealtime() - metaTS);
				String furi = exportToCSV(MainActivity.this);
	      		  Log.i(TAG, "File exported: "+furi);
	      		  Toast.makeText(MainActivity.this, "File exported: "+furi, Toast.LENGTH_LONG).show();
	      	  } catch (IOException e) {
	      		  // TODO Auto-generated catch block
	      		  e.printStackTrace();
	      	  }
	    }    

		private void launch(long i) {
	        if(!mPool.isShutdown()) {
	            mPool.execute(new CheckRunnable(NetInfo.getIpFromLongUnsigned(i), SOCKET_TIMEOUT));
	        }
	    }
		
		private class CheckRunnable implements Runnable {
			private String ipAddr;
		    private String macAddr;
	        private int socket_timeout; //timeout in ms

	        CheckRunnable(String addr, int timeout) {
	        	this.ipAddr = addr;
	        	this.macAddr = NetInfo.NOMAC;
	            this.socket_timeout = timeout;
	        }
	        
	        private void publish(){
	        	if(!hostmap.containsKey(ipAddr)){
                	hostmap.put(ipAddr, macAddr);
                	timemap.put(ipAddr, SystemClock.elapsedRealtime() - metaTS);
                }else if(!hostmap.get(ipAddr).equals(macAddr)){
                	hostmap.put(ipAddr, macAddr);
                	timemap.put(ipAddr, SystemClock.elapsedRealtime() - metaTS);
                }
	        }

	        public void run() {
	            //if(isCancelled()) {
	              //  publish(null);
	            //}
	            //Log.e(TAG, "run="+addr);
	            // Create host object
	            try {
	                InetAddress h = InetAddress.getByName(ipAddr);
	                
	                // Arp Check #1
	                macAddr = NetInfo.getHardwareAddress(ipAddr);
	                if(!NetInfo.NOMAC.equals(macAddr)){
	                    Log.i(TAG, "found using arp #1 "+ipAddr + " MAC: "+macAddr);
	                    publish();
	                    return;
	                }
	                
	                // Native InetAddress check
	                if (h.isReachable(socket_timeout)) {
	                    Log.i(TAG, "found using InetAddress ping "+ipAddr);
	                    // Arp Check #2
		                macAddr = NetInfo.getHardwareAddress(ipAddr);
		                if(!NetInfo.NOMAC.equals(macAddr)){
		                    Log.i(TAG, "found using arp #2 "+ipAddr + " MAC: "+macAddr);
		                    publish();
		                    return;
		                }
	                }	                

	                // TODO: Get ports from options
	                Socket s = new Socket();
	                for (int i = 0; i < DPORTS.length; i++) {
	                    try {
	                        s.bind(null);
	                        s.connect(new InetSocketAddress(ipAddr, DPORTS[i]), socket_timeout);
	                        Log.i(TAG, "found using TCP connect "+ipAddr+" on port=" + DPORTS[i]);
	                    } catch (IOException e) {
	                    } catch (IllegalArgumentException e) {
	                    } finally {
	                        try {
	                            s.close();
	                        } catch (Exception e){
	                        }
	                    }
	                }

	                // Arp Check #3
	                macAddr = NetInfo.getHardwareAddress(ipAddr);
	                if(!NetInfo.NOMAC.equals(macAddr)){
	                    Log.i(TAG, "found using arp #3 "+ipAddr + " MAC: "+macAddr);
	                    publish();
	                    return;
	                }	                

	            } catch (IOException e) {
	                //publish(null);
	                Log.i(TAG, e.getMessage());
	            } 
	        }
	    }
		
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
	private boolean isNetworkOn(){
		ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
		return (networkInfo != null && networkInfo.isConnected());
	}
	
	public String exportToCSV(Context context) throws IOException{
		//String result = "";
		String furi = "";
		FileHelper fh = new FileHelper(context);
		
		String root = fh.getDir();
		File sd = new File(root);
		
		if (sd.canWrite()){
			
			String backupDBPath = "ARPScanDemo.txt";
	    	furi = root + "/" + backupDBPath;
	    	
	    	File file = new File(sd, backupDBPath);
	    	FileWriter filewriter = new FileWriter(file);  
	        BufferedWriter out = new BufferedWriter(filewriter);
	        
	        for(Map.Entry<String,String> entry : hostmap.entrySet()){
	        	out.write(timemap.get(entry.getKey())+"#"+entry.getKey()+"#"+entry.getValue()+"\n");
	        }       
	        
		    out.close();
		}
		
		return furi;
	}
	
	public void getValidARPCache() {
        try {
        	BufferedReader bufferedReader = new BufferedReader(new FileReader("/proc/net/arp"), NetInfo.BUF);
            String line = "";  
            long dt = SystemClock.elapsedRealtime() - metaTS;
            while ((line = bufferedReader.readLine()) != null) {
            	String[] ipmac = line.split("[ ]+");
                if (!ipmac[0].matches("IP")) {
                    String ip = ipmac[0];
                    String mac = ipmac[3];
                    if (!NetInfo.NOMAC.equals(mac) && !hostmap.containsKey(ip)) {
                        hostmap.put(ip, mac);  
                        timemap.put(ip, dt); 
                        //Log.i(TAG, "ARP Entry");
                    }                   
                }
            }
            bufferedReader.close();
        } catch (IOException e) {
            Log.e(TAG, "Can't open/read file ARP: " + e.getMessage());
        }
    }

}
