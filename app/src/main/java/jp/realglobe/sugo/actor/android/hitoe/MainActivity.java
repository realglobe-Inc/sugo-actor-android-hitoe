/*----------------------------------------------------------------------
 * Copyright 2017 realglobe Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *----------------------------------------------------------------------*/

package jp.realglobe.sugo.actor.android.hitoe;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
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
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import jp.ne.docomo.smt.dev.hitoetransmitter.sdk.HitoeSdkAPIImpl;
import jp.realglobe.sugo.actor.Actor;
import jp.realglobe.sugo.actor.Emitter;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = MainActivity.class.getName();

    private static final long LOCATION_INTERVAL = 10_000;

    // 送信データのキー
    private static final String KEY_HEART_RATE = "heartRate";
    private static final String KEY_LOCATION = "location";
    private static final String KEY_DATE = "date";
    private static final String KEY_ID = "id";

    // 状態
    private enum State {
        MAIN,
        WARNING,
        EMERGENCY,
    }

    private final int permissionRequestCode = (int) (Integer.MAX_VALUE * Math.random());

    private Vibrator vibrator;
    private Ringtone ringtone;
    private GoogleApiClient googleApiClient;
    private static HitoeWrapper hitoe;

    private Handler handler;
    private Handler timer;
    private CountDownTimer callTimer;

    private State state = State.MAIN;

    // 現在位置
    private volatile Location location;
    // hitoe の準備が終わっているか
    private boolean hitoeReady;
    // 計測した心拍数
    private volatile Pair<Long, Integer> heartrate;
    // 心拍数を表示する部品
    private volatile TextView heartrateView;
    // 通報の識別番号
    private int reportId = Math.abs((int) System.nanoTime());

    // 警告文の表示場所
    private TextView warningView;

    private Actor actor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初回に actor ID を生成する
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final String actorSuffix = preferences.getString(getString(R.string.key_actor_suffix), null);
        if (actorSuffix == null) {
            preferences.edit().putString(getString(R.string.key_actor_suffix), String.valueOf(Math.abs((new Random(System.nanoTime())).nextInt()))).apply();
        }

        this.vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        this.ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        this.googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                                ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }
                        LocationServices.FusedLocationApi.requestLocationUpdates(
                                MainActivity.this.googleApiClient,
                                LocationRequest.create()
                                        .setInterval(LOCATION_INTERVAL)
                                        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY),
                                location -> {
                                    MainActivity.this.location = location;
                                    Log.d(LOG_TAG, "Location changed to " + location);
                                });
                        Log.d(LOG_TAG, "Location monitor started");
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        Log.d(LOG_TAG, "Location monitor suspended");
                    }
                })
                .addOnConnectionFailedListener(connectionResult -> {
                    final String warning = "Location detection error: " + connectionResult;
                    MainActivity.this.warningView.post(() -> setWarning(warning));
                    Log.w(LOG_TAG, warning);
                })
                .build();
        hitoe = new HitoeWrapper(HitoeSdkAPIImpl.getInstance(this.getApplicationContext()));
        hitoe.setHeartrateReceiver(() -> {
            synchronized (this) {
                // メイン画面から hitoe の準備画面に移るためのボタンを消す
                if (!this.hitoeReady) {
                    this.hitoeReady = true;
                    handler.post(() -> {
                        synchronized (this) {
                            if (this.hitoeReady) {
                                this.disableHitoeSetting();
                            }
                        }
                    });
                }
            }
        }, (date, heartrate) -> {
            this.heartrate = new Pair<>(date, heartrate);
            this.heartrateView.post(() -> this.heartrateView.setText(String.format(Locale.US, "%d", heartrate)));
        });
        hitoe.setDisconnectCallback(() -> {
            synchronized (this) {
                // メイン画面に hitoe の準備画面に移るためのボタンを出す
                this.hitoeReady = false;
                handler.post(() -> {
                    synchronized (this) {
                        if (!this.hitoeReady) {
                            this.enableHitoeSetting();
                        }
                    }
                });
            }
        });
        this.handler = new Handler();
        this.timer = new Handler();
        this.heartrate = new Pair<>(0L, 0);

        // 画面を初期化
        reset();

        // 必要な許可を取得できているか調べる
        checkPermission();
    }

    private synchronized void setWarning(String warning) {
        this.warningView.setText(warning);
    }

    /**
     * hitoe の準備画面に移るボタンを有効にする
     */
    private void enableHitoeSetting() {
        final Button button = (Button) findViewById(R.id.button_hitoe_setting);
        if (button == null) {
            return;
        }
        button.setEnabled(true);
        button.setVisibility(View.VISIBLE);
    }

    /**
     * hitoe の準備画面に移るボタンを無効にする
     */
    private void disableHitoeSetting() {
        final Button button = (Button) findViewById(R.id.button_hitoe_setting);
        if (button == null) {
            return;
        }
        button.setEnabled(false);
        button.setVisibility(View.INVISIBLE);
    }


    /**
     * 必要な許可を取得しているか調べて、取得していなかったら要求する
     */
    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 位置情報には許可が必要。
            this.requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
            }, this.permissionRequestCode);
        } else {
            showPermissionStatus(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != this.permissionRequestCode) {
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

        showPermissionStatus(required.isEmpty());
    }

    /**
     * 許可の取得状態を表示する
     *
     * @param allowed 取得できているなら true
     */
    private void showPermissionStatus(boolean allowed) {
        final String message;
        if (allowed) {
            message = "心拍数の測定と救助要請への位置情報の付加が可能です";
            synchronized (this) {
                if (!this.hitoeReady) {
                    enableHitoeSetting();
                }
            }
        } else {
            message = "心拍数の測定と救助要請への位置情報の付加ができません\nメニューから許可設定を行ってください";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        reset();
        hitoe.disconnect(() -> {
        });
    }

    @Override
    public void onBackPressed() {
        (new FinishDialog()).show(getFragmentManager(), "dialog");
    }

    private void superOnBackPressed() {
        super.onBackPressed();
    }

    /**
     * 終了確認ダイアログ
     */
    public static class FinishDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final MainActivity activity = (MainActivity) getActivity();
            return (new AlertDialog.Builder(activity))
                    .setTitle("終了させますか？")
                    .setPositiveButton("終了させる", (dialog, which) -> activity.superOnBackPressed())
                    .create();
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.item_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (item.getItemId() == R.id.item_allow) {
            checkPermission();
        } else if (item.getItemId() == R.id.item_hitoe_settings) {
            startActivity(new Intent(this, HitoeSettingActivity.class));
        } else if (item.getItemId() == R.id.item_reset) {
            reset();
        } else if (item.getItemId() == R.id.item_call) {
            callAfterDialog();
        } else if (item.getItemId() == R.id.item_timer_start) {
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
        this.timer.removeCallbacksAndMessages(null);
        if (this.callTimer != null) {
            this.callTimer.cancel();
            this.callTimer = null;
        }
        this.vibrator.cancel();
        this.ringtone.stop();
        this.googleApiClient.disconnect();
        final Button hitoeSettingButton = (Button) findViewById(R.id.button_hitoe_setting);
        hitoeSettingButton.setOnClickListener(v -> startActivity(new Intent(this, HitoeSettingActivity.class)));
        if (this.hitoeReady) {
            disableHitoeSetting();
        } else {
            enableHitoeSetting();
        }
        this.heartrateView = (TextView) findViewById(R.id.text_heartrate_value);
        this.heartrateView.setText(String.format(Locale.US, "%d", heartrate.second));

        if (this.actor != null) {
            this.actor.disconnect();
            this.actor = null;
        }

        relayWarningView();

        Log.d(LOG_TAG, "Mode was reset");
    }

    private synchronized void relayWarningView() {
        final TextView old = this.warningView;
        this.warningView = (TextView) findViewById(R.id.text_warning);
        if (old != null) {
            this.warningView.setText(old.getText());
        }
    }

    /**
     * 警告中にする
     */
    private synchronized void warn() {
        if (this.state != State.MAIN) {
            // 初期状態からのみ
            return;
        }

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final long delay = Long.parseLong(sharedPreferences.getString(getString(R.string.key_delay), getString(R.string.default_delay)));

        setContentView(R.layout.activity_warning);
        this.state = State.WARNING;
        this.timer.removeCallbacksAndMessages(null);
        if (this.callTimer != null) {
            this.callTimer.cancel();
        }
        this.callTimer = new CountDownTimer(1_000L * delay, 100) {
            @Override
            public void onTick(long l) {
                final TextView view = (TextView) findViewById(R.id.text_counter_count);
                if (view == null) {
                    return;
                }
                view.setText(String.format(Locale.US, "%d", (int) Math.ceil(l / 1_000.0)));
            }

            @Override
            public void onFinish() {
                call();
            }
        };
        this.callTimer.start();

        this.vibrator.vibrate(new long[]{500, 1_000}, 0);
        this.ringtone.play();
        if (!(this.googleApiClient.isConnecting() || this.googleApiClient.isConnected())) {
            this.googleApiClient.connect();
        }

        findViewById(R.id.button_call).setOnClickListener(view -> callAfterDialog());
        findViewById(R.id.button_stop).setOnClickListener(view -> (new CancelDialog()).show(getFragmentManager(), "dialog"));

        this.heartrateView = (TextView) findViewById(R.id.text_heartrate_value);
        this.heartrateView.setText(String.format(Locale.US, "%d", heartrate.second));

        startReport();

        relayWarningView();

        Log.d(LOG_TAG, "Warning mode started");
    }

    /**
     * 通報キャンセルダイアログ
     */
    public static class CancelDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final MainActivity activity = (MainActivity) getActivity();
            return (new AlertDialog.Builder(activity))
                    .setTitle("異常はありませんか？")
                    .setPositiveButton("異常無し", (dialog, which) -> {
                        synchronized (activity) {
                            if (activity.state == State.WARNING) {
                                activity.reset();
                            }
                        }
                    })
                    .create();
        }
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
        this.timer.removeCallbacksAndMessages(null);
        if (this.callTimer != null) {
            this.callTimer.cancel();
            this.callTimer = null;
        }
        this.vibrator.cancel();
        this.ringtone.stop();
        if (!(this.googleApiClient.isConnecting() || this.googleApiClient.isConnected())) {
            this.googleApiClient.connect();
        }
        this.heartrateView = (TextView) findViewById(R.id.text_heartrate_value);
        this.heartrateView.setText(String.format(Locale.US, "%d", heartrate.second));

        startReport();

        relayWarningView();

        Log.d(LOG_TAG, "Emergency mode started");
    }

    /**
     * ダイアログで確認してから救助要請する
     */
    private void callAfterDialog() {
        (new CallDialog()).show(getFragmentManager(), "dialog");
    }

    /**
     * 通報ダイアログ
     */
    public static class CallDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final MainActivity activity = (MainActivity) getActivity();
            return (new AlertDialog.Builder(activity))
                    .setTitle("救助を要請しますか？")
                    .setPositiveButton("要請する", (dialog, which) -> activity.call())
                    .create();
        }
    }

    /**
     * 異常検知イベントを発生させるタイマーを作動させる
     */
    private synchronized void startEventTimer() {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final long delay = 1_000 * Long.parseLong(sharedPreferences.getString(getString(R.string.key_timer), getString(R.string.default_timer)));

        this.timer.removeCallbacksAndMessages(null);
        this.timer.postDelayed(() -> {
            warn();
            Log.d(LOG_TAG, "Dummy emergency was triggered");
        }, delay);
        Log.d(LOG_TAG, "Event timer started");
    }

    /**
     * サーバーへの報告を始める
     */
    private synchronized void startReport() {
        if (this.actor != null) {
            Log.d(LOG_TAG, "Already connecting");
            return;
        }
        this.reportId++;
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final String server = sharedPreferences.getString(getString(R.string.key_server), getString(R.string.default_server));
        final String actorKey = getString(R.string.actor_prefix) + sharedPreferences.getString(getString(R.string.key_actor_suffix), getString(R.string.default_actor_suffix));
        final long interval = 1_000L * Long.parseLong(sharedPreferences.getString(getString(R.string.key_report_interval), getString(R.string.default_report_interval)));

        this.actor = new Actor(actorKey, getString(R.string.module), null);
        final Emitter emitter;
        try {
            emitter = actor.addModule(getString(R.string.module), getPackageManager().getPackageInfo(this.getPackageName(), 0).versionName, getString(R.string.description), new Object());
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        actor.setOnConnect(() -> report(emitter, interval));
        actor.connect(server);
    }

    private synchronized void report(Emitter emitter, long interval) {
        if (this.actor == null) {
            // 終了
            return;
        }

        final Map<String, Object> data = new HashMap<>();
        final Pair<Long, Integer> heartrate = this.heartrate;
        data.put(KEY_ID, this.reportId);
        data.put(KEY_DATE, (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.US)).format(new Date(heartrate.first)));
        data.put(KEY_HEART_RATE, heartrate.second);
        final Location curLocation = this.location;
        if (curLocation != null) {
            data.put(KEY_LOCATION, Arrays.asList(curLocation.getLatitude(), curLocation.getLongitude(), curLocation.getAltitude()));
        } else {
            data.put(KEY_LOCATION, Arrays.asList(0, 0, 0));
        }
        emitter.emit(this.state.name().toLowerCase(), data);
        Log.d(LOG_TAG, "Sent report");

        this.handler.postDelayed(() -> report(emitter, interval), interval);
    }

    /**
     * hitoe のドライバを返す
     *
     * @return hitoe のドライバ
     */
    static HitoeWrapper getHitoe() {
        return hitoe;
    }

}
