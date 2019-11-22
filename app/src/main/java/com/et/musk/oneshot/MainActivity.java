package com.et.musk.oneshot;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.winnermicro.smartconfig.ConfigType;
import com.winnermicro.smartconfig.IOneShotConfig;
import com.winnermicro.smartconfig.SmartConfigFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static String TAG = "OneShortActivity";

    private static final int REQUEST_PERMISSION = 0x01;
    public static final int TYPE_NO_PASSWD = 0x11;
    public static final int TYPE_WEP = 0x12;
    public static final int TYPE_WPA = 0x13;
    private static final int MENU_ITEM_ABOUT = 0;

    private Button btnConf;
    private TextView textSsid;
    private TextView text_total;
    private EditText editPsw;
    private boolean isStart = false;
    private String ssid;
    private String psw = null;
    private IOneShotConfig oneshotConfig = null;
    private Boolean isThreadDisable = false;//指示监听线程是否终止
    private List<String> lstMac = new ArrayList<String>();
    private UdpHelper udphelper;
    private Thread tReceived;
    private ListView listView;
    private ResultAdapter adapter = null;
    private SmartConfigFactory factory = null;
    private TextView mMessageTV;
    private TextView mApBssidTV;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textSsid = findViewById(R.id.text_ssid);
        btnConf = findViewById(R.id.btn_conf);
        editPsw = findViewById(R.id.text_psw);
        text_total = findViewById(R.id.text_total);
        listView = findViewById(R.id.listView1);
        mMessageTV = findViewById(R.id.message);
        if (isSDKAtLeastP()) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                String[] permissions = {
                        Manifest.permission.ACCESS_COARSE_LOCATION
                };

                requestPermissions(permissions, REQUEST_PERMISSION);
            } else {
                registerBroadcastReceiver();
            }

        } else {
            registerBroadcastReceiver();
        }


        btnConf.setOnClickListener(onButtonConfClick);

        factory = new SmartConfigFactory();
        //通过修改参数ConfigType，确定使用何种方式进行一键配置，需要和固件侧保持一致。
        oneshotConfig = factory.createOneShotConfig(ConfigType.UDP);
        editPsw.requestFocus();
    }

    private boolean mReceiverRegistered = false;
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            assert wifiManager != null;

            switch (action) {
                case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                    WifiInfo wifiInfo;
                    if (intent.hasExtra(WifiManager.EXTRA_WIFI_INFO)) {
                        wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                    } else {
                        wifiInfo = wifiManager.getConnectionInfo();
                    }
                    onWifiChanged(wifiInfo);
                    break;
                case LocationManager.PROVIDERS_CHANGED_ACTION:
                    onWifiChanged(wifiManager.getConnectionInfo());
                    break;
            }
        }
    };

    private void onWifiChanged(WifiInfo info) {
        boolean disconnected = info == null
                || info.getNetworkId() == -1
                || "<unknown ssid>".equals(info.getSSID());
        if (disconnected) {
            textSsid.setText("");
            textSsid.setTag(null);
//			mApBssidTV.setText("");
            mMessageTV.setText(R.string.no_wifi_connection);
            btnConf.setEnabled(false);

            if (isSDKAtLeastP()) {
                checkLocation();
            }
//
//			if (mTask != null) {
//				mTask.cancelEsptouch();
//				mTask = null;
//				new AlertDialog.Builder(EsptouchDemoActivity.this)
//						.setMessage(R.string.configure_wifi_change_message)
//						.setNegativeButton(android.R.string.cancel, null)
//						.show();
//			}
        } else {
            String ssid = info.getSSID();
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            textSsid.setText(ssid);
//			textSsid.setTag(ByteUtil.getBytesByString(ssid));
//			byte[] ssidOriginalData = TouchNetUtil.getOriginalSsidBytes(info);
//			mApSsidTV.setTag(ssidOriginalData);

            String bssid = info.getBSSID();
//			mApBssidTV.setText(bssid);

            btnConf.setEnabled(true);
            mMessageTV.setText("");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int frequency = info.getFrequency();
                if (frequency > 4900 && frequency < 5900) {
                    // Connected 5G wifi. Device does not support 5G
                    Toast.makeText(MainActivity.this, R.string.wifi_5g_message + " Frequency: " + frequency + "Hz", Toast.LENGTH_SHORT).show();
                    mMessageTV.setText(R.string.wifi_5g_message);
                }
            }
        }
    }

    /**
     * 检查位置权限
     */
    private void checkLocation() {
        boolean enable;
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            enable = false;
        } else {
            boolean locationGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean locationNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            enable = locationGPS || locationNetwork;
        }

        if (!enable) {
            mMessageTV.setText(R.string.location_disable_message);
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        if (isSDKAtLeastP()) {
            filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        }
        registerReceiver(mReceiver, filter);
        mReceiverRegistered = true;
    }

    private boolean isSDKAtLeastP() {
        return Build.VERSION.SDK_INT >= 28;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case REQUEST_PERMISSION:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (!mDestroyed) {
                        registerBroadcastReceiver();
                    }
                }
                break;
        }
    }

    private boolean mDestroyed = false;

    /**
     * 停止配网
     */
    @Override
    protected void onStop() {
        super.onStop();
        stopConfig();
    }

    /**
     * 设备 bar 菜单
     *
     * @param menu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.one_shot, menu);
        menu.add(Menu.NONE, MENU_ITEM_ABOUT, 0, R.string.menu_item_about)
                .setIcon(R.drawable.ic_info_outline_white_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.action_custom:

                // 在action bar点击app icon; 回到 home

                Intent intent = new Intent(this, CusDataActivity.class);

                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

                startActivity(intent);

                return true;
            case R.id.about_app:
            case MENU_ITEM_ABOUT:
                showAboutDialog();
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 显示 App,SDK 版本信息
     */
    private void showAboutDialog() {
        String oneshotSDKVer = factory.getVersion();
        String appVer = "";
        PackageManager packageManager = getPackageManager();
        try {
            PackageInfo info = packageManager.getPackageInfo(getPackageName(), 0);
            appVer = info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        CharSequence[] items = new CharSequence[]{
                getString(R.string.about_app_version, appVer),
                getString(R.string.about_oneshort_version, oneshotSDKVer),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_item_about)
                .setIcon(R.drawable.ic_info_outline_black_24dp)
                .setItems(items, null)
                .show();
    }

    /**
     * 按下 Back 键,取消配网
     *
     * @param keyCode
     * @param event
     * @return
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isStart) {
                stopConfig();
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setEditable(boolean value) {
        if (value) {
			/*editPsw.setFilters(new InputFilter[] { new InputFilter() {
				public CharSequence filter(CharSequence source, int start,
						int end, Spanned dest, int dstart, int dend) {
					return null;
				}
			} });*/
            editPsw.setCursorVisible(true);
            editPsw.setFocusable(true);
            editPsw.setFocusableInTouchMode(true);
            editPsw.requestFocus();
        } else {
			/*
			editPsw.setFilters(new InputFilter[] { new InputFilter() {
				@Override
				public CharSequence filter(CharSequence source, int start,
						int end, Spanned dest, int dstart, int dend) {
					return source.length() < 1 ? dest.subSequence(dstart, dend)
							: "";
				}

			} });*/
            editPsw.setCursorVisible(false);
            editPsw.setFocusable(false);
            editPsw.setFocusableInTouchMode(false);
            editPsw.clearFocus();
        }
    }

    private void stopConfig() {
        isThreadDisable = true;
        if (isStart) {
            isStart = false;
            btnConf.setEnabled(false);
        }
        oneshotConfig.stop();
    }

    /**
     * 当设备配置信息有改动（比如屏幕方向的改变，实体键盘的推开或合上等）时，
     * <p>
     * 并且如果此时有activity正在运行，系统会调用这个函数。
     * <p>
     * 注意：onConfigurationChanged只会监测应用程序在AnroidMainifest.xml中通过
     * <p>
     * android:configChanges="xxxx"指定的配置类型的改动；
     * <p>
     * 而对于其他配置的更改，则系统会onDestroy()当前Activity，然后重启一个新的Activity实例。
     */

    @Override
    public void onConfigurationChanged(Configuration newConfig) {

        super.onConfigurationChanged(newConfig);

        // 检测屏幕的方向：纵向或横向

        if (this.getResources().getConfiguration().orientation

                == Configuration.ORIENTATION_LANDSCAPE) {

            // 当前为横屏， 在此处添加额外的处理代码

        } else if (this.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {

            // 当前为竖屏， 在此处添加额外的处理代码

        }

        // 检测实体键盘的状态：推出或者合上

        if (newConfig.hardKeyboardHidden

                == Configuration.HARDKEYBOARDHIDDEN_NO) {

            // 实体键盘处于推出状态，在此处添加额外的处理代码
        } else if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) {
            // 实体键盘处于合上状态，在此处添加额外的处理代码
        }
    }

    /**
     * 判断热点开启状态
     */
    public boolean isWifiApEnabled() {
        return getWifiApState() == WIFI_AP_STATE.WIFI_AP_STATE_ENABLED;
    }

    public enum WIFI_AP_STATE {
        WIFI_AP_STATE_DISABLING,
        WIFI_AP_STATE_DISABLED,
        WIFI_AP_STATE_ENABLING,
        WIFI_AP_STATE_ENABLED,
        WIFI_AP_STATE_FAILED
    }

    private WIFI_AP_STATE getWifiApState() {
        int tmp;
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            Method method = wifiManager.getClass().getMethod("getWifiApState");
            tmp = ((Integer) method.invoke(wifiManager));
            // Fix for Android 4
            if (tmp > 10) {
                tmp = tmp - 10;
            }
            return WIFI_AP_STATE.class.getEnumConstants()[tmp];
        } catch (Exception e) {
            Log.d(TAG, "获取 WIFI 状态失败");
            e.printStackTrace();
            return WIFI_AP_STATE.WIFI_AP_STATE_FAILED;
        }
    }


    @Override
    protected void onStart() {
        super.onStart();
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);

            if (wifiManager.isWifiEnabled()) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                String ssidString = null;
                if (wifiInfo != null) {
                    ssidString = wifiInfo.getSSID();
                    int version = getAndroidSDKVersion();
                    if (version > 16 && ssidString.startsWith("\"") && ssidString.endsWith("\"")) {
                        ssidString = ssidString.substring(1, ssidString.length() - 1);
                    }
                }
                Log.d(TAG, "SSID:" + ssidString);
                this.textSsid.setText(ssidString);
            } else if (isWifiApEnabled()) {
                WifiConfiguration conf = getWifiApConfiguration();
                String ssidString = null;
                if (conf != null) {
                    ssidString = conf.SSID;
                }
                Log.d(TAG, "SSID:" + ssidString);
                this.textSsid.setText(ssidString);
            } else {
                displayToast("网络不可用，请检查网络!");
            }
            adapter = new ResultAdapter(this, android.R.layout.simple_expandable_list_item_1, lstMac);
            listView.setAdapter(adapter);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public void displayToast(String str) {
        Toast.makeText(this, str, Toast.LENGTH_SHORT).show();
    }

    private WifiConfiguration getWifiApConfiguration() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            Method method = wifiManager.getClass().getMethod("getWifiApConfiguration");
            WifiConfiguration tmp = ((WifiConfiguration) method.invoke(wifiManager));

            return tmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private int getAndroidSDKVersion() {
        int version = 0;
        try {
            version = Integer.valueOf(android.os.Build.VERSION.SDK_INT);
        } catch (NumberFormatException e) {
            Log.e(e.toString(), e.getMessage());
        }
        return version;
    }

    private View.OnClickListener onButtonConfClick = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            if (isStart) {
                stopConfig();
                return;
            }
//			String ssid = mySpinner.getSelectedItem().toString();
//			if(ssid.length() == 0){
//				displayToast("请先连接WIFI网络!");
//				return;
//			}
            ssid = textSsid.getText().toString();
            psw = editPsw.getText().toString();
            lstMac.clear();
            adapter.notifyDataSetChanged();
            isStart = true;
            isThreadDisable = false;
            setEditable(false);
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            udphelper = new UdpHelper(wifiManager);
            tReceived = new Thread(udphelper);
            tReceived.start();
            new Thread(new UDPReqThread()).start();
            text_total.setText(String.format("%d connected.", lstMac.size()));
            btnConf.setText(getText(R.string.btn_stop_conf));
        }
    };

    private Runnable confPost = new Runnable() {

        @Override
        public void run() {
            isStart = false;
            isThreadDisable = true;
            btnConf.setEnabled(true);
            setEditable(true);
            btnConf.setText(getText(R.string.btn_conf));
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

    };

    private Runnable notifyPost = new Runnable() {

        @Override
        public void run() {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            text_total.setText(String.format("%d connected.", lstMac.size()));
            Toast.makeText(getApplicationContext(), String.format("%d connected.", lstMac.size()),
                    Toast.LENGTH_SHORT).show();
        }

    };

    class UDPReqThread implements Runnable {
        public void run() {
            Log.d(TAG, "开启配网线程");
            WifiManager wifiManager = null;
            try {
                wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifiManager.isWifiEnabled() || isWifiApEnabled()) {
                    int timeout = 60;//miao
                    oneshotConfig.start(ssid, psw, timeout, MainActivity.this);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                oneshotConfig.stop();
                runOnUiThread(confPost);
            }
        }
    }

    @SuppressLint("DefaultLocale")
    class UdpHelper implements Runnable {

        private WifiManager.MulticastLock lock;
        InetAddress mInetAddress;

        public UdpHelper(WifiManager manager) {
            this.lock = manager.createMulticastLock("UDPwifi");
        }

        public void StartListen() {
            // UDP服务器监听的端口
            Integer port = 65534;
            // 接收的字节大小，客户端发送的数据不能超过这个大小
            byte[] message = new byte[100];
            try {
                // 建立Socket连接
                DatagramSocket datagramSocket = new DatagramSocket(port);
                datagramSocket.setBroadcast(true);
                datagramSocket.setSoTimeout(1000);
                DatagramPacket datagramPacket = new DatagramPacket(message,
                        message.length);
                try {
                    while (!isThreadDisable) {
                        // 准备接收数据
                        Log.d("UDP Demo", "准备接收");
                        this.lock.acquire();
                        try {
                            datagramSocket.receive(datagramPacket);
                            String strMsg = "";
                            int count = datagramPacket.getLength();
                            for (int i = 0; i < count; i++) {
                                strMsg += String.format("%02X:", datagramPacket.getData()[i]);
                            }
                            strMsg = strMsg.toUpperCase() + ";" + datagramPacket.getAddress().getHostAddress();
                            if (!lstMac.contains(strMsg)) {
                                lstMac.add(strMsg);
                                runOnUiThread(notifyPost);
                            }
                            Log.d("UDP Demo", datagramPacket.getAddress()
                                    .getHostAddress() + ":" + strMsg);
                        } catch (SocketTimeoutException ex) {
                            Log.d("UDP Demo", "UDP Receive Timeout.");
                        }
                        this.lock.release();
                    }
                } catch (IOException e) {//IOException
                    e.printStackTrace();
                }
                datagramSocket.close();
            } catch (SocketException e) {
                e.printStackTrace();
            } finally {
                if (!isThreadDisable) {
                    runOnUiThread(confPost);
                }
            }
        }

        @Override
        public void run() {
            StartListen();
        }

    }
}
