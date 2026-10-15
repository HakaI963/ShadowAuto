package com.raphele.shell;

import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

public final class Main {
    private static final String PROCESS_MARKER = "com.raphele.shell.Main";
    private static volatile AutomationEngine activeEngine;
    private static volatile LogHub activeLogs;

    static {
        installGlobalExceptionLogger();
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable e) {
            printFatal(Thread.currentThread(), e);
            Process.killProcess(Process.myPid());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Config config = Config.from(args);
        LogHub logs = new LogHub();
        if (hasFlag(args, "--ocr-test")) {
            OcrTest.run(logs);
            return;
        }
        prepareLooper();
        killExistingShellProcesses();
        ShellContext.init();
        ensurePortFree(config.port, logs);
        // Tell the Shizuku entry point that stale port holders are gone: a
        // pending DESTROY may now safely exit this process.
        ShellUserService.notifyStartupComplete();
        if (hasFlag(args, "--video-test")) {
            VideoTest.run(logs);
            return;
        }
        AutomationEngine engine = new AutomationEngine(config, logs);
        activeEngine = engine;
        activeLogs = logs;
        logs.info("silent shell listening on 127.0.0.1:" + config.port);
        Thread rpcThread = new Thread(() -> runRpcServer(config, logs, engine), "rpc-server");
        rpcThread.start();
        Looper.loop();
    }

    private static void installGlobalExceptionLogger() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            printFatal(thread, error);
            Process.killProcess(Process.myPid());
            System.exit(1);
        });
    }

    private static void printFatal(Thread thread, Throwable error) {
        synchronized (System.err) {
            String name = thread == null ? "unknown" : thread.getName();
            System.err.println("[fatal] uncaught exception in thread: " + name);
            if (error != null) {
                error.printStackTrace(System.err);
            }
            System.err.flush();
        }
    }

    private static void prepareLooper() {
        if (Looper.myLooper() != null) {
            return;
        }
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
        }
    }

    static void shutdown() {
        try {
            if (activeEngine != null) {
                activeEngine.stopTask();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (activeLogs != null) {
                activeLogs.task("shell shutdown requested");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void runRpcServer(Config config, LogHub logs, AutomationEngine engine) {        try {
            new RpcServer(config.port, engine, logs).run();
        } catch (Throwable e) {
            logs.error("rpc server failed", e);
            printFatal(Thread.currentThread(), e);
            Process.killProcess(Process.myPid());
            System.exit(1);
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A leftover server (old APK daemon, manual app_process, or a previous
     * Shizuku process) can still hold the RPC port and answer requests with
     * missing methods. As shell UID we can kill those leftovers: retry a few
     * times so a fresh server never silently loses the port race.
     */
    private static void ensurePortFree(int port, LogHub logs) {
        for (int attempt = 0; attempt < 4; attempt++) {
            if (portAvailable(port)) {
                return;
            }
            killPortOwners(port, logs);
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        if (!portAvailable(port)) {
            logs.error("port " + port + " still busy after cleanup", null);
        }
    }

    private static boolean portAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port, 20, InetAddress.getByName("127.0.0.1"))) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void killPortOwners(int port, LogHub logs) {
        int currentPid = Process.myPid();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return;
        }
        String portArg = "--port=" + port;
        for (File entry : entries) {
            String name = entry.getName();
            if (!isPid(name)) {
                continue;
            }
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (pid == currentPid) {
                continue;
            }
            String commandLine = readCommandLine(new File(entry, "cmdline"));
            if (commandLine.isEmpty() || !isServerProcess(commandLine, portArg)) {
                continue;
            }
            logs.info("killing stale server process " + pid);
            try {
                Process.killProcess(pid);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isServerProcess(String commandLine, String portArg) {
        return commandLine.contains(portArg)
                || commandLine.contains("com.raphele.shell.Main")
                || commandLine.contains("com.raphele.shell.ShellUserService")
                || commandLine.contains("raphele-shell")
                // Legacy ShadowAuto installs: still kill their leftover daemons
                // so they cannot keep answering on our port.
                || commandLine.contains("com.silentauto.shell.Main")
                || commandLine.contains("com.silentauto.shell.ShellUserService")
                || commandLine.contains("shadowauto-shell");
    }

    private static void killExistingShellProcesses() {
        int currentPid = Process.myPid();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            String name = entry.getName();
            if (!isPid(name)) {
                continue;
            }
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (pid == currentPid) {
                continue;
            }
            String commandLine = readCommandLine(new File(entry, "cmdline"));
            if (commandLine.isEmpty() || !isShellMainProcess(commandLine)) {
                continue;
            }
            try {
                Process.killProcess(pid);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isShellMainProcess(String commandLine) {
        if (!commandLine.contains(PROCESS_MARKER)
                && !commandLine.contains("com.silentauto.shell.Main")) {
            return false;
        }
        return commandLine.startsWith("app_process")
                || commandLine.startsWith("/system/bin/app_process");
    }

    private static boolean isPid(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String readCommandLine(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int length = input.read(buffer);
            if (length <= 0) {
                return "";
            }
            StringBuilder out = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                byte value = buffer[i];
                out.append(value == 0 ? ' ' : (char) value);
            }
            return out.toString().trim();
        } catch (IOException ignored) {
            return "";
        }
    }
}
