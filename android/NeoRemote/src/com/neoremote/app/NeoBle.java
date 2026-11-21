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
 * Протокол узято з офіційного додатка SVAKOM 4.1.6 (com.sva.base_ble_library):
 *   BLE-ім'я:   "Alex NEO 2" (або "S63E Alex NEO 2"), productCode 248
 *   сервіс:     0000ffe0-0000-1000-8000-00805f9b34fb
 *   запис:      0000ffe1-...   сповіщення: 0000ffe2-...
 *   сила:       55 04 00 00 00 VV AA   (VV = 0..255, «Дотик», «Звук», «Музика»)
 *   режим:      55 03 00 00 MM SS      (MM = 1..7 вбудований режим, SS = сила, у Svakom 5)
 *   стоп:       55 04 00 00 00 00 AA  і  55 03 00 00 00 00
 *   батарея:    запит 55 00, відповідь 55 80 .. .. .. BB  або  55 02 BB ..
 */
@SuppressLint("MissingPermission")
public class NeoBle {

    public static final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static final UUID TX = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    public static final UUID RX = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final int MAX_POWER = 255;
    public static final int MODE_COUNT = 7;
    public static final int DEFAULT_MODE_STRENGTH = 5;

    public static final int ST_IDLE = 0;
    public static final int ST_SCANNING = 1;
    public static final int ST_CONNECTING = 2;
    public static final int ST_READY = 3;

    private static final long TICK_MS = 100;     // як у додатку Svakom: 1 пакет / 100 мс
    private static final long KEEPALIVE_MS = 1000;
    private static final long BATTERY_MS = 60000;
    private static final long SCAN_MS = 12000;
    private static final int MAX_RECONNECTS = 3;

    public interface Listener {
        void onState(int state, String text);
        void onFound(BluetoothDevice dev, String name, int rssi);
        void onLog(String line);
        void onBattery(int percent);
    }

    /** Плавна сила 0..255. */
    public static byte[] powerCmd(int v) {
        v = Math.max(0, Math.min(MAX_POWER, v));
        return new byte[]{0x55, 0x04, 0x00, 0x00, 0x00, (byte) v, (byte) 0xAA};
    }

    /** Вбудований режим 1..7 із силою strength (0 = стоп режиму). */
    public static byte[] modeCmd(int mode, int strength) {
        return new byte[]{0x55, 0x03, 0x00, 0x00, (byte) mode, (byte) strength};
    }

    public static final byte[] STOP_POWER = powerCmd(0);
    public static final byte[] STOP_MODE = modeCmd(0, 0);
    public static final byte[] READ_BATTERY = {0x55, 0x00};

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
    private BluetoothGattCharacteristic rx;
    private int state = ST_IDLE;
    private boolean userDisconnect;
    private int reconnects;

    // що має робити іграшка зараз
    private static final int OUT_STOP = 0, OUT_POWER = 1, OUT_MODE = 2;
    private int outKind = OUT_STOP, outA = 0, outB = 0;
    private String sentKey = null;
    private long lastSendAt, lastBatteryAt;
    private boolean keepAlive = true;
    private int battery = -1;
    private final java.util.ArrayDeque<byte[]> queue = new java.util.ArrayDeque<byte[]>();

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
    public int getBattery() { return battery; }
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
            write(STOP_POWER);
            write(STOP_MODE);
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
        rx = null;
        sentKey = null;
        queue.clear();
        battery = -1;
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

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, final byte[] value) {
            h.post(new Runnable() { @Override public void run() { onNotify(value); } });
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return; // на 33+ викликається варіант вище
            final byte[] value = c.getValue();
            h.post(new Runnable() { @Override public void run() { onNotify(value); } });
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
                    sentKey = null; // одразу надіслати поточний стан
                    queue.clear();
                    rx = findChar(g, RX);
                    if (rx != null) enableNotify(g, rx);
                    lastBatteryAt = SystemClock.uptimeMillis() - BATTERY_MS + 700; // перший запит через 0,7 с
                    log("Готово: " + tx.getUuid() + (rx != null ? ", сповіщення " + rx.getUuid() : ""));
                    String n = safeName(g.getDevice());
                    setState(ST_READY, "Підключено: " + (n == null ? g.getDevice().getAddress() : n));
                }
            });
        }
    };

    private static BluetoothGattCharacteristic findTx(BluetoothGatt g) { return findChar(g, TX); }

    private static BluetoothGattCharacteristic findChar(BluetoothGatt g, UUID id) {
        BluetoothGattService s = g.getService(SERVICE);
        if (s != null) {
            BluetoothGattCharacteristic c = s.getCharacteristic(id);
            if (c != null) return c;
        }
        List<BluetoothGattService> all = g.getServices();
        for (BluetoothGattService sv : all) {
            BluetoothGattCharacteristic c = sv.getCharacteristic(id);
            if (c != null) return c;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            g.setCharacteristicNotification(c, true);
            android.bluetooth.BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) return;
            byte[] v = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v);
            else { d.setValue(v); g.writeDescriptor(d); }
        } catch (Exception e) {
            log("Не вдалося увімкнути сповіщення: " + e.getMessage());
        }
    }

    private void onNotify(byte[] v) {
        if (v == null || v.length < 3) return;
        int b0 = v[0] & 0xFF, b1 = v[1] & 0xFF;
        int pct = -1;
        if (b0 == 0x55 && b1 == 0x80 && v.length >= 6) pct = v[5] & 0xFF;
        else if (b0 == 0x55 && b1 == 0x02 && v.length >= 7) pct = v[2] & 0xFF;
        else if (b0 == 0x12 && b1 == 0x80 && v.length >= 4) pct = v[3] & 0xFF;
        if (pct > 0) {
            battery = Math.min(pct, 100);
            listener.onBattery(battery);
        }
        log("← " + hex(v));
    }

    // ------------------------------------------------------------ output

    /** Плавна сила 0..255 (0 = стоп). Надсилається з ticker-а, коли змінилась. */
    public void setPower(int v) {
        v = Math.max(0, Math.min(MAX_POWER, v));
        if (v == 0) { outKind = OUT_STOP; outA = 0; outB = 0; }
        else { outKind = OUT_POWER; outA = v; outB = 0; }
    }

    /** Вбудований режим іграшки 1..7 із силою strength. */
    public void setBuiltinMode(int mode, int strength) {
        outKind = OUT_MODE;
        outA = Math.max(1, Math.min(MODE_COUNT, mode));
        outB = Math.max(1, Math.min(10, strength));
    }

    /** Поточна сила для показу (0..255); для вбудованого режиму -1. */
    public int currentPower() { return outKind == OUT_POWER ? outA : outKind == OUT_MODE ? -1 : 0; }

    /** Негайна зупинка. */
    public void stopNow() {
        outKind = OUT_STOP; outA = 0; outB = 0;
        queue.clear();
        if (isReady()) {
            write(STOP_POWER);
            queue.add(STOP_MODE);
            sentKey = key();
            lastSendAt = SystemClock.uptimeMillis();
        }
    }

    public boolean sendRaw(byte[] data) {
        if (!isReady()) { log("Не підключено"); return false; }
        // ручна команда «тримається», доки користувач не зробить щось інше
        outKind = OUT_STOP; outA = 0; outB = 0;
        sentKey = key();
        queue.clear();
        boolean ok = write(data);
        if (!ok) queue.add(data);
        lastSendAt = SystemClock.uptimeMillis();
        log("→ " + hex(data) + "  (вручну)");
        return true;
    }

    private String key() { return outKind + ":" + outA + ":" + outB; }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            h.postDelayed(this, TICK_MS);
            if (!isReady()) return;
            long now = SystemClock.uptimeMillis();
            String k = key();
            if (!k.equals(sentKey)) {
                // новий стан: замінюємо все, що ще не встигли надіслати
                queue.clear();
                if (outKind == OUT_POWER) {
                    queue.add(powerCmd(outA));
                } else if (outKind == OUT_MODE) {
                    queue.add(modeCmd(outA, outB));
                } else {
                    queue.add(STOP_POWER);
                    queue.add(STOP_MODE);
                }
                if (!k.startsWith(OUT_POWER + ":") || !String.valueOf(sentKey).startsWith(OUT_POWER + ":")) {
                    log("→ " + describe());
                }
                sentKey = k;
            } else if (keepAlive && outKind == OUT_POWER && queue.isEmpty() && now - lastSendAt > KEEPALIVE_MS) {
                queue.add(powerCmd(outA));
            } else if (queue.isEmpty() && now - lastBatteryAt > BATTERY_MS && rx != null) {
                queue.add(READ_BATTERY);
                lastBatteryAt = now;
            }
            byte[] next = queue.peekFirst();
            if (next != null && write(next)) {
                queue.pollFirst();
                lastSendAt = now;
            }
        }
    };

    private String describe() {
        if (outKind == OUT_POWER) return "сила " + outA + "/255";
        if (outKind == OUT_MODE) return "вбудований режим " + outA + ", сила " + outB;
        return "стоп";
    }

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
        if (gatt != null && tx != null) { write(STOP_POWER); write(STOP_MODE); }
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
