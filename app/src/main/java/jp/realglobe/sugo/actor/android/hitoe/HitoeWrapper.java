package jp.realglobe.sugo.actor.android.hitoe;

import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.ne.docomo.smt.dev.hitoetransmitter.HitoeSdkAPI;

/**
 * HitoeSdkAPI のラッパー
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

    private static final String ROW_SEPARATOR = "(\\n|\\r|\\n\\r)";
    private static final String COLUMN_SEPARATOR = ",";
    private static final String DATAKEYS_SEPARATOR = "\\|";

    private static final int STATUS_COLUMN_NUMBER = 6;
    private static final int STATUS_SESSION_COLUMN = 0;
    private static final int STATUS_ID_COLUMN = 1;
    private static final int STATUS_CONNECTION_COLUMN = 2;
    private static final int STATUS_DATAKEYS_COLUMN = 3;

    private static final int INFO_COLUMN_NUMBER = 5;
    private static final int INFO_TYPE_COLUMN = 0;
    private static final int INFO_ID_COLUMN = 1;
    private static final int INFO_ADDRESS_COLUMN = 2;
    private static final int INFO_MODE_COLUMN = 3;
    private static final int INFO_DATAKEYS_COLUMN = 4;

    private static final String MODE_REALTIME = "realtime";

    private static final String DATA_KEY_HR = "raw.hr";

    private static final int DATA_HR_COLUMN_NUMBER = 2;
    private static final int DATE_HR_TIMESTAMP_COLUMN = 0;
    private static final int DATA_HR_BPM_COLUMN = 1;


    private static final String LOG_TAG = HitoeWrapper.class.getName();


    interface StatusCallback {
        /**
         * 状態を受け取る
         *
         * @param sensorId 既に接続している場合はセンサー ID。そうでなければ null
         */
        void call(String sensorId);
    }

    interface SearchCallback {
        /**
         * 見つかったセンサーを受け取る
         *
         * @param sensors 見つかったセンサー
         */
        void call(List<SensorInfo> sensors);
    }

    interface ConnectCallback {
        /**
         * 接続結果を受け取る
         *
         * @param result 接続結果
         */
        void call(ConnectResult result);
    }

    interface HeartrateReceiver {
        /**
         * 心拍数を受け取る
         *
         * @param date      ミリ秒単位の UNIX 時間
         * @param heartrate 心拍数
         */
        void receive(long date, int heartrate);
    }

    private interface InnerStatusCallback {
        /**
         * 状態を受け取る
         *
         * @param status 状態
         */
        void call(Map<Pair<String, String>, Pair<String, Set<String>>> status);
    }

    private interface InnerSearchCallback {
        /**
         * 見つかったセンサーを受け取る
         *
         * @param sensors 見つかったセンサー
         */
        void call(List<SensorInfo> sensors);
    }

    private interface InnerConnectCallback {
        /**
         * 接続結果を受け取る
         *
         * @param result  接続結果
         * @param session 接続に成功した場合はセッション ID
         */
        void call(ConnectResult result, String session);
    }

    private interface InnerAddReceiverCallback {
        /**
         * レシーバーの登録結果を受け取る
         *
         * @param result     登録結果
         * @param connection 登録に成功した場合は登録 ID
         */
        void call(ConnectResult result, String connection);
    }

    private final HitoeSdkAPI core;
    private volatile InnerStatusCallback statusCallback;
    private volatile InnerSearchCallback searchCallback;
    private volatile InnerConnectCallback connectCallback;
    private volatile InnerAddReceiverCallback addReceiverCallback;

    private volatile Runnable disconnectCallback;
    private volatile Runnable setReceiverCallback;
    private volatile HeartrateReceiver heartrateReceiver;

    // 接続のセッション
    // (セッション ID, センサー ID)
    private Pair<String, String> session;
    // レシーバーの登録 ID
    private String connection;

    HitoeWrapper(HitoeSdkAPI core) {
        this.core = core;
        this.core.setAPICallback(this::callback);
    }

    void setDisconnectCallback(Runnable callback) {
        this.disconnectCallback = callback;
    }

    void setHeartrateReceiver(Runnable setCallback, HeartrateReceiver receiver) {
        this.setReceiverCallback = setCallback;
        this.heartrateReceiver = receiver;
    }

    private void callback(int apiId, int responseId, String response) {
        Log.d(LOG_TAG, "apiId=" + apiId + ",responseId=" + responseId + ",response=" + response);

        switch (apiId) {
            case API_ID_GET_STATUS:
                // 状態の取得
                final Map<Pair<String, String>, Pair<String, Set<String>>> status;
                switch (responseId) {
                    case RES_ID_SUCCESS:
                        status = Status.parse(response);
                        break;
                    default:
                        status = null;
                }
                final InnerStatusCallback statusCallback = this.statusCallback;
                if (statusCallback != null) {
                    statusCallback.call(status);
                }
                break;

            case API_ID_GET_AVAILABLE_SENSOR:
                // センサーを探した
                final List<SensorInfo> sensors;
                switch (responseId) {
                    case RES_ID_SUCCESS:
                        sensors = new ArrayList<>();
                        for (String line : response.split(ROW_SEPARATOR, -1)) {
                            final String l = line.trim();
                            if (l.isEmpty()) {
                                continue;
                            }
                            Log.d(LOG_TAG, "Raw sensor " + l + " was found");
                            final SensorInfo sensor;
                            try {
                                sensor = SensorInfo.parse(l);
                            } catch (IllegalArgumentException e) {
                                Log.w(LOG_TAG, e.toString());
                                continue;
                            }
                            if (sensor.getMode().equals(MODE_REALTIME) && sensor.getDataKeys().contains(DATA_KEY_HR)) {
                                sensors.add(sensor);
                            }
                        }
                        break;
                    default:
                        sensors = Collections.emptyList();
                }
                final InnerSearchCallback searchCallback = this.searchCallback;
                if (searchCallback != null) {
                    searchCallback.call(sensors);
                }
                break;

            case API_ID_CONNECT:
                // センサーにつなげた
                final ConnectResult result;
                final String session;
                switch (responseId) {
                    case RES_ID_SENSOR_CONNECT:
                        result = ConnectResult.OK;
                        session = response;
                        break;
                    case RES_ID_SENSOR_CONNECT_FAILURE:
                        result = ConnectResult.NOT_FOUND;
                        session = null;
                        break;
                    case RES_ID_SENSOR_UNAUTHORIZED:
                        result = ConnectResult.INVALID_PINCODE;
                        session = null;
                        break;
                    case RES_ID_SENSOR_DISCONNECT_NOTICE:
                        result = ConnectResult.NOT_FOUND;
                        session = response;
                        innerDisconnect(session);
                        break;
                    default:
                        result = ConnectResult.NOT_FOUND;
                        session = null;
                }
                final InnerConnectCallback connectCallback = this.connectCallback;
                if (connectCallback != null) {
                    connectCallback.call(result, session);
                }
                break;

            case ADI_ID_ADD_RECEIVER:
                // レシーバーを登録した
                final ConnectResult result1;
                final String connection;
                switch (responseId) {
                    case RES_ID_SUCCESS:
                        result1 = ConnectResult.OK;
                        connection = response;
                        break;
                    default:
                        result1 = ConnectResult.RECEIVER_ERROR;
                        connection = null;
                }
                final InnerAddReceiverCallback addReceiverCallback = this.addReceiverCallback;
                if (addReceiverCallback != null) {
                    addReceiverCallback.call(result1, connection);
                }
                break;

            case API_ID_DISCONNECT:
                innerDisconnect(response);
                break;
            default:
        }
    }

    private void innerDisconnect(String session) {
        final Runnable callback;
        synchronized (this) {
            if (this.session == null || !session.equals(this.session.first)) {
                return;
            }
            this.session = null;
            this.connection = null;
            callback = this.disconnectCallback;
        }
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                Log.w(LOG_TAG, e.toString());
            }
        }
    }


    /**
     * 状態を返す
     *
     * @param callback 状態を受け取るコールバック
     */
    void getStatus(StatusCallback callback) {
        Log.d(LOG_TAG, "Get status");

        this.statusCallback = status -> {
            try {
                String sensorId = null;
                for (Map.Entry<Pair<String, String>, Pair<String, Set<String>>> entry : status.entrySet()) {
                    if (entry.getKey().second.isEmpty() || entry.getKey().first.isEmpty()) {
                        continue;
                    } else if (!entry.getValue().second.contains(DATA_KEY_HR)) {
                        continue;
                    }
                    // 接続してた
                    sensorId = entry.getValue().first;
                    break;
                }
                callback.call(sensorId);
            } finally {
                this.statusCallback = null;
            }
        };
        final int responseId = this.core.getStatus();
        if (responseId != RES_ID_SUCCESS) {
            throw new RuntimeException("Cannot get status");
        }
    }

    /**
     * センサーを探す
     *
     * @param searchTime 探す時間（ミリ秒）
     * @param callback   結果を受け取るコールバック
     */
    void search(long searchTime, SearchCallback callback) {
        Log.d(LOG_TAG, "Search sensors");

        this.searchCallback = sensors -> {
            try {
                callback.call(sensors);
            } finally {
                this.searchCallback = null;
            }
        };
        final int responseId = this.core.getAvailableSensor(GET_AVAILABLE_SENSOR_DEVICE_TYPE, GET_AVAILABLE_SENSOR_PARAM_SEARCH_TIME + "=" + searchTime);
        if (responseId != RES_ID_SUCCESS) {
            throw new RuntimeException("Cannot search sensors");
        }
    }

    /**
     * センサーに接続する
     *
     * @param sensor   接続するセンサー
     * @param pincode  ピンコード
     * @param callback 結果を受け取るコールバック
     */
    void connect(SensorInfo sensor, String pincode, ConnectCallback callback) {
        if (this.session != null) {
            if (this.session.second.equals(sensor.getId())) {
                // もう接続してる
                if (this.connection == null && this.heartrateReceiver != null) {
                    setHeartrateReceiver(callback);
                    return;
                }
                callback.call(ConnectResult.OK);
                return;
            }

            // 別のセンサーと接続してる
            disconnect(() -> connect(sensor, pincode, callback));
            return;
        }

        Log.d(LOG_TAG, "Connect to sensor " + sensor);

        this.connectCallback = (result, session) -> {
            try {
                if (result != ConnectResult.OK) {
                    callback.call(result);
                    return;
                }
                this.session = new Pair<>(session, sensor.getId());
                this.connection = null;
                setHeartrateReceiver(callback);
            } finally {
                this.connectCallback = null;
            }
        };
        final int responseId = this.core.connect(sensor.getType(), sensor.getAddress(), sensor.getMode(), CONNECT_PARAM_PINCODE + "=" + pincode);
        if (responseId != RES_ID_SUCCESS) {
            throw new RuntimeException("Cannot connect to sensor " + sensor);
        }
    }

    /**
     * 接続を切る
     *
     * @param callback コールバック
     */
    void disconnect(Runnable callback) {
        // 別のセンサーと接続してる
        if (this.connection != null) {
            final int responseId = this.core.removeReceiver(this.connection);
            if (responseId != RES_ID_SUCCESS) {
                throw new RuntimeException("Cannot remove receiver " + this.connection);
            }
        }
        if (this.session != null) {
            final int responseId = this.core.disconnect(this.session.first);
            if (responseId != RES_ID_SUCCESS) {
                throw new RuntimeException("Cannot disconnect " + this.session.first);
            }
        }
        callback.run();
    }

    private void setHeartrateReceiver(ConnectCallback callback) {
        this.addReceiverCallback = (result, connection) -> {
            try {
                this.connection = connection;
                final Runnable setCallback = this.setReceiverCallback;
                if (setCallback != null) {
                    setCallback.run();
                }
                callback.call(result);
            } finally {
                this.addReceiverCallback = null;
            }
        };
        final int responseId = this.core.addReceiver(this.session.first, new String[]{DATA_KEY_HR}, (connection, responseId1, dataKey, data) -> {
            final String[] rows = data.split(ROW_SEPARATOR);
            final String[] columns = rows[rows.length - 1].split(COLUMN_SEPARATOR); // 最新の値だけ使う
            if (columns.length < DATA_HR_COLUMN_NUMBER) {
                return;
            }
            final HeartrateReceiver receiver = this.heartrateReceiver;
            if (receiver == null) {
                return;
            }
            long date = Long.parseLong(columns[DATE_HR_TIMESTAMP_COLUMN]);
            int heartrate = (int) Double.parseDouble(columns[DATA_HR_BPM_COLUMN]);
            receiver.receive(date, heartrate);
        }, "", "");
        if (responseId != RES_ID_SUCCESS) {
            throw new RuntimeException("Cannot add heartrate receiver on " + this.session.first);
        }
    }

    /**
     * 状態
     */
    private static class Status {
        /**
         * 状態を読み取る
         *
         * @param str 状態を表す文字列
         * @return (セッション ID, コネクション ID) -> (センサー ID, データキー)
         */
        static Map<Pair<String, String>, Pair<String, Set<String>>> parse(String str) {
            final Map<Pair<String, String>, Pair<String, Set<String>>> status = new HashMap<>();
            for (String line : str.split(ROW_SEPARATOR)) {
                final String l = line.trim();
                if (l.isEmpty()) {
                    continue;
                }
                final String[] tokens = l.split(COLUMN_SEPARATOR, -1);
                if (tokens.length != STATUS_COLUMN_NUMBER) {
                    throw new IllegalArgumentException("invalid status " + l + " [" + l.length() + "]");
                }
                status.put(new Pair<>(tokens[STATUS_SESSION_COLUMN], tokens[STATUS_CONNECTION_COLUMN]), new Pair<>(tokens[STATUS_ID_COLUMN], new HashSet<>(Arrays.asList(tokens[STATUS_DATAKEYS_COLUMN].split(DATAKEYS_SEPARATOR)))));
            }
            return status;
        }
    }

    /**
     * センサー
     */
    static class SensorInfo {

        private final String type;
        private final String id;
        private final String address;
        private final String mode;
        private final Set<String> dataKeys;

        private SensorInfo(@NonNull String type, @NonNull String id, @NonNull String address, @NonNull String mode, @NonNull Collection<String> dataKeys) {
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
            final String[] tokens = str.split(COLUMN_SEPARATOR, -1);
            if (tokens.length != INFO_COLUMN_NUMBER) {
                throw new IllegalArgumentException("invalid sensor info " + str);
            }
            return new SensorInfo(tokens[INFO_TYPE_COLUMN], tokens[INFO_ID_COLUMN], tokens[INFO_ADDRESS_COLUMN], tokens[INFO_MODE_COLUMN], Arrays.asList(tokens[INFO_DATAKEYS_COLUMN].split(DATAKEYS_SEPARATOR)));
        }

    }

}
