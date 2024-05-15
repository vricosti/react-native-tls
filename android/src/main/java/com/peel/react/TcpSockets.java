/**
 * Copyright (c) 2015-present, Peel Technologies, Inc.
 * All rights reserved.
 */

package com.peel.react;

import androidx.annotation.NonNull;
import android.util.Base64;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;

/**
 * The NativeModule acting as an api layer for {@link TcpSocketManager}
 */
public final class TcpSockets extends ReactContextBaseJavaModule implements TcpSocketListener {
    private static final String TAG = "TcpSockets";

    private boolean mShuttingDown = false;
    private TcpSocketManager socketManager;

    private ReactContext mReactContext;

    public TcpSockets(ReactApplicationContext reactContext) {
        super(reactContext);
        mReactContext = reactContext;

        try {
            socketManager = new TcpSocketManager(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void initialize() {
        mShuttingDown = false;
    }

    @Override
    public void onCatalystInstanceDestroy() {
        mShuttingDown = true;

        try {
            new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
                @Override
                protected void doInBackgroundGuarded(Void... params) {
                    socketManager.closeAllSockets();
                }
            }.execute().get();
        } catch (InterruptedException ioe) {
            FLog.e(TAG, "onCatalystInstanceDestroy", ioe);
        } catch (ExecutionException ee) {
            FLog.e(TAG, "onCatalystInstanceDestroy", ee);
        }
    }

    private void sendEvent(String eventName, WritableMap params) {
        mReactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit(eventName, params);
    }

    @ReactMethod
    public void listen(final Integer cId, final String host, final Integer port) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                try {
                    socketManager.listen(cId, host, port);
                } catch (UnknownHostException uhe) {
                    FLog.e(TAG, "listen", uhe);
                    onError(cId, uhe.getMessage());
                } catch (IOException ioe) {
                    FLog.e(TAG, "listen", ioe);
                    onError(cId, ioe.getMessage());
                }
            }
        }.execute();
    }

    @ReactMethod
    public void connect(final Integer cId, final @NonNull String host, final Integer port, final ReadableMap options) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                // NOTE : ignoring options for now, just use the available interface.
                try {
                    socketManager.connect(cId, host, port);
                } catch (UnknownHostException uhe) {
                    FLog.e(TAG, "connect", uhe);
                    onError(cId, uhe.getMessage());
                } catch (IOException ioe) {
                    FLog.e(TAG, "connect", ioe);
                    onError(cId, ioe.getMessage());
                }
            }
        }.execute();
    }

    @ReactMethod
    public void write(final Integer cId, final String baseString, final Callback callback) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                socketManager.write(cId, hexStringToBytes(baseString));
                if (callback != null) {
                    callback.invoke();
                }
            }
        }.execute();
    }

    @ReactMethod
    public void end(final Integer cId) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                socketManager.close(cId);
            }
        }.execute();
    }

    @ReactMethod
    public void destroy(final Integer cId) {
        end(cId);
    }

    /** TcpSocketListener */

    @Override
    public void onConnection(Integer serverId, Integer clientId, InetSocketAddress socketAddress) {
        if (mShuttingDown) {
            return;
        }
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", serverId);

        WritableMap infoParams = Arguments.createMap();
        infoParams.putInt("id", clientId);

        final InetAddress address = socketAddress.getAddress();

        WritableMap addressParams = Arguments.createMap();
        addressParams.putString("address", address.getHostAddress());
        addressParams.putInt("port", socketAddress.getPort());
        addressParams.putString("family", address instanceof Inet6Address ? "IPv6" : "IPv4");

        infoParams.putMap("address", addressParams);
        eventParams.putMap("info", infoParams);

        sendEvent("connection", eventParams);
    }

    @Override
    public void onConnect(Integer id, InetSocketAddress socketAddress) {
        if (mShuttingDown) {
            return;
        }
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);

        final InetAddress address = socketAddress.getAddress();

        WritableMap addressParams = Arguments.createMap();
        addressParams.putString("address", address.getHostAddress());
        addressParams.putInt("port", socketAddress.getPort());
        addressParams.putString("family", address instanceof Inet6Address ? "IPv6" : "IPv4");

        eventParams.putMap("address", addressParams);

        sendEvent("connect", eventParams);
    }

    @Override
    public void onData(Integer id, byte[] data) {
        if (mShuttingDown) {
            return;
        }
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putString("data", bytesToString(data));

        sendEvent("data", eventParams);
    }

    @Override
    public void onClose(Integer id, String error) {
        if (mShuttingDown) {
            return;
        }
        if (error != null) {
            onError(id, error);
        }

        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putBoolean("hadError", error != null);

        sendEvent("close", eventParams);
    }

    @Override
    public void onError(Integer id, String error) {
        if (mShuttingDown) {
            return;
        }

        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putString("error", error);

        sendEvent("error", eventParams);
    }

    private static String autoGenericCode(String code) {
        if(code.length() % 2 == 0) {
            return code;
        } else {
            StringBuffer sb = new StringBuffer();
            sb.append("0");
            sb.append(code);
            return sb.toString();
        }
    }

    private static byte charToByte(char c) {
        return (byte) "0123456789ABCDEF".indexOf(c);
    }

    /**
     * 十六进制字符串转byte数组
     * @param hexString
     * @return
     */
    private static byte[] hexStringToBytes(String hexString) {
        hexString = autoGenericCode(hexString);
        if (hexString == null || hexString.equals("")) {
            return null;
        }
        hexString = hexString.toUpperCase();
        int length = hexString.length() / 2;
        char[] hexChars = hexString.toCharArray();
        byte[] d = new byte[length];
        for (int i = 0; i < length; i++) {
            int pos = i * 2;
            d[i] = (byte) (charToByte(hexChars[pos]) << 4 | charToByte(hexChars[pos + 1]));
        }
        return d;
    }

    /**
     * byte数组转成字符串
     * @param bytes
     * @return
     */
    public static String bytesToString(byte[] bytes) {
        String hexString = bytesToHexString(bytes);
        return hexStringToString(hexString);
    }

    /**
     * byte数组转换成十六进制字符串
     * @param bArray
     * @return HexString
     */
    private static String bytesToHexString(byte[] bArray) {
        StringBuffer sb = new StringBuffer(bArray.length);
        String sTemp;
        for (int i = 0; i < bArray.length; i++) {
            sTemp = Integer.toHexString(0xFF & bArray[i]);
            if (sTemp.length() < 2)
                sb.append(0);
            sb.append(sTemp.toUpperCase());
        }
        return sb.toString();
    }

    /**
     * 16进制字符串转换为字符串
     *
     * @param s
     * @return
     */
    private static String hexStringToString(String s) {
        if (s == null || s.equals("")) {
            return null;
        }
        s = s.replace(" ", "");
        byte[] baKeyword = new byte[s.length() / 2];
        for (int i = 0; i < baKeyword.length; i++) {
            try {
                baKeyword[i] = (byte) (0xff & Integer.parseInt(
                  s.substring(i * 2, i * 2 + 2), 16));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            s = new String(baKeyword, "gbk");
            new String();
        } catch (Exception e1) {
            e1.printStackTrace();
        }
        return s;
    }
}
