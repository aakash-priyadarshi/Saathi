package org.saathi.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Foreground, synthetic-data architecture probe. Not the native Saathi client. */
public class ProbeActivity extends Activity {
    private static final String SERVICE = "org.saathi.probe.v1";
    private static final String[] PERMISSIONS = {
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES
    };
    private static final int TOTAL = 1024 * 1024, CHUNK = 8192;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray evidence = new JSONArray();
    private final Map<String, String> discovered = new LinkedHashMap<>();
    private final ArrayList<Double> rtts = new ArrayList<>();
    private final String temporaryName = "Saathi-" + UUID.randomUUID().toString().substring(0, 6);
    private ConnectionsClient client;
    private WifiP2pManager directManager;
    private WifiP2pManager.Channel directChannel;
    private LinearLayout peers;
    private TextView state;
    private String connected, incomingId, outgoingId, pingRun;
    private byte[] received;
    private int nextReceived, nextSent, pingIndex;
    private long pairBegan, pingBegan, transferBegan;
    private boolean testing, receiving;
    private Button latency, transfer;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        client = Nearby.getConnectionsClient(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        text(body, "Saathi transport test", 26);
        text(body, "Engineering probe only. Uses generated test bytes, not relief data. Keep both phones open. No calls, accounts or publication are provided here.", 16);
        text(body, "Nearby SDK measurement; Wi-Fi Direct discovery and Wi-Fi Aware availability probes. Radio range, lock behavior and battery use must be measured on real phones.", 14);
        state = text(body, "Allow nearby access, then advertise on one phone and search on the other.", 16);
        button(body, "Allow nearby access", this::permissions);
        button(body, "Open Wi-Fi settings", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        button(body, "Open Bluetooth settings", () -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        button(body, "Show device capabilities", this::capabilities);
        button(body, "Advertise this test phone", this::advertise);
        button(body, "Find nearby test phones", this::discover);
        peers = new LinearLayout(this); peers.setOrientation(LinearLayout.VERTICAL); body.addView(peers);
        latency = button(body, "Measure 20 message round trips", this::startLatency);
        transfer = button(body, "Offer 1 MiB generated test data", this::offerTransfer);
        button(body, "Probe Wi-Fi Direct discovery", this::directProbe);
        button(body, "Stop and disconnect", () -> stop("Stopped by tester"));
        button(body, "Save measurement JSON", this::export);
        text(body, "Google Play services' Nearby SDK may collect usage analytics. This app adds no analytics and never uploads its measurement report. Review Google's Nearby documentation before testing.", 14);
        available(false); capabilities();
    }
    private TextView text(LinearLayout body, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, 8, 0, 12); body.addView(view); return view;
    }
    private Button button(LinearLayout body, String label, Runnable work) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false);
        view.setOnClickListener(v -> { try { work.run(); } catch (Exception e) { fail(e); } });
        body.addView(view); return view;
    }
    private void note(String kind, Object value) {
        try {
            JSONObject entry = new JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("value", value);
            if (evidence.length() >= 500) evidence.remove(0);
            evidence.put(entry);
            state.setText(kind + ": " + value);
        } catch (Exception e) { state.setText("Measurement could not be recorded"); }
    }
    private void fail(Exception e) { note("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
    private void available(boolean yes) { latency.setEnabled(yes); transfer.setEnabled(yes); }
    private void permissions() { requestPermissions(PERMISSIONS, 1); }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) {
        super.onRequestPermissionsResult(code, names, grants);
        note("permission_result", Arrays.toString(grants));
    }
    private boolean ready() {
        for (String permission : PERMISSIONS) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            note("not_ready", "Allow nearby access through the permission prompt. Denied access is a test result."); return false;
        }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
            note("not_ready", "Compatible Google Play services is unavailable; use the other transport probes."); return false;
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        BluetoothManager bt = getSystemService(BluetoothManager.class);
        if (wifi == null || !wifi.isWifiEnabled() || bt == null || bt.getAdapter() == null || !bt.getAdapter().isEnabled()) {
            note("not_ready", "Turn on Wi-Fi and Bluetooth in system settings, then try again. The probe does not toggle radios."); return false;
        }
        return true;
    }
    private void capabilities() {
        try {
            WifiAwareManager aware = getSystemService(WifiAwareManager.class);
            JSONObject report = new JSONObject().put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("securityPatch", Build.VERSION.SECURITY_PATCH)
                .put("nearbySdk", "19.5.1").put("targetSdk", 36)
                .put("wifiDirectFeature", getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
                .put("wifiAwareFeature", getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
                .put("wifiAwareAvailable", aware != null && aware.isAvailable())
                .put("batteryPercent", getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("physicalRange", JSONObject.NULL).put("batteryImpact", JSONObject.NULL);
            note("capabilities", report);
        } catch (Exception e) { fail(e); }
    }
    private void advertise() {
        if (!ready()) return;
        stop("New advertising attempt"); pairBegan = SystemClock.elapsedRealtime();
        client.startAdvertising(temporaryName, SERVICE, lifecycle,
            new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).setConnectionType(ConnectionType.BALANCED).build())
            .addOnSuccessListener(v -> note("advertising", temporaryName)).addOnFailureListener(this::fail);
    }
    private void discover() {
        if (!ready()) return;
        stop("New discovery attempt"); pairBegan = SystemClock.elapsedRealtime();
        client.startDiscovery(SERVICE, new EndpointDiscoveryCallback() {
            @Override public void onEndpointFound(String id, DiscoveredEndpointInfo info) {
                discovered.put(id, info.getEndpointName()); renderPeers();
            }
            @Override public void onEndpointLost(String id) { discovered.remove(id); renderPeers(); }
        }, new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
            .addOnSuccessListener(v -> note("discovery", "Searching for test phones")) .addOnFailureListener(this::fail);
    }
    private void renderPeers() {
        peers.removeAllViews();
        discovered.forEach((id, name) -> button(peers, "Pair with " + name, () -> {
            pairBegan = SystemClock.elapsedRealtime();
            client.requestConnection(temporaryName, id, lifecycle).addOnFailureListener(this::fail);
        }));
    }
    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String id, ConnectionInfo info) {
            new AlertDialog.Builder(ProbeActivity.this).setTitle("Compare both phone codes")
                .setMessage(info.getEndpointName() + "\n" + info.getAuthenticationDigits() + "\nAccept only when both phones show the same code.")
                .setPositiveButton("Codes match", (d, which) -> client.acceptConnection(id, payloads).addOnFailureListener(ProbeActivity.this::fail))
                .setNegativeButton("Reject", (d, which) -> client.rejectConnection(id))
                .setOnCancelListener(d -> client.rejectConnection(id)).show();
        }
        @Override public void onConnectionResult(String id, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                connected = id; client.stopDiscovery(); client.stopAdvertising(); available(true);
                note("paired", new JSONObject(Map.of("setupMs", SystemClock.elapsedRealtime() - pairBegan, "includesHumanConfirmation", true)));
            } else note("pair_failed", result.getStatus().toString());
        }
        @Override public void onDisconnected(String id) { if (id.equals(connected)) stop("Connection lost; repeat pairing to measure reconnection"); }
    };
    private JSONObject frame(String type) throws Exception { return new JSONObject().put("v", 1).put("type", type); }
    private void send(JSONObject value) {
        if (connected == null) return;
        client.sendPayload(connected, Payload.fromBytes(value.toString().getBytes(StandardCharsets.UTF_8))).addOnFailureListener(this::fail);
    }
    private final PayloadCallback payloads = new PayloadCallback() {
        @Override public void onPayloadReceived(String from, Payload payload) {
            if (!from.equals(connected) || payload.getType() != Payload.Type.BYTES || payload.asBytes() == null || payload.asBytes().length > 16000) return;
            try {
                JSONObject value = new JSONObject(new String(payload.asBytes(), StandardCharsets.UTF_8));
                if (value.optInt("v") != 1) return;
                switch (value.getString("type")) {
                    case "PING": send(frame("PONG").put("index", value.getInt("index")).put("run", value.getString("run"))); break;
                    case "PONG":
                        if (!testing || !value.getString("run").equals(pingRun) || value.getInt("index") != pingIndex) break;
                        rtts.add((SystemClock.elapsedRealtimeNanos() - pingBegan) / 1e6);
                        if (++pingIndex < 20) handler.postDelayed(ProbeActivity.this::ping, 100);
                        else { testing = false; available(true); double[] samples = rtts.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                            note("rtt_ms", new JSONObject().put("samples", new JSONArray(rtts)).put("median", (samples[9] + samples[10]) / 2).put("p95", samples[18])); }
                        break;
                    case "OFFER":
                        if (receiving || testing || outgoingId != null || value.getInt("bytes") != TOTAL) break;
                        String offerId = value.getString("id");
                        new AlertDialog.Builder(ProbeActivity.this).setTitle("Receive generated test data?")
                            .setMessage("1 MiB of generated bytes, held in memory for checksum verification. This measures application throughput.")
                            .setPositiveButton("Receive", (d, which) -> { try { received = new byte[TOTAL]; nextReceived = 0; incomingId = offerId; receiving = true;
                                transferBegan = SystemClock.elapsedRealtimeNanos(); available(false); send(frame("READY").put("id", offerId)); } catch (Exception e) { fail(e); } })
                            .setNegativeButton("Decline", (d, which) -> { try { send(frame("DECLINE").put("id", offerId)); } catch (Exception e) { fail(e); } }).show();
                        break;
                    case "READY": if (value.getString("id").equals(outgoingId)) { transferBegan = SystemClock.elapsedRealtimeNanos(); sendChunk(); } break;
                    case "CHUNK":
                        if (!receiving || !value.getString("id").equals(incomingId) || value.getInt("index") != nextReceived) break;
                        byte[] bytes = Base64.decode(value.getString("data"), Base64.NO_WRAP);
                        if (bytes.length != CHUNK || nextReceived >= TOTAL / CHUNK) throw new IllegalArgumentException("Invalid test chunk");
                        System.arraycopy(bytes, 0, received, nextReceived * CHUNK, CHUNK); nextReceived++;
                        send(frame("ACK").put("id", incomingId).put("next", nextReceived));
                        if (nextReceived == TOTAL / CHUNK) {
                            double seconds = (SystemClock.elapsedRealtimeNanos() - transferBegan) / 1e9;
                            boolean valid = Arrays.equals(MessageDigest.getInstance("SHA-256").digest(received), MessageDigest.getInstance("SHA-256").digest(testBytes()));
                            note("received_1mib", new JSONObject().put("seconds", seconds).put("mibPerSecond", 1 / seconds).put("checksumVerified", valid).put("chunkAckPolicy", "stop-and-wait"));
                            received = null; receiving = false; incomingId = null; available(true);
                        }
                        break;
                    case "ACK":
                        if (outgoingId == null || !value.getString("id").equals(outgoingId) || value.getInt("next") != nextSent + 1) break;
                        nextSent++; if (nextSent < TOTAL / CHUNK) sendChunk();
                        else { note("sent_1mib_ms", (SystemClock.elapsedRealtimeNanos() - transferBegan) / 1e6); outgoingId = null; available(true); }
                        break;
                    case "DECLINE": if (value.getString("id").equals(outgoingId)) { outgoingId = null; available(true); note("transfer", "Declined by receiver"); } break;
                    default: break;
                }
            } catch (Exception e) { fail(e); }
        }
        @Override public void onPayloadTransferUpdate(String endpoint, PayloadTransferUpdate update) {
            if (update.getStatus() == PayloadTransferUpdate.Status.FAILURE) note("payload_failed", update.getPayloadId());
        }
    };
    private void startLatency() {
        testing = true; pingIndex = 0; pingRun = UUID.randomUUID().toString(); rtts.clear(); available(false); ping();
        String run = pingRun;
        handler.postDelayed(() -> { if (testing && run.equals(pingRun)) { testing = false; available(connected != null); note("rtt_incomplete", rtts.size() + " of 20 replies in 30 seconds"); } }, 30000);
    }
    private void ping() {
        if (!testing) return;
        try { pingBegan = SystemClock.elapsedRealtimeNanos(); send(frame("PING").put("index", pingIndex).put("run", pingRun)); } catch (Exception e) { fail(e); }
    }
    private static byte[] testBytes() { byte[] data = new byte[TOTAL]; for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + 7); return data; }
    private void offerTransfer() {
        try { outgoingId = UUID.randomUUID().toString(); nextSent = 0; available(false); send(frame("OFFER").put("id", outgoingId).put("bytes", TOTAL));
            String run = outgoingId; handler.postDelayed(() -> { if (run.equals(outgoingId)) { outgoingId = null; available(connected != null); note("transfer_timeout", "No completed transfer after 90 seconds"); } }, 90000);
        } catch (Exception e) { fail(e); }
    }
    private void sendChunk() throws Exception {
        byte[] bytes = new byte[CHUNK]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ((nextSent * CHUNK + i) * 31 + 7);
        send(frame("CHUNK").put("id", outgoingId).put("index", nextSent).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)));
    }
    private void directProbe() {
        stop("Switching to separate Wi-Fi Direct discovery probe");
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) { permissions(); return; }
        WifiP2pManager manager = getSystemService(WifiP2pManager.class);
        if (manager == null) { note("direct", "Unavailable"); return; }
        WifiP2pManager.Channel channel = manager.initialize(this, getMainLooper(), null);
        directManager = manager; directChannel = channel;
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { note("direct_discovery", "Started; sampling peer count in 10 seconds (no identities exported)");
                handler.postDelayed(() -> {
                    if (directChannel != channel) return;
                    if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                        note("direct", "Permission was removed during discovery"); stopDirect(); return;
                    }
                    try { manager.requestPeers(channel, list -> {
                        note("direct_peer_count", list.getDeviceList().size()); stopDirect();
                    }); } catch (SecurityException e) { fail(e); stopDirect(); }
                }, 10000);
            }
            @Override public void onFailure(int reason) { note("direct_failed", reason); stopDirect(); }
        });
    }
    private void stopDirect() {
        WifiP2pManager manager = directManager; WifiP2pManager.Channel channel = directChannel;
        directManager = null; directChannel = null;
        if (manager == null || channel == null) return;
        try { manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { channel.close(); }
            @Override public void onFailure(int reason) { channel.close(); }
        }); } catch (SecurityException e) { channel.close(); fail(e); }
    }
    private void stop(String reason) {
        stopDirect();
        connected = null; testing = false; receiving = false;
        received = null; incomingId = null; outgoingId = null; available(false);
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints();
        discovered.clear(); if (peers != null) peers.removeAllViews(); note("disconnected", reason);
    }
    private void export() { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "saathi-android-probe.json"), 2); }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 2 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (var out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("No report destination");
            out.write(new JSONObject().put("format", "saathi-android-probe-v1").put("sdk", "Nearby 19.5.1")
                .put("scope", "Foreground synthetic-data probe; not production compatibility")
                .put("measurements", evidence).toString(2).getBytes(StandardCharsets.UTF_8));
            note("export", "Saved measurement report");
        } catch (Exception e) { fail(e); }
    }
    @Override public void onStop() { super.onStop(); if (!isChangingConfigurations()) stop("App left foreground; background transport intentionally unimplemented"); }
}
