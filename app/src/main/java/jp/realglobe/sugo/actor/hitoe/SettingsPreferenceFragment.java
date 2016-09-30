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

    private final Set<String> showDefaultKeys = new HashSet<>();

    private SharedPreferences.OnSharedPreferenceChangeListener changeListenter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        showDefaultKeys.addAll(Arrays.asList(
                getString(R.string.key_delay),
                getString(R.string.key_server),
                getString(R.string.key_timer),
                getString(R.string.key_actor_suffix)
        ));

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
