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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.PreferenceFragment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import jp.realglobe.sugo.actor.android.hitoe.R;

/**
 * 設定画面
 * Created by fukuchidaisuke on 16/09/26.
 */

public class SettingsPreferenceFragment extends PreferenceFragment {

    private final Set<String> showDefaultKeys = new HashSet<>();

    private SharedPreferences.OnSharedPreferenceChangeListener changeListener;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        showDefaultKeys.addAll(Arrays.asList(
                getString(R.string.key_delay),
                getString(R.string.key_server),
                getString(R.string.key_report_interval),
                getString(R.string.key_actor_suffix),
                getString(R.string.key_timer)
        ));

        addPreferencesFromResource(R.xml.activity_settings);
        showDefaults();
        changeListener = (sharedPreferences, key) -> {
            if (showDefaultKeys.contains(key)) {
                showDefault(key);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = getPreferenceScreen().getSharedPreferences();
        sharedPreferences.registerOnSharedPreferenceChangeListener(changeListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        SharedPreferences sharedPreferences = getPreferenceScreen().getSharedPreferences();
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(changeListener);
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
