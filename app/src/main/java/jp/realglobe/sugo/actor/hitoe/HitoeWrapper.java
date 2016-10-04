package jp.realglobe.sugo.actor.hitoe;

import android.support.annotation.NonNull;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jp.ne.docomo.smt.dev.hitoetransmitter.HitoeSdkAPI;

/**
 * HitoeSdkAPI の一部を同期的に扱えるようにするラッパー
 * Created by fukuchidaisuke on 16/10/04.
 */
class HitoeWrapper {

    /**
     * connect の結果
     */
    enum ConnectResult {
        OK,
        NOT_FOUND,
        INVALID_PINCODE,
        RECEIVER_ERROR,
    }

    private static final String GET_AVAILABLE_SENSOR_DEVICE_TYPE = "hitoe D01";
    private static final String GET_AVAILABLE_SENSOR_PARAM_SEARCH_TIME = "search_time";

    private static final String CONNECT_PARAM_PINCODE = "pincode";

    private static final int API_ID_GET_AVAILABLE_SENSOR = 0x1010;
    private static final int API_ID_CONNECT = 0x1020;
    private static final int API_ID_DISCONNECT = 0x1021;
    private static final int API_ID_GET_AVAILABLE_DATA = 0x1030;
    private static final int ADI_ID_ADD_RECEIVER = 0x1040;
    private static final int API_ID_REMOVE_RECEIVER = 0x1041;
    private static final int API_ID_GET_STATUS = 0x1090;

    private static final int RES_ID_SUCCESS = 0x00;
    private static final int RES_ID_FAILURE = 0x01;
    private static final int RES_ID_CONTINUE = 0x05;
    private static final int RES_ID_API_BUSY = 0x09;
    private static final int RES_ID_INVALID_ARG = 0x10;
    private static final int RES_ID_INVALID_PARAM = 0x30;
    private static final int RES_ID_SENSOR_CONNECT = 0x60;
    private static final int RES_ID_SENSOR_CONNECT_FAILURE = 0x61;
    private static final int RES_ID_SENSOR_CONNECT_NOTICE = 0x62;
    private static final int RES_ID_SENSOR_UNAUTHORIZED = 0x63;
    private static final int RES_ID_SENSOR_DISCONNECT = 0x65;
    private static final int RES_ID_SENSOR_DISCONNECT_NOTICE = 0x66;

    private static final String INFO_SENSOR_SEPARATOR = "(\\n|\\r|\\n\\r)";
    private static final String INFO_COLUMN_SEPARATOR = ",";
    private static final String INFO_DATAKEYS_SEPARATOR = "\\|";
    private static final int INFO_COLUMN_NUMBER = 5;
    private static final int INFO_TYPE_COLUMN = 0;
    private static final int INFO_ID_COLUMN = 1;
    private static final int INFO_ADDRESS_COLUMN = 2;
    private static final int INFO_MODE_COLUMN = 3;
    private static final int INFO_DATAKEYS_COLUMN = 4;

    private static final String MODE_REALTIME = "realtime";

    private static final String DATA_KEY_HR = "raw.hr";

    private static final String DATA_HR_COLUMN_SEPARATOR = ",";
    private static final int DATA_HR_COLUMN_NUMBER = 2;
    private static final int DATA_HR_BPM_COLUMN = 1;


    private static final String LOG_TAG = HitoeWrapper.class.getName();

    // BlockingQueue に null を入れられないため。
    private static final String NULL = "null";

    private final HitoeSdkAPI core;
    private volatile Runnable disconnectCallback;
    private volatile HeartrateReceiver heartrateReceiver;

    private final BlockingQueue<String> infoQueue;
    private final BlockingQueue<String> sessionQueue;
    private final BlockingQueue<String> connectionQueue;

    // 接続しているセンサー
    private SensorInfo sensor;
    // 接続のセッション
    private String session;
    // レシーバーの登録 ID
    private String connection;

    HitoeWrapper(HitoeSdkAPI core) {
        this.core = core;
        this.infoQueue = new ArrayBlockingQueue<>(1);
        this.sessionQueue = new ArrayBlockingQueue<>(1);
        this.connectionQueue = new ArrayBlockingQueue<>(1);

        this.core.setAPICallback(this::callback);
    }

    void setDisconnectCallback(Runnable callback) {
        this.disconnectCallback = callback;
    }

    void setHeartrateReceiver(HeartrateReceiver receiver) {
        this.heartrateReceiver = receiver;
    }

    private void callback(int apiId, int responseId, String response) {
        Log.d(LOG_TAG, "apiId=" + apiId + ",responseId=" + responseId + ",response=" + response);

        try {
            callbackCore(apiId, responseId, response);
        } catch (DisconnectException e) {
            final Runnable disconnectCallback;
            synchronized (this) {
                if (!e.getSession().equals(this.session)) {
                    return;
                }
                this.sensor = null;
                this.session = null;
                this.connection = null;
                disconnectCallback = this.disconnectCallback;
            }
            if (disconnectCallback != null) {
                disconnectCallback.run();
            }
        }
    }

    private void callbackCore(int apiId, int responseId, String response) throws DisconnectException {
        switch (apiId) {
            case API_ID_GET_AVAILABLE_SENSOR:
                final String info;
                switch (responseId) {
                    case RES_ID_SUCCESS:
                        info = response;
                        break;

                    default:
                        info = NULL;
                }
                queueOffer(this.infoQueue, info);
                break;

            case API_ID_CONNECT:
                final String session;
                switch (responseId) {
                    case RES_ID_SENSOR_CONNECT:
                        session = response;
                        break;

                    case RES_ID_SENSOR_CONNECT_FAILURE:
                        session = NULL;
                        break;

                    case RES_ID_SENSOR_UNAUTHORIZED:
                        session = "";
                        break;

                    case RES_ID_SENSOR_DISCONNECT_NOTICE:
                        throw new DisconnectException(response);

                    default:
                        session = NULL;
                }
                queueOffer(this.sessionQueue, session);
                break;

            case ADI_ID_ADD_RECEIVER:
                final String connection;
                switch (responseId) {
                    case RES_ID_SUCCESS:
                        connection = response;
                        break;

                    default:
                        connection = NULL;
                }
                queueOffer(this.connectionQueue, connection);
                break;

            case API_ID_DISCONNECT:
                throw new DisconnectException(response);
        }
    }

    private <E> void queueOffer(BlockingQueue<E> queue, E elem) {
        while (!queue.offer(elem)) {
            queue.clear();
        }
    }

    private boolean isNull(String value) {
        return value == null || value == NULL;
    }

    /**
     * センサーを探す
     *
     * @param searchTime 探索時間（ミリ秒）
     * @return センサー情報のリスト
     * @throws InterruptedException 終了させられた時
     */
    synchronized List<SensorInfo> search(long searchTime) throws InterruptedException {
        Log.d(LOG_TAG, "Search sensors");

        this.infoQueue.clear();
        int responseId = this.core.getAvailableSensor(GET_AVAILABLE_SENSOR_DEVICE_TYPE, GET_AVAILABLE_SENSOR_PARAM_SEARCH_TIME + "=" + searchTime);
        if (responseId != RES_ID_SUCCESS) {
            throw new RuntimeException("Cannot search sensors");
        }
        final String response = this.infoQueue.poll(2 * searchTime, TimeUnit.MILLISECONDS);

        if (isNull(response) || response.isEmpty()) {
            Log.d(LOG_TAG, "No raw sensors were found");
            return Collections.emptyList();
        }

        final List<SensorInfo> sensors = new ArrayList<>();
        for (String line : response.split(INFO_SENSOR_SEPARATOR)) {
            Log.d(LOG_TAG, "Raw sensor " + line + " was found");
            final SensorInfo sensor;
            try {
                sensor = SensorInfo.parse(line);
            } catch (IllegalArgumentException e) {
                Log.w(LOG_TAG, e.toString());
                continue;
            }
            if (sensor.getMode().equals(MODE_REALTIME) &&
                    sensor.getDataKeys().contains(DATA_KEY_HR)) {
                sensors.add(sensor);
            }
        }
        return sensors;
    }

    /**
     * センサーとセッションを確立する。
     *
     * @param sensor  セッションを確立するセンサーの情報
     * @param pincode ピンコード
     * @param timeout 待ち時間（ミリ秒）
     * @return 結果
     * @throws InterruptedException 終了させられた時
     */
    synchronized ConnectResult connect(SensorInfo sensor, String pincode, long timeout) throws InterruptedException {
        if (this.sensor != null) {
            if (this.sensor.equals(sensor)) {
                // もう接続してる
                if (this.connection == null && this.heartrateReceiver != null) {
                    this.connection = setHeartrateReceiver(this.session, this.heartrateReceiver, timeout);
                    if (this.heartrateReceiver == null) {
                        return ConnectResult.RECEIVER_ERROR;
                    }
                    return ConnectResult.OK;
                }
                return ConnectResult.OK;
            }

            // 別のセンサーと接続してる
            if (this.connection != null) {
                this.core.removeReceiver(this.connection);
            }
            this.core.disconnect(this.session);
            this.sensor = null;
            this.session = null;
            this.connection = null;
        }

        Log.d(LOG_TAG, "Connect to the sensor [" + sensor + "]");

        this.sessionQueue.clear();
        int responseId = this.core.connect(sensor.getType(), sensor.getAddress(), sensor.getMode(), CONNECT_PARAM_PINCODE + "=" + pincode);
        if (responseId != RES_ID_SUCCESS) {
            throw new RuntimeException("Cannot connect to the sensor [" + sensor + "]");
        }
        final String session = this.sessionQueue.poll(timeout, TimeUnit.MILLISECONDS);

        if (isNull(session)) {
            return ConnectResult.NOT_FOUND;
        } else if (session.isEmpty()) {
            return ConnectResult.INVALID_PINCODE;
        }
        this.sensor = sensor;
        this.session = session;
        this.connection = setHeartrateReceiver(this.session, this.heartrateReceiver, timeout);
        if (this.heartrateReceiver == null) {
            return ConnectResult.RECEIVER_ERROR;
        }
        return ConnectResult.OK;
    }

    /**
     * 心拍数レシーバーを登録する
     *
     * @param session  レシーバーを登録するセッションの ID
     * @param receiver レシーバー
     * @param timeout  待ち時間（ミリ秒）
     * @return 登録 ID。失敗なら null
     * @throws InterruptedException 終了させられた時
     */
    private String setHeartrateReceiver(String session, HeartrateReceiver receiver, long timeout) throws InterruptedException {
        Log.d(LOG_TAG, "Set heartrate receiver on session " + session);

        this.connectionQueue.clear();
        int responseId = this.core.addReceiver(session, new String[]{DATA_KEY_HR}, (connection, responseId1, dataKey, data) -> {
            final String[] columns = data.split(DATA_HR_COLUMN_SEPARATOR);
            if (columns.length < DATA_HR_COLUMN_NUMBER) {
                return;
            }
            int heartrate = (int) Double.parseDouble(columns[DATA_HR_BPM_COLUMN]);
            receiver.receive(heartrate);
        }, "", "");
        if (responseId != RES_ID_SUCCESS) {
            throw new RuntimeException("Cannot add heartrate receiver on " + session);
        }
        final String connection = this.connectionQueue.poll(timeout, TimeUnit.MILLISECONDS);

        if (isNull(connection)) {
            return null;
        }
        return connection;
    }

    static class SensorInfo {

        private final String type;
        private final String id;
        private final String address;
        private final String mode;
        private final Set<String> dataKeys;

        SensorInfo(@NonNull String type, @NonNull String id, @NonNull String address, @NonNull String mode, @NonNull Collection<String> dataKeys) {
            this.type = type;
            this.id = id;
            this.address = address;
            this.mode = mode;
            this.dataKeys = new HashSet<>(dataKeys);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SensorInfo that = (SensorInfo) o;
            return type.equals(that.type) && id.equals(that.id) && address.equals(that.address) && mode.equals(that.mode) && dataKeys.equals(that.dataKeys);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + id.hashCode();
            result = 31 * result + address.hashCode();
            result = 31 * result + mode.hashCode();
            result = 31 * result + dataKeys.hashCode();
            return result;
        }

        String getType() {
            return type;
        }

        String getId() {
            return id;
        }

        String getAddress() {
            return address;
        }

        String getMode() {
            return mode;
        }

        Set<String> getDataKeys() {
            return dataKeys;
        }

        @Override
        public String toString() {
            final List<String> list = new ArrayList<>(this.dataKeys);
            Collections.sort(list);
            StringBuilder dataKeysStr = new StringBuilder();
            for (String dataKey : list) {
                if (dataKeysStr.length() > 0) {
                    dataKeysStr.append("|");
                }
                dataKeysStr.append(dataKey);
            }
            return this.type + "," + this.id + "," + this.address + "," + this.mode + "," + dataKeysStr.toString();
        }

        static SensorInfo parse(String str) {
            final String[] tokens = str.split(INFO_COLUMN_SEPARATOR);
            if (tokens.length != INFO_COLUMN_NUMBER) {
                throw new IllegalArgumentException("invalid sensor info " + str + " " + tokens.length);
            }
            return new SensorInfo(tokens[INFO_TYPE_COLUMN], tokens[INFO_ID_COLUMN], tokens[INFO_ADDRESS_COLUMN], tokens[INFO_MODE_COLUMN], Arrays.asList(tokens[INFO_DATAKEYS_COLUMN].split(INFO_DATAKEYS_SEPARATOR)));
        }

    }

    private static class DisconnectException extends Exception {

        private final String session;

        DisconnectException(String session) {
            super();

            this.session = session;
        }


        String getSession() {
            return this.session;
        }

    }

    interface HeartrateReceiver {
        /**
         * 心拍数を受け取る
         *
         * @param heartrate 心拍数
         */
        void receive(int heartrate);
    }
}
