package com.liuc.remotebell;

final class Constants {
    static final String DEFAULT_SERVER = "https://ntfy.sh";
    static final String DEFAULT_TOPIC = "rb-liuc-2f4c9d7a8e6b4130a5d2c8e9f1b7a6c3";

    static final String PREFS = "remote_bell";
    static final String PREF_SERVER = "server";
    static final String PREF_TOPIC = "topic";

    static final String ACTION_START = "com.liuc.remotebell.START";
    static final String ACTION_STOP_LISTENER = "com.liuc.remotebell.STOP_LISTENER";
    static final String ACTION_STOP_ALARM = "com.liuc.remotebell.STOP_ALARM";
    static final String ACTION_LOCAL_ALARM = "com.liuc.remotebell.LOCAL_ALARM";

    static final String EXTRA_SERVER = "server";
    static final String EXTRA_TOPIC = "topic";
    static final String EXTRA_MESSAGE = "message";

    private Constants() {
    }
}
