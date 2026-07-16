package dev.sk2andy.voiceisolation.service;

import dev.sk2andy.voiceisolation.service.IIsolationCallback;

interface IIsolationUserService {
    void destroy() = 16777114;
    void start(float volume, IIsolationCallback callback) = 1;
    void stop() = 2;
    void setVolume(float volume) = 3;
    boolean isRunning() = 4;
}
