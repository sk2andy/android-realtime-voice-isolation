package dev.sk2andy.voiceisolation.service;

oneway interface IIsolationCallback {
    void onStateChanged(int state, String message);
    void onStats(float inferenceMs, float realTimeFactor, float inputDb, int underruns);
}
