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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jp.realglobe.sugo.actor.android.hitoe.R;

/**
 * 心拍センサーを準備する
 */
public class HitoeSettingActivity extends AppCompatActivity {

    private static final String LOG_TAG = HitoeSettingActivity.class.getName();

    private static final long SEARCH_TIME = 5_000;
    private static final long BACK_DELAY = 3_000;

    private HitoeWrapper hitoe;

    private SharedPreferences preferences;

    private Handler handler;
    private TextView messageView;
    private Button searchButton;
    private Button backButton;
    private TextView backPrefixView;
    private TextView backCountView;
    private TextView backSuffixView;
    private CountDownTimer backTimer;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hitoe_setting);

        this.hitoe = MainActivity.getHitoe();

        this.preferences = PreferenceManager.getDefaultSharedPreferences(this);

        this.handler = new Handler();
        this.messageView = (TextView) findViewById(R.id.text_hitoe_message);

        this.searchButton = (Button) findViewById(R.id.button_search);
        this.searchButton.setEnabled(false);
        this.searchButton.setVisibility(View.INVISIBLE);
        this.searchButton.setOnClickListener(v -> {
            stopBackTimer();
            searchAfterDisconnect();
        });

        this.backButton = (Button) findViewById(R.id.button_back);
        this.backButton.setEnabled(false);
        this.backButton.setVisibility(View.INVISIBLE);
        this.backButton.setOnClickListener(v -> onBackPressed());

        this.backPrefixView = (TextView) findViewById(R.id.text_back_prefix);
        this.backCountView = (TextView) findViewById(R.id.text_back_count);
        this.backSuffixView = (TextView) findViewById(R.id.text_back_suffix);

        this.handler.post(() -> {
            stopBackTimer();
            check();
        });
    }

    /**
     * 自動的に戻るタイマーを開始する
     */
    private synchronized void startBackTimer() {
        if (this.backTimer != null) {
            this.backTimer.cancel();
        }
        this.backPrefixView.setVisibility(View.VISIBLE);
        this.backCountView.setVisibility(View.VISIBLE);
        this.backSuffixView.setVisibility(View.VISIBLE);
        this.backTimer = new CountDownTimer(BACK_DELAY, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                HitoeSettingActivity.this.backCountView.setText(String.format(Locale.US, "%d", (int) Math.ceil(millisUntilFinished / 1_000.0)));
            }

            @Override
            public void onFinish() {
                onBackPressed();
            }
        };
        this.backTimer.start();
    }

    /**
     * 自動的に戻るタイマーを止める
     */
    private synchronized void stopBackTimer() {
        if (this.backTimer != null) {
            this.backTimer.cancel();
            this.backTimer = null;
        }
        this.backPrefixView.setVisibility(View.INVISIBLE);
        this.backCountView.setVisibility(View.INVISIBLE);
        this.backSuffixView.setVisibility(View.INVISIBLE);
    }

    /**
     * 準備ができているか調べる
     */
    private void check() {
        this.hitoe.getStatus(sensorId -> {
            if (sensorId != null) {
                // もう接続してる
                String message = sensorId + " と接続しています";
                this.messageView.post(() -> this.messageView.setText(message));
                this.searchButton.post(() -> {
                    this.searchButton.setEnabled(true);
                    this.searchButton.setVisibility(View.VISIBLE);
                });
                this.backButton.post(() -> {
                    this.backButton.setEnabled(true);
                    this.backButton.setVisibility(View.VISIBLE);
                });
                return;
            }
            start();
        });
    }

    /**
     * 準備を開始する
     */
    private void start() {
        final String sensorStr = preferences.getString(getString(R.string.key_hitoe_sensor), null);
        if (sensorStr == null) {
            // 探索する
            search();
            return;
        }

        final HitoeWrapper.SensorInfo sensor;
        try {
            sensor = HitoeWrapper.SensorInfo.parse(sensorStr);
        } catch (IllegalArgumentException e) {
            // 探索からやり直し
            Log.d(LOG_TAG, "Last sensor info is broken");
            this.handler.post(this::searchAfterDisconnect);
            return;
        }
        connectAfterDialog(sensor);
    }

    /**
     * センサーを探す
     */
    private void search() {
        Log.d(LOG_TAG, "Search sensors");
        this.messageView.post(() -> this.messageView.setText("心拍センサーを探します"));

        this.searchButton.post(() -> {
            this.searchButton.setEnabled(false);
            this.searchButton.setVisibility(View.INVISIBLE);
        });
        this.hitoe.search(SEARCH_TIME, this::requestSensorSelection);
    }

    /**
     * ダイアログで確認してから接続する
     */
    private void connectAfterDialog(HitoeWrapper.SensorInfo sensor) {
        if (isDestroyed()) {
            return;
        }
        DialogFragment dialog = ConnectDialog.newInstance(sensor);
        dialog.setCancelable(false);
        dialog.show(getFragmentManager(), "dialog");
    }

    /**
     * 接続確認ダイアログ
     */
    public static class ConnectDialog extends DialogFragment {
        private static final String KEY_SENSOR = "sensor";

        static ConnectDialog newInstance(HitoeWrapper.SensorInfo sensor) {
            final Bundle args = new Bundle();
            args.putString(KEY_SENSOR, sensor.toString());

            final ConnectDialog dialog = new ConnectDialog();
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final HitoeWrapper.SensorInfo sensor = HitoeWrapper.SensorInfo.parse(getArguments().getString(KEY_SENSOR));
            final HitoeSettingActivity activity = (HitoeSettingActivity) getActivity();
            final View view = activity.getLayoutInflater().inflate(R.layout.dialog_connect, null);
            ((TextView) view.findViewById(R.id.text_sensor)).setText(sensor.getId());
            return (new AlertDialog.Builder(activity))
                    .setTitle("心拍センサーと接続します")
                    .setView(view)
                    .setPositiveButton("OK", (dialog, which) -> activity.connect(sensor))
                    .setNegativeButton("心拍センサーを探し直す", (dialog, which) -> activity.searchAfterDisconnect())
                    .create();
        }
    }

    /**
     * 最初からやり直す
     */
    private void searchAfterDisconnect() {
        this.hitoe.disconnect(this::search);
    }

    /**
     * 接続する
     *
     * @param sensor 接続先
     */
    private void connect(HitoeWrapper.SensorInfo sensor) {
        preferences.edit().putString(getString(R.string.key_hitoe_sensor), sensor.toString()).apply();

        Log.d(LOG_TAG, "Connect to " + sensor);
        this.messageView.post(() -> this.messageView.setText("心拍センサー " + sensor.getId() + " に接続します"));

        final String pincode = preferences.getString(getString(R.string.key_prefix_hitoe_pincode) + sensor.toString(), null);
        if (pincode == null) {
            requestPincode(sensor);
            return;
        }

        connect(sensor, pincode);
    }

    /**
     * 接続する
     *
     * @param sensor  接続先
     * @param pincode ピンコード
     */
    private void connect(HitoeWrapper.SensorInfo sensor, String pincode) {
        preferences.edit().putString(getString(R.string.key_prefix_hitoe_pincode) + sensor.toString(), pincode).apply();

        Log.d(LOG_TAG, "Use pincode " + pincode);

        hitoe.connect(sensor, pincode, result -> {
            switch (result) {
                case OK:
                    break;
                case NOT_FOUND:
                    // 探索からやり直し
                    Log.d(LOG_TAG, "Could not connect to sensor " + sensor);
                    this.handler.post(this::searchAfterDisconnect);
                    return;
                case INVALID_PINCODE:
                    // ピンコード間違い
                    Log.d(LOG_TAG, "Invalid pincode for sensor " + sensor);
                    preferences.edit().remove(getString(R.string.key_prefix_hitoe_pincode) + sensor.toString()).apply();
                    this.handler.post(() -> requestPincode(sensor));
                    return;
                case RECEIVER_ERROR:
                    // もう1回
                    Log.d(LOG_TAG, "Setting receiver failed for sensor " + sensor);
                    this.handler.post(() -> connect(sensor, pincode));
                    return;
            }

            Log.d(LOG_TAG, "Connected to sensor " + sensor);
            this.messageView.post(() -> this.messageView.setText("心拍センサー " + sensor.getId() + " に接続しました"));
            this.searchButton.post(() -> {
                this.searchButton.setEnabled(true);
                this.searchButton.setVisibility(View.VISIBLE);
            });
            this.backButton.post(() -> {
                this.backButton.setEnabled(true);
                this.backButton.setVisibility(View.VISIBLE);
            });
            this.handler.post(this::startBackTimer);
        });
    }

    /**
     * ユーザーにセンサーを選択させる
     *
     * @param sensors 候補
     */
    private void requestSensorSelection(List<HitoeWrapper.SensorInfo> sensors) {
        if (isDestroyed()) {
            return;
        }
        final DialogFragment dialog = SensorSelectionDialog.newInstance(sensors);
        dialog.setCancelable(false);
        dialog.show(getFragmentManager(), "dialog");
    }

    /**
     * センサーを選択させるダイアログ
     */
    public static class SensorSelectionDialog extends DialogFragment {

        private static final String KEY_ITEMS = "items";

        static SensorSelectionDialog newInstance(@NonNull Collection<HitoeWrapper.SensorInfo> sensors) {
            final Bundle args = new Bundle();
            final ArrayList<String> items = new ArrayList<>();
            for (HitoeWrapper.SensorInfo sensor : sensors) {
                items.add(sensor.toString());
            }
            args.putStringArrayList(KEY_ITEMS, items);

            final SensorSelectionDialog dialog = new SensorSelectionDialog();
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final List<String> items = getArguments().getStringArrayList(KEY_ITEMS);
            final HitoeSettingActivity activity = (HitoeSettingActivity) getActivity();
            if (items == null || items.isEmpty()) {
                return (new AlertDialog.Builder(activity))
                        .setTitle("心拍センサーが見つかりませんでした")
                        .setPositiveButton("心拍センサーを探し直す", (dialog, which) -> activity.searchAfterDisconnect())
                        .create();
            }
            final List<String> ids = new ArrayList<>();
            final Map<String, HitoeWrapper.SensorInfo> sensors = new HashMap<>();
            for (String item : items) {
                final HitoeWrapper.SensorInfo sensor = HitoeWrapper.SensorInfo.parse(item);
                ids.add(sensor.getId());
                sensors.put(sensor.getId(), sensor);
            }
            return (new AlertDialog.Builder(activity))
                    .setCancelable(false)
                    .setTitle("どの心拍センサーで測定しますか？")
                    .setItems(ids.toArray(new String[ids.size()]), (dialog, which) -> activity.connect(sensors.get(ids.get(which))))
                    .setNegativeButton("心拍センサーを探し直す", (dialog, which) -> activity.searchAfterDisconnect())
                    .create();
        }
    }

    /**
     * ユーザーにピンコードを入力させる
     *
     * @param sensor 対象の心拍センサー
     */
    private void requestPincode(HitoeWrapper.SensorInfo sensor) {
        if (isDestroyed()) {
            return;
        }
        final DialogFragment dialog = PincodeDialog.newInstance(sensor);
        dialog.setCancelable(false);
        dialog.show(getFragmentManager(), "dialog");
    }

    /**
     * ピンコードを入力させるダイアログ
     */
    public static class PincodeDialog extends DialogFragment {

        private static final String KEY_SENSOR = "sensor";

        static DialogFragment newInstance(HitoeWrapper.SensorInfo sensor) {
            final Bundle args = new Bundle();
            args.putString(KEY_SENSOR, sensor.toString());

            final PincodeDialog dialog = new PincodeDialog();
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final HitoeWrapper.SensorInfo sensor = HitoeWrapper.SensorInfo.parse(getArguments().getString(KEY_SENSOR));
            final HitoeSettingActivity activity = (HitoeSettingActivity) getActivity();
            final View view = activity.getLayoutInflater().inflate(R.layout.dialog_pincode, null);
            return (new AlertDialog.Builder(activity))
                    .setView(view)
                    .setTitle(sensor.getId() + "のピンコードを入力してください")
                    .setPositiveButton("OK", (dialog, whichButton) -> activity.connect(sensor, ((EditText) view.findViewById(R.id.edit_pincode)).getText().toString()))
                    .setNegativeButton("心拍センサーを探し直す", (dialog, whichButton) -> activity.searchAfterDisconnect())
                    .create();
        }
    }

}
