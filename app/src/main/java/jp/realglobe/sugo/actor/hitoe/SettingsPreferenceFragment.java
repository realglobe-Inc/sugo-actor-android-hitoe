package jp.realglobe.sugo.actor.hitoe;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.PreferenceFragment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 設定画面
 * Created by fukuchidaisuke on 16/09/26.
 */

public class SettingsPreferenceFragment extends PreferenceFragment {

    private static final Set<String> showDefaultKeys = new HashSet<>();

    static {
        showDefaultKeys.addAll(Arrays.asList("key_delay", "key_server", "key_timer"));
    }

    private SharedPreferences.OnSharedPreferenceChangeListener changeListenter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.activity_settings);
        showDefaults();
        changeListenter = (sharedPreferences, key) -> {
            if (showDefaultKeys.contains(key)) {
                showDefault(key);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = getPreferenceScreen().getSharedPreferences();
        sharedPreferences.registerOnSharedPreferenceChangeListener(changeListenter);
    }

    @Override
    public void onPause() {
        super.onPause();
        SharedPreferences sharedPreferences = getPreferenceScreen().getSharedPreferences();
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(changeListenter);
    }

    /**
     * summary に現在値を表示する
     *
     * @param key キー
     */
    private void showDefault(String key) {
        final EditTextPreference delayPreference = (EditTextPreference) findPreference(key);
        delayPreference.setSummary(delayPreference.getText());
    }

    /**
     * summary に現在値を表示する
     */
    private void showDefaults() {
        for (String key : showDefaultKeys) {
            showDefault(key);
        }
    }
}
