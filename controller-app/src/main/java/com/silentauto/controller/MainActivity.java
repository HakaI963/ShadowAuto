package com.silentauto.controller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;
import androidx.viewpager2.widget.ViewPager2;

import org.json.JSONObject;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity implements AutomationTaskView.Listener, RpcClient.Listener {
    private static final int PORT = 43110;
    private static final int REQUEST_CONFIG = 1001;
    private static final int MAIN_PADDING_DP = 24;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicInteger nextTask = new AtomicInteger(1);
    private final List<AutomationTaskView> tasks = Collections.synchronizedList(new ArrayList<>());

    private AiConfigStore configStore;
    private AiConfig config = new AiConfig("", "", "");
    private RpcClient rpcClient;
    private ViewPager2 pager;
    private TaskPagerAdapter pagerAdapter;
    private TextView indicator;
    private boolean configOpen;
    private TextView serverStatus;
    private Button serverButton;
    private Button stopServerButton;
    private View serverDot;
    private TextView ocrStatus;
    private Button ocrButton;
    private View ocrDot;
    private boolean serverStarting;
    private boolean serverStopping;
    private Shizuku.UserServiceArgs userServiceArgs;
    private ServiceConnection userServiceConn;
    private final AtomicReference<IBinder> shellBinder = new AtomicReference<>();
    private ShizukuServerManager.OcrStatus lastOcr;
    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            refreshServerStatus();
            main.postDelayed(this, 3000);
        }
    };
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            this::onShizukuPermissionResult;
    private final Shizuku.OnBinderReceivedListener shizukuBinderListener =
            this::onShizukuBinderChanged;
    private final Shizuku.OnBinderDeadListener shizukuDeadListener =
            this::onShizukuBinderChanged;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        UiUtils.setupSystemBars(this);
        startKeepAliveService();
        configStore = new AiConfigStore(this);
        config = configStore.load();
        rpcClient = new RpcClient("127.0.0.1", PORT, this);
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
            Shizuku.addBinderReceivedListener(shizukuBinderListener);
            Shizuku.addBinderDeadListener(shizukuDeadListener);
        } catch (Throwable ignored) {
        }
        if (config.isConfigured()) {
            showMain();
        } else {
            openConfig(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPoll();
        if (configStore == null) {
            return;
        }
        config = configStore.load();
        if (!config.isConfigured() && !configOpen) {
            openConfig(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CONFIG) {
            return;
        }
        configOpen = false;
        config = configStore.load();
        if (config.isConfigured()) {
            showMain();
        } else {
            openConfig(false);
        }
    }

    @Override
    protected void onPause() {
        stopPoll();
        super.onPause();
    }

    @Override
    public void onTaskStart(AutomationTaskView task) {
        io.execute(() -> {
            boolean running;
            try {
                running = ShizukuServerManager.isServerRunning();
            } catch (Throwable t) {
                running = false;
            }
            if (!running) {
                reportRpcError(task, new ConnectException(getString(R.string.rpc_shell_unavailable)));
                return;
            }
            sendStart(task);
        });
    }

    @Override
    public void onTaskStop(String taskId) {
        io.execute(() -> sendStop(taskId));
    }

    @Override
    public void onLine(String line) {
        handleRpcLine(line);
    }

    @Override
    public void onClosed(Exception error) {
        appendGlobal("[closed] " + error.getMessage());
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
            Shizuku.removeBinderReceivedListener(shizukuBinderListener);
            Shizuku.removeBinderDeadListener(shizukuDeadListener);
        } catch (Throwable ignored) {
        }
        try {
            if (userServiceArgs != null && userServiceConn != null) {
                ShizukuServerManager.unbind(userServiceArgs, userServiceConn);
            }
        } catch (Throwable ignored) {
        }
        userServiceArgs = null;
        userServiceConn = null;
        shellBinder.set(null);
        stopPoll();
        io.shutdownNow();
        super.onDestroy();
    }

    private void startKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    private void openConfig(boolean fromSettings) {
        configOpen = true;
        Intent intent = new Intent(this, ConfigActivity.class);
        intent.putExtra(ConfigActivity.EXTRA_FROM_SETTINGS, fromSettings);
        startActivityForResult(intent, REQUEST_CONFIG);
    }

    private void showMain() {
        setContentView(R.layout.activity_main);
        FrameLayout root = findViewById(R.id.mainRoot);
        LinearLayout page = findViewById(R.id.mainPage);
        ImageButton stopAll = findViewById(R.id.stopAllButton);
        ImageButton settings = findViewById(R.id.settingsButton);
        ImageButton help = findViewById(R.id.helpButton);
        Button add = findViewById(R.id.addTaskButton);
        pager = findViewById(R.id.taskPager);
        indicator = findViewById(R.id.pageIndicator);

        UiUtils.clearButtonTint(add);
        FrameLayout.LayoutParams addLp = (FrameLayout.LayoutParams) add.getLayoutParams();
        UiUtils.applyMainInsets(this, root, page, add, addLp, MAIN_PADDING_DP);

        ensureTaskPage();
        pagerAdapter = new TaskPagerAdapter(tasks);
        pager.setAdapter(pagerAdapter);
        pager.setOffscreenPageLimit(2);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateIndicator();
            }
        });

        stopAll.setOnClickListener(v -> confirmStopAll());
        settings.setOnClickListener(v -> openConfig(true));
        help.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
        add.setOnClickListener(v -> addEmptyTask());
        updateIndicator();
        setupServerBar();
        deployOcrAsync();
        refreshServerStatus();
    }

    private void ensureTaskPage() {
        synchronized (tasks) {
            if (!tasks.isEmpty()) {
                return;
            }
            tasks.add(new AutomationTaskView(this, main, newTaskId(), this));
        }
    }

    private void addEmptyTask() {
        Toast.makeText(this, getString(R.string.parallel_task_warning), Toast.LENGTH_SHORT).show();
        AutomationTaskView task = new AutomationTaskView(this, main, newTaskId(), this);
        int index;
        synchronized (tasks) {
            tasks.add(task);
            index = tasks.size() - 1;
        }
        pagerAdapter.notifyItemInserted(index);
        pager.setCurrentItem(index, true);
        updateIndicator();
    }

    private void confirmStopAll() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.stop_all_title))
                .setMessage(getString(R.string.stop_all_message))
                .setNegativeButton(getString(R.string.no), null)
                .setPositiveButton(getString(R.string.yes), (dialog, which) -> stopAllTasks())
                .show();
    }

    private void stopAllTasks() {
        synchronized (tasks) {
            for (AutomationTaskView task : tasks) {
                task.setRunning(false);
            }
        }
        io.execute(() -> {
            if (sendStopAll()) {
                main.post(() -> Toast.makeText(this, getString(R.string.stop_all_sent), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String newTaskId() {
        return "app-" + nextTask.getAndIncrement();
    }

    private void sendStart(AutomationTaskView task) {
        try {
            JSONObject params = new JSONObject();
            params.put("taskId", task.taskId());
            params.put("goal", task.goalText());
            params.put("apiKey", config.apiKey);
            params.put("apiBase", config.apiBase);
            params.put("model", config.model);
            rpcClient.send("startTask", params);
        } catch (Exception e) {
            reportRpcError(task, e);
        }
    }

    private void sendStop(String taskId) {
        try {
            rpcClient.send("stopTask", new JSONObject().put("taskId", taskId));
        } catch (Exception e) {
            reportRpcError(taskById(taskId), e);
        }
    }

    private boolean sendStopAll() {
        try {
            rpcClient.send("stopTask", new JSONObject());
            return true;
        } catch (Exception e) {
            reportRpcError(null, e);
            return false;
        }
    }

    private void handleRpcLine(String line) {
        try {
            JSONObject object = new JSONObject(line);
            if (!"event".equals(object.optString("method"))) {
                return;
            }
            JSONObject params = object.getJSONObject("params");
            AutomationTaskView task = taskById(params.optString("taskId"));
            if (task == null) {
                return;
            }
            String type = params.optString("type");
            if ("video.config".equals(type)) {
                task.showVideoConfig(params);
            } else if ("video.sample".equals(type)) {
                task.showVideoSample(params);
            } else if ("frame".equals(type)) {
                return;
            } else {
                task.append(params.optString("message"));
            }
        } catch (Exception ignored) {
        }
    }

    private AutomationTaskView taskById(String taskId) {
        synchronized (tasks) {
            for (AutomationTaskView task : tasks) {
                if (task.taskId().equals(taskId)) {
                    return task;
                }
            }
        }
        return null;
    }

    private void appendGlobal(String text) {
        runOnUiThread(() -> {
            synchronized (tasks) {
                if (!tasks.isEmpty()) {
                    tasks.get(0).append(text);
                }
            }
        });
    }

    private void reportRpcError(AutomationTaskView task, Exception error) {
        String detail = rpcErrorMessage(error);
        String message = getString(R.string.rpc_error, detail);
        if (task != null) {
            task.append(message);
            task.setRunning(false);
        } else {
            appendGlobal(message);
        }
        main.post(() -> Toast.makeText(this, detail, Toast.LENGTH_LONG).show());
    }

    private String rpcErrorMessage(Exception error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException) {
                return getString(R.string.rpc_shell_unavailable);
            }
            current = current.getCause();
        }
        String message = error.getMessage();
        if (message != null && message.toLowerCase(Locale.US).contains("refused")) {
            return getString(R.string.rpc_shell_unavailable);
        }
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void updateIndicator() {
        if (indicator == null || pagerAdapter == null || pager == null) {
            return;
        }
        int count = pagerAdapter.getItemCount();
        int current = pager.getCurrentItem();
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                dots.append("  ");
            }
            dots.append(i == current ? "\u25CF" : "\u25CB");
        }
        indicator.setText(dots.toString());
    }

    private void startPoll() {
        main.removeCallbacks(statusPoll);
        main.post(statusPoll);
    }

    private void stopPoll() {
        main.removeCallbacks(statusPoll);
    }

    private void setupServerBar() {
        serverDot = findViewById(R.id.serverDot);
        serverStatus = findViewById(R.id.serverStatus);
        serverButton = findViewById(R.id.serverButton);
        stopServerButton = findViewById(R.id.stopServerButton);
        ocrDot = findViewById(R.id.ocrDot);
        ocrStatus = findViewById(R.id.ocrStatus);
        ocrButton = findViewById(R.id.ocrButton);
        if (serverButton != null) {
            serverButton.setOnClickListener(v -> onServerButton());
        }
        if (stopServerButton != null) {
            stopServerButton.setOnClickListener(v -> onStopServerButton());
        }
        if (ocrButton != null) {
            ocrButton.setOnClickListener(v -> onOcrButton());
        }
    }

    private void onServerButton() {
        if (serverStarting || serverStopping) {
            return;
        }
        io.execute(() -> {
            boolean running = safeProbe();
            if (running) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.server_running), Toast.LENGTH_SHORT).show());
                refreshServerStatus();
                return;
            }
            boolean binder = safeBinder();
            if (!binder) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.shizuku_missing), Toast.LENGTH_LONG).show());
                refreshServerStatus();
                return;
            }
            if (!safePermission()) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.shizuku_requesting), Toast.LENGTH_SHORT).show());
                ShizukuServerManager.requestPermission();
                return;
            }
            main.post(() -> {
                serverStarting = true;
                refreshServerStatus();
            });
            if (userServiceArgs == null) {
                userServiceArgs = ShizukuServerManager.serviceArgs(this);
            }
            if (userServiceConn == null) {
                userServiceConn = ShizukuServerManager.connection((ok, msg) -> main.post(() -> {
                    serverStarting = false;
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    refreshServerStatus();
                }), shellBinder);
            }
            ShizukuServerManager.startServer(this, userServiceArgs, userServiceConn,
                    (ok, msg) -> main.post(() -> {
                        serverStarting = false;
                        if (!ok) {
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        }
                        refreshServerStatus();
                    }));
        });
    }

    private void onStopServerButton() {
        if (serverStarting || serverStopping) {
            return;
        }
        io.execute(() -> {
            if (!safeProbe()) {
                main.post(this::refreshServerStatus);
                return;
            }
            main.post(() -> {
                serverStopping = true;
                refreshServerStatus();
            });
            IBinder bound = shellBinder.get();
            if (bound != null && userServiceArgs != null && userServiceConn != null) {
                ShizukuServerManager.stopServer(bound, userServiceArgs, userServiceConn,
                        (ok, msg) -> main.post(() -> onServerStopped(ok, msg)));
            } else {
                if (userServiceArgs == null) {
                    userServiceArgs = ShizukuServerManager.serviceArgs(this);
                }
                ShizukuServerManager.ensureBoundThenStop(this, userServiceArgs, shellBinder,
                        (ok, msg) -> main.post(() -> onServerStopped(ok, msg)));
            }
        });
    }

    private void onServerStopped(boolean ok, String msg) {
        serverStopping = false;
        if (ok) {
            shellBinder.set(null);
            userServiceConn = null;
            synchronized (tasks) {
                for (AutomationTaskView task : tasks) {
                    task.setRunning(false);
                }
            }
        }
        Toast.makeText(this, msg, ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        refreshServerStatus();
    }

    private void onOcrButton() {
        io.execute(() -> {
            if (!safeProbe()) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.ocr_no_server), Toast.LENGTH_SHORT).show());
                return;
            }
            ShizukuServerManager.OcrStatus cached = lastOcr;
            boolean needFix = cached == null || !cached.serverUp || !cached.available;
            if (!needFix) {
                refreshServerStatus();
                return;
            }
            main.post(() -> {
                if (ocrStatus != null) {
                    ocrStatus.setText(getString(R.string.ocr_deploying));
                }
                if (ocrButton != null) {
                    ocrButton.setEnabled(false);
                }
            });
            String result;
            try {
                result = OcrAssetDeployer.deploy(this, true);
            } catch (Throwable e) {
                result = "ocr deploy failed: " + e.getMessage();
            }
            String message = "[ocr] " + result;
            main.post(() -> {
                appendGlobal(message);
                Toast.makeText(this, getString(R.string.ocr_fixed), Toast.LENGTH_SHORT).show();
                refreshServerStatus();
            });
        });
    }

    private void deployOcrAsync() {
        io.execute(() -> {
            String result;
            try {
                result = OcrAssetDeployer.deploy(this);
            } catch (Throwable e) {
                result = "ocr deploy failed: " + e.getMessage();
            }
            String message = "[ocr] " + result;
            main.post(() -> {
                appendGlobal(message);
                refreshServerStatus();
            });
        });
    }

    private void refreshServerStatus() {
        io.execute(() -> {
            boolean running = safeProbe();
            if (!running) {
                boolean binder = safeBinder();
                boolean perm = binder && safePermission();
                main.post(() -> renderServerStopped(binder, perm));
                return;
            }
            ShizukuServerManager.queryOcrStatus(ocr -> main.post(() -> renderServerRunning(ocr)));
        });
    }

    private void renderServerStopped(boolean binder, boolean perm) {
        if (serverStatus == null) {
            return;
        }
        if (serverDot != null) {
            serverDot.setBackgroundResource(R.drawable.dot_red);
        }
        if (serverStarting) {
            serverStatus.setText(getString(R.string.server_starting));
        } else if (serverStopping) {
            serverStatus.setText(getString(R.string.server_stopping));
        } else if (!binder) {
            serverStatus.setText(getString(R.string.shizuku_missing));
        } else if (!perm) {
            serverStatus.setText(getString(R.string.shizuku_denied));
        } else {
            serverStatus.setText(getString(R.string.server_stopped));
        }
        if (serverButton != null) {
            serverButton.setText(serverStarting
                    ? getString(R.string.server_starting) : getString(R.string.server_start));
            serverButton.setEnabled(!serverStarting && !serverStopping);
        }
        if (stopServerButton != null) {
            stopServerButton.setText(getString(R.string.server_stop));
            stopServerButton.setEnabled(false);
        }
        if (ocrDot != null) {
            ocrDot.setBackgroundResource(R.drawable.dot_yellow);
        }
        if (ocrStatus != null) {
            ocrStatus.setText(getString(R.string.ocr_no_server));
        }
        if (ocrButton != null) {
            ocrButton.setText(getString(R.string.ocr_retest));
            ocrButton.setEnabled(false);
        }
    }

    private void renderServerRunning(ShizukuServerManager.OcrStatus ocr) {
        if (serverStatus == null) {
            return;
        }
        lastOcr = ocr;
        if (serverDot != null) {
            serverDot.setBackgroundResource(R.drawable.dot_green);
        }
        if (serverStopping) {
            serverStatus.setText(getString(R.string.server_stopping));
        } else {
            serverStatus.setText(getString(R.string.server_running));
        }
        if (serverButton != null) {
            serverButton.setText(getString(R.string.server_running));
            serverButton.setEnabled(false);
        }
        if (stopServerButton != null) {
            stopServerButton.setText(getString(R.string.server_stop));
            stopServerButton.setEnabled(!serverStopping);
        }
        if (ocr == null || !ocr.serverUp) {
            if (ocrDot != null) {
                ocrDot.setBackgroundResource(R.drawable.dot_yellow);
            }
            if (ocrStatus != null) {
                ocrStatus.setText(getString(R.string.ocr_checking));
            }
            if (ocrButton != null) {
                ocrButton.setText(getString(R.string.ocr_retest));
                ocrButton.setEnabled(true);
            }
            return;
        }
        if (ocr.available) {
            if (ocrDot != null) {
                ocrDot.setBackgroundResource(R.drawable.dot_green);
            }
            if (ocrStatus != null) {
                ocrStatus.setText(getString(R.string.ocr_ready));
            }
            if (ocrButton != null) {
                ocrButton.setText(getString(R.string.ocr_retest));
                ocrButton.setEnabled(true);
            }
            return;
        }
        if (ocrDot != null) {
            ocrDot.setBackgroundResource(R.drawable.dot_red);
        }
        if (ocrStatus != null) {
            String detail = ocr.error == null || ocr.error.isEmpty() ? ocr.diagnosis : ocr.error;
            if (detail == null || detail.isEmpty()) {
                ocrStatus.setText(getString(R.string.ocr_missing));
            } else {
                if (detail.length() > 120) {
                    detail = detail.substring(0, 120) + "…";
                }
                ocrStatus.setText(getString(R.string.ocr_broken, detail));
            }
        }
        if (ocrButton != null) {
            ocrButton.setText(getString(R.string.ocr_fix));
            ocrButton.setEnabled(true);
        }
    }

    private boolean safeProbe() {
        try {
            return ShizukuServerManager.isServerRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean safeBinder() {
        try {
            return ShizukuServerManager.isBinderAlive();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean safePermission() {
        try {
            return ShizukuServerManager.hasPermission();
        } catch (Throwable t) {
            return false;
        }
    }

    private void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != ShizukuServerManager.PERMISSION_CODE) {
            return;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            deployOcrAsync();
            onServerButton();
        } else {
            refreshServerStatus();
        }
    }

    private void onShizukuBinderChanged() {
        refreshServerStatus();
    }
}
