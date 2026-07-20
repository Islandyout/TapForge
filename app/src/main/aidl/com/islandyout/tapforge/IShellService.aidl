// AIDL interface implemented by ShellUserService, which runs in a separate
// process with shell (or root) privilege granted by Shizuku. The app talks
// to it over this Binder interface instead of the removed Shizuku.newProcess.
package com.islandyout.tapforge;

interface IShellService {
    // Runs a shell command and returns combined stdout. Blocking.
    String exec(String cmd);

    // Starts a long-running command (e.g. `getevent -lt <dev>`) and returns
    // a session id used to poll output and stop it later.
    int startStream(String cmd);

    // Reads and clears whatever output has accumulated since the last call.
    String readStream(int sessionId);

    // Kills the process behind a stream session.
    void stopStream(int sessionId);

    void destroy();
}
