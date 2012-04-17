/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cellbroadcastreceiver;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.Locale;

import static com.android.cellbroadcastreceiver.CellBroadcastReceiver.DBG;

/**
 * Manages alert audio and vibration and text-to-speech. Runs as a service so that
 * it can continue to play if another activity overrides the CellBroadcastListActivity.
 */
public class CellBroadcastAlertAudio extends Service implements TextToSpeech.OnInitListener,
        TextToSpeech.OnUtteranceCompletedListener {
    private static final String TAG = "CellBroadcastAlertAudio";

    /** Action to start playing alert audio/vibration/speech. */
    static final String ACTION_START_ALERT_AUDIO = "ACTION_START_ALERT_AUDIO";

    /** Extra for alert audio duration (from settings). */
    public static final String ALERT_AUDIO_DURATION_EXTRA =
            "com.android.cellbroadcastreceiver.ALERT_AUDIO_DURATION";

    /** Extra for message body to speak (if speech enabled in settings). */
    public static final String ALERT_AUDIO_MESSAGE_BODY =
            "com.android.cellbroadcastreceiver.ALERT_AUDIO_MESSAGE_BODY";

    /** Extra for text-to-speech language (if speech enabled in settings). */
    public static final String ALERT_AUDIO_MESSAGE_LANGUAGE =
            "com.android.cellbroadcastreceiver.ALERT_AUDIO_MESSAGE_LANGUAGE";

    /** Pause duration between alert sound and alert speech. */
    private static final int PAUSE_DURATION_BEFORE_SPEAKING_MSEC = 1000;

    /** Vibration uses the same on/off pattern as the CMAS alert tone */
    private static final long[] sVibratePattern = new long[] { 0, 2000, 500, 1000, 500, 1000, 500 };

    private static final int STATE_IDLE = 0;
    private static final int STATE_ALERTING = 1;
    private static final int STATE_PAUSING = 2;
    private static final int STATE_SPEAKING = 3;

    private int mState;

    private TextToSpeech mTts;
    private boolean mTtsEngineReady;

    private String mMessageBody;
    private String mMessageLanguage;
    private boolean mTtsLanguageSupported;

    private Vibrator mVibrator;
    private MediaPlayer mMediaPlayer;
    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager;
    private int mInitialCallState;

    // Internal messages
    private static final int ALERT_SOUND_FINISHED = 1000;
    private static final int ALERT_PAUSE_FINISHED = 1001;
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ALERT_SOUND_FINISHED:
                    if (DBG) Log.v(TAG, "ALERT_SOUND_FINISHED");
                    stop();     // stop alert sound
                    // if we can speak the message text
                    if (mMessageBody != null && mTtsEngineReady && mTtsLanguageSupported) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(ALERT_PAUSE_FINISHED),
                                PAUSE_DURATION_BEFORE_SPEAKING_MSEC);
                        mState = STATE_PAUSING;
                    } else {
                        stopSelf();
                        mState = STATE_IDLE;
                    }
                    break;

                case ALERT_PAUSE_FINISHED:
                    if (DBG) Log.v(TAG, "ALERT_PAUSE_FINISHED");
                    if (mMessageBody != null && mTtsEngineReady && mTtsLanguageSupported) {
                        if (DBG) Log.v(TAG, "Speaking broadcast text: " + mMessageBody);
                        mTts.speak(mMessageBody, TextToSpeech.QUEUE_FLUSH, null);
                        mState = STATE_SPEAKING;
                    } else {
                        Log.w(TAG, "TTS engine not ready or language not supported");
                        stopSelf();
                        mState = STATE_IDLE;
                    }
                    break;

                default:
                    Log.e(TAG, "Handler received unknown message, what=" + msg.what);
            }
        }
    };

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String ignored) {
            // Stop the alert sound and speech if the call state changes.
            if (state != TelephonyManager.CALL_STATE_IDLE
                    && state != mInitialCallState) {
                stopSelf();
            }
        }
    };

    /**
     * Callback from TTS engine after initialization.
     * @param status {@link TextToSpeech#SUCCESS} or {@link TextToSpeech#ERROR}.
     */
    public void onInit(int status) {
        if (DBG) Log.v(TAG, "onInit() TTS engine status: " + status);
        if (status == TextToSpeech.SUCCESS) {
            mTtsEngineReady = true;
            // try to set the TTS language to match the broadcast
            setTtsLanguage();
        } else {
            mTtsEngineReady = false;
            mTts = null;
            Log.e(TAG, "onInit() TTS engine error: " + status);
        }
    }

    /**
     * Try to set the TTS engine language to the value of mMessageLanguage.
     * mTtsLanguageSupported will be updated based on the response.
     */
    private void setTtsLanguage() {
        if (mMessageLanguage != null) {
            if (DBG) Log.v(TAG, "Setting TTS language to '" + mMessageLanguage + '\'');
            int result = mTts.setLanguage(new Locale(mMessageLanguage));
            // success values are >= 0, failure returns negative value
            if (DBG) Log.v(TAG, "TTS setLanguage() returned: " + result);
            mTtsLanguageSupported = result >= 0;
        } else {
            // try to use the default TTS language for broadcasts with no language specified
            if (DBG) Log.v(TAG, "No language specified in broadcast: using default");
            mTtsLanguageSupported = true;
        }
    }

    /**
     * Callback from TTS engine.
     * @param utteranceId the identifier of the utterance.
     */
    public void onUtteranceCompleted(String utteranceId) {
        stopSelf();
    }

    @Override
    public void onCreate() {
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // Listen for incoming calls to kill the alarm.
        mTelephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mTelephonyManager.listen(
                mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        CellBroadcastAlertWakeLock.acquireCpuWakeLock(this);
    }

    @Override
    public void onDestroy() {
        stop();
        // Stop listening for incoming calls.
        mTelephonyManager.listen(mPhoneStateListener, 0);
        CellBroadcastAlertWakeLock.releaseCpuLock();
        // shutdown TTS engine
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // No intent, tell the system not to restart us.
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // This extra should always be provided by CellBroadcastAlertService,
        // but default to 4 seconds just to be safe
        int duration = intent.getIntExtra(ALERT_AUDIO_DURATION_EXTRA, 4);

        // Get text to speak (if enabled by user)
        mMessageBody = intent.getStringExtra(ALERT_AUDIO_MESSAGE_BODY);
        mMessageLanguage = intent.getStringExtra(ALERT_AUDIO_MESSAGE_LANGUAGE);

        if (mMessageBody != null) {
            if (mTts == null) {
                mTts = new TextToSpeech(this, this);
            } else if (mTtsEngineReady) {
                setTtsLanguage();
            }
        }

        play(duration * 1000);  // convert to milliseconds

        // Record the initial call state here so that the new alarm has the
        // newest state.
        mInitialCallState = mTelephonyManager.getCallState();

        return START_STICKY;
    }

    // Volume suggested by media team for in-call alarms.
    private static final float IN_CALL_VOLUME = 0.125f;

    /**
     * Start playing the alert sound, and send delayed message when it's time to stop.
     * @param duration the alert sound duration in milliseconds
     */
    private void play(int duration) {
        // stop() checks to see if we are already playing.
        stop();

        if (DBG) Log.v(TAG, "play()");

        // future optimization: reuse media player object
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setOnErrorListener(new OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.e(TAG, "Error occurred while playing audio.");
                mp.stop();
                mp.release();
                mMediaPlayer = null;
                return true;
            }
        });

        try {
            // Check if we are in a call. If we are, play the alert
            // sound at a low volume to not disrupt the call.
            if (mTelephonyManager.getCallState()
                    != TelephonyManager.CALL_STATE_IDLE) {
                Log.v(TAG, "in call: reducing volume");
                mMediaPlayer.setVolume(IN_CALL_VOLUME, IN_CALL_VOLUME);
            }
            setDataSourceFromResource(getResources(), mMediaPlayer,
                    R.raw.attention_signal);
            mAudioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            startAlarm(mMediaPlayer);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to play alert sound", ex);
        }

        /* Start the vibrator after everything is ok with the media player */
        mVibrator.vibrate(sVibratePattern, 1);

        // stop alert after the specified duration
        mHandler.sendMessageDelayed(mHandler.obtainMessage(ALERT_SOUND_FINISHED), duration);
        mState = STATE_ALERTING;
    }

    // Do the common stuff when starting the alarm.
    private static void startAlarm(MediaPlayer player)
            throws java.io.IOException, IllegalArgumentException,
                   IllegalStateException {
        player.setAudioStreamType(AudioManager.STREAM_ALARM);
        player.setLooping(true);
        player.prepare();
        player.start();
    }

    private static void setDataSourceFromResource(Resources resources,
            MediaPlayer player, int res) throws java.io.IOException {
        AssetFileDescriptor afd = resources.openRawResourceFd(res);
        if (afd != null) {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(),
                    afd.getLength());
            afd.close();
        }
    }

    /**
     * Stops alert audio and speech.
     */
    public void stop() {
        if (DBG) Log.v(TAG, "stop()");

        mHandler.removeMessages(ALERT_SOUND_FINISHED);
        mHandler.removeMessages(ALERT_PAUSE_FINISHED);

        if (mState == STATE_ALERTING) {
            // Stop audio playing
            if (mMediaPlayer != null) {
                mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
            }

            // Stop vibrator
            mVibrator.cancel();
        } else if (mState == STATE_SPEAKING && mTts != null) {
            mTts.stop();
        }
        mAudioManager.abandonAudioFocus(null);
        mState = STATE_IDLE;
    }
}
