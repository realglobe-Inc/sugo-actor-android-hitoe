package jp.realglobe.sugo.actor.hitoe;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = MainActivity.class.getName();

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.handler = new Handler();
        this.timerHandler = new Handler();
        this.vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        this.ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));

        reset();

        this.executor.submit(this::runForEvent);
        Log.d(LOG_TAG, "Event waiter started");
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
        final int delay = Integer.parseInt(sharedPreferences.getString("key_delay", "30"));

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
        final long delay = 1_000L * Integer.parseInt(sharedPreferences.getString("key_timer", "300"));
        this.timerHandler.postDelayed(() -> {
            MainActivity.this.eventQueue.offer("dummy");
            Log.d(LOG_TAG, "Dummy emergency event was generated");
        }, delay);
        Log.d(LOG_TAG, "Event timer started");
    }

}
