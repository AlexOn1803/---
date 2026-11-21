package com.neoremote.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BLE-зв'язок із Svakom Alex Neo 2.
 *
 * Протокол (той самий, що в open-source бібліотеці Buttplug, "svakom-alex-v2"):
 *   BLE-ім'я:  "Alex NEO 2" (або "S63E Alex NEO 2")
 *   сервіс:    0000ffe0-0000-1000-8000-00805f9b34fb
 *   запис у:   0000ffe1-0000-1000-8000-00805f9b34fb (без підтвердження)
 *   команда:   55 03 03 00 LL (LL+5), де LL = рівень 0..3 (0 = стоп)
 */
@SuppressLint("MissingPermission")
public class NeoBle {

    public static final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static final UUID TX = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    public static final int MAX_LEVEL = 3;

    public static final int ST_IDLE = 0;
    public static final int ST_SCANNING = 1;
    public static final int ST_CONNECTING = 2;
    public static final int ST_READY = 3;

    private static final long TICK_MS = 60;
    private static final long KEEPALIVE_MS = 2000;
    private static final long SCAN_MS = 12000;
    private static final int MAX_RECONNECTS = 3;

    public interface Listener {
        void onState(int state, String text);
        void onFound(BluetoothDevice dev, String name, int rssi);
        void onLog(String line);
    }

    public static byte[] levelCmd(int level) {
        if (level < 0) level = 0;
        if (level > MAX_LEVEL) level = MAX_LEVEL;
        return new byte[]{0x55, 0x03, 0x03, 0x00, (byte) level, (byte) (level + 5)};
    }

    public static boolean looksLikeAlex(String name) {
        if (name == null) return false;
        String n = name.toUpperCase();
        return n.contains("ALEX");
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private final Set<String> seen = new HashSet<String>();

    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private int state = ST_IDLE;
    private boolean userDisconnect;
    private int reconnects;

    private int wantLevel = 0;
    private int sentLevel = -1;
    private long lastSendAt;
    private boolean keepAlive = true;

    public NeoBle(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        h.post(ticker);
    }

    // ------------------------------------------------------------ state

    public int getState() { return state; }
    public boolean isReady() { return state == ST_READY; }
    public String deviceName() { return device == null ? null : safeName(device); }
    public String deviceAddress() { return device == null ? null : device.getAddress(); }
    public int getLevel() { return wantLevel; }
    public void setKeepAlive(boolean on) { keepAlive = on; }
    public boolean getKeepAlive() { return keepAlive; }

    private void setState(int s, String text) {
        state = s;
        listener.onState(s, text);
    }

    private void log(String s) { listener.onLog(s); }

    public BluetoothAdapter adapter() {
        if (adapter == null) {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
        }
        return adapter;
    }

    // ------------------------------------------------------------ scan

    public boolean startScan() {
        BluetoothAdapter a = adapter();
        if (a == null) { setState(ST_IDLE, "Bluetooth недоступний"); return false; }
        if (!a.isEnabled()) { setState(ST_IDLE, "Увімкніть Bluetooth"); return false; }
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) { setState(ST_IDLE, "Bluetooth недоступний"); return false; }
        stopScan();
        seen.clear();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCb);
        } catch (Exception e) {
            setState(ST_IDLE, "Помилка пошуку: " + e.getMessage());
            return false;
        }
        scanning = true;
        setState(ST_SCANNING, "Пошук…");
        log("Пошук BLE-пристроїв");
        h.postDelayed(scanTimeout, SCAN_MS);
        return true;
    }

    public void stopScan() {
        h.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
        }
        scanning = false;
    }

    private final Runnable scanTimeout = new Runnable() {
        @Override public void run() {
            stopScan();
            if (state == ST_SCANNING) setState(ST_IDLE, "Не знайдено. Увімкніть іграшку і спробуйте ще");
        }
    };

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, final ScanResult r) {
            final BluetoothDevice d = r.getDevice();
            String name = null;
            if (r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
            if (name == null) name = safeName(d);
            if (name == null || name.trim().isEmpty()) return;
            final String n = name.trim();
            final int rssi = r.getRssi();
            h.post(new Runnable() {
                @Override public void run() {
                    if (seen.add(d.getAddress())) {
                        log("Знайдено: " + n + " (" + d.getAddress() + ", " + rssi + " dBm)");
                        listener.onFound(d, n, rssi);
                    }
                }
            });
        }

        @Override public void onScanFailed(final int errorCode) {
            h.post(new Runnable() {
                @Override public void run() {
                    scanning = false;
                    setState(ST_IDLE, "Помилка пошуку (" + errorCode + ")");
                }
            });
        }
    };

    private static String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (SecurityException e) { return null; }
    }

    // ------------------------------------------------------------ connect

    public BluetoothDevice remoteDevice(String address) {
        BluetoothAdapter a = adapter();
        if (a == null || address == null) return null;
        try { return a.getRemoteDevice(address); } catch (Exception e) { return null; }
    }

    public void connect(BluetoothDevice d) {
        stopScan();
        closeGatt();
        device = d;
        userDisconnect = false;
        reconnects = 0;
        doConnect();
    }

    private void doConnect() {
        if (device == null) return;
        String n = safeName(device);
        setState(ST_CONNECTING, "Підключення до " + (n == null ? device.getAddress() : n) + "…");
        try {
            gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            setState(ST_IDLE, "Помилка підключення: " + e.getMessage());
        }
    }

    public void disconnect() {
        userDisconnect = true;
        stopScan();
        h.removeCallbacks(reconnectRunnable);
        if (gatt != null && tx != null) {
            write(levelCmd(0));
            h.postDelayed(new Runnable() {
                @Override public void run() { closeGatt(); setState(ST_IDLE, "Відключено"); }
            }, 200);
        } else {
            closeGatt();
            setState(ST_IDLE, "Відключено");
        }
    }

    private void closeGatt() {
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        tx = null;
        sentLevel = -1;
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() { if (!userDisconnect) doConnect(); }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) { try { g.close(); } catch (Exception ignored) { } return; }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        log("З'єднано, шукаю сервіси…");
                        h.postDelayed(new Runnable() {
                            @Override public void run() { if (gatt != null) gatt.discoverServices(); }
                        }, 300);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        log("З'єднання розірвано (status " + status + ")");
                        closeGatt();
                        if (!userDisconnect && reconnects < MAX_RECONNECTS) {
                            reconnects++;
                            setState(ST_CONNECTING, "Перепідключення (" + reconnects + "/" + MAX_RECONNECTS + ")…");
                            h.postDelayed(reconnectRunnable, 1500);
                        } else if (!userDisconnect) {
                            setState(ST_IDLE, "Зв'язок втрачено");
                        }
                    }
                }
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) return;
                    tx = findTx(g);
                    if (tx == null) {
                        log("Характеристику FFE1 не знайдено. Сервіси пристрою:");
                        for (BluetoothGattService s : g.getServices()) {
                            log("  " + s.getUuid());
                            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                                log("    " + c.getUuid() + " props=0x" + Integer.toHexString(c.getProperties()));
                            }
                        }
                        setState(ST_IDLE, "Це не схоже на Alex Neo 2");
                        userDisconnect = true;
                        closeGatt();
                        return;
                    }
                    reconnects = 0;
                    sentLevel = -1; // одразу надіслати поточний рівень
                    log("Готово: " + tx.getUuid());
                    String n = safeName(g.getDevice());
                    setState(ST_READY, "Підключено: " + (n == null ? g.getDevice().getAddress() : n));
                }
            });
        }
    };

    private static BluetoothGattCharacteristic findTx(BluetoothGatt g) {
        BluetoothGattService s = g.getService(SERVICE);
        if (s != null) {
            BluetoothGattCharacteristic c = s.getCharacteristic(TX);
            if (c != null) return c;
        }
        List<BluetoothGattService> all = g.getServices();
        for (BluetoothGattService sv : all) {
            BluetoothGattCharacteristic c = sv.getCharacteristic(TX);
            if (c != null) return c;
        }
        return null;
    }

    // ------------------------------------------------------------ output

    /** Бажаний рівень 0..3. Реально надсилається з ticker-а, тільки коли змінився. */
    public void setLevel(int level) {
        if (level < 0) level = 0;
        if (level > MAX_LEVEL) level = MAX_LEVEL;
        wantLevel = level;
    }

    /** Негайна зупинка. */
    public void stopNow() {
        wantLevel = 0;
        if (isReady() && write(levelCmd(0))) {
            sentLevel = 0;
            lastSendAt = SystemClock.uptimeMillis();
        }
    }

    public boolean sendRaw(byte[] data) {
        if (!isReady()) { log("Не підключено"); return false; }
        boolean ok = write(data);
        log((ok ? "→ " : "✗ ") + hex(data));
        // наступний tick перешле поточний рівень, тільки якщо він зміниться
        return ok;
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            h.postDelayed(this, TICK_MS);
            if (!isReady()) return;
            long now = SystemClock.uptimeMillis();
            boolean changed = wantLevel != sentLevel;
            boolean ka = keepAlive && wantLevel > 0 && now - lastSendAt > KEEPALIVE_MS;
            if (changed || ka) {
                byte[] cmd = levelCmd(wantLevel);
                if (write(cmd)) {
                    if (changed) log("→ " + hex(cmd) + "  (рівень " + wantLevel + ")");
                    sentLevel = wantLevel;
                    lastSendAt = now;
                }
            }
        }
    };

    @SuppressWarnings("deprecation")
    private boolean write(byte[] data) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = tx;
        if (g == null || c == null) return false;
        int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS;
            } else {
                c.setWriteType(type);
                c.setValue(data);
                return g.writeCharacteristic(c);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void release() {
        h.removeCallbacksAndMessages(null);
        userDisconnect = true;
        stopScan();
        if (gatt != null && tx != null) write(levelCmd(0));
        closeGatt();
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    /** "55 03 03 00 01 06" / "550303000106" → bytes, або null. */
    public static byte[] parseHex(String s) {
        String t = s.replaceAll("(?i)0x", "").replaceAll("[^0-9a-fA-F]", "");
        if (t.isEmpty() || t.length() % 2 != 0) return null;
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
