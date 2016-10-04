package jp.realglobe.sugo.actor.hitoe;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HitoeSettingActivity extends AppCompatActivity {

    private static final String LOG_TAG = HitoeSettingActivity.class.getName();

    private static final String NULL = "";

    private static final long CONNECTION_TIME = 60_000;
    private static final long SEARCH_TIME = 5_000;

    private TextView messageView;
    private Button backButton;
    private Handler handler;

    private HitoeWrapper hitoe;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hitoe_setting);
        this.messageView = (TextView) findViewById(R.id.text_hitoe_message);
        this.backButton = (Button) findViewById(R.id.button_back);
        this.backButton.setEnabled(false);
        this.backButton.setVisibility(View.INVISIBLE);
        this.backButton.setOnClickListener(v -> {
            onBackPressed();
        });
        this.handler = new Handler();

        this.hitoe = MainActivity.getHitoe();
        this.executor.submit(this::runForConnect);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.executor.shutdownNow();
    }

    /**
     * hitoe に接続する
     */
    private void runForConnect() {
        while (true) {
            try {
                if (connect()) {
                    this.backButton.post(() -> {
                        HitoeSettingActivity.this.backButton.setEnabled(true);
                        HitoeSettingActivity.this.backButton.setVisibility(View.VISIBLE);
                    });
                    break;
                }
            } catch (InterruptedException e) {
                // 終了
                break;
            }
        }
    }

    /**
     * hitoe に接続する
     *
     * @return 接続できたら true
     * @throws InterruptedException 終了信号を受け取った
     */
    private boolean connect() throws InterruptedException {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        final HitoeWrapper.SensorInfo sensor;
        final String sensorStr = sharedPreferences.getString(getString(R.string.key_hitoe_sensor), null);
        if (sensorStr != null) {
            try {
                sensor = HitoeWrapper.SensorInfo.parse(sensorStr);
            } catch (IllegalArgumentException e) {
                // 探索からやり直し
                Log.d(LOG_TAG, "Last sensor info is broken");
                sharedPreferences.edit().remove(getString(R.string.key_hitoe_sensor)).apply();
                return false;
            }
        } else {
            Log.d(LOG_TAG, "Search sensors");
            this.messageView.post(() -> this.messageView.setText("心拍センサーを探します"));

            final List<HitoeWrapper.SensorInfo> sensors = this.hitoe.search(SEARCH_TIME);
            final int n = requestSensorSelection(sensors);
            if (n < 0 || sensors.size() <= n) {
                // もう一度探索からやり直し
                Log.d(LOG_TAG, "Retry sensor search");
                return false;
            }
            sensor = sensors.get(n);
            sharedPreferences.edit().putString(getString(R.string.key_hitoe_sensor), sensor.toString()).apply();
        }

        Log.d(LOG_TAG, "Connect to " + sensor);
        this.messageView.post(() -> this.messageView.setText("心拍センサー " + sensor + " に接続します"));

        final String pincodeKey = getString(R.string.key_prefix_hitoe_pincode) + sensor.toString();
        String pincode = sharedPreferences.getString(pincodeKey, null);
        if (pincode == null) {
            pincode = requestPincode(sensor);
            if (pincode == null) {
                // 探索からやり直し
                Log.d(LOG_TAG, "Retry from sensor search");
                sharedPreferences.edit().remove(getString(R.string.key_hitoe_sensor)).apply();
                return false;
            }
        }

        Log.d(LOG_TAG, "Use pincode " + pincode);

        final HitoeWrapper.ConnectResult result = hitoe.connect(sensor, pincode, CONNECTION_TIME);
        switch (result) {
            case OK:
                break;
            case NOT_FOUND:
                // 探索からやり直し
                Log.d(LOG_TAG, "Could not connect to sensor " + sensor);
                sharedPreferences.edit().remove(getString(R.string.key_hitoe_sensor)).apply();
                return false;
            case INVALID_PINCODE:
                // ピンコード間違い
                Log.d(LOG_TAG, "Invalid pincode for sensor " + sensor);
                sharedPreferences.edit().remove(pincodeKey).apply();
                return false;
            case RECEIVER_ERROR:
                // もう1回
                Log.d(LOG_TAG, "Setting receiver failed for sensor " + sensor);
                return false;
        }

        Log.d(LOG_TAG, "Connected to sensor " + sensor);
        this.messageView.post(() -> this.messageView.setText("心拍センサー " + sensor + " に接続しました"));

        sharedPreferences.edit().putString(pincodeKey, pincode).apply();
        return true;
    }

    /**
     * ユーザーに心拍センサーを選択させる
     *
     * @param sensors 候補
     * @return 選ばれた候補の番号
     * 候補がお気に召さなかった場合は -1
     */
    private int requestSensorSelection(List<HitoeWrapper.SensorInfo> sensors) throws InterruptedException {
        final String[] items = new String[sensors.size() + 1];
        for (int i = 0; i < sensors.size(); i++) {
            items[i] = sensors.get(i).toString();
        }
        items[items.length - 1] = "心拍センサーを探し直す";

        final String title = sensors.isEmpty() ? "心拍センサーが見つかりませんでした" : "どの心拍センサーで測定しますか？";
        final BlockingQueue<Integer> resultQueue = new ArrayBlockingQueue<>(1);
        this.handler.post(() -> (new AlertDialog.Builder(this))
                .setCancelable(false)
                .setTitle(title)
                .setItems(items, (dialog, which) -> {
                    resultQueue.offer(which);
                })
                .show());
        final int result = resultQueue.take();
        if (result >= sensors.size()) {
            return -1;
        }
        return result;
    }

    /**
     * ユーザーにピンコードを入力させる
     *
     * @param sensor 対象の心拍センサー
     * @return ピンコード
     * 対象の心拍センサーがお気に召さなかった場合は null
     */
    private String requestPincode(HitoeWrapper.SensorInfo sensor) throws InterruptedException {
        final BlockingQueue<String> resultQueue = new ArrayBlockingQueue<>(1);
        final View view = getLayoutInflater().inflate(R.layout.dialog_pincode, null);
        this.handler.post(() -> (new AlertDialog.Builder(this))
                .setCancelable(false)
                .setView(view)
                .setTitle(sensor + "のピンコードを入力してください")
                .setPositiveButton("OK", (dialog, whichButton) -> {
                    resultQueue.offer(((EditText) view.findViewById(R.id.edit_pincode)).getText().toString());
                })
                .setNegativeButton("心拍センサーを探し直す", (dialog, whichButton) -> {
                    resultQueue.offer(NULL);
                })
                .show());
        final String result = resultQueue.take();
        if (result == NULL) {
            return null;
        }
        return result;
    }

}
