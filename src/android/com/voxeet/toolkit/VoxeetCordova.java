package com.voxeet.toolkit;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.voxeet.audio.AudioRoute;
import com.voxeet.authent.token.RefreshTokenCallback;
import com.voxeet.authent.token.TokenCallback;
import com.voxeet.push.firebase.FirebaseController;
import com.voxeet.sdk.core.VoxeetSdk;
import com.voxeet.sdk.core.preferences.VoxeetPreferences;
import com.voxeet.sdk.core.services.AudioService;
import com.voxeet.sdk.core.services.ConferenceService;
import com.voxeet.sdk.core.services.MediaDeviceService;
import com.voxeet.sdk.core.services.SessionService;
import com.voxeet.sdk.events.error.PermissionRefusedEvent;
import com.voxeet.sdk.events.sdk.ConferenceStateEvent;
import com.voxeet.sdk.events.sdk.SocketConnectEvent;
import com.voxeet.sdk.events.sdk.SocketStateChangeEvent;
import com.voxeet.sdk.events.v2.UserAddedEvent;
import com.voxeet.sdk.factories.VoxeetIntentFactory;
import com.voxeet.sdk.json.UserInfo;
import com.voxeet.sdk.json.internal.MetadataHolder;
import com.voxeet.sdk.json.internal.ParamsHolder;
import com.voxeet.sdk.models.User;
import com.voxeet.sdk.models.v1.CreateConferenceResult;
import com.voxeet.sdk.utils.AndroidManifest;
import com.voxeet.sdk.utils.Validate;
import com.voxeet.toolkit.configuration.ActionBar;
import com.voxeet.toolkit.configuration.Configuration;
import com.voxeet.toolkit.configuration.Overlay;
import com.voxeet.toolkit.configuration.Users;
import com.voxeet.toolkit.controllers.VoxeetToolkit;
import com.voxeet.toolkit.implementation.overlays.OverlayState;
import com.voxeet.toolkit.notification.CordovaIncomingBundleChecker;
import com.voxeet.toolkit.notification.CordovaIncomingCallActivity;
import com.voxeet.toolkit.notification.RNBundleChecker;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import eu.codlab.simplepromise.Promise;
import eu.codlab.simplepromise.solve.ErrorPromise;
import eu.codlab.simplepromise.solve.PromiseExec;
import eu.codlab.simplepromise.solve.Solver;

/**
 * Voxeet implementation for Cordova
 */

public class VoxeetCordova extends CordovaPlugin {

    private static final String CONSUMER_KEY = "cordova_consumer_key";
    private static final String CONSUMER_SECRET = "cordova_consumer_secret";

    private static final String ERROR_SDK_NOT_INITIALIZED = "ERROR_SDK_NOT_INITIALIZED";
    private static final String ERROR_SDK_NOT_LOGGED_IN = "ERROR_SDK_NOT_LOGGED_IN";
    private static final String SDK_ALREADY_CONFIGURED_ERROR = "The SDK is already configured";
    private static final String TAG = VoxeetCordova.class.getSimpleName();

    //static to let CordovaIncomingBundleChecker to check the state of it
    //it'll do the trick until the sdk is able to create and maintain a proper
    //conference configuration holder (something expected second half 2019)
    public static boolean startVideoOnJoin = false;

    private final Handler mHandler;
    private UserInfo _current_user;
    private CallbackContext _log_in_callback;
    private ReentrantLock lock = new ReentrantLock();
    private ReentrantLock lockAwaitingToken = new ReentrantLock();
    private List<TokenCallback> mAwaitingTokenCallback;
    private CallbackContext refreshAccessTokenCallbackInstance;
    private MicrophonePermissionWait waitMicrophonePermission;
    private CordovaWebView mWebView;

    public VoxeetCordova() {
        super();
        mHandler = new Handler(Looper.getMainLooper());
        mAwaitingTokenCallback = new ArrayList<>();

        Promise.setHandler(mHandler);
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        mWebView = webView;

        Context context = cordova.getContext();
        String consumerKeyManifest = AndroidManifest.readMetadata(context, CONSUMER_KEY, null);
        String consumerSecretManifest = AndroidManifest.readMetadata(context, CONSUMER_SECRET, null);

        if (null != consumerKeyManifest && null != consumerSecretManifest && null == VoxeetSdk.instance()) {
            VoxeetSdk.initialize(consumerKeyManifest, consumerSecretManifest);
            internalInitialize(null);
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);

        //check for permission result
        //actually not testing it in the permission callback to prevent issue with flow
        MicrophonePermissionWait current = waitMicrophonePermission;
        if (null != current) {
            waitMicrophonePermission = null;
            if (Validate.hasMicrophonePermissions(mWebView.getContext())) {
                join(current.getConfId(), current.getCb());
            } else if (null != current) {
                current.getCb().error("microphone permission rejected");
            }
        }

        setVolumeVoiceCall();
        checkForAwaitingConference(null);
    }

    @Override
    public void onPause(boolean multitasking) {
        setVolumeVoiceCall();

        super.onPause(multitasking);

        if (null != AudioService.getSoundManager()) {
            AudioService.getSoundManager().requestAudioFocus();
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        super.onRequestPermissionResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case PermissionRefusedEvent.RESULT_MICROPHONE: {
                /*MicrophonePermissionWait current = waitMicrophonePermission;
                int index = index(permissions, PermissionRefusedEvent.Permission.MICROPHONE.getPermissions()[0]);

                //remove the current instance
                waitMicrophonePermission = null;
                if (index >= 0
                        && index < grantResults.length
                        && grantResults[index] == PackageManager.PERMISSION_GRANTED
                        && null != VoxeetSdk.getInstance() && null != current) {
                    join(current.getConfId(), current.getCb());
                } else if (null != current) {
                    current.getCb().error("microphone permission rejected");
                }*/
                break;
            }
            case PermissionRefusedEvent.RESULT_CAMERA: {
                ConferenceService service = VoxeetSdk.conference();
                if (null != service && service.isLive()) {
                    service.startVideo()
                            .then(new PromiseExec<Boolean, Object>() {
                                @Override
                                public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {

                                }
                            })
                            .error(new ErrorPromise() {
                                @Override
                                public void onError(@NonNull Throwable error) {
                                    error.printStackTrace();
                                }
                            });
                }
                return;
            }
            default:
        }
    }

    private int index(@NonNull String[] permissions, @NonNull String permission) {
        int i = 0;
        for (String perm : permissions) {
            if (permission.equalsIgnoreCase(perm)) return i;
            i++;
        }
        return -1;
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Log.d("VoxeetCordova", "execute: request " + action);
        if (action != null) {
            switch (action) {
                case "onAccessTokenOk":
                    onAccessTokenOk(args.getString(0), callbackContext);
                    break;
                case "onAccessTokenKo":
                    onAccessTokenKo(args.getString(0), callbackContext);
                    break;
                case "initializeToken":
                    initialize(args.getString(0), callbackContext);
                    break;
                case "initialize":
                    initialize(args.getString(0),
                            args.getString(1),
                            callbackContext);
                    break;
                case "refreshAccessTokenCallback":
                    refreshAccessTokenCallback(callbackContext);
                    break;
                case "connect":
                case "openSession":
                    JSONObject userInfo = null;
                    if (!args.isNull(0)) userInfo = args.getJSONObject(0);
                    UserInfo user = null;

                    if (null != userInfo) {
                        user = new UserInfo(userInfo.getString("name"),
                                userInfo.getString("externalId"),
                                userInfo.getString("avatarUrl"));
                    }

                    openSession(user, callbackContext);
                    break;
                case "disconnect":
                case "closeSession":
                    closeSession(callbackContext);
                    break;
                case "isUserLoggedIn":
                    isUserLoggedIn(callbackContext);
                    break;
                case "isAudio3DEnabled":
                    isAudio3DEnabled(callbackContext);
                    break;
                case "isTelecomMode":
                    isTelecomMode(callbackContext);
                    break;
                case "checkForAwaitingConference":
                    checkForAwaitingConference(callbackContext);
                    break;
                case "create":
                    try {
                        JSONObject parameters = args.getJSONObject(0);


                        String confAlias = parameters.getString("alias");
                        JSONObject object = null;
                        if (!parameters.isNull("metadata")) {
                            object = parameters.getJSONObject("metadata");
                        }

                        MetadataHolder holder = new MetadataHolder();
                        if (null != object) {
                            Iterator<String> keys = object.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                holder.putValue(key, object.get(key));
                            }
                        }

                        if (!parameters.isNull("params")) {
                            object = parameters.getJSONObject("params");
                        }
                        ParamsHolder pholder = new ParamsHolder();
                        if (null != object) {
                            Iterator<String> keys = object.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                pholder.putValue(key, object.get(key));
                            }
                        }

                        create(confAlias, holder, pholder, callbackContext);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                    break;
                case "broadcast":
                    try {
                        String confId = args.getString(0);
                        Log.d(TAG, "execute: broadcast");
                        broadcast(confId, callbackContext);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                    break;
                case "join":
                    try {
                        boolean listener = false;
                        String confId = args.getString(0);
                        if (!args.isNull(1)) {
                            try {
                                JSONObject object = args.optJSONObject(1);
                                if (null != object) {
                                    JSONObject params_user = object.optJSONObject("user");
                                    if (null != params_user) {
                                        String userType = params_user.optString("type");
                                        listener = "listener".equals(userType);
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        Log.d(TAG, "execute: listener := " + listener);
                        if (listener) {
                            listen(confId, callbackContext);
                        } else {
                            join(confId, callbackContext);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                    break;
                case "invite":
                    try {
                        String conferenceId = null;
                        if (!args.isNull(0)) conferenceId = args.getString(0);
                        JSONArray array = null;
                        if (!args.isNull(1)) array = args.getJSONArray(1);

                        List<UserInfo> participants = new ArrayList<>();
                        if (null != array) {
                            JSONObject object;
                            int index = 0;
                            while (index < array.length()) {
                                object = array.getJSONObject(index);

                                participants.add(new UserInfo(object.getString("name"),
                                        object.getString("externalId"),
                                        object.getString("avatarUrl")));

                                index++;
                            }
                        }

                        invite(conferenceId, participants, callbackContext);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                case "setUIConfiguration":
                    try {
                        JSONObject object = args.getJSONObject(0);
                        setUiConfiguration(object);
                        callbackContext.success();
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error("Wrong configuration or issue with it");
                    }
                case "startConference":
                    /*try {
                        String confId = args.getString(0);
                        JSONArray array = null;
                        if (!args.isNull(1)) array = args.getJSONArray(1);

                        List<UserInfo> participants = new ArrayList<>();
                        if (null != array) {
                            JSONObject object;
                            int index = 0;
                            while (index < array.length()) {
                                object = array.getJSONObject(index);

                                participants.add(new UserInfo(object.getString("name"),
                                        object.getString("externalId"),
                                        object.getString("avatarUrl")));

                                index++;
                            }
                        }

                        startConference(confId, participants, callbackContext);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }*/
                    callbackContext.error("Not implemented anymore, see create->join->invite");
                    break;
                case "leave":
                case "stopConference":
                    stopConference(callbackContext);
                    break;
                //create -> objet key -> value (value->key->value)
                //join
                //leave
                case "startRecording":
                    startRecording(callbackContext);
                    break;
                case "stopRecording":
                    stopRecording(callbackContext);
                    break;
                case "setAudio3DEnabled":
                    setAudio3DEnabled(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "setTelecomMode":
                    setTelecomMode(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "appearMaximized":
                    appearMaximized(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "defaultVideo":
                    defaultVideo(args.getBoolean(0));
                    callbackContext.success();
                case "defaultBuiltInSpeaker":
                    defaultBuiltInSpeaker(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "screenAutoLock":
                    screenAutoLock(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "sendBroadcastMessage":
                    sendBroadcastMessage(args.getString(0), callbackContext);
                    break;
                default:
                    return false;
            }

            return true; //default return false - so true is ok
        }
        return false;
    }

    private void setUiConfiguration(JSONObject object) {
        JSONObject actionBar = jsonObject(object, "actionBar");
        JSONObject overlay = jsonObject(object, "overlay");
        JSONObject users = jsonObject(object, "users");

        Configuration configuration = VoxeetToolkit.getInstance().getConferenceToolkit().Configuration;
        ActionBar cactionBar = configuration.ActionBar;
        Overlay coverlay = configuration.Overlay;
        Users cusers = configuration.Users;
        if (null != actionBar) {
            if (actionBar.has("displayMute"))
                cactionBar.displayMute = bool(object, "displayMute");
            if (actionBar.has("displaySpeaker"))
                cactionBar.displaySpeaker = bool(object, "displaySpeaker");
            if (actionBar.has("displayCamera"))
                cactionBar.displayCamera = bool(object, "displayCamera");
            if (actionBar.has("displayScreenShare"))
                cactionBar.displayScreenShare = bool(object, "displayScreenShare");
            if (actionBar.has("displayLeave"))
                cactionBar.displayLeave = bool(object, "displayLeave");
        }
        if (null != overlay) {
            if (object.has("backgroundMaximizedColor"))
                coverlay.background_maximized_color = integer(object, "backgroundMaximizedColor");
            if (actionBar.has("backgroundMinimizedColor"))
                coverlay.background_minimized_color = integer(object, "backgroundMinimizedColor");
        }
        if (null != users) {
            if (object.has("speakingUserColor"))
                cusers.speaking_user_color = integer(object, "speakingUserColor");
            if (actionBar.has("selectedUserColor"))
                cusers.selected_user_color = integer(object, "selectedUserColor");
        }
    }

    private boolean bool(@Nullable JSONObject object, @NonNull String key) {
        if (null == object) return false;
        try {
            return object.optBoolean(key, false);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private int integer(@Nullable JSONObject object, @NonNull String key) {
        if (null == object) return 0;
        try {
            return object.optInt(key, 0);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    @Nullable
    private JSONObject jsonObject(@Nullable JSONObject object, @NonNull String key) {
        if (null == object) return null;
        try {
            return object.optJSONObject(key);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable

    private void defaultVideo(boolean startVideo) {
        startVideoOnJoin = startVideo;
        VoxeetPreferences.setDefaultVideoOn(startVideo);
    }


    private void initialize(final String accessToken,
                            final CallbackContext callbackContext) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Application application = (Application) cordova.getActivity().getApplicationContext();

                if (null == VoxeetSdk.instance()) {
                    VoxeetSdk.initialize(accessToken,
                            new RefreshTokenCallback() {
                                @Override
                                public void onRequired(TokenCallback callback) {
                                    lock(lockAwaitingToken);
                                    if (!mAwaitingTokenCallback.contains(callback)) {
                                        mAwaitingTokenCallback.add(callback);
                                    }
                                    unlock(lockAwaitingToken);
                                    postRefreshAccessToken();
                                }
                            });

                    internalInitialize(callbackContext);
                } else {
                    callbackContext.success();
                }
            }
        });
    }

    private void initialize(final String consumerKey,
                            final String consumerSecret,
                            final CallbackContext callbackContext) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Application application = (Application) cordova.getActivity().getApplicationContext();

                if (null == VoxeetSdk.instance()) {
                    VoxeetSdk.initialize(consumerKey, consumerSecret);

                    internalInitialize(callbackContext);
                } else {
                    callbackContext.success();
                }
            }
        });
    }

    private void internalInitialize(@Nullable final CallbackContext callbackContext) {
        ConferenceService service = VoxeetSdk.conference();
        if (null != service) service.setTimeOut(-1); //no timeout by default in the cordova impl

        Application application = (Application) cordova.getActivity().getApplicationContext();

        //also enable the push token upload and log
        FirebaseController.getInstance()
                .log(true)
                .enable(true);
        FirebaseController
                .createNotificationChannel(application);

        //reset the incoming call activity, in case the SDK was no initialized, it would have
        //erased this method call
        VoxeetPreferences.setDefaultActivity(CordovaIncomingCallActivity.class.getCanonicalName());

        //set the 2 optional default configuration from previous saved state
        VoxeetCordova.startVideoOnJoin = VoxeetPreferences.isDefaultVideoOn();
        defaultBuiltInSpeaker(VoxeetPreferences.isDefaultBuiltinSpeakerOn());

        VoxeetToolkit
                .initialize(application, EventBus.getDefault())
                .enableOverlay(true);

        VoxeetToolkit.instance().getConferenceToolkit().setScreenShareEnabled(false).enable(true);

        VoxeetSdk.instance().register(this);

        if (null != callbackContext) callbackContext.success();
    }

    private void openSession(final UserInfo userInfo,
                             final CallbackContext cb) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                SessionService service = VoxeetSdk.session();

                if (null == service) {
                    cb.error(ERROR_SDK_NOT_INITIALIZED);
                    return;
                }

                //if we are trying to connect the same user !
                if (isConnected() && isSameUser(userInfo)) {
                    cb.success();
                    return;
                }

                _log_in_callback = cb;
                if (_current_user == null) {
                    _current_user = userInfo;
                    logSelectedUser();
                } else {
                    _current_user = userInfo;
                    //we have an user
                    service.close()
                            .then(new PromiseExec<Boolean, Object>() {
                                @Override
                                public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {
                                    logSelectedUser();
                                }
                            })
                            .error(new ErrorPromise() {
                                @Override
                                public void onError(Throwable error) {
                                    logSelectedUser();
                                }
                            });
                }
            }
        });
    }

    /**
     * Call this method to log the current selected user
     */
    public void logSelectedUser() {
        SessionService service = VoxeetSdk.session();
        if (null != service) {
            service.open(_current_user)
                    .then(new PromiseExec<Boolean, Object>() {
                        @Override
                        public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {
                            //TODO possibility here to add management for user to be socket managed here
                        }
                    })
                    .error(new ErrorPromise() {
                        @Override
                        public void onError(@NonNull Throwable error) {
                            //error
                        }
                    });
        }
    }

    public UserInfo getCurrentUser() {
        return _current_user;
    }

    private void closeSession(final CallbackContext cb) {
        SessionService service = VoxeetSdk.session();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                service.close()
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean aBoolean, @NonNull Solver<Object> solver) {
                                _current_user = null;
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                _current_user = null;
                                cb.error("Error while logging out with the server");
                            }
                        });
            }
        });
    }

    private void isUserLoggedIn(final CallbackContext cb) {
        SessionService service = VoxeetSdk.session();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean logged_in = null != service && service.isSocketOpen();
                cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, logged_in));
            }
        });
    }

    private void isAudio3DEnabled(final CallbackContext cb) {
        MediaDeviceService service = VoxeetSdk.mediaDevice();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean enabled = null != service && service.isAudio3DEnabled();
                cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, enabled));
            }
        });
    }

    private void isTelecomMode(final CallbackContext cb) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                ConferenceService service = VoxeetSdk.conference();
                boolean enabled = null != service && service.isTelecomMode();

                cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, enabled));
            }
        });
    }

    private void checkForAwaitingConference(@Nullable final CallbackContext cb) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                lock();
                SessionService service = VoxeetSdk.session();
                if (null == service) {
                    if (null != cb) cb.error(ERROR_SDK_NOT_INITIALIZED);
                } else {
                    CordovaIncomingBundleChecker checker = CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE;

                    if (null != CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_LAUNCH_ACCEPT) {
                        RNBundleChecker bundle = CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_LAUNCH_ACCEPT;
                        Activity activity = cordova.getActivity();
                        if (null != activity) {
                            Intent intent = VoxeetIntentFactory.buildFrom(activity, VoxeetPreferences.getDefaultActivity(), bundle.createExtraBundle());
                            if (intent != null)
                                activity.startActivity(intent);
                        }
                    } else if (null != CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_ACCEPT) {
                        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_ACCEPT.onAccept();
                    } else if (null != CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_DECLINE) {
                        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_DECLINE.onDecline();
                    } else if (null != checker && checker.isBundleValid()) {
                        if (null != service && service.isSocketOpen()) {
                            checker.onAccept();
                            CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
                            if (null != cb) cb.success();
                        } else {
                            if (null != cb) cb.error(ERROR_SDK_NOT_LOGGED_IN);
                        }
                    } else {
                        if (null != cb) cb.success();
                    }

                    cleanBundles();
                }
                unlock();
            }
        });
    }

    private void invite(final String conferenceId,
                        final List<UserInfo> participants,
                        final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                service.invite(conferenceId, participants)
                        .then(new PromiseExec<List<User>, Object>() {
                            @Override
                            public void onCall(@Nullable List<User> conferenceRefreshedEvents, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while initializing the conference");
                            }
                        });
            }
        });
    }

    private void create(String conferenceAlias,
                        MetadataHolder holder,
                        ParamsHolder pholder, final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        service.create(conferenceAlias, holder, pholder)
                .then(new PromiseExec<CreateConferenceResult, Object>() {
                    @Override
                    public void onCall(@Nullable CreateConferenceResult result, @NonNull Solver<Object> solver) {
                        //TODO add isNew
                        JSONObject object = new JSONObject();
                        try {
                            object.put("conferenceId", result.conferenceId);
                            object.put("conferenceAlias", result.conferenceAlias);
                            object.put("isNew", result.isNew);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        cb.success(object);
                    }
                })
                .error(new ErrorPromise() {
                    @Override
                    public void onError(@NonNull Throwable error) {
                        cb.error("Error while creating the conference " + conferenceAlias);
                    }
                });
    }

    private void broadcast(@NonNull String conferenceId, @NonNull final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        Context context = mWebView.getContext();
        Log.d(TAG, "broadcast: broadcasting conference");

        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        if (null != context && Validate.hasMicrophonePermissions(mWebView.getContext())) {
            service.broadcast(conferenceId)
                    .then(new PromiseExec<Boolean, Object>() {
                        @Override
                        public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {

                            cleanBundles();

                            if (startVideoOnJoin) {
                                startVideo(null);
                            }

                            cb.success();
                        }
                    })
                    .error(new ErrorPromise() {
                        @Override
                        public void onError(@NonNull Throwable error) {
                            cb.error("Error while joining the conference " + conferenceId);
                        }
                    });
        } else {
            waitMicrophonePermission = new MicrophonePermissionWait(conferenceId, cb);
            requestMicrophonePermission();
        }
    }

    private void join(@NonNull String conferenceId, @NonNull final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        Context context = mWebView.getContext();
        Log.d(TAG, "join: joining conference");

        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        if (null != context && Validate.hasMicrophonePermissions(mWebView.getContext())) {
            service.join(conferenceId)
                    .then(new PromiseExec<Boolean, Object>() {
                        @Override
                        public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {

                            cleanBundles();

                            if (startVideoOnJoin) {
                                startVideo(null);
                            }

                            cb.success();
                        }
                    })
                    .error(new ErrorPromise() {
                        @Override
                        public void onError(@NonNull Throwable error) {
                            cb.error("Error while joining the conference " + conferenceId);
                        }
                    });
        } else {
            waitMicrophonePermission = new MicrophonePermissionWait(conferenceId, cb);
            requestMicrophonePermission();
        }
    }

    private void listen(@NonNull String conferenceId, @NonNull final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        Context context = mWebView.getContext();

        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        service.listen(conferenceId)
                .then(new PromiseExec<Boolean, Object>() {
                    @Override
                    public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {
                        cleanBundles();

                        cb.success();
                    }
                })
                .error(new ErrorPromise() {
                    @Override
                    public void onError(@NonNull Throwable error) {
                        cb.error("Error while joining the conference " + conferenceId);
                    }
                });
    }

    private void startVideo(final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }
        service.startVideo()
                .then(new PromiseExec<Boolean, Object>() {
                    @Override
                    public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {
                        if (null != cb) cb.success();
                    }
                })
                .error(new ErrorPromise() {
                    @Override
                    public void onError(@NonNull Throwable error) {
                        if (null != cb) {
                            cb.error("Error while starting video");
                        }
                    }
                });
    }

    private void stopConference(final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                service.leave()
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean bool, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while leaving");
                            }
                        });
            }
        });
    }

    private void startRecording(final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                service.startRecording()
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean bool, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while start recording");
                            }
                        });
            }
        });
    }

    private void stopRecording(final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                service.stopRecording()
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean bool, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while stop recording");
                            }
                        });
            }
        });
    }

    private void sendBroadcastMessage(final String message, final CallbackContext cb) {
        ConferenceService service = VoxeetSdk.conference();
        if (null == service) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        String conferenceId = service.getConferenceId();
        if (null == conferenceId || TextUtils.isEmpty(conferenceId)) {
            cb.error("You're not in a conference");
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                service.sendMessage(conferenceId, message)
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean aBoolean, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while sending the message to the server");
                            }
                        });
            }
        });
    }

    private void setAudio3DEnabled(boolean enabled) {
        MediaDeviceService service = VoxeetSdk.mediaDevice();
        if (null != service) service.setAudio3DEnabled(enabled);
    }

    private void setTelecomMode(boolean telecomMode) {
        ConferenceService service = VoxeetSdk.conference();
        if (null != service) {
            service.setTelecomMode(telecomMode);
        }
    }

    private void appearMaximized(final Boolean enabled) {
        if (null == VoxeetToolkit.getInstance()) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                VoxeetToolkit.getInstance()
                        .getConferenceToolkit()
                        .setDefaultOverlayState(enabled ? OverlayState.EXPANDED
                                : OverlayState.MINIMIZED);
            }
        });
    }

    private void defaultBuiltInSpeaker(final boolean enabled) {
        AudioService service = VoxeetSdk.audio();
        if (null == service) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                AudioRoute route = AudioRoute.ROUTE_PHONE;
                if (enabled) route = AudioRoute.ROUTE_SPEAKER;

                VoxeetPreferences.setDefaultBuiltInSpeakerOn(enabled);
                service.setAudioRoute(route);
            }
        });
    }

    private void screenAutoLock(Boolean enabled) {
        //TODO not available in the current sdk
    }

    private void postRefreshAccessToken() {
        Log.d("VoxeetCordova", "postRefreshAccessToken: sending call to javascript to refresh token");
        if (null != refreshAccessTokenCallbackInstance) {
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK);
            pluginResult.setKeepCallback(true);
            refreshAccessTokenCallbackInstance.sendPluginResult(pluginResult);
        }
    }

    private void onAccessTokenOk(final String accessToken,
                                 final CallbackContext callbackContext) {
        lock(lockAwaitingToken);
        for (TokenCallback callback : mAwaitingTokenCallback) {
            try {
                callback.ok(accessToken);
            } catch (Exception ee) {
                ee.printStackTrace();
            }
        }
        unlock(lockAwaitingToken);
        callbackContext.success();
    }

    private void onAccessTokenKo(final String reason,
                                 final CallbackContext callbackContext) {
        try {
            throw new Exception("refreshToken failed with reason := " + reason);
        } catch (Exception e) {
            lock(lockAwaitingToken);
            for (TokenCallback callback : mAwaitingTokenCallback) {
                try {
                    callback.error(e);
                } catch (Exception ee) {
                    ee.printStackTrace();
                }
            }
            unlock(lockAwaitingToken);
        }
        callbackContext.success();
    }

    private void refreshAccessTokenCallback(final CallbackContext callbackContext) {
        refreshAccessTokenCallbackInstance = callbackContext;
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(final SocketConnectEvent event) {
        if (null != _log_in_callback) {
            _log_in_callback.success();
            _log_in_callback = null;

            checkForIncomingConference();
        }
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(SocketStateChangeEvent event) {
        switch (event.message()) {
            case "CLOSING":
            case "CLOSED":
                if (null != _log_in_callback) {
                    _log_in_callback.error("Error while logging in");
                    _log_in_callback = null;

                    cancelIncomingConference();
                }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(PermissionRefusedEvent event) {
        if (null != event.getPermission()) {
            switch (event.getPermission()) {
                case CAMERA:
                    //Validate.requestMandatoryPermissions(VoxeetToolkit.getInstance().getCurrentActivity(),
                    //        new String[]{Manifest.permission.CAMERA},
                    //        PermissionRefusedEvent.RESULT_CAMERA);

                    PermissionHelper.requestPermissions(
                            this,
                            PermissionRefusedEvent.RESULT_CAMERA,
                            PermissionRefusedEvent.Permission.CAMERA.getPermissions()
                    );
                    break;
            }
        }
    }

    private void requestMicrophonePermission() {
        PermissionHelper.requestPermissions(
                this,
                PermissionRefusedEvent.RESULT_MICROPHONE,
                PermissionRefusedEvent.Permission.MICROPHONE.getPermissions()
        );
    }

    private boolean isConnected() {
        SessionService service = VoxeetSdk.session();
        return null != service && service.isSocketOpen();
    }

    private boolean isSameUser(@NonNull UserInfo userInfo) {
        return userInfo.getExternalId().equals(getCurrentUser());
    }

    private boolean checkForIncomingConference() {
        CordovaIncomingBundleChecker checker = CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE;
        SessionService service = VoxeetSdk.session();
        if (null != checker && checker.isBundleValid() && null != checker) {
            if (service.isSocketOpen()) {
                checker.onAccept();
                CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
                return true;
            } else {
                return false;
            }
        }
        return true;
    }

    private void cancelIncomingConference() {
        CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
    }

    private void lock(ReentrantLock lock) {
        try {
            lock.lock();
        } catch (Exception e) {

        }
    }

    private void unlock(ReentrantLock lock) {
        try {
            if (lock.isLocked())
                lock.unlock();
        } catch (Exception e) {


        }
    }

    private void lock() {
        lock(lock);
    }

    private void unlock() {
        unlock(lock);
    }

    private class MicrophonePermissionWait {
        private String confId;
        private CallbackContext cb;

        private MicrophonePermissionWait() {

        }

        public MicrophonePermissionWait(@NonNull String confId, @NonNull CallbackContext cb) {
            this();
            this.confId = confId;
            this.cb = cb;
        }

        public String getConfId() {
            return confId;
        }

        public CallbackContext getCb() {
            return cb;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(UserAddedEvent event) {
        setVolumeVoiceCall();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ConferenceStateEvent event) {
        switch (event.state) {
            case CREATING:
            case CREATED:
            case JOINING:
                setVolumeVoiceCall();
                break;
            case JOINED:
                Log.d(TAG, "onEvent: ConferenceJoinedSuccessEvent :: removing the various bundles");
                setVolumeVoiceCall();
                cleanBundles();
                break;
            case CREATED_ERROR:
                setVolumeStream();
                break;
            case JOINED_ERROR:
                setVolumeStream();
                cleanBundles();
                break;
            case LEAVING:
            case LEFT:
            case LEFT_ERROR:
                setVolumeStream();
            default:
        }
        setVolumeStream();
    }

    private void setVolumeVoiceCall() {
        cordova.getActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        if (null != AudioService.getSoundManager()) {
            AudioService.getSoundManager().setMediaRoute().enable().requestAudioFocus();
        }
    }

    private void setVolumeStream() {
        cordova.getActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (null != AudioService.getSoundManager()) {
            AudioService.getSoundManager().abandonAudioFocusRequest().unsetMediaRoute().enableMedia();
        }
    }

    private void cleanBundles() {
        //and finally clear them
        CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_ACCEPT = null;
        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_DECLINE = null;
        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_LAUNCH_ACCEPT = null;
    }
}
