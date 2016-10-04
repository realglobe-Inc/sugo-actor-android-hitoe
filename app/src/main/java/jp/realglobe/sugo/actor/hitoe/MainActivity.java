package jp.realglobe.sugo.actor.hitoe;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationServices;

import org.json.JSONObject;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.socket.client.Ack;
import io.socket.client.Manager;
import io.socket.client.Socket;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = MainActivity.class.getName();

    private static final long REPORT_INTERVAL = 1_000;

    private static final String NAMESPACE = "/actors";

    private static final String KEY_KEY = "key";
    private static final String KEY_NAME = "name";
    private static final String KEY_SPEC = "spec";
    private static final String KEY_VERSION = "version";
    private static final String KEY_DESC = "desc";
    private static final String KEY_METHODS = "methods";
    private static final String KEY_MODULE = "module";
    private static final String KEY_EVENT = "event";
    private static final String KEY_DATA = "data";
    private static final String KEY_HEART_RATE = "heartRate";
    private static final String KEY_LOCATION = "location";

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private BlockingQueue<String> eventQueue = new ArrayBlockingQueue<>(1);
    private Handler handler;
    private Handler timerHandler;
    private CountDownTimer callTimer;

    private enum State {
        MAIN,
        WARNING,
        EMERGENCY,
    }

    private volatile State state = State.MAIN;

    private Vibrator vibrator;
    private Ringtone ringtone;
    private final int requestCode = (int) (Integer.MAX_VALUE * Math.random());

    private GoogleApiClient googleApiClient;
    private volatile Location location;

    private static final String EVENT_WARNING = State.WARNING.name().toLowerCase();
    private static final String EVENT_EMERGENCY = State.EMERGENCY.name().toLowerCase();

    private Future<?> reporter;

    private volatile int heartRate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.handler = new Handler();
        this.timerHandler = new Handler();
        this.vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        this.ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        checkLocationPermission();

        this.googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(new MyLocationListener())
                .addOnConnectionFailedListener(connectionResult -> Log.w(LOG_TAG, "Location detection error: " + connectionResult))
                .build();

        reset();

        this.executor.submit(this::runForEvent);
        Log.d(LOG_TAG, "Event waiter started");
    }

    private void checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 位置情報には許可が必要。
            this.requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
            }, requestCode);
        } else {
            showLocationNotice(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != this.requestCode) {
            return;
        }

        final Set<String> required = new HashSet<>(Arrays.asList(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));
        for (int i = 0; i < permissions.length; i++) {
            if (!required.contains(permissions[i])) {
                continue;
            }
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                continue;
            }
            required.remove(permissions[i]);
        }

        showLocationNotice(required.isEmpty());
    }

    private void showLocationNotice(boolean allowed) {
        final String message;
        if (allowed) {
            message = "救助要請に位置情報を付加できます";
        } else {
            message = "救助要請に位置情報を付加することができません\nメニューから許可設定を行ってください";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private class MyLocationListener implements ConnectionCallbacks, LocationListener {

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            final Location location = LocationServices.FusedLocationApi.getLastLocation(MainActivity.this.googleApiClient);
            MainActivity.this.location = location;

            Log.d(LOG_TAG, "Location is " + location);
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onLocationChanged(Location location) {
            MainActivity.this.location = location;

            Log.d(LOG_TAG, "Location changed to " + location);
        }
    }

    @Override
    public void onBackPressed() {
        (new AlertDialog.Builder(this))
                .setTitle("終了させますか？")
                .setPositiveButton("終了させる", (dialog, which) -> super.onBackPressed())
                .show();
    }

    /**
     * 異常検知イベントを受け取って警告状態に遷移させる。
     * 別スレッドで実行される。
     */
    private void runForEvent() {
        while (true) {
            final String event;
            try {
                event = this.eventQueue.take();
            } catch (InterruptedException e) {
                // 終了
                break;
            }
            Log.d(LOG_TAG, "Received emergency event " + event);
            this.handler.post(this::warn);
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        } else if (item.getItemId() == R.id.action_allow) {
            checkLocationPermission();
        } else if (item.getItemId() == R.id.action_reset) {
            reset();
        } else if (item.getItemId() == R.id.action_call) {
            callAfterDialog();
        } else if (item.getItemId() == R.id.action_timer_start) {
            startEventTimer();
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 初期状態に戻す
     */
    private synchronized void reset() {
        setContentView(R.layout.activity_main);
        this.state = State.MAIN;
        this.timerHandler.removeCallbacksAndMessages(null);
        if (this.callTimer != null) {
            this.callTimer.cancel();
            this.callTimer = null;
        }
        this.eventQueue.clear();
        this.vibrator.cancel();
        this.ringtone.stop();
        this.googleApiClient.disconnect();
        if (this.reporter != null) {
            if ((!this.reporter.isDone()) && (!this.reporter.isCancelled())) {
                this.reporter.cancel(true);
            }
            this.reporter = null;
        }

        Log.d(LOG_TAG, "Mode was reset");
    }

    /**
     * 警告中にする
     */
    private synchronized void warn() {
        if (this.state != State.MAIN) {
            // 初期状態からのみ
            return;
        }
        setContentView(R.layout.activity_warning);
        this.state = State.WARNING;
        this.timerHandler.removeCallbacksAndMessages(null);
        if (this.callTimer != null) {
            this.callTimer.cancel();
        }
        this.eventQueue.clear();

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final int delay = Integer.parseInt(sharedPreferences.getString(getString(R.string.key_delay), getString(R.string.default_delay)));

        this.vibrator.vibrate(new long[]{500, 1_000}, 0);
        this.ringtone.play();

        this.callTimer = new CountDownTimer(1_000L * delay, 100) {
            @Override
            public void onTick(long l) {
                ((TextView) findViewById(R.id.counter_count)).setText(Integer.toString((int) Math.ceil(l / 1_000.0)));
            }

            @Override
            public void onFinish() {
                call();
            }
        };
        this.callTimer.start();
        findViewById(R.id.button_call).setOnClickListener(view -> callAfterDialog());
        findViewById(R.id.button_stop).setOnClickListener(view -> (new AlertDialog.Builder(this))
                .setTitle("異常はありませんか？")
                .setPositiveButton("異常無し", (dialog, which) -> {
                    if (state == State.WARNING) {
                        reset();
                    }
                })
                .show());

        this.googleApiClient.connect();

        if (this.reporter == null) {
            this.reporter = this.executor.submit(this::runForReport);
        }

        Log.d(LOG_TAG, "Warning mode started");
    }

    /**
     * 異常発生中にする
     */
    private synchronized void call() {
        if (this.state == State.EMERGENCY) {
            return;
        }
        setContentView(R.layout.activity_emergency);
        this.state = State.EMERGENCY;
        this.timerHandler.removeCallbacksAndMessages(null);
        if (this.callTimer != null) {
            this.callTimer.cancel();
            this.callTimer = null;
        }
        this.eventQueue.clear();
        this.vibrator.cancel();
        this.ringtone.stop();
        if (!(this.googleApiClient.isConnecting() && this.googleApiClient.isConnected())) {
            this.googleApiClient.connect();
        }
        if (this.reporter == null) {
            this.reporter = this.executor.submit(this::runForReport);
        }

        Log.d(LOG_TAG, "Emergency mode started");
    }

    /**
     * ダイアログで確認してから救助要請する
     */
    private void callAfterDialog() {
        (new AlertDialog.Builder(this))
                .setTitle("救助を要請しますか？")
                .setPositiveButton("要請する", (dialog, which) -> call())
                .show();
    }

    /**
     * 異常検知イベントを発生させるタイマーを作動させる
     */
    private synchronized void startEventTimer() {
        this.timerHandler.removeCallbacksAndMessages(null);

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final long delay = 1_000L * Integer.parseInt(sharedPreferences.getString(getString(R.string.key_timer), getString(R.string.default_timer)));
        this.timerHandler.postDelayed(() -> {
            MainActivity.this.eventQueue.offer("dummy");
            Log.d(LOG_TAG, "Dummy emergency event was generated");
        }, delay);
        Log.d(LOG_TAG, "Event timer started");
    }

    private String actorKey;

    /**
     * 現状をサーバーに報告する
     */
    private void runForReport() {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final String server = sharedPreferences.getString(getString(R.string.key_server), getString(R.string.default_server));
        this.actorKey = getString(R.string.actor_prefix) + sharedPreferences.getString(getString(R.string.key_actor_suffix), getString(R.string.default_actor_suffix));

        final CountDownLatch endFlag = new CountDownLatch(1);
        final Socket socket = (new Manager(URI.create(server))).socket(NAMESPACE);
        try {
            final CountDownLatch startFlag = new CountDownLatch(1);
            socket.on(Socket.EVENT_CONNECT, args -> {
                Log.d(LOG_TAG, socket.id() + " connected to " + server);
                processAfterConnection(socket, endFlag);
            });
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                Log.d(LOG_TAG, socket.id() + " disconnected from " + server);
            });
            socket.connect();

            endFlag.await();

            while (true) {
                final Map<String, Object> data2 = new HashMap<>();
                data2.put(KEY_HEART_RATE, this.heartRate);
                final Location curLocation = this.location;
                if (curLocation != null) {
                    data2.put(KEY_LOCATION, Arrays.asList(curLocation.getLatitude(), curLocation.getLongitude(), curLocation.getAltitude()));
                }
                if (this.state == State.WARNING) {
                    emit(socket, EVENT_WARNING, data2);
                } else if (this.state == State.EMERGENCY) {
                    emit(socket, EVENT_EMERGENCY, data2);
                }
                Log.d(LOG_TAG, socket.id() + " sent report");

                Thread.sleep(REPORT_INTERVAL);
            }
        } catch (InterruptedException e) {
            // 終了
        } finally {
            endFlag.countDown();
            socket.close();
        }
    }

    private void processAfterConnection(Socket socket, CountDownLatch endFlag) {
        if (endFlag.getCount() == 0) {
            return;
        }

        final Map<String, Object> data = new HashMap<>();
        data.put(KEY_KEY, this.actorKey);
        socket.emit(SocketConstants.GreetingEvents.HI, new JSONObject(data), (Ack) args -> {
            Log.d(LOG_TAG, socket.id() + " greeted");
            processAfterGreeting(socket, endFlag);
        });
    }

    private void processAfterGreeting(Socket socket, CountDownLatch endFlag) {
        if (endFlag.getCount() == 0) {
            return;
        }

        final Map<String, Object> specData = new HashMap<>();
        specData.put(KEY_NAME, getString(R.string.module));
        try {
            specData.put(KEY_VERSION, getPackageManager().getPackageInfo(this.getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        specData.put(KEY_DESC, getString(R.string.description));
        specData.put(KEY_METHODS, new HashMap<String, Object>());

        final Map<String, Object> data = new HashMap<>();
        data.put(KEY_NAME, getString(R.string.module));
        data.put(KEY_SPEC, specData);

        socket.emit(SocketConstants.RemoteEvents.SPEC, new JSONObject(data), (Ack) args -> {
            Log.d(LOG_TAG, socket.id() + " sent specification");
            endFlag.countDown();
        });
    }

    private void emit(Socket socket, String event, Map<String, Object> data) {
        final Map<String, Object> wrapData = new HashMap<>();
        wrapData.put(KEY_KEY, this.actorKey);
        wrapData.put(KEY_MODULE, getString(R.string.module));
        wrapData.put(KEY_EVENT, event);
        if (data != null) {
            wrapData.put(KEY_DATA, data);
        }
        socket.emit(SocketConstants.RemoteEvents.PIPE, new JSONObject(wrapData));
    }

}
